"""
Pydantic Schemas cho AI Microservice.

Định nghĩa cấu trúc dữ liệu Request/Response giữa Spring Boot và Python.
Spring Boot gửi danh sách nhân viên đủ điều kiện (đã lọc sơ bộ) kèm thông tin ca,
Python trả về danh sách đã chấm điểm Match Score.
"""

# pyrefly: ignore [missing-import]
from pydantic import BaseModel, Field
from typing import List, Optional


# ============================================================
# REQUEST: Spring Boot → Python
# ============================================================

class EmployeeData(BaseModel):
    """
    Dữ liệu một nhân viên được Spring Boot gửi sang.
    Chỉ gồm các trường cần thiết cho AI chấm điểm, KHÔNG gửi password_hash.
    """
    employee_id: int = Field(..., description="ID nhân viên")
    full_name: str = Field(..., description="Họ tên nhân viên")
    skill_level: int = Field(..., ge=1, le=5, description="Mức kỹ năng của nhân viên cho skill yêu cầu")
    total_completed: int = Field(0, ge=0, description="Tổng ca đã hoàn thành")
    total_cancelled: int = Field(0, ge=0, description="Tổng ca đã hủy")
    late_arrival_count: int = Field(0, ge=0, description="Số lần đến muộn")
    preferred_time: str = Field("MORNING", description="Khung giờ ưa thích: MORNING|AFTERNOON|EVENING|NIGHT")
    hours_worked_this_week: float = Field(0.0, ge=0, description="Số giờ đã làm trong tuần hiện tại")
    max_hours_per_week: int = Field(40, description="Giới hạn giờ làm/tuần")
    branch_id: int = Field(0, description="ID chi nhánh chính của nhân viên")


class ShiftData(BaseModel):
    """
    Dữ liệu ca làm việc cần tìm người phù hợp.
    """
    shift_id: int = Field(..., description="ID ca làm việc")
    branch_id: int = Field(..., description="ID chi nhánh")
    required_skill_id: int = Field(..., description="ID kỹ năng yêu cầu")
    required_level: int = Field(1, ge=1, le=5, description="Mức kỹ năng yêu cầu")
    start_time: str = Field(..., description="Thời gian bắt đầu (ISO 8601)")
    end_time: str = Field(..., description="Thời gian kết thúc (ISO 8601)")
    shift_duration_hours: float = Field(..., description="Thời lượng ca (giờ)")


class MatchRequest(BaseModel):
    """
    ★ REQUEST CHÍNH: Spring Boot gửi sang Python.
    Gồm thông tin ca + danh sách nhân viên đủ điều kiện cơ bản.
    """
    shift: ShiftData
    candidates: List[EmployeeData] = Field(..., min_length=1, description="Danh sách ứng viên (≥1 người)")


# ============================================================
# RESPONSE: Python → Spring Boot
# ============================================================

class EmployeeScore(BaseModel):
    """
    Kết quả chấm điểm cho một nhân viên.
    """
    employee_id: int = Field(..., description="ID nhân viên")
    full_name: str = Field(..., description="Họ tên nhân viên")
    match_score: float = Field(..., ge=0, le=100, description="Điểm phù hợp 0-100%")
    breakdown: dict = Field(
        default_factory=dict,
        description="Chi tiết điểm từng tiêu chí (để debug/hiển thị)"
    )


class MatchResponse(BaseModel):
    """
    ★ RESPONSE CHÍNH: Python trả về Spring Boot.
    Danh sách nhân viên đã sắp xếp theo Match Score giảm dần.
    """
    shift_id: int
    ranked_candidates: List[EmployeeScore] = Field(
        ..., description="Danh sách ứng viên đã xếp hạng theo match_score giảm dần"
    )
    total_candidates: int = Field(..., description="Tổng số ứng viên")
