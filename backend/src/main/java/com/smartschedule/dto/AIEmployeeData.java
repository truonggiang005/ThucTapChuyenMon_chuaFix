package com.smartschedule.dto;

import lombok.*;

/**
 * DTO gửi dữ liệu nhân viên sang Python AI Service.
 * Mapping 1:1 với EmployeeData trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIEmployeeData {
    private Long employee_id;
    private String full_name;
    private Integer skill_level;
    private Integer total_completed;
    private Integer total_cancelled;
    private Integer late_arrival_count;
    private String preferred_time;
    private Double hours_worked_this_week;
    private Integer max_hours_per_week;
}
