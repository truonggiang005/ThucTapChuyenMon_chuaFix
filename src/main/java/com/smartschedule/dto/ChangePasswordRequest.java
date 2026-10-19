package com.smartschedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/** Đổi mật khẩu của chính mình. */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {
    @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại")
    @Size(max = 100, message = "Mật khẩu quá dài")
    private String currentPassword;

    @NotBlank(message = "Vui lòng nhập mật khẩu mới")
    @Size(max = 100, message = "Mật khẩu quá dài")
    private String newPassword;
}
