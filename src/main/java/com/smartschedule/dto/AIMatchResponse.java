package com.smartschedule.dto;

import lombok.*;
import java.util.List;

/**
 * DTO Response nhận từ Python AI Service.
 * Mapping 1:1 với MatchResponse trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIMatchResponse {
    private Long shift_id;
    private List<AIEmployeeScore> ranked_candidates;
    private Integer total_candidates;

    /**
     * true nếu Python AI không khả dụng và điểm là điểm dự phòng do Java tự đặt (không phản ánh độ phù hợp thật).
     * Cron job KHÔNG tự gán ca khi cờ này bật (LOG-08).
     */
    private boolean fallback;
}
