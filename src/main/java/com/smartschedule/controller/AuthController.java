package com.smartschedule.controller;

import com.smartschedule.dto.AuthUserDTO;
import com.smartschedule.dto.ChangePasswordRequest;
import com.smartschedule.dto.LoginRequest;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.EmployeeService;
import com.smartschedule.service.LoginAttemptService;
import com.smartschedule.util.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Đăng nhập / đăng xuất / người dùng hiện tại / đổi mật khẩu (SEC-01, SEC-02, C2, C4).
 *
 * Xác thực chạy ở SERVER: mật khẩu kiểm bằng BCrypt, vai trò đọc từ DB.
 * Phiên là session phía server (cookie HttpOnly), trình duyệt không giữ mật khẩu hay vai trò.
 * Đăng xuất: POST /api/auth/logout (do Spring Security xử lý).
 *
 * Chống dò mật khẩu (C2): giới hạn theo IP (rộng) VÀ theo cặp IP+tài khoản (chặt) — nên sau reverse proxy
 * một người gõ sai không khóa cả hệ thống; thêm khóa tài khoản trong DB sau N lần sai liên tiếp (C4).
 * Nhớ bật server.forward-headers-strategy=native khi chạy sau reverse proxy để getRemoteAddr() là IP thật.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final RateLimiter rateLimiter;
    private final LoginAttemptService loginAttempts;
    private final EmployeeService employeeService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /** Số lần đăng nhập tối đa mỗi phút từ một IP (rộng, vì nhiều người có thể chung IP sau proxy/NAT) */
    @Value("${app.security.login-per-ip-per-minute:30}")
    private int perIpLimit;

    /** Số lần đăng nhập tối đa mỗi phút cho một tài khoản từ một IP */
    @Value("${app.security.login-per-account-per-minute:5}")
    private int perAccountLimit;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String ip = httpRequest.getRemoteAddr();
        rateLimiter.check("login-ip:" + ip, perIpLimit, Duration.ofMinutes(1));
        rateLimiter.check("login-acct:" + ip + ":" + email, perAccountLimit, Duration.ofMinutes(1));

        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.getPassword()));

            // Giới hạn số phiên đồng thời + chống session fixation + đăng ký phiên
            sessionAuthenticationStrategy.onAuthentication(auth, httpRequest, httpResponse);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            AppUserDetails user = (AppUserDetails) auth.getPrincipal();
            loginAttempts.recordSuccess(user.getEmployeeId());
            log.info("[AUTH] Đăng nhập thành công: nhân viên #{}", user.getEmployeeId());
            return ResponseEntity.ok(toDto(user));

        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                    "error", "ACCOUNT_LOCKED",
                    "message", "Tài khoản tạm thời bị khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau "
                            + loginAttempts.lockMinutes() + " phút hoặc nhờ quản lý đặt lại mật khẩu."));
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "ACCOUNT_DISABLED",
                    "message", "Tài khoản đã bị vô hiệu hóa. Vui lòng liên hệ quản lý."));
        } catch (AuthenticationException e) {
            loginAttempts.recordFailure(email);
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

    /** Đổi mật khẩu của chính mình (phải nhập đúng mật khẩu hiện tại). */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                              @AuthenticationPrincipal AppUserDetails me) {
        employeeService.changePassword(me.getEmployeeId(), request);
        return ResponseEntity.ok(Map.of("message", "Đổi mật khẩu thành công!"));
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
