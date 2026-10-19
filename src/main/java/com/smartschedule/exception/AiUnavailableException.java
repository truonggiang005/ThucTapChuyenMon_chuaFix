package com.smartschedule.exception;

/** AI service không dùng được (down, sai khóa nội bộ, quá tải) → HTTP 503. */
public class AiUnavailableException extends RuntimeException {
    private final String reason;

    public AiUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public AiUnavailableException(String reason) {
        this(reason, null);
    }

    public String getReason() {
        return reason;
    }
}
