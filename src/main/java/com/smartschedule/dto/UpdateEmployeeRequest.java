package com.smartschedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

/** Admin sửa thông tin nhân viên. Trường null = giữ nguyên (riêng skills: null = giữ nguyên, [] = xóa hết). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateEmployeeRequest {
    @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
    private String fullName;

    private String role;

    @Min(value = 1, message = "Giờ tối đa/tuần phải từ 1")
    @Max(value = 168, message = "Giờ tối đa/tuần tối đa 168")
    private Integer maxHoursPerWeek;

    private String preferredTime;

    private Long primaryBranchId;

    private Boolean active;

    @Valid
    private List<SkillLevelDTO> skills;
}
