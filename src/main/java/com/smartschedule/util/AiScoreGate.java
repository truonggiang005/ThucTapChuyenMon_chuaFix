package com.smartschedule.util;

/**
 * Quyết định cron có được dùng kết quả chấm điểm để TỰ GÁN ca hay không.
 *
 *   available=false              → không có điểm nào (AI service tắt / không kết nối / lỗi) → không thể gán.
 *   available=true, fallback=false → điểm do Gemini chấm → gán bình thường.
 *   available=true, fallback=true  → Gemini hết quota/lỗi nên điểm do CÔNG THỨC có trọng số tính
 *                                    (tin cậy 30%, kỹ năng 25%, khung giờ 20%, khối lượng 15%, chi nhánh 10%).
 *                                    Vẫn gán nếu allowFormulaFallback=true (app.scheduler.allow-formula-fallback).
 *
 * Tách riêng thành hàm thuần để kiểm thử được mà không cần Spring.
 */
public final class AiScoreGate {

    public enum Decision {
        /** Điểm do Gemini: gán bình thường. */
        PROCEED,
        /** Điểm do công thức dự phòng: vẫn gán, nhưng ghi rõ trong log/thông báo admin. */
        PROCEED_BY_FORMULA,
        /** Không dùng được: dừng lượt (hoặc bỏ qua ca). */
        SKIP
    }

    private AiScoreGate() {
    }

    public static Decision decide(boolean available, boolean fallback, boolean allowFormulaFallback) {
        if (!available) {
            return Decision.SKIP;
        }
        if (!fallback) {
            return Decision.PROCEED;
        }
        return allowFormulaFallback ? Decision.PROCEED_BY_FORMULA : Decision.SKIP;
    }
}
