"""
Bộ chấm điểm theo QUY TẮC (heuristic) - tất định, không gọi mạng.

Dùng cho hai việc:
  1. Chấm điểm NHANH cho Chợ ca (không tốn quota Gemini) - xem model.quick_scores.
  2. Dự phòng khi Gemini lỗi: kết quả luôn được GẮN CỜ source="heuristic" để Java/giao diện biết đây không phải điểm AI.

Các sửa so với bản cũ (B11):
  - Độ tin cậy tính theo TỈ LỆ (hủy/tổng ca, muộn/ca hoàn thành) có làm mịn theo số ca đã làm, thay vì trừ số điểm tuyệt đối:
    nhân viên 50 ca hủy 2 không còn bị ~60 điểm như người 5 ca hủy 2.
  - Khung giờ ưa thích đủ 4 giá trị, kể cả NIGHT; ca 00:00-05:59 là ban đêm chứ không phải "sáng".
    Mức phù hợp tính theo TỈ LỆ thời gian ca rơi vào khung ưa thích (ca 17:00-22:00 phần lớn là tối).
  - Thêm location_fit (cùng chi nhánh) - trước đây branch_id được gửi sang nhưng không dùng (A14, D2).
"""

import math
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple

from schemas import EmployeeData, EmployeeScore, ShiftData

# Trọng số (tổng = 1.0). Dùng chung cho điểm Gemini để match_score luôn nhất quán với breakdown (B7).
WEIGHTS: Dict[str, float] = {
    "reliability": 0.30,
    "skill_fit": 0.25,
    "time_fit": 0.20,
    "workload_fit": 0.15,
    "location_fit": 0.10,
}

# Làm mịn tỉ lệ bằng K "ca giả" ở tỉ lệ nền: ít dữ liệu -> kéo về mức trung bình thay vì kết luận vội.
_SMOOTH_K = 5.0
_PRIOR_CANCEL_RATE = 0.08
_PRIOR_LATE_RATE = 0.10

# Khung giờ trong ngày: (tên, [giờ bắt đầu, giờ kết thúc)); NIGHT gồm 22-24 và 0-6.
_BUCKETS: List[Tuple[str, int, int]] = [
    ("NIGHT", 0, 6), ("MORNING", 6, 12), ("AFTERNOON", 12, 18), ("EVENING", 18, 22), ("NIGHT", 22, 24),
]
_ADJACENT = {
    "MORNING": {"NIGHT", "AFTERNOON"},
    "AFTERNOON": {"MORNING", "EVENING"},
    "EVENING": {"AFTERNOON", "NIGHT"},
    "NIGHT": {"EVENING", "MORNING"},
}
_LABELS_VI = {"MORNING": "sáng", "AFTERNOON": "chiều", "EVENING": "tối", "NIGHT": "đêm", "ANY": "linh hoạt"}


# ------------------------------------------------------------------ thời gian ca

def parse_dt(text: str) -> Optional[datetime]:
    """Đọc ISO 8601 (có thể có 'Z'/offset). Trả None nếu không đọc được. Bỏ múi giờ: so sánh theo giờ địa phương của ca."""
    if not text:
        return None
    try:
        dt = datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt.replace(tzinfo=None)


def shift_duration_hours(shift: ShiftData) -> float:
    """Thời lượng ca: ưu tiên giá trị Java gửi; không có thì suy ra từ start/end; vẫn không có thì 4 giờ."""
    if shift.shift_duration_hours and shift.shift_duration_hours > 0:
        return shift.shift_duration_hours
    start, end = parse_dt(shift.start_time), parse_dt(shift.end_time)
    if start and end and end > start:
        return (end - start).total_seconds() / 3600.0
    return 4.0


def bucket_fractions(shift: ShiftData) -> Optional[Dict[str, float]]:
    """
    Tỉ lệ thời gian của ca rơi vào từng khung (MORNING/AFTERNOON/EVENING/NIGHT), tổng = 1.
    Trả None nếu không đọc được giờ của ca. Duyệt theo bước 15 phút (ca <= 168h nên rẻ).
    """
    start, end = parse_dt(shift.start_time), parse_dt(shift.end_time)
    if not start or not end or end <= start:
        return None
    total = (end - start).total_seconds()
    if total > 7 * 24 * 3600:
        end = start + timedelta(days=7)
        total = (end - start).total_seconds()
    step = timedelta(minutes=15)
    counts = {"MORNING": 0.0, "AFTERNOON": 0.0, "EVENING": 0.0, "NIGHT": 0.0}
    cur, n = start, 0
    while cur < end:
        seg = min(step, end - cur).total_seconds()
        h = cur.hour
        for name, lo, hi in _BUCKETS:
            if lo <= h < hi:
                counts[name] += seg
                break
        cur += step
        n += 1
        if n > 1000:   # chốt chặn an toàn
            break
    s = sum(counts.values())
    return {k: v / s for k, v in counts.items()} if s > 0 else None


def dominant_bucket(fractions: Optional[Dict[str, float]]) -> Optional[str]:
    if not fractions:
        return None
    return max(fractions.items(), key=lambda kv: kv[1])[0]


# ------------------------------------------------------------------ các tiêu chí

def reliability_score(c: EmployeeData) -> float:
    """0-100, tính theo TỈ LỆ hủy/muộn có làm mịn theo số ca đã làm."""
    done, cancelled, late = c.total_completed, c.total_cancelled, c.late_arrival_count
    cancel_rate = (cancelled + _SMOOTH_K * _PRIOR_CANCEL_RATE) / (done + cancelled + _SMOOTH_K)
    late_rate = (late + _SMOOTH_K * _PRIOR_LATE_RATE) / (done + _SMOOTH_K)
    score = 100.0 - 100.0 * (1.5 * cancel_rate + 0.6 * late_rate)
    return max(10.0, min(100.0, score))


def skill_fit_score(c: EmployeeData, shift: ShiftData) -> float:
    req = shift.required_level or 1
    lvl = c.skill_level or 1
    if lvl >= req:
        return min(100.0, 80.0 + (lvl - req) * 10.0)
    return max(20.0, 60.0 - (req - lvl) * 20.0)


def time_fit_score(c: EmployeeData, fractions: Optional[Dict[str, float]]) -> float:
    """Phù hợp khung giờ: 95 nếu cả ca nằm trong khung ưa thích, thấp dần theo tỉ lệ ngoài khung."""
    pref = c.preferred_time
    if pref == "ANY":
        return 85.0
    if not fractions:
        return 80.0   # không biết giờ ca: trung lập
    f = fractions.get(pref, 0.0)
    # Phần ca ngoài khung ưa thích: nằm kề khung ưa thích (vd ưa chiều mà ca vào tối) đỡ tệ hơn nằm xa (vd ưa sáng mà ca đêm)
    other_adjacent = sum(v for k, v in fractions.items() if k in _ADJACENT[pref])
    other_far = max(0.0, 1.0 - f - other_adjacent)
    score = 95.0 * f + 60.0 * other_adjacent + 40.0 * other_far
    return max(35.0, min(95.0, score))


def workload_fit_score(c: EmployeeData, hours: float) -> float:
    """Ưu tiên người còn nhiều giờ trống so với giới hạn CỦA CHÍNH HỌ: 100 khi chưa làm gì, 45 khi vừa chạm trần, 30 khi vượt."""
    limit = float(max(1, c.max_hours_per_week))
    after = c.hours_worked_this_week + hours
    u = after / limit
    if u > 1.0:
        return 30.0
    return 100.0 - 55.0 * u


def location_fit_score(c: EmployeeData, shift: ShiftData) -> float:
    """100 nếu chi nhánh chính trùng chi nhánh của ca, 60 nếu khác, 70 nếu không rõ."""
    if not shift.branch_id or not c.branch_id:
        return 70.0
    return 100.0 if shift.branch_id == c.branch_id else 60.0


def weighted_total(parts: Dict[str, float]) -> float:
    """match_score = tổng có trọng số của các tiêu chí (một nguồn sự thật duy nhất cho cả Gemini và quy tắc)."""
    return max(0.0, min(100.0, sum(WEIGHTS[k] * parts[k] for k in WEIGHTS)))


def data_confidence(c: EmployeeData) -> float:
    """
    Độ tin cậy theo LƯỢNG DỮ LIỆU lịch sử (40..95): người có nhiều ca đã ghi nhận thì các tỉ lệ hủy/muộn đáng tin hơn.
    Đây là chỉ báo đơn giản, KHÔNG phải khoảng tin cậy thống kê.
    """
    n = c.total_completed + c.total_cancelled
    return 40.0 + 55.0 * min(1.0, n / 40.0)


def score_band(score: float, confidence: float) -> Tuple[float, float]:
    """Khoảng dao động hợp lý quanh điểm: càng ít tin cậy khoảng càng rộng (±2 .. ±12)."""
    margin = max(2.0, (100.0 - confidence) * 0.2)
    return max(0.0, round(score - margin, 1)), min(100.0, round(score + margin, 1))


def _explain(c: EmployeeData, shift: ShiftData, parts: Dict[str, float]) -> str:
    return (f"Điểm theo quy tắc: kỹ năng Lv.{c.skill_level}/{shift.required_level or 1}, "
            f"tin cậy {int(round(parts['reliability']))}%, "
            f"đã làm {c.hours_worked_this_week:.1f}/{c.max_hours_per_week}h tuần này, "
            f"ưa thích ca {_LABELS_VI.get(c.preferred_time, 'linh hoạt')}.")


# ------------------------------------------------------------------ API chính

def component_scores(c: EmployeeData, shift: ShiftData, hours: float,
                     fractions: Optional[Dict[str, float]]) -> Dict[str, float]:
    return {
        "reliability": reliability_score(c),
        "skill_fit": skill_fit_score(c, shift),
        "time_fit": time_fit_score(c, fractions),
        "workload_fit": workload_fit_score(c, hours),
        "location_fit": location_fit_score(c, shift),
    }


def score_one(c: EmployeeData, shift: ShiftData, hours: Optional[float] = None,
              fractions: Optional[Dict[str, float]] = None, with_fractions: bool = True) -> EmployeeScore:
    """Chấm một ứng viên bằng quy tắc. hours/fractions có thể truyền sẵn để không tính lại cho từng người."""
    if hours is None:
        hours = shift_duration_hours(shift)
    if fractions is None and with_fractions:
        fractions = bucket_fractions(shift)
    parts = component_scores(c, shift, hours, fractions)
    total = round(weighted_total(parts), 1)
    # Ứng viên chưa đủ kỹ năng hoặc vượt trần giờ: chặn trên điểm (Java thường đã lọc, đây là lưới an toàn)
    if c.skill_level < (shift.required_level or 1):
        total = min(total, 40.0)
    if c.hours_worked_this_week + hours > c.max_hours_per_week + 1e-9:
        total = min(total, 35.0)
    conf = round(data_confidence(c) * 0.85, 1)     # quy tắc không được tin bằng một chuỗi kiểm chứng: trần ~81
    low, high = score_band(total, conf)
    breakdown = {k: round(v, 1) for k, v in parts.items()}
    breakdown["explanation"] = _explain(c, shift, parts)
    return EmployeeScore(
        employee_id=c.employee_id, full_name=c.full_name, match_score=total, breakdown=breakdown,
        source="heuristic", confidence=conf, score_low=low, score_high=high,
    )


def heuristic_match_scores(shift: ShiftData, candidates: List[EmployeeData]) -> List[EmployeeScore]:
    """Chấm toàn bộ ứng viên bằng quy tắc, sắp giảm dần theo điểm (ổn định theo employee_id khi bằng điểm)."""
    hours = shift_duration_hours(shift)
    fractions = bucket_fractions(shift)
    out = [score_one(c, shift, hours, fractions) for c in candidates]
    out.sort(key=lambda s: (-s.match_score, s.employee_id))
    return out


def is_finite(x: float) -> bool:
    return isinstance(x, (int, float)) and math.isfinite(x)
