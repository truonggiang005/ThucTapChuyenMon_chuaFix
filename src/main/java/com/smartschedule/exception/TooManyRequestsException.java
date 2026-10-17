package com.smartschedule.exception;

/** Vượt giới hạn tần suất → HTTP 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
