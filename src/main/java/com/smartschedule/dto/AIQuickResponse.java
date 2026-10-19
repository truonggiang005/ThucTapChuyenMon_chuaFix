package com.smartschedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.List;

/** Kết quả chấm điểm nhanh theo lô từ Python. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIQuickResponse {

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private Long shift_id;
        private Long employee_id;
        private Double match_score;
        private Double confidence;
        private String source;
    }

    private List<Result> results;
}
