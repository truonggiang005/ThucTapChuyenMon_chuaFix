package com.smartschedule.dto;

import lombok.*;

/**
 * DTO trả về thông tin nhân viên cho Frontend.
 * KHÔNG bao gồm password_hash.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeDTO {
    private Long id;
    private String fullName;
    private String email;
    private Integer maxHoursPerWeek;
    private Integer totalCompleted;
    private Integer totalCancelled;
    private Integer lateArrivalCount;
    private String preferredTime;
    private Long primaryBranchId;
    private String primaryBranchName;
}
