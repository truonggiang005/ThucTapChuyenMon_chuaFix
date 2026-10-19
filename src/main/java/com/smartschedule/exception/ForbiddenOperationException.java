package com.smartschedule.exception;

/** Người dùng không có quyền thao tác trên đối tượng này (vd: nhả ca của người khác) → HTTP 403. */
public class ForbiddenOperationException extends RuntimeException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
