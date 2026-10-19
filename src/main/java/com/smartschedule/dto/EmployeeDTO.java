package com.smartschedule.dto;

import lombok.*;

import java.util.List;

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
    private String role;
    private boolean active;
    private Integer maxHoursPerWeek;
    private Integer totalCompleted;
    private Integer totalCancelled;
    private Integer lateArrivalCount;
    private String preferredTime;
    private Long primaryBranchId;
    private String primaryBranchName;
    /** Chỉ có khi lấy chi tiết một nhân viên (không có ở danh sách, tránh N+1). */
    private List<SkillLevelDTO> skills;
}
