package com.smartschedule.dto;

import lombok.*;

/** Thông tin người dùng đang đăng nhập trả về cho frontend (không có mật khẩu). */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthUserDTO {
    private Long id;
    private String fullName;
    private String email;
    private String role;
}
