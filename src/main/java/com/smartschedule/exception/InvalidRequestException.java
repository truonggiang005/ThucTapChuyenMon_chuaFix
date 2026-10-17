package com.smartschedule.exception;

/** Dữ liệu đầu vào không hợp lệ → HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
