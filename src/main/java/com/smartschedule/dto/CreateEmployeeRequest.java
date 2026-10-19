package com.smartschedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

/** Admin tạo tài khoản nhân viên (A6). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEmployeeRequest {
    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    @Size(max = 200, message = "Email tối đa 200 ký tự")
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(max = 100, message = "Mật khẩu tối đa 100 ký tự")
    private String password;

    /** ADMIN | EMPLOYEE (mặc định EMPLOYEE) */
    private String role;

    @Min(value = 1, message = "Giờ tối đa/tuần phải từ 1")
    @Max(value = 168, message = "Giờ tối đa/tuần tối đa 168")
    private Integer maxHoursPerWeek;

    /** MORNING | AFTERNOON | EVENING | NIGHT | ANY */
    private String preferredTime;

    private Long primaryBranchId;

    @Valid
    private List<SkillLevelDTO> skills;
}
