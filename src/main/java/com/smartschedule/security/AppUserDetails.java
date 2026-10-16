package com.smartschedule.security;

import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Người dùng đã đăng nhập (lưu trong session phía server).
 * employeeId lấy từ đây, KHÔNG tin employeeId do client gửi (SEC-03).
 */
public class AppUserDetails implements UserDetails {

    private final Long employeeId;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final Role role;

    public AppUserDetails(Employee e) {
        this.employeeId = e.getId();
        this.email = e.getEmail();
        this.fullName = e.getFullName();
        this.passwordHash = e.getPasswordHash();
        this.role = e.getRole() != null ? e.getRole() : Role.EMPLOYEE;
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
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
