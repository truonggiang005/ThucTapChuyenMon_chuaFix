package com.smartschedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/** Tạo/sửa kỹ năng (A6). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class SkillRequest {
    @NotBlank(message = "Tên kỹ năng không được để trống")
    @Size(max = 100, message = "Tên kỹ năng tối đa 100 ký tự")
    private String name;
}
