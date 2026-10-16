"""
AI Microservice - FastAPI Application.

Microservice chấm điểm Match Score cho hệ thống SmartSchedule.
Nhận request từ Spring Boot, trả về danh sách nhân viên đã xếp hạng.

Endpoints:
  POST /api/v1/match-score  → Chấm điểm danh sách ứng viên cho 1 ca
  GET  /api/v1/health       → Health check
  GET  /                    → Thông tin service

Chạy:
  python main.py            (mặc định 127.0.0.1:8001, không reload)
  AI_DEBUG=1 python main.py (bật /docs và reload - CHỈ khi phát triển)
"""

# pyrefly: ignore [missing-import]
import os
import hmac
import logging
# pyrefly: ignore [missing-import]
from dotenv import load_dotenv
# Đọc file ".env" nằm cùng thư mục với main.py (src/ai-service/) dù chạy từ thư mục nào.
# File này KHÔNG được commit (xem .env.example).
load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))
# pyrefly: ignore [missing-import]
from fastapi import FastAPI, HTTPException, Depends, Header
# pyrefly: ignore [missing-import]
from fastapi.middleware.cors import CORSMiddleware

from schemas import MatchRequest, MatchResponse, EmployeeScore
from model import calculate_match_scores

# ============================================================
# LOGGING SETUP
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("ai-service")

# ============================================================
# FASTAPI APP
# ============================================================
DEBUG = os.environ.get("AI_DEBUG") == "1"

app = FastAPI(
    title="SmartSchedule AI Service",
    description="AI Microservice chấm điểm Match Score cho phân bổ nhân sự",
    version="1.0.0",
    # /docs và /redoc chỉ bật khi phát triển (AI_DEBUG=1)
    docs_url="/docs" if DEBUG else None,
    redoc_url="/redoc" if DEBUG else None,
    openapi_url="/openapi.json" if DEBUG else None,
)

# CORS: Spring Boot gọi từ server nên KHÔNG cần CORS. Chỉ cho đúng một origin, không bật credentials (SEC-07).
app.add_middleware(
    CORSMiddleware,
    allow_origins=[os.environ.get("AI_ALLOWED_ORIGIN", "http://localhost:8080")],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-Internal-Key"],
)


def require_key(x_internal_key: str = Header(None)):
    """Khóa bí mật dùng chung giữa Spring và Python (header X-Internal-Key) - SEC-08."""
    expected = os.environ.get("INTERNAL_KEY", "")
    if not expected or expected == "change_me":
        # Chưa cấu hình khóa -> từ chối thay vì mở toang
        logger.error("INTERNAL_KEY chưa được cấu hình trong .env - từ chối mọi yêu cầu chấm điểm.")
        raise HTTPException(status_code=503, detail="AI service chưa được cấu hình")
    if not x_internal_key or not hmac.compare_digest(x_internal_key, expected):
        raise HTTPException(status_code=401, detail="Unauthorized")


# ============================================================
# ENDPOINTS
# ============================================================

@app.get("/", tags=["Info"])
def root():
    """Thông tin cơ bản về service."""
    return {
        "service": "SmartSchedule AI Microservice",
        "version": "1.0.0",
        "status": "running",
    }


@app.get("/api/v1/health", tags=["Health"])
def health_check():
    """
    Health check endpoint.
    Spring Boot có thể gọi endpoint này để kiểm tra AI service còn sống không.
    """
    return {"status": "healthy", "service": "ai-scoring-model"}


@app.post("/api/v1/match-score", response_model=MatchResponse, tags=["AI Scoring"],
          dependencies=[Depends(require_key)])
def compute_match_score(request: MatchRequest):
    """
    ★ ENDPOINT CHÍNH: Chấm điểm Match Score cho danh sách ứng viên.

    Flow:
      1. Spring Boot lọc sơ bộ nhân viên (đủ skill, không trùng lịch, chưa quá 40h)
      2. Spring Boot gửi POST request đến endpoint này
      3. Python tính Match Score (0-100%) cho từng ứng viên
      4. Trả về danh sách đã sắp xếp theo score giảm dần

    Request Body (MatchRequest):
      - shift: Thông tin ca (id, branch, skill, thời gian)
      - candidates: Danh sách nhân viên đủ điều kiện cơ bản

    Response (MatchResponse):
      - shift_id: ID ca
      - ranked_candidates: Danh sách đã xếp hạng
      - total_candidates: Tổng số ứng viên
    """
    logger.info(
        f"[MATCH REQUEST] Shift #{request.shift.shift_id} | "
        f"Candidates: {len(request.candidates)} | "
        f"Required Skill ID: {request.shift.required_skill_id} "
        f"Level: {request.shift.required_level}"
    )

    try:
        # Gọi model chấm điểm
        ranked = calculate_match_scores(
            shift=request.shift,
            candidates=request.candidates,
        )

        # Log kết quả top 3
        for i, emp in enumerate(ranked[:3]):
            logger.info(
                f"  Top {i+1}: {emp.full_name} (ID={emp.employee_id}) "
                f"→ Score: {emp.match_score}%"
            )

        response = MatchResponse(
            shift_id=request.shift.shift_id,
            ranked_candidates=ranked,
            total_candidates=len(ranked),
        )

        logger.info(
            f"[MATCH RESULT] Shift #{request.shift.shift_id} | "
            f"Best: {ranked[0].full_name} ({ranked[0].match_score}%) | "
            f"Worst: {ranked[-1].full_name} ({ranked[-1].match_score}%)"
        )

        return response

    except Exception as e:
        logger.error("[ERROR] Match scoring failed", exc_info=True)
        # Chi tiết lỗi chỉ ghi vào log nội bộ, không trả cho client
        raise HTTPException(status_code=500, detail="AI scoring failed")


# ============================================================
# ENTRY POINT
# ============================================================
if __name__ == "__main__":
    # pyrefly: ignore [missing-import]
    import uvicorn
    uvicorn.run(
        "main:app",
        host=os.environ.get("AI_HOST", "127.0.0.1"),   # chỉ nghe localhost; đổi AI_HOST nếu Spring ở máy khác
        port=int(os.environ.get("AI_PORT", "8001")),
        reload=DEBUG,                                  # auto-reload chỉ khi phát triển
        log_level="info",
    )
