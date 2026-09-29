package com.smartschedule.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Xử lý exception toàn cục cho tất cả REST Controller.
 * Trả về JSON response thống nhất thay vì stack trace.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Xử lý lỗi nghiệp vụ (IllegalStateException).
     * Ví dụ: Ca không ở trạng thái OPEN, trùng lịch, quá giờ/tuần.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("[BUSINESS ERROR] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "BUSINESS_ERROR",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Xử lý lỗi tham số không hợp lệ (IllegalArgumentException).
     * Ví dụ: Không tìm thấy ca, không tìm thấy nhân viên.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[NOT FOUND] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * ★ Xử lý lỗi Optimistic Locking (tương tranh).
     * Xảy ra khi 2 người nhận 1 ca cùng lúc.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("[CONCURRENCY] Optimistic Locking conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONCURRENCY_CONFLICT",
                "message", "Ca này vừa bị người khác nhận trước bạn. Vui lòng tải lại trang và thử ca khác.",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Xử lý mọi exception không mong đợi.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("[SYSTEM ERROR] Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "Lỗi hệ thống. Vui lòng thử lại sau.",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
