import os
import json
import time
import logging
from typing import List, Dict, Any
# pyrefly: ignore [missing-import]
from pydantic import BaseModel
# pyrefly: ignore [missing-import]
from dotenv import load_dotenv
# pyrefly: ignore [missing-import]
from google import genai
from schemas import EmployeeData, ShiftData, EmployeeScore

# Nạp API key ngay tại module để model.py có thể chạy độc lập
_env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ApiGeminiKey.env")
if os.path.exists(_env_path):
    load_dotenv(_env_path)
else:
    load_dotenv()

logger = logging.getLogger("ai-service")

# In-memory cache để chống spam token: { cache_key: { "expires_at": float, "data": List[EmployeeScore] } }
_MEMORY_CACHE: Dict[str, Dict[str, Any]] = {}
CACHE_TTL_SECONDS = 600  # 10 phút

# Khai báo schema để ép Gemini trả về đúng định dạng JSON ta cần
class GeminiCandidateResult(BaseModel):
    employee_id: int
    match_score: float
    reliability_score: float
    skill_fit_score: float
    time_fit_score: float
    workload_fit_score: float
    explanation: str

class GeminiResponse(BaseModel):
    results: List[GeminiCandidateResult]


def heuristic_match_scores(
    shift: ShiftData,
    candidates: List[EmployeeData],
    fallback_reason: str = ""
) -> List[EmployeeScore]:
    """
    Thuật toán chấm điểm quy tắc thông minh (Heuristic/Fallback).
    Được gọi khi:
      1. Hết token / Quota Gemini API (429)
      2. Mất kết nối internet hoặc API Google quá tải (503)
      3. Thiếu GEMINI_API_KEY
    Đảm bảo hệ thống KHÔNG BAO GIỜ bị gián đoạn hay 'hỏng chương trình'.
    """
    results: List[EmployeeScore] = []

    # Xác định khung giờ ca (Sáng / Chiều / Tối)
    is_morning = True
    shift_hour = 8
    try:
        start_str = str(shift.start_time)
        if "T" in start_str:
            shift_hour = int(start_str.split("T")[1].split(":")[0])
        elif ":" in start_str:
            shift_hour = int(start_str.split(":")[0])
        is_morning = shift_hour < 12
    except Exception:
        is_morning = True

    shift_time_label = "Ca sáng" if is_morning else ("Ca chiều" if shift_hour < 18 else "Ca tối")

    for c in candidates:
        # 1. Điểm độ tin cậy (Reliability): 0 - 100
        # Hủy ca trừ 20đ, đi muộn trừ 10đ, hoàn thành ca cộng thêm
        penalties = (c.total_cancelled * 20.0) + (c.late_arrival_count * 10.0)
        base_reliability = 100.0 - penalties
        if c.total_completed >= 5:
            base_reliability += min(10.0, c.total_completed * 1.5)
        reliability = max(15.0, min(100.0, base_reliability))

        # 2. Điểm kỹ năng (Skill Fit): 0 - 100
        req_level = shift.required_level if shift.required_level else 1
        emp_level = c.skill_level if c.skill_level else 1
        if emp_level >= req_level:
            skill_fit = min(100.0, 80.0 + (emp_level - req_level) * 10.0)
        else:
            skill_fit = max(20.0, 60.0 - (req_level - emp_level) * 20.0)

        # 3. Điểm phù hợp thời gian (Time Fit): 0 - 100
        pref = (c.preferred_time or "").lower()
        if is_morning and ("sáng" in pref or "morning" in pref):
            time_fit = 95.0
        elif (not is_morning) and ("chiều" in pref or "tối" in pref or "afternoon" in pref or "evening" in pref):
            time_fit = 95.0
        elif "linh hoạt" in pref or "any" in pref or not pref:
            time_fit = 85.0
        else:
            time_fit = 65.0

        # 4. Điểm khối lượng công việc (Workload Fit): 0 - 100
        # Ưu tiên nhân viên chưa đủ 40h/tuần
        hours_worked = c.hours_worked_this_week if c.hours_worked_this_week else 0.0
        duration = shift.shift_duration_hours if shift.shift_duration_hours else 4.0
        after_shift = hours_worked + duration

        if after_shift <= 40.0:
            # Còn thiếu giờ -> rất ưu tiên
            workload_fit = 85.0 + ((40.0 - after_shift) / 40.0) * 15.0
        elif after_shift <= c.max_hours_per_week:
            # Đủ 40h nhưng chưa vượt quá max -> chấp nhận làm thêm
            workload_fit = 70.0
        else:
            # Vượt quá giờ tối đa -> trừ điểm
            workload_fit = 35.0

        # Tính tổng điểm có trọng số
        match_score = round(
            reliability * 0.30 +
            skill_fit * 0.30 +
            time_fit * 0.20 +
            workload_fit * 0.20,
            1
        )

        note_prefix = f"[{fallback_reason}] " if fallback_reason else ""
        explanation = (
            f"{note_prefix}Kỹ năng Lv.{emp_level}/{req_level}, độ tin cậy {int(reliability)}%, "
            f"đã làm {hours_worked:.1f}h/40h tuần này ({c.preferred_time or 'Thời gian linh hoạt'})."
        )

        score = EmployeeScore(
            employee_id=c.employee_id,
            full_name=c.full_name,
            match_score=match_score,
            breakdown={
                "reliability": round(reliability, 1),
                "skill_fit": round(skill_fit, 1),
                "time_fit": round(time_fit, 1),
                "workload_fit": round(workload_fit, 1),
                "explanation": explanation
            }
        )
        results.append(score)

    results.sort(key=lambda x: x.match_score, reverse=True)
    return results


def calculate_match_scores(
    shift: ShiftData,
    candidates: List[EmployeeData]
) -> List[EmployeeScore]:
    """
    Dùng Gemini API để chấm điểm ứng viên kèm bộ nhớ cache và cơ chế dự phòng tự động (fallback)
    khi hết token / giới hạn cuộc gọi API.
    """
    if not candidates:
        return []

    # 1. Kiểm tra cache
    cache_key = f"shift_{shift.shift_id}_{len(candidates)}_" + "_".join(str(c.employee_id) for c in candidates)
    now = time.time()
    
    # Dọn dẹp cache quá hạn khi dung lượng vượt quá 100 bản ghi
    if len(_MEMORY_CACHE) > 100:
        expired = [k for k, v in _MEMORY_CACHE.items() if now >= v["expires_at"]]
        for k in expired:
            del _MEMORY_CACHE[k]

    if cache_key in _MEMORY_CACHE:
        cache_entry = _MEMORY_CACHE[cache_key]
        if now < cache_entry["expires_at"]:
            logger.info(f"[CACHE HIT] Trả về gợi ý từ bộ nhớ cache cho Ca #{shift.shift_id}")
            return cache_entry["data"]

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        logger.warning("Thiếu biến môi trường GEMINI_API_KEY, chuyển sang bộ chấm điểm dự phòng.")
        fallback = heuristic_match_scores(shift, candidates, fallback_reason="Quy tắc chuẩn")
        _MEMORY_CACHE[cache_key] = {"expires_at": now + CACHE_TTL_SECONDS, "data": fallback}
        return fallback

    # 2. Tạo Prompt chứa dữ liệu
    shift_info = (f"Ca làm việc ID {shift.shift_id}: Từ {shift.start_time} đến {shift.end_time} "
                  f"({shift.shift_duration_hours} giờ), Yêu cầu Skill Level: {shift.required_level}")
    
    candidates_info = ""
    for c in candidates:
        candidates_info += (f"- ID {c.employee_id}: {c.full_name}, Kỹ năng: {c.skill_level}, "
                            f"Đã làm {c.hours_worked_this_week}/{c.max_hours_per_week} giờ. "
                            f"Lịch sử: {c.total_completed} hoàn thành, {c.total_cancelled} hủy, {c.late_arrival_count} đi muộn. "
                            f"Ưa thích ca: {c.preferred_time}\n")

    prompt = f"""Bạn là AI phân bổ nhân sự. Nhiệm vụ: Đánh giá độ phù hợp của từng ứng viên cho ca làm việc.
Tiêu chí: 
1. Reliability: Đi muộn hoặc hủy ca nhiều thì trừ điểm.
2. Skill: Level kỹ năng phải bằng hoặc hơn yêu cầu.
3. Time: Ưa thích giờ nào thì làm giờ đó tốt hơn.
4. Workload: Số giờ làm tối thiểu trong tuần là 40h, ưu tiên người chưa đủ 40h. Nếu đã đủ 40h vẫn có thể làm thêm. (Giờ làm việc của ca này là {shift.shift_duration_hours} giờ)

Thông tin ca:
{shift_info}

Danh sách ứng viên:
{candidates_info}

Hãy chấm điểm match_score (0-100) và các điểm thành phần (0-100). Đưa ra explanation ngắn gọn.

BẠN PHẢI TRẢ VỀ ĐÚNG ĐỊNH DẠNG JSON NHƯ SAU (chỉ trả về JSON, không thêm text nào khác):
{{
  "results": [
    {{
      "employee_id": 1,
      "match_score": 85,
      "reliability_score": 90,
      "skill_fit_score": 100,
      "time_fit_score": 80,
      "workload_fit_score": 70,
      "explanation": "..."
    }}
  ]
}}
"""

    # 3. Gọi Gemini với retry và fallback
    client = genai.Client(api_key=api_key)
    response = None
    fallback_reason = ""

    try:
        response = client.models.generate_content(
            model='gemini-3.8-flash',
            contents=prompt,
            config={
                'response_mime_type': 'application/json',
            },
        )
    except Exception as e:
        error_msg = str(e)
        if "429" in error_msg or "RESOURCE_EXHAUSTED" in error_msg or "quota" in error_msg.lower():
            logger.warning("🚨 GEMINI 429: Hết lượt gọi API hoặc chạm giới hạn RPM. Kích hoạt thuật toán quy tắc dự phòng.")
            fallback_reason = "Tự động dự phòng khi AI hết token"
        elif "503" in error_msg or "UNAVAILABLE" in error_msg:
            logger.warning("⚠️ GEMINI 503: Server Google quá tải. Kích hoạt thuật toán quy tắc dự phòng.")
            fallback_reason = "Dự phòng khi Google Server bận"
        else:
            logger.warning(f"⚠️ Lỗi gọi Gemini ({error_msg}). Kích hoạt thuật toán quy tắc dự phòng.")
            fallback_reason = "Dự phòng thuật toán thông minh"

        # TỰ ĐỘNG DỰ PHÒNG: KHÔNG ĐỂ LỖI LÀM HỎNG CHƯƠNG TRÌNH
        fallback_results = heuristic_match_scores(shift, candidates, fallback_reason=fallback_reason)
        _MEMORY_CACHE[cache_key] = {"expires_at": now + CACHE_TTL_SECONDS, "data": fallback_results}
        return fallback_results

    # 4. Phân tích kết quả từ Gemini
    try:
        response_text = response.text.strip()
        if response_text.startswith("```json"):
            response_text = response_text[7:]
        if response_text.endswith("```"):
            response_text = response_text[:-3]
        ai_data = json.loads(response_text.strip())

        final_results = []
        for emp in candidates:
            ai_eval = next((item for item in ai_data.get("results", []) if item["employee_id"] == emp.employee_id), None)
            if ai_eval:
                score = EmployeeScore(
                    employee_id=emp.employee_id,
                    full_name=emp.full_name,
                    match_score=float(ai_eval["match_score"]),
                    breakdown={
                        "reliability": float(ai_eval["reliability_score"]),
                        "skill_fit": float(ai_eval["skill_fit_score"]),
                        "time_fit": float(ai_eval["time_fit_score"]),
                        "workload_fit": float(ai_eval.get("workload_fit_score", 0)),
                        "explanation": str(ai_eval.get("explanation", ""))
                    }
                )
                final_results.append(score)

        if not final_results:
            final_results = heuristic_match_scores(shift, candidates, fallback_reason="Chuẩn hóa kết quả")

        final_results.sort(key=lambda x: x.match_score, reverse=True)
        # Lưu cache để các lần click sau không tốn thêm token
        _MEMORY_CACHE[cache_key] = {"expires_at": now + CACHE_TTL_SECONDS, "data": final_results}
        return final_results

    except Exception as parse_err:
        logger.error(f"Lỗi parse JSON từ Gemini: {parse_err}. Kích hoạt thuật toán dự phòng.", exc_info=True)
        fallback_results = heuristic_match_scores(shift, candidates, fallback_reason="Dự phòng parse AI")
        _MEMORY_CACHE[cache_key] = {"expires_at": now + CACHE_TTL_SECONDS, "data": fallback_results}
        return fallback_results
