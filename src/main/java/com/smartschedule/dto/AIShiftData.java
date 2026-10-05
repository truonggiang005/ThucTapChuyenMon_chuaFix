package com.smartschedule.dto;

import lombok.*;

/**
 * DTO gửi dữ liệu ca làm việc sang Python AI Service.
 * Mapping 1:1 với ShiftData trong schemas.py (Python).
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIShiftData {
    private Long shift_id;
    private Long branch_id;
    private Long required_skill_id;
    private Integer required_level;
    private String start_time;
    private String end_time;
    private Double shift_duration_hours;
}
