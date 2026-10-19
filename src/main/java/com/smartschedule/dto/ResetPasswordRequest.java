package com.smartschedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/** Admin đặt lại mật khẩu cho nhân viên. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {
    @NotBlank(message = "Vui lòng nhập mật khẩu mới")
    @Size(max = 100, message = "Mật khẩu quá dài")
    private String newPassword;
}
