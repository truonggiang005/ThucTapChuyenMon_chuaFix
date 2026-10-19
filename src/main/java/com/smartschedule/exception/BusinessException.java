package com.smartschedule.exception;

/** Vi phạm quy tắc nghiệp vụ (ca không còn mở, trùng lịch, quá giờ/tuần...) → HTTP 409. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
