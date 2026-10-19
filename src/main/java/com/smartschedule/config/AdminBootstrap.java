package com.smartschedule.config;

import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Role;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo quản trị viên ĐẦU TIÊN khi chạy thật (C1): prod không seed dữ liệu mẫu nên nếu không có cơ chế này
 * sẽ không có ai đăng nhập được để tạo tài khoản.
 *
 * Chỉ chạy khi: (1) đã đặt BOOTSTRAP_ADMIN_EMAIL và BOOTSTRAP_ADMIN_PASSWORD, và (2) trong DB CHƯA có ADMIN nào.
 * Mật khẩu phải đạt PasswordPolicy (≥ 8 ký tự, có chữ và số) và được băm BCrypt; không bao giờ ghi ra log.
 * Có ADMIN rồi thì bỏ qua: đặt lại biến môi trường không thể dùng để chiếm quyền.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap-admin.email:}")
    private String email;

    @Value("${app.bootstrap-admin.name:Quản trị viên}")
    private String name;

    @Value("${app.bootstrap-admin.password:}")
    private String password;

    @Override
    @Transactional
    public void run(String... args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return; // không cấu hình: không làm gì
        }
        if (employeeRepository.existsByRole(Role.ADMIN)) {
            log.info("[BOOTSTRAP] Đã có tài khoản ADMIN, bỏ qua tạo admin khởi tạo.");
            return;
        }
        PasswordPolicy.validate(password); // sai chính sách => ném lỗi, ứng dụng dừng với thông báo rõ ràng

        String normalizedEmail = email.trim().toLowerCase();
        if (employeeRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL trùng với một tài khoản nhân viên đã có; "
                    + "hãy dùng email khác hoặc nâng quyền tài khoản đó trực tiếp trong DB.");
        }
        employeeRepository.save(Employee.builder()
                .fullName(name == null || name.isBlank() ? "Quản trị viên" : name.trim())
                .email(normalizedEmail)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(password))
                .maxHoursPerWeek(40)
                .build());
        log.info("[BOOTSTRAP] Đã tạo tài khoản ADMIN đầu tiên: {}. Nên đổi mật khẩu ngay sau lần đăng nhập đầu.", normalizedEmail);
    }
}
