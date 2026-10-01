package com.smartschedule.dto;

import lombok.*;

/**
 * DTO cho request tạo ca mới.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateShiftRequest {
    private String title;
    private String startTime;
    private String endTime;
    private Integer requiredLevel;
    private Long branchId;
    private Long requiredSkillId;
    private Long assignedToId;   // Nullable: nếu null → status = OPEN
}
