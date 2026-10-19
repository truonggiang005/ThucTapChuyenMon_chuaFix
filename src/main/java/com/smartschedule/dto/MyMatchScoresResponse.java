package com.smartschedule.dto;

import lombok.*;
import java.util.List;

/** Điểm của người đăng nhập cho nhiều ca một lúc. available=false khi không gọi được dịch vụ chấm điểm. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MyMatchScoresResponse {
    private boolean available;
    private String message;
    private List<MyMatchScoreDTO> scores;
}
