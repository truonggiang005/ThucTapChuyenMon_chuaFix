"""
Pydantic Schemas cho AI Microservice (hợp đồng dữ liệu Spring Boot <-> Python).

Nguyên tắc (B10): phía nhận phải KHOAN DUNG với dữ liệu "gần đúng" và chỉ từ chối khi dữ liệu vô nghĩa.
  - null / thiếu trường tùy chọn  -> dùng giá trị mặc định hợp lý (Java có thể gửi Integer/String null).
  - số nằm ngoài khoảng           -> kẹp (clamp) vào khoảng hợp lệ thay vì trả 422.
  - chuỗi số ("12")               -> ép về số (pydantic lax mode).
Chỉ những trường không thể suy ra (shift_id, employee_id, danh sách ứng viên rỗng/quá lớn) mới bị từ chối.

Mọi lỗi 422 đều đã được Java báo thẳng cho người dùng (AI_BAD_REQUEST) chứ không còn rơi im lặng vào điểm 75% giả.
"""

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator

# Giới hạn kích thước đầu vào (SEC-09): chống phình RAM / đốt quota
MAX_CANDIDATES = 200
MAX_QUICK_ITEMS = 100
MAX_SHIFT_HOURS = 168.0   # 1 tuần; Java mặc định chỉ cho ca tối đa 24h (app.shift.max-hours)

_PREFERRED_TIMES = {"MORNING", "AFTERNOON", "EVENING", "NIGHT", "ANY"}
_VI_PREFERRED = {
    "SÁNG": "MORNING", "SANG": "MORNING",
    "CHIỀU": "AFTERNOON", "CHIEU": "AFTERNOON",
    "TỐI": "EVENING", "TOI": "EVENING",
    "ĐÊM": "NIGHT", "DEM": "NIGHT",
    "LINH HOẠT": "ANY", "LINH HOAT": "ANY",
}


def _as_float(value: Any, default: float) -> float:
    if value is None or isinstance(value, bool):
        return default
    try:
        f = float(value)
    except (TypeError, ValueError):
        return default
    return default if f != f else f   # NaN -> default


def _clamp_int(value: Any, default: int, lo: int, hi: int) -> int:
    f = _as_float(value, float(default))
    return int(max(lo, min(hi, round(f))))


def normalize_preferred_time(value: Any) -> str:
    """Chuẩn hóa khung giờ ưa thích về MORNING|AFTERNOON|EVENING|NIGHT|ANY (không bao giờ ném lỗi)."""
    if value is None:
        return "ANY"
    text = str(value).strip().upper()
    if text in _PREFERRED_TIMES:
        return text
    return _VI_PREFERRED.get(text, "ANY")


# REQUEST: Spring Boot -> Python

class EmployeeData(BaseModel):
    """
    Dữ liệu một nhân viên do Spring gửi sang. Chỉ gồm trường cần để chấm điểm, KHÔNG có mật khẩu/email.
    full_name chỉ để hiển thị lại trong kết quả, KHÔNG đưa vào prompt gửi Google.
    """
    employee_id: int = Field(..., description="ID nhân viên")
    full_name: str = Field("", description="Họ tên (chỉ để hiển thị)")
    skill_level: int = Field(1, description="Mức kỹ năng cho skill yêu cầu (1..5)")
    total_completed: int = Field(0, description="Tổng ca đã hoàn thành")
    total_cancelled: int = Field(0, description="Tổng ca đã hủy")
    late_arrival_count: int = Field(0, description="Số lần đến muộn")
    preferred_time: str = Field("ANY", description="MORNING|AFTERNOON|EVENING|NIGHT|ANY")
    hours_worked_this_week: float = Field(0.0, description="Giờ đã làm trong tuần của ca")
    max_hours_per_week: int = Field(40, description="Giới hạn giờ làm/tuần")
    branch_id: int = Field(0, description="ID chi nhánh chính của nhân viên (0 = không rõ)")

    @field_validator("full_name", mode="before")
    @classmethod
    def _name(cls, v: Any) -> str:
        return "" if v is None else str(v)[:150]

    @field_validator("skill_level", mode="before")
    @classmethod
    def _skill(cls, v: Any) -> int:
        return _clamp_int(v, 1, 1, 5)

    @field_validator("total_completed", "total_cancelled", "late_arrival_count", mode="before")
    @classmethod
    def _counts(cls, v: Any) -> int:
        return _clamp_int(v, 0, 0, 1_000_000)

    @field_validator("preferred_time", mode="before")
    @classmethod
    def _pref(cls, v: Any) -> str:
        return normalize_preferred_time(v)

    @field_validator("hours_worked_this_week", mode="before")
    @classmethod
    def _hours(cls, v: Any) -> float:
        return max(0.0, min(200.0, _as_float(v, 0.0)))

    @field_validator("max_hours_per_week", mode="before")
    @classmethod
    def _max_hours(cls, v: Any) -> int:
        return _clamp_int(v, 40, 1, 168)

    @field_validator("branch_id", mode="before")
    @classmethod
    def _branch(cls, v: Any) -> int:
        return _clamp_int(v, 0, 0, 2**62)


class ShiftData(BaseModel):
    """Dữ liệu ca làm việc cần tìm người phù hợp."""
    shift_id: int = Field(..., description="ID ca làm việc")
    branch_id: int = Field(0, description="ID chi nhánh (0 = không rõ)")
    required_skill_id: int = Field(0, description="ID kỹ năng yêu cầu (0 = không yêu cầu)")
    required_level: int = Field(1, description="Mức kỹ năng yêu cầu (1..5)")
    start_time: str = Field("", description="Bắt đầu (ISO 8601, giờ địa phương không kèm múi giờ)")
    end_time: str = Field("", description="Kết thúc (ISO 8601)")
    shift_duration_hours: float = Field(0.0, description="Thời lượng ca (giờ); <=0 thì tự suy ra từ start/end")

    @field_validator("branch_id", "required_skill_id", mode="before")
    @classmethod
    def _ids(cls, v: Any) -> int:
        return _clamp_int(v, 0, 0, 2**62)

    @field_validator("required_level", mode="before")
    @classmethod
    def _level(cls, v: Any) -> int:
        return _clamp_int(v, 1, 1, 5)

    @field_validator("start_time", "end_time", mode="before")
    @classmethod
    def _times(cls, v: Any) -> str:
        return "" if v is None else str(v)[:40]

    @field_validator("shift_duration_hours", mode="before")
    @classmethod
    def _duration(cls, v: Any) -> float:
        return max(0.0, min(MAX_SHIFT_HOURS, _as_float(v, 0.0)))


class MatchRequest(BaseModel):
    """★ REQUEST CHÍNH: thông tin ca + danh sách ứng viên đã được Spring lọc đủ điều kiện."""
    shift: ShiftData
    candidates: List[EmployeeData] = Field(
        ..., min_length=1, max_length=MAX_CANDIDATES,
        description=f"Danh sách ứng viên (1..{MAX_CANDIDATES} người)"
    )


class QuickItem(BaseModel):
    """Một cặp (ca, ứng viên) cần chấm điểm nhanh."""
    shift: ShiftData
    candidate: EmployeeData


class QuickRequest(BaseModel):
    """Chấm điểm nhanh theo lô cho Chợ ca (B4): nhiều ca trong MỘT lời gọi, chỉ dùng quy tắc, không tốn quota Gemini."""
    items: List[QuickItem] = Field(..., min_length=1, max_length=MAX_QUICK_ITEMS)


# RESPONSE: Python -> Spring Boot

class EmployeeScore(BaseModel):
    """Kết quả chấm điểm một nhân viên."""
    employee_id: int
    full_name: str = ""
    match_score: float = Field(..., ge=0, le=100, description="Điểm phù hợp 0-100")
    breakdown: Dict[str, Any] = Field(
        default_factory=dict,
        description="reliability, skill_fit, time_fit, workload_fit, location_fit (0-100) + explanation"
    )
    source: str = Field("gemini", description="Nguồn của điểm này: 'gemini' hoặc 'heuristic'")
    confidence: float = Field(50.0, ge=0, le=100,
                              description="Độ tin cậy 0-100: nhiều dữ liệu lịch sử và (với Gemini) đồng thuận với quy tắc -> cao")
    score_low: float = Field(0.0, ge=0, le=100, description="Cận dưới của khoảng dao động hợp lý")
    score_high: float = Field(100.0, ge=0, le=100, description="Cận trên của khoảng dao động hợp lý")


class MatchResponse(BaseModel):
    """
    ★ RESPONSE CHÍNH. ranked_candidates đã sắp theo match_score giảm dần.

    source: "gemini" (mọi điểm do Gemini chấm) | "heuristic" (toàn bộ do quy tắc) | "mixed" (một phần).
    fallback = True khi KHÔNG phải mọi điểm đều do Gemini chấm -> Java không cho cron tự gán, giao diện phải báo rõ (B1, B2).
    """
    shift_id: int
    ranked_candidates: List[EmployeeScore]
    total_candidates: int
    source: str = "gemini"
    fallback: bool = False
    fallback_reason: Optional[str] = Field(None, description="Mã lý do: GEMINI_NOT_CONFIGURED, GEMINI_RATE_LIMITED, ...")
    message: Optional[str] = Field(None, description="Thông báo thân thiện cho người dùng khi fallback")


class QuickResult(BaseModel):
    shift_id: int
    employee_id: int
    match_score: float = Field(..., ge=0, le=100)
    confidence: float = Field(..., ge=0, le=100)
    source: str = "heuristic"


class QuickResponse(BaseModel):
    results: List[QuickResult]
