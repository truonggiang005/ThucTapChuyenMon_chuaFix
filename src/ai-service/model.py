import os
import json
from typing import List
# pyrefly: ignore [missing-import]
from pydantic import BaseModel
# pyrefly: ignore [missing-import]
from google import genai
from schemas import EmployeeData, ShiftData, EmployeeScore

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

def calculate_match_scores(
    shift: ShiftData,
    candidates: List[EmployeeData]
) -> List[EmployeeScore]:
    """
    Dùng Gemini API để chấm điểm toàn bộ ứng viên thay vì tự viết logic.
    """
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("Thiếu biến môi trường GEMINI_API_KEY")

    # 1. Tạo Prompt chứa dữ liệu
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

    # 2. Gọi Gemini và ép kiểu trả về JSON
    client = genai.Client(api_key=api_key)
    
    # Thử gọi API tối đa 3 lần nếu gặp lỗi mạng
    max_retries = 3
    import time
    for attempt in range(max_retries):
        try:
            response = client.models.generate_content(
                model='gemini-3.8-flash',
                contents=prompt,
                config={
                    'response_mime_type': 'application/json',
                },
            )
            break  # Nếu thành công thì thoát vòng lặp
        except Exception as e:
            error_msg = str(e)
            # Phát hiện lỗi 429 - Hết lượt gọi API
            if "429" in error_msg or "RESOURCE_EXHAUSTED" in error_msg:
                print("\n" + "="*60)
                print("🚨 CẢNH BÁO: HẾT LƯỢT GỌI API GEMINI (LỖI 429)")
                print("🚨 Bạn đã vượt quá số lần gọi miễn phí trong 1 phút hoặc 1 ngày.")
                print("🚨 Giải pháp: Đợi 1 phút rồi thử lại, HOẶC thay API Key mới!")
                print("="*60 + "\n")
                raise ValueError("Google báo lỗi 429: Bạn đã hết lượt gọi API miễn phí (Quota Exceeded). Vui lòng đợi 1 phút hoặc thay API Key mới!")
            
            # Phát hiện lỗi 503 - Server Google quá tải
            if "503" in error_msg or "UNAVAILABLE" in error_msg:
                print(f"⚠️ Google Server đang quá tải (503). Lần thử thứ {attempt + 1}/{max_retries}...")
            
            if attempt == max_retries - 1:
                raise ValueError(f"Lỗi khi gọi API Gemini sau 3 lần thử: {error_msg}")
            time.sleep(2)  # Nghỉ 2 giây rồi thử lại

    # 3. Phân tích JSON trả về và map sang EmployeeScore của hệ thống
    response_text = response.text.strip()
    if response_text.startswith("```json"):
        response_text = response_text[7:]
    if response_text.endswith("```"):
        response_text = response_text[:-3]
    ai_data = json.loads(response_text.strip())
    
    final_results = []
    for emp in candidates:
        # Tìm kết quả AI đánh giá cho nhân viên này
        ai_eval = next((item for item in ai_data["results"] if item["employee_id"] == emp.employee_id), None)
        
        if ai_eval:
            score = EmployeeScore(
                employee_id=emp.employee_id,
                full_name=emp.full_name,
                match_score=ai_eval["match_score"],
                breakdown={
                    "reliability": ai_eval["reliability_score"],
                    "skill_fit": ai_eval["skill_fit_score"],
                    "time_fit": ai_eval["time_fit_score"],
                    "workload_fit": ai_eval.get("workload_fit_score", 0),
                    "explanation": ai_eval["explanation"]
                }
            )
            final_results.append(score)

    # Sắp xếp giảm dần theo điểm
    final_results.sort(key=lambda x: x.match_score, reverse=True)
    return final_results
