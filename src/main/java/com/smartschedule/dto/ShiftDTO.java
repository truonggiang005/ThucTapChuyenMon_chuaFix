package com.smartschedule.dto;

import lombok.*;

/**
 * DTO trả về thông tin ca làm việc cho Frontend.
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftDTO {
    private Long id;
    private String title;
    private String startTime;
    private String endTime;
    private String status;
    private Integer requiredLevel;
    private Long branchId;
    private String branchName;
    private Long requiredSkillId;
    private String requiredSkillName;
    private Long assignedToId;
    private String assignedToName;
    private Long version;
}
