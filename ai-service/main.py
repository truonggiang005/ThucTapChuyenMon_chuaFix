"""
AI Microservice - FastAPI Application.

Microservice chấm điểm Match Score cho hệ thống SmartSchedule.
Nhận request từ Spring Boot, trả về danh sách nhân viên đã xếp hạng.

Endpoints:
  POST /api/v1/match-score  → Chấm điểm danh sách ứng viên cho 1 ca
  GET  /api/v1/health       → Health check
  GET  /                    → Thông tin service

Chạy:
  uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import logging

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
app = FastAPI(
    title="SmartSchedule AI Service",
    description="AI Microservice chấm điểm Match Score cho phân bổ nhân sự",
    version="1.0.0",
    docs_url="/docs",      # Swagger UI tại http://localhost:8000/docs
    redoc_url="/redoc",    # ReDoc tại http://localhost:8000/redoc
)

# CORS - cho phép Spring Boot gọi từ port khác
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],    # Production nên giới hạn origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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
        "docs": "/docs",
    }


@app.get("/api/v1/health", tags=["Health"])
def health_check():
    """
    Health check endpoint.
    Spring Boot có thể gọi endpoint này để kiểm tra AI service còn sống không.
    """
    return {"status": "healthy", "service": "ai-scoring-model"}


@app.post("/api/v1/match-score", response_model=MatchResponse, tags=["AI Scoring"])
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
        logger.error(f"[ERROR] Match scoring failed: {str(e)}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"AI scoring failed: {str(e)}"
        )


# ============================================================
# ENTRY POINT
# ============================================================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,  # Auto-reload khi sửa code (chỉ dùng khi dev)
        log_level="info",
    )
