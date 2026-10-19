package com.smartschedule.exception;

/** Không tìm thấy đối tượng → HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
