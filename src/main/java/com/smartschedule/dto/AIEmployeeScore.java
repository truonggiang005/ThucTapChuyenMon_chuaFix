package com.smartschedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.Map;

/**
 * DTO nhận kết quả chấm điểm 1 nhân viên từ Python.
 * Mapping với EmployeeScore trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIEmployeeScore {
    private Long employee_id;
    private String full_name;
    private Double match_score;
    private Map<String, Object> breakdown;

    /** Nguồn của điểm này: "gemini" hoặc "heuristic" */
    private String source;

    /** Độ tin cậy của điểm (0-100): nhiều dữ liệu lịch sử → cao, ít dữ liệu → thấp (E7). */
    private Double confidence;

    /** Khoảng dao động hợp lý của điểm [score_low, score_high] (E7). */
    private Double score_low;
    private Double score_high;
}
