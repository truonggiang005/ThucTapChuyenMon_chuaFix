"""
AI Scoring Model - Mock Model chấm điểm Match Score.

Module này chứa logic chấm điểm độ phù hợp của nhân viên với ca làm việc.
Hiện tại sử dụng Mock Model (rule-based scoring).
Sau này có thể thay thế bằng ML model thật (Scikit-learn / TensorFlow).

★ CÔNG THỨC TÍNH MATCH SCORE (0-100%):
┌─────────────────────────────────────────────────────────┐
│  Match Score = w1 × Reliability   (35%)                 │
│             + w2 × Skill Fit      (25%)                 │
│             + w3 × Time Fit       (20%)                 │
│             + w4 × Workload Fit   (20%)                 │
│                                                         │
│  Trong đó:                                              │
│  - Reliability: Dựa trên lịch sử hoàn thành/hủy/muộn   │
│  - Skill Fit: So sánh skill_level vs required_level     │
│  - Time Fit: Khung giờ ưa thích vs giờ ca thực tế      │
│  - Workload Fit: Số giờ còn lại trong tuần              │
└─────────────────────────────────────────────────────────┘
"""

import pandas as pd
from datetime import datetime
from typing import List, Dict
from schemas import EmployeeData, ShiftData, EmployeeScore


# ============================================================
# TRỌNG SỐ CÁC TIÊU CHÍ (tổng = 1.0)
# ============================================================
WEIGHT_RELIABILITY = 0.35   # Độ tin cậy (lịch sử làm việc)
WEIGHT_SKILL_FIT = 0.25     # Mức kỹ năng phù hợp
WEIGHT_TIME_FIT = 0.20      # Khung giờ ưa thích
WEIGHT_WORKLOAD_FIT = 0.20  # Khối lượng công việc còn lại


# ============================================================
# MAPPING KHUNG GIỜ
# ============================================================
TIME_SLOTS = {
    "MORNING": (6, 12),     # 06:00 - 12:00
    "AFTERNOON": (12, 17),  # 12:00 - 17:00
    "EVENING": (17, 21),    # 17:00 - 21:00
    "NIGHT": (21, 6),       # 21:00 - 06:00 (qua ngày)
}


def _get_shift_time_slot(start_time_str: str) -> str:
    """
    Xác định khung giờ của ca dựa trên start_time.
    Trả về: MORNING / AFTERNOON / EVENING / NIGHT
    """
    try:
        start_time = datetime.fromisoformat(start_time_str)
        hour = start_time.hour
    except (ValueError, TypeError):
        return "MORNING"  # fallback

    if 6 <= hour < 12:
        return "MORNING"
    elif 12 <= hour < 17:
        return "AFTERNOON"
    elif 17 <= hour < 21:
        return "EVENING"
    else:
        return "NIGHT"


def _calc_reliability_score(emp: EmployeeData) -> float:
    """
    ★ Tiêu chí 1: Độ tin cậy (0-100 điểm)

    Công thức:
      total_shifts = completed + cancelled
      completion_rate = completed / total_shifts  (0-1)
      late_penalty = min(late_count × 5, 30)      (tối đa trừ 30 điểm)
      cancel_penalty = min(cancelled × 8, 40)     (tối đa trừ 40 điểm)

      reliability = completion_rate × 100 - late_penalty - cancel_penalty

    Ví dụ:
      NV A: completed=50, cancelled=2, late=1 → (50/52)×100 - 5 - 16 = 75.2
      NV B: completed=10, cancelled=8, late=5 → (10/18)×100 - 25 - 64 = -33.4 → 0
    """
    total_shifts = emp.total_completed + emp.total_cancelled

    if total_shifts == 0:
        # Nhân viên mới, chưa có lịch sử → điểm trung bình
        return 50.0

    completion_rate = emp.total_completed / total_shifts
    base_score = completion_rate * 100

    # Phạt trễ: mỗi lần trễ trừ 5 điểm, tối đa 30 điểm
    late_penalty = min(emp.late_arrival_count * 5, 30)

    # Phạt hủy: mỗi lần hủy trừ 8 điểm, tối đa 40 điểm
    cancel_penalty = min(emp.total_cancelled * 8, 40)

    score = base_score - late_penalty - cancel_penalty
    return max(0.0, min(100.0, score))  # Clamp [0, 100]


def _calc_skill_fit_score(emp: EmployeeData, required_level: int) -> float:
    """
    ★ Tiêu chí 2: Độ phù hợp kỹ năng (0-100 điểm)

    Logic:
      - skill_level == required_level → 80 điểm (vừa đủ)
      - skill_level > required_level  → 80 + bonus (tối đa 100)
      - skill_level < required_level  → giảm mạnh (đáng lẽ đã bị lọc ở Spring Boot)

    Ví dụ:
      required=3, skill=3 → 80
      required=3, skill=5 → 80 + (2/5)×20 = 88
      required=3, skill=1 → (1/3)×60 = 20
    """
    if emp.skill_level >= required_level:
        # Đạt yêu cầu: base 80 + bonus cho skill thừa
        excess = emp.skill_level - required_level
        bonus = (excess / 5) * 20  # Mỗi level thừa thêm 4 điểm
        return min(100.0, 80.0 + bonus)
    else:
        # Không đạt (trường hợp ngoại lệ - Spring Boot lẽ ra đã lọc)
        ratio = emp.skill_level / required_level
        return ratio * 60.0


def _calc_time_fit_score(emp: EmployeeData, shift_time_slot: str) -> float:
    """
    ★ Tiêu chí 3: Khung giờ ưa thích (0-100 điểm)

    Logic:
      - preferred_time == shift_time_slot → 100 điểm (trùng khớp hoàn hảo)
      - Lệch 1 khung giờ (VD: MORNING vs AFTERNOON) → 60 điểm
      - Lệch 2+ khung giờ → 30 điểm

    Ví dụ:
      NV thích MORNING, ca MORNING → 100
      NV thích MORNING, ca AFTERNOON → 60
      NV thích MORNING, ca NIGHT → 30
    """
    slots_order = ["MORNING", "AFTERNOON", "EVENING", "NIGHT"]

    if emp.preferred_time == shift_time_slot:
        return 100.0

    try:
        emp_idx = slots_order.index(emp.preferred_time)
        shift_idx = slots_order.index(shift_time_slot)
        distance = min(abs(emp_idx - shift_idx), 4 - abs(emp_idx - shift_idx))
    except ValueError:
        return 50.0  # fallback nếu giá trị không hợp lệ

    if distance == 1:
        return 60.0
    else:
        return 30.0


def _calc_workload_fit_score(emp: EmployeeData, shift_duration_hours: float) -> float:
    """
    ★ Tiêu chí 4: Khối lượng công việc (0-100 điểm)

    Logic:
      remaining_hours = max_hours - hours_worked
      capacity_ratio = remaining_hours / max_hours

      - Còn nhiều giờ rảnh → điểm cao (ưu tiên phân bổ đều)
      - Gần hết quota → điểm thấp

    Ví dụ:
      Max=40h, worked=10h, shift=8h → remaining=30h → (30/40)×100 = 75
      Max=40h, worked=35h, shift=8h → remaining=5h → (5/40)×100 = 12.5
    """
    remaining = emp.max_hours_per_week - emp.hours_worked_this_week

    if remaining <= 0 or remaining < shift_duration_hours:
        return 0.0  # Hết quota hoặc không đủ giờ cho ca này

    capacity_ratio = remaining / emp.max_hours_per_week
    return capacity_ratio * 100.0


def calculate_match_scores(
    shift: ShiftData,
    candidates: List[EmployeeData]
) -> List[EmployeeScore]:
    """
    ★ HÀM CHÍNH: Tính Match Score cho tất cả ứng viên.

    Input:
      - shift: Thông tin ca làm việc
      - candidates: Danh sách nhân viên đã qua vòng lọc sơ bộ (Spring Boot)

    Output:
      - Danh sách EmployeeScore đã sắp xếp theo match_score GIẢM DẦN

    Pipeline:
      1. Xác định khung giờ ca (MORNING/AFTERNOON/EVENING/NIGHT)
      2. Với mỗi ứng viên, tính 4 tiêu chí con
      3. Tổng hợp: match_score = Σ(weight_i × score_i)
      4. Sắp xếp giảm dần theo match_score
    """
    shift_time_slot = _get_shift_time_slot(shift.start_time)
    results: List[EmployeeScore] = []

    for emp in candidates:
        # Tính 4 tiêu chí con
        reliability = _calc_reliability_score(emp)
        skill_fit = _calc_skill_fit_score(emp, shift.required_level)
        time_fit = _calc_time_fit_score(emp, shift_time_slot)
        workload_fit = _calc_workload_fit_score(emp, shift.shift_duration_hours)

        # Tổng hợp điểm theo trọng số
        match_score = (
            WEIGHT_RELIABILITY * reliability +
            WEIGHT_SKILL_FIT * skill_fit +
            WEIGHT_TIME_FIT * time_fit +
            WEIGHT_WORKLOAD_FIT * workload_fit
        )

        # Làm tròn 2 chữ số thập phân
        match_score = round(match_score, 2)

        # Lưu chi tiết breakdown để debug / hiển thị trên UI
        breakdown = {
            "reliability": round(reliability, 2),
            "skill_fit": round(skill_fit, 2),
            "time_fit": round(time_fit, 2),
            "workload_fit": round(workload_fit, 2),
            "weights": {
                "reliability": WEIGHT_RELIABILITY,
                "skill_fit": WEIGHT_SKILL_FIT,
                "time_fit": WEIGHT_TIME_FIT,
                "workload_fit": WEIGHT_WORKLOAD_FIT,
            }
        }

        results.append(EmployeeScore(
            employee_id=emp.employee_id,
            full_name=emp.full_name,
            match_score=match_score,
            breakdown=breakdown,
        ))

    # Sắp xếp giảm dần theo match_score
    results.sort(key=lambda x: x.match_score, reverse=True)
    return results
