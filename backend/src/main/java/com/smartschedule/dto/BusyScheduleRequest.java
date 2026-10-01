package com.smartschedule.dto;

import lombok.*;

/**
 * DTO cho request đăng ký lịch bận.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusyScheduleRequest {
    private Long employeeId;
    private String startTime;
    private String endTime;
    private String reason;
}
