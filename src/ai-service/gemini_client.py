"""
Lớp gọi Gemini có kiểm soát (B8):

  * MỘT client SDK dùng lại (không tạo genai.Client mỗi lần gọi).
  * Thời hạn tổng (deadline) cho mỗi lần chấm điểm: Python trả lời TRƯỚC khi Java bỏ cuộc (Java chờ 15s),
    không còn cảnh Java đã nhận lỗi mà Python vẫn chạy tiếp.
  * Retry có kiểm soát: chỉ thử lại lỗi tạm thời (5xx, mất kết nối) tối đa 1 lần và chỉ khi còn đủ thời gian.
    Không retry 429 (hết quota) hay lỗi cấu hình.
  * Circuit breaker: lỗi lặp lại / hết quota / sai khóa -> tạm ngưng gọi Gemini một lúc thay vì gọi trước mỗi request.
  * Phân loại lỗi bằng MÃ HTTP của SDK (APIError.code/status), không so chuỗi "quota".
  * Giới hạn số lời gọi đồng thời tới Gemini.

Module này không import google-genai ở mức module để có thể kiểm thử bằng transport giả.
"""

import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from typing import Any, Callable, Dict, Optional, TypeVar

T = TypeVar("T")

logger = logging.getLogger("ai-service")


# ------------------------------------------------------------------ lỗi

class GeminiError(Exception):
    """Lỗi khi gọi Gemini. `code` là mã ổn định (đưa ra ngoài thành fallback_reason), `message` là câu tiếng Việt cho người dùng."""

    def __init__(self, code: str, message: str, *, retryable: bool = False, cooldown: Optional[float] = None,
                 http_status: Optional[int] = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.cooldown = cooldown          # None: theo chính sách mặc định của breaker
        self.http_status = http_status


NOT_CONFIGURED = "GEMINI_NOT_CONFIGURED"
AUTH = "GEMINI_AUTH"
MODEL_NOT_FOUND = "GEMINI_MODEL_NOT_FOUND"
BAD_REQUEST = "GEMINI_BAD_REQUEST"
RATE_LIMITED = "GEMINI_RATE_LIMITED"
UNAVAILABLE = "GEMINI_UNAVAILABLE"
TIMEOUT = "GEMINI_TIMEOUT"
BUSY = "GEMINI_BUSY"
BAD_RESPONSE = "GEMINI_BAD_RESPONSE"
PARTIAL = "GEMINI_PARTIAL"
OTHER = "GEMINI_ERROR"


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(float(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return default


def classify_exception(e: BaseException) -> GeminiError:
    """Đổi exception của SDK/mạng thành GeminiError có mã ổn định. Dựa vào mã HTTP, không so chuỗi thông báo."""
    if isinstance(e, GeminiError):
        return e

    code = getattr(e, "code", None)
    status = str(getattr(e, "status", "") or "").upper()
    if not isinstance(code, int):
        code = None

    rate_cd = _env_float("GEMINI_RATE_LIMIT_COOLDOWN_SECONDS", 60.0)
    cfg_cd = _env_float("GEMINI_CONFIG_ERROR_COOLDOWN_SECONDS", 120.0)

    if code == 429 or status == "RESOURCE_EXHAUSTED":
        return GeminiError(RATE_LIMITED, "Gemini đang giới hạn tần suất hoặc đã hết quota.",
                           cooldown=rate_cd, http_status=429)
    if code in (401, 403) or status in ("UNAUTHENTICATED", "PERMISSION_DENIED"):
        return GeminiError(AUTH, "Khóa GEMINI_API_KEY không hợp lệ hoặc không có quyền.",
                           cooldown=cfg_cd, http_status=code or 401)
    if code == 404 or status == "NOT_FOUND":
        return GeminiError(MODEL_NOT_FOUND, "Không tìm thấy model Gemini đã cấu hình (kiểm tra GEMINI_MODEL).",
                           cooldown=cfg_cd, http_status=404)
    if code == 400 or status in ("INVALID_ARGUMENT", "FAILED_PRECONDITION"):
        return GeminiError(BAD_REQUEST, "Gemini từ chối yêu cầu (dữ liệu hoặc cấu hình không hợp lệ).",
                           cooldown=cfg_cd, http_status=400)
    if (code is not None and code >= 500) or status in ("UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED"):
        return GeminiError(UNAVAILABLE, "Máy chủ Gemini đang quá tải hoặc gặp sự cố.",
                           retryable=True, http_status=code)

    name = type(e).__name__.lower()
    if isinstance(e, TimeoutError) or "timeout" in name:
        return GeminiError(TIMEOUT, "Gemini phản hồi quá chậm.")
    if isinstance(e, (ConnectionError, OSError)) or "connect" in name or "network" in name:
        return GeminiError(UNAVAILABLE, "Không kết nối được tới Gemini.", retryable=True)
    return GeminiError(OTHER, "Lỗi không xác định khi gọi Gemini.")


# ------------------------------------------------------------------ circuit breaker

class CircuitBreaker:
    """
    closed    : cho gọi bình thường.
    open      : từ chối ngay (không gọi Gemini) cho tới hết thời gian nghỉ.
    half_open : hết thời gian nghỉ -> cho ĐÚNG MỘT lời gọi thử; thành công thì đóng lại, thất bại thì mở lại.

    Mở ngay khi lỗi cấu hình/hết quota (có cooldown riêng); lỗi tạm thời (5xx, timeout) phải lặp `threshold` lần liên tiếp.
    """

    def __init__(self, threshold: int = 3, cooldown: float = 30.0, clock: Callable[[], float] = time.monotonic):
        self.threshold = max(1, threshold)
        self.cooldown = cooldown
        self._clock = clock
        self._lock = threading.Lock()
        self._failures = 0
        self._open_until = 0.0
        self._state = "closed"
        self._trial_in_flight = False
        self.last_error: Optional[GeminiError] = None

    @property
    def state(self) -> str:
        with self._lock:
            return self._effective_state()

    def _effective_state(self) -> str:
        if self._state == "open" and self._clock() >= self._open_until:
            return "half_open"
        return self._state

    def retry_in(self) -> float:
        with self._lock:
            return max(0.0, self._open_until - self._clock()) if self._state == "open" else 0.0

    def allow(self) -> bool:
        with self._lock:
            state = self._effective_state()
            if state == "closed":
                return True
            if state == "half_open":
                if self._trial_in_flight:
                    return False
                self._state = "half_open"
                self._trial_in_flight = True
                return True
            return False

    def record_success(self) -> None:
        with self._lock:
            self._failures = 0
            self._state = "closed"
            self._trial_in_flight = False
            self.last_error = None

    def record_failure(self, err: GeminiError) -> None:
        with self._lock:
            self.last_error = err
            self._trial_in_flight = False
            was_trial = self._state == "half_open"
            self._failures += 1
            immediate = err.cooldown is not None     # lỗi cấu hình / hết quota: mở ngay
            if immediate or was_trial or self._failures >= self.threshold:
                self._state = "open"
                self._open_until = self._clock() + (err.cooldown if err.cooldown is not None else self.cooldown)
                logger.warning("[BREAKER] Mở mạch Gemini %.0fs vì %s", self._open_until - self._clock(), err.code)

    def release_trial(self) -> None:
        """Gỡ cờ 'đang thử' khi lời gọi thử không đi tới Gemini (vd bị từ chối vì quá tải nội bộ)."""
        with self._lock:
            self._trial_in_flight = False


# ------------------------------------------------------------------ SDK (nạp lười)

_SDK_CLIENTS: Dict[str, Any] = {}
_SDK_LOCK = threading.Lock()


def _sdk_client(api_key: str) -> Any:
    """MỘT client cho mỗi khóa (dùng lại giữa các request)."""
    with _SDK_LOCK:
        client = _SDK_CLIENTS.get(api_key)
        if client is None:
            from google import genai   # nạp lười: kiểm thử không cần cài SDK
            _SDK_CLIENTS.clear()       # đổi khóa thì bỏ client cũ
            client = genai.Client(api_key=api_key)
            _SDK_CLIENTS[api_key] = client
        return client


def sdk_transport(api_key: str, model: str, prompt: str, schema: Dict[str, Any], timeout_s: float) -> str:
    """Gọi Gemini thật, ép đầu ra JSON theo `schema`, temperature 0 cho kết quả ổn định. Trả về text JSON."""
    from google.genai import types

    client = _sdk_client(api_key)
    kwargs: Dict[str, Any] = dict(
        response_mime_type="application/json",
        response_json_schema=schema,
        temperature=0.0,
        max_output_tokens=_env_int("GEMINI_MAX_OUTPUT_TOKENS", 16384),
    )
    try:
        kwargs["http_options"] = types.HttpOptions(timeout=int(max(1.0, timeout_s) * 1000))  # ms
    except Exception:  # noqa: BLE001 - phiên bản SDK không có tùy chọn này: dựa vào deadline phía ta
        pass
    response = client.models.generate_content(
        model=model, contents=prompt, config=types.GenerateContentConfig(**kwargs))
    text = getattr(response, "text", None)
    if not text:
        raise GeminiError(BAD_RESPONSE, "Gemini trả về nội dung rỗng.")
    return text


# ------------------------------------------------------------------ client có kiểm soát

Transport = Callable[[str, str, str, Dict[str, Any], float], str]


class GeminiClient:
    def __init__(self,
                 api_key_provider: Callable[[], Optional[str]],
                 model_provider: Callable[[], str],
                 transport: Transport = sdk_transport,
                 breaker: Optional[CircuitBreaker] = None,
                 deadline_s: Optional[float] = None,
                 max_concurrency: Optional[int] = None,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep):
        self._api_key = api_key_provider
        self._model = model_provider
        self._transport = transport
        self.breaker = breaker or CircuitBreaker(
            threshold=_env_int("GEMINI_BREAKER_THRESHOLD", 3),
            cooldown=_env_float("GEMINI_BREAKER_COOLDOWN_SECONDS", 30.0))
        self.deadline_s = deadline_s if deadline_s is not None else _env_float("GEMINI_TIMEOUT_SECONDS", 12.0)
        workers = max(1, max_concurrency if max_concurrency is not None else _env_int("GEMINI_MAX_CONCURRENCY", 4))
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="gemini")
        self._clock = clock
        self._sleep = sleep

    # -- trạng thái cho /ready
    def configured(self) -> bool:
        return bool(self._api_key())

    def status(self) -> Dict[str, Any]:
        err = self.breaker.last_error
        return {
            "gemini_configured": self.configured(),
            "model": self._model(),
            "circuit": self.breaker.state,
            "circuit_retry_in_seconds": round(self.breaker.retry_in(), 1),
            "last_error": err.code if err else None,
        }

    def generate_json(self, prompt: str, schema: Dict[str, Any], parse: Callable[[str], T] = lambda t: t) -> T:  # type: ignore[assignment]
        """
        Gọi Gemini và trả về parse(text). Ném GeminiError (có mã ổn định) nếu không thành công.
        `parse` chạy TRONG vòng kiểm soát: phản hồi không đọc được (GeminiError BAD_RESPONSE) cũng tính là một lần thất bại
        của breaker, nên Gemini trả rác liên tục sẽ bị tạm ngưng chứ không bị gọi lại mãi.
        """
        api_key = self._api_key()
        if not api_key:
            raise GeminiError(NOT_CONFIGURED, "Chưa cấu hình GEMINI_API_KEY nên AI (Gemini) không được dùng.")

        if not self.breaker.allow():
            last = self.breaker.last_error
            wait = int(round(self.breaker.retry_in()))
            raise GeminiError(
                last.code if last else OTHER,
                (last.message if last else "Gemini tạm ngưng") + f" Hệ thống tạm ngưng gọi Gemini, thử lại sau ~{wait}s.",
                http_status=last.http_status if last else None)

        start = self._clock()
        deadline = start + self.deadline_s
        model = self._model()
        attempt = 0
        last_err: Optional[GeminiError] = None
        while True:
            attempt += 1
            remaining = deadline - self._clock()
            if remaining <= 0.25:
                last_err = last_err or GeminiError(TIMEOUT, "Gemini phản hồi quá chậm.")
                break
            try:
                future = self._executor.submit(self._transport, api_key, model, prompt, schema, remaining)
                try:
                    text = future.result(timeout=remaining)
                except FutureTimeout:
                    future.cancel()
                    raise GeminiError(TIMEOUT, "Gemini phản hồi quá chậm.")
                result = parse(text)
                self.breaker.record_success()
                return result
            except Exception as e:  # noqa: BLE001 - mọi lỗi đều được phân loại
                err = classify_exception(e)
                last_err = err
                logger.warning("[GEMINI] Lần %d thất bại: %s (%s)", attempt, err.code, type(e).__name__)
                if err.retryable and attempt < 2 and (deadline - self._clock()) > 3.0:
                    self._sleep(0.4)
                    continue
                break

        assert last_err is not None
        self.breaker.record_failure(last_err)
        raise last_err
