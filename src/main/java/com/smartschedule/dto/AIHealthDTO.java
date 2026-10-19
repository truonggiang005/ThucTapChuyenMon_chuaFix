package com.smartschedule.dto;

import lombok.*;

/**
 * Trạng thái AI service cho giao diện (B5). healthy=true chỉ khi Spring gọi được endpoint CẦN KHÓA của Python
 * (nên khóa nội bộ lệch cũng bị phát hiện), không chỉ khi tiến trình Python còn sống.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIHealthDTO {
    private boolean healthy;
    /** Có cấu hình GEMINI_API_KEY hợp lệ không (false → chỉ chấm điểm bằng quy tắc dự phòng). */
    private boolean geminiConfigured;
    /** Mã lý do khi không healthy: AI_UNREACHABLE | AI_KEY_MISMATCH | AI_NOT_CONFIGURED | AI_ERROR */
    private String reason;
    private String message;
}
