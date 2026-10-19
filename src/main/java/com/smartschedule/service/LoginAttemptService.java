package com.smartschedule.service;

import com.smartschedule.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Khóa tài khoản tạm thời khi đăng nhập sai nhiều lần liên tiếp (C4). Số lần sai lưu trong DB nên có tác dụng
 * dù chạy nhiều instance (khác với rate limit trong bộ nhớ).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private final EmployeeRepository employeeRepository;
    private final Clock clock;

    @Value("${app.security.max-failed-logins:5}")
    private int maxFailures;

    @Value("${app.security.lock-minutes:15}")
    private long lockMinutes;

    public long lockMinutes() {
        return lockMinutes;
    }

    /** Ghi nhận một lần đăng nhập sai; đủ số lần thì khóa tài khoản lockMinutes phút. */
    @Transactional
    public void recordFailure(String email) {
        String e = email.trim().toLowerCase(Locale.ROOT);
        if (employeeRepository.registerFailedLogin(e) > 0
                && employeeRepository.lockIfTooManyFailures(e, LocalDateTime.now(clock).plusMinutes(lockMinutes), maxFailures) > 0) {
            log.warn("[AUTH] Tài khoản bị khóa {} phút do đăng nhập sai {} lần", lockMinutes, maxFailures);
        }
    }

    @Transactional
    public void recordSuccess(Long employeeId) {
        employeeRepository.clearLoginFailures(employeeId);
    }
}
