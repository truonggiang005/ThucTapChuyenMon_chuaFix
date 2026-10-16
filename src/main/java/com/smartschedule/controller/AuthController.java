package com.smartschedule.controller;

import com.smartschedule.dto.AuthUserDTO;
import com.smartschedule.dto.LoginRequest;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Đăng nhập / đăng xuất / người dùng hiện tại (SEC-01, SEC-02).
 *
 * Xác thực chạy ở SERVER: mật khẩu kiểm bằng BCrypt, vai trò đọc từ DB.
 * Phiên là session phía server (cookie HttpOnly), trình duyệt không giữ mật khẩu hay vai trò.
 * Đăng xuất: POST /api/auth/logout (do Spring Security xử lý).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final RateLimiter rateLimiter;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        // Chống dò mật khẩu: tối đa 10 lần/phút cho mỗi địa chỉ IP
        rateLimiter.check("login:" + httpRequest.getRemoteAddr(), 10, Duration.ofMinutes(1));

        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.getEmail().trim(), request.getPassword()));

            // Chống session fixation: đổi id phiên cũ nếu đã có
            if (httpRequest.getSession(false) != null) {
                httpRequest.changeSessionId();
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            AppUserDetails user = (AppUserDetails) auth.getPrincipal();
            log.info("[AUTH] Đăng nhập thành công: nhân viên #{}", user.getEmployeeId());
            return ResponseEntity.ok(toDto(user));

        } catch (AuthenticationException e) {
            // Thông báo chung, không tiết lộ email có tồn tại hay không
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "BAD_CREDENTIALS",
                    "message", "Email hoặc mật khẩu không đúng"));
        }
    }

    /** Frontend gọi khi tải trang để biết ai đang đăng nhập (chưa đăng nhập → 401). */
    @GetMapping("/me")
    public ResponseEntity<AuthUserDTO> me(@AuthenticationPrincipal AppUserDetails me) {
        return ResponseEntity.ok(toDto(me));
    }

    private static AuthUserDTO toDto(AppUserDetails u) {
        return AuthUserDTO.builder()
                .id(u.getEmployeeId())
                .fullName(u.getFullName())
                .email(u.getUsername())
                .role(u.getRole().name())
                .build();
    }
}
