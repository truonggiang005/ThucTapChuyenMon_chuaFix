package com.smartschedule.dto;

import lombok.*;

/**
 * Điểm phù hợp của CHÍNH người đăng nhập cho một ca ở Chợ ca (B4).
 * Ca mà nhân viên không đủ điều kiện nhận thì eligible=false kèm reason, KHÔNG có điểm (tránh hiển thị
 * "Phù hợp" rồi bấm nhận mới bị 409).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MyMatchScoreDTO {
    private Long shiftId;
    private boolean eligible;
    private String reason;
    private Double matchScore;
    private Double confidence;
    /** "heuristic" (quy tắc, không tốn quota AI) */
    private String source;
}
