package com.smartschedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

/** Tạo/sửa chi nhánh (A6). maxCapacity = số người làm ĐỒNG THỜI tối đa (null = không giới hạn). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class BranchRequest {
    @NotBlank(message = "Tên chi nhánh không được để trống")
    @Size(max = 150, message = "Tên chi nhánh tối đa 150 ký tự")
    private String name;

    @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
    private String address;

    @Positive(message = "Sức chứa tối đa phải là số dương")
    private Integer maxCapacity;
}
