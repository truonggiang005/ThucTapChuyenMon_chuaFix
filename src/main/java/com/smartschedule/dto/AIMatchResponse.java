package com.smartschedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.List;

/**
 * DTO Response nhận từ Python AI Service (và cũng là response trả cho giao diện).
 * Mapping với MatchResponse trong schemas.py (Python).
 *
 * Ý nghĩa các cờ (B1, B2):
 *   - fallback = true  : điểm KHÔNG do Gemini chấm mà do bộ quy tắc dự phòng (hoặc một phần ứng viên) → chỉ để tham khảo,
 *                        cron KHÔNG được tự gán dựa trên điểm này, giao diện phải báo rõ cho người dùng.
 *   - available = false: không gọi được AI service (down / sai khóa / hết quota...) → không có điểm nào cả; xem message.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIMatchResponse {
    private Long shift_id;
    private List<AIEmployeeScore> ranked_candidates;
    private Integer total_candidates;

    /** "gemini" | "heuristic" | "mixed" | "none" */
    private String source;

    private boolean fallback;
    private String fallback_reason;

    @Builder.Default
    private boolean available = true;

    /** Mã lý do khi available=false (AI_UNREACHABLE, AI_KEY_MISMATCH, ...) */
    private String error_code;

    /** Thông báo thân thiện cho người dùng khi available=false hoặc fallback=true */
    private String message;
}
