package com.smartschedule.config;

import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.exception.TooManyRequestsException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Xử lý exception toàn cục cho tất cả REST Controller.
 * Trả về JSON response thống nhất thay vì stack trace.
 *
 * Lỗi do người dùng (thiếu tham số, sai kiểu, JSON hỏng, sai method...) trả 4xx, KHÔNG trả 500 (SEC-14).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /** Lỗi nghiệp vụ (IllegalStateException): ca không OPEN, trùng lịch, quá giờ/tuần... → 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("[BUSINESS ERROR] {}", e.getMessage());
        return build(HttpStatus.CONFLICT, "BUSINESS_ERROR", e.getMessage());
    }

    /** Không tìm thấy đối tượng (IllegalArgumentException) → 404 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[NOT FOUND] {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /** Dữ liệu đầu vào sai logic (vd: giờ kết thúc trước giờ bắt đầu) → 400 */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidRequestException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /** @Valid trên body thất bại → 400 kèm danh sách lỗi từng trường */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg.isBlank() ? "Dữ liệu không hợp lệ" : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ");
    }

    /** JSON hỏng, ngày giờ sai định dạng, kiểu dữ liệu sai, thiếu tham số, sai kiểu tham số → 400 */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        log.debug("[BAD REQUEST] {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Yêu cầu không hợp lệ (thiếu hoặc sai định dạng dữ liệu)");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMedia(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Kiểu nội dung không được hỗ trợ");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên");
    }

    /** Spring Security ném khi @PreAuthorize từ chối: PHẢI trả 403/401, không để rơi vào handler 500 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Vui lòng đăng nhập");
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handleTooMany(TooManyRequestsException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", e.getMessage());
    }

    /** ★ Lỗi Optimistic Locking (tương tranh): 2 người nhận 1 ca cùng lúc → 409 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("[CONCURRENCY] Optimistic Locking conflict: {}", e.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                "Ca này vừa bị người khác nhận trước bạn. Vui lòng tải lại trang và thử ca khác.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("[DATA INTEGRITY] {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "Dữ liệu xung đột hoặc không hợp lệ");
    }

    /** Mọi exception không mong đợi → 500 (chi tiết chỉ ghi log, không trả cho client) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("[SYSTEM ERROR] Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi hệ thống. Vui lòng thử lại sau.");
    }
}
