package com.smartschedule.security;

import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Người dùng đã đăng nhập (lưu trong session phía server).
 * employeeId lấy từ đây, KHÔNG tin employeeId do client gửi (SEC-03).
 *
 * Tài khoản bị vô hiệu hóa (active=false) hoặc đang khóa tạm (lockedUntil ở tương lai) không đăng nhập được (C4).
 * equals/hashCode theo employeeId để SessionRegistry nhận ra cùng một người ở các phiên khác nhau (giới hạn số phiên).
 */
public class AppUserDetails implements UserDetails {

    private final Long employeeId;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final Role role;
    private final boolean active;
    private final LocalDateTime lockedUntil;

    public AppUserDetails(Employee e) {
        this.employeeId = e.getId();
        this.email = e.getEmail();
        this.fullName = e.getFullName();
        this.passwordHash = e.getPasswordHash();
        this.role = e.getRole() != null ? e.getRole() : Role.EMPLOYEE;
        this.active = e.isActive();
        this.lockedUntil = e.getLockedUntil();
    }

    public Long getEmployeeId() { return employeeId; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() {
        // JVM chạy đúng múi giờ nghiệp vụ (SmartScheduleApplication), cùng múi giờ với lockedUntil lưu trong DB
        return lockedUntil == null || !lockedUntil.isAfter(LocalDateTime.now());
    }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppUserDetails other)) return false;
        return Objects.equals(employeeId, other.employeeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(employeeId);
    }
}
