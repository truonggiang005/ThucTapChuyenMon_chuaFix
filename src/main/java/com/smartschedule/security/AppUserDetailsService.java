package com.smartschedule.security;

import com.smartschedule.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Nạp nhân viên theo email cho Spring Security (đăng nhập). */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final EmployeeRepository employeeRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return employeeRepository.findByEmail(email)
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản"));
    }
}
