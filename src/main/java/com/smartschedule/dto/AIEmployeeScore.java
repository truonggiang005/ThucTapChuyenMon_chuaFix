package com.smartschedule.dto;

import lombok.*;
import java.util.Map;

/**
 * DTO nhận kết quả chấm điểm 1 nhân viên từ Python.
 * Mapping 1:1 với EmployeeScore trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIEmployeeScore {
    private Long employee_id;
    private String full_name;
    private Double match_score;
    private Map<String, Object> breakdown;
}
