package com.smartschedule.dto;

import lombok.*;

/**
 * DTO response cho lịch bận.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusyScheduleDTO {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private String startTime;
    private String endTime;
    private String reason;
}
