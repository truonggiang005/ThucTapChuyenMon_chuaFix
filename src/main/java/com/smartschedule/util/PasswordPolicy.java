package com.smartschedule.util;

import com.smartschedule.exception.InvalidRequestException;

/** Chính sách mật khẩu tối thiểu (C4): 8–100 ký tự, có cả chữ và số. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 100;

    private PasswordPolicy() {}

    /** @throws InvalidRequestException nếu mật khẩu không đạt (HTTP 400) */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new InvalidRequestException("Mật khẩu phải có ít nhất " + MIN_LENGTH + " ký tự");
        }
        if (password.length() > MAX_LENGTH) {
            throw new InvalidRequestException("Mật khẩu tối đa " + MAX_LENGTH + " ký tự");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        if (!hasLetter || !hasDigit) {
            throw new InvalidRequestException("Mật khẩu phải gồm cả chữ và số");
        }
    }
}
