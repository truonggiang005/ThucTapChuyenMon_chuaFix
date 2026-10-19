package com.smartschedule.dto;

import lombok.*;

/**
 * DTO gửi dữ liệu nhân viên sang Python AI Service.
 * Mapping 1:1 với EmployeeData trong schemas.py (Python).
 * AIService luôn điền giá trị mặc định thay cho null để không vi phạm hợp đồng với Python (B10).
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
    private Long branch_id;
}
