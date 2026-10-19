package com.smartschedule.entity;

/**
 * Vai trò của người dùng. Quyền được kiểm tra ở SERVER (Spring Security), không phải ở trình duyệt.
 */
public enum Role {
    ADMIN,
    EMPLOYEE
}
