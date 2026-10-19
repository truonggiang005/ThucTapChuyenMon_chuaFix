"""
AI Microservice - FastAPI Application.

Microservice chấm điểm Match Score cho hệ thống SmartSchedule. Chỉ Spring Boot gọi (kèm X-Internal-Key).

Endpoints:
  GET  /api/v1/health        → Liveness (không cần khóa): tiến trình còn sống. KHÔNG chứng tỏ AI dùng được.
  GET  /api/v1/ready         → Readiness (CẦN khóa): khóa khớp + trạng thái Gemini/circuit breaker. Spring dùng cái này (B5).
  POST /api/v1/match-score   → Chấm điểm danh sách ứng viên cho 1 ca (Gemini, có dự phòng gắn cờ)
  POST /api/v1/quick-scores  → Chấm điểm NHANH theo lô cho Chợ ca (chỉ quy tắc, không tốn quota) (B4)

Chạy:
  python main.py            (mặc định 127.0.0.1:8001, không reload)
  AI_DEBUG=1 python main.py (bật /docs và reload - CHỈ khi phát triển)
"""

import logging
import os

# pyrefly: ignore [missing-import]
from dotenv import load_dotenv
# Đọc ".env" cạnh main.py (src/ai-service/) dù chạy từ thư mục nào. File này KHÔNG được commit (xem .env.example).
load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))
# pyrefly: ignore [missing-import]
from fastapi import FastAPI, HTTPException, Depends, Header, Request
# pyrefly: ignore [missing-import]
from fastapi.exceptions import RequestValidationError
# pyrefly: ignore [missing-import]
from fastapi.responses import JSONResponse

import security
from schemas import MatchRequest, MatchResponse, QuickRequest, QuickResponse
import model

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("ai-service")

DEBUG = os.environ.get("AI_DEBUG") == "1"

# Không có CORS (Spring gọi từ server, trình duyệt không bao giờ gọi trực tiếp) và không lộ số phiên bản (B12).
app = FastAPI(
    title="SmartSchedule AI Service",
    docs_url="/docs" if DEBUG else None,
    redoc_url="/redoc" if DEBUG else None,
    openapi_url="/openapi.json" if DEBUG else None,
)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_request: Request, exc: RequestValidationError):
    """422 chỉ nêu TRƯỜNG nào sai, không lặp lại giá trị đầu vào (có thể chứa họ tên nhân viên) - B12."""
    problems = [{"field": ".".join(str(p) for p in err.get("loc", [])), "problem": err.get("type", "invalid")}
                for err in exc.errors()]
    logger.warning("[422] Dữ liệu không hợp lệ ở các trường: %s", [p["field"] for p in problems][:10])
    return JSONResponse(status_code=422, content={"detail": "Dữ liệu không hợp lệ", "errors": problems[:20]})


def require_key(x_internal_key: str = Header(None)):
    """Khóa bí mật dùng chung giữa Spring và Python (header X-Internal-Key) - SEC-08, có xoay vòng (C6)."""
    result = security.check_key(x_internal_key)
    if result == security.UNCONFIGURED:
        logger.error("INTERNAL_KEY chưa được cấu hình trong .env - từ chối mọi yêu cầu.")
        raise HTTPException(status_code=503, detail="AI service chưa được cấu hình")
    if result == security.UNAUTHORIZED:
        raise HTTPException(status_code=401, detail="Unauthorized")


@app.get("/api/v1/health", tags=["Health"])
def health_check():
    """Liveness: tiến trình còn sống (không cần khóa). Muốn biết AI có DÙNG ĐƯỢC không thì gọi /api/v1/ready."""
    return {"status": "alive"}


@app.get("/api/v1/ready", tags=["Health"], dependencies=[Depends(require_key)])
def ready():
    """Readiness: gọi được endpoint này nghĩa là khóa nội bộ khớp; kèm trạng thái Gemini và circuit breaker."""
    return model.readiness()


@app.post("/api/v1/match-score", response_model=MatchResponse, tags=["AI Scoring"],
          dependencies=[Depends(require_key)])
def compute_match_score(request: MatchRequest):
    """
    ★ Chấm điểm Match Score cho danh sách ứng viên của một ca.

    Spring đã lọc ứng viên đủ điều kiện (kỹ năng, không trùng ca/lịch bận, giới hạn giờ/tuần, active) và gửi sang đây.
    Phản hồi có `source`, `fallback`, `fallback_reason`, `message`: fallback=true nghĩa là KHÔNG phải mọi điểm do Gemini
    chấm (thiếu khóa, hết quota, quá hạn...). Java dựa vào cờ này để giao diện báo đúng sự thật và ghi chú trong thông báo khi cron tự gán theo điểm dự phòng.
    """
    logger.info("[MATCH] shift=%s candidates=%d required_level=%s",
                request.shift.shift_id, len(request.candidates), request.shift.required_level)
    try:
        outcome = model.calculate_match_scores(request.shift, request.candidates)
    except Exception:  # noqa: BLE001 - lỗi lập trình: ghi log nội bộ, không lộ chi tiết ra ngoài
        logger.error("[ERROR] Match scoring failed", exc_info=True)
        raise HTTPException(status_code=500, detail="AI scoring failed")

    top = outcome.ranked[0] if outcome.ranked else None
    logger.info("[MATCH RESULT] shift=%s source=%s fallback=%s reason=%s top_employee_id=%s top_score=%s",
                request.shift.shift_id, outcome.source, outcome.fallback, outcome.reason,
                top.employee_id if top else None, top.match_score if top else None)

    return MatchResponse(
        shift_id=request.shift.shift_id,
        ranked_candidates=outcome.ranked,
        total_candidates=len(outcome.ranked),
        source=outcome.source,
        fallback=outcome.fallback,
        fallback_reason=outcome.reason,
        message=outcome.message,
    )


@app.post("/api/v1/quick-scores", response_model=QuickResponse, tags=["AI Scoring"],
          dependencies=[Depends(require_key)])
def compute_quick_scores(request: QuickRequest):
    """
    Chấm điểm NHANH theo lô cho Chợ ca (B4): mỗi cặp (ca, nhân viên) được chấm bằng quy tắc, không gọi Gemini nên
    không tốn quota và không phụ thuộc mạng. Kết quả luôn có source="heuristic" để giao diện ghi rõ là điểm ước tính.
    """
    try:
        results = model.quick_scores([(i.shift, i.candidate) for i in request.items])
    except Exception:  # noqa: BLE001
        logger.error("[ERROR] Quick scoring failed", exc_info=True)
        raise HTTPException(status_code=500, detail="AI scoring failed")
    logger.info("[QUICK] items=%d", len(results))
    return QuickResponse(results=results)


if __name__ == "__main__":
    # pyrefly: ignore [missing-import]
    import uvicorn
    uvicorn.run(
        "main:app",
        host=os.environ.get("AI_HOST", "127.0.0.1"),   # chỉ nghe localhost; đổi AI_HOST nếu Spring ở máy khác
        port=int(os.environ.get("AI_PORT", "8001")),
        reload=DEBUG,
        log_level="info",
    )
