package com.smartschedule.util;

import com.smartschedule.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {

    @Test
    void acceptsLettersAndDigits() {
        PasswordPolicy.validate("matkhau2026");
    }

    @Test
    void rejectsTooShort() {
        assertThrows(InvalidRequestException.class, () -> PasswordPolicy.validate("abc123"));
    }

    @Test
    void rejectsDigitsOnly() {
        assertThrows(InvalidRequestException.class, () -> PasswordPolicy.validate("12345678"));
    }

    @Test
    void rejectsLettersOnly() {
        assertThrows(InvalidRequestException.class, () -> PasswordPolicy.validate("abcdefgh"));
    }

    @Test
    void rejectsNullAndTooLong() {
        assertThrows(InvalidRequestException.class, () -> PasswordPolicy.validate(null));
        assertThrows(InvalidRequestException.class, () -> PasswordPolicy.validate("a1".repeat(60)));
    }
}
