package com.smartschedule.config;

import com.smartschedule.entity.*;
import com.smartschedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSeeder: Tạo dữ liệu mẫu khi ứng dụng khởi động.
 * Chỉ chạy khi app.seed.enabled=true (mặc định bật cho phát triển, TẮT ở profile prod - SEC-16)
 * và chỉ seed nếu DB trống (kiểm tra bảng branches).
 * Mật khẩu băm bằng BCrypt từ app.seed.password (chỉ dùng cho dữ liệu mẫu khi phát triển).
 *
 * Dữ liệu mẫu bao gồm:
 *   - 2 chi nhánh
 *   - 3 kỹ năng
 *   - 5 nhân viên (với lịch sử AI khác nhau)
 *   - Employee-Skill mappings
 *   - 5 ca mẫu (mix trạng thái ASSIGNED + OPEN)
 *   - 2 lịch bận mẫu (BusySchedule)
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final ShiftRepository shiftRepository;
    private final BusyScheduleRepository busyScheduleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.password}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (branchRepository.count() > 0) {
            log.info("[SEEDER] Database đã có dữ liệu, bỏ qua seed.");
            return;
        }

        log.info("[SEEDER] 🌱 Bắt đầu seed dữ liệu mẫu...");

        // ── 1. Chi nhánh ──
        Branch hcm = branchRepository.save(Branch.builder()
                .name("Chi nhánh HCM - Quận 1")
                .address("123 Nguyễn Huệ, Quận 1, TP.HCM")
                .maxCapacity(50).build());

        Branch hn = branchRepository.save(Branch.builder()
                .name("Chi nhánh Hà Nội - Cầu Giấy")
                .address("456 Trần Duy Hưng, Cầu Giấy, Hà Nội")
                .maxCapacity(40).build());

        // ── 2. Kỹ năng ──
        Skill barista = skillRepository.save(Skill.builder().name("Pha chế (Barista)").build());
        Skill cashier = skillRepository.save(Skill.builder().name("Thu ngân (Cashier)").build());
        Skill kitchen = skillRepository.save(Skill.builder().name("Bếp (Kitchen)").build());

        // ── 3. Nhân viên (với lịch sử AI khác nhau) ──
        // Mật khẩu thật được băm bằng BCrypt (không dùng hash giả); An (id 1) là ADMIN, còn lại EMPLOYEE
        String hash = passwordEncoder.encode(seedPassword);
        Employee emp1 = employeeRepository.save(Employee.builder()
                .fullName("Trần Xuân Bách")
                .email("bach@gmail.com")
                .role(Role.ADMIN)
                .passwordHash(hash)
                .maxHoursPerWeek(20)
                .totalCompleted(50).totalCancelled(2).lateArrivalCount(1)
                .preferredTime("MORNING")
                .primaryBranch(hcm).build());

        Employee emp2 = employeeRepository.save(Employee.builder()
                .fullName("Lưu Trường Giang")
                .email("giang@gmail.com")
                .passwordHash(hash)
                .maxHoursPerWeek(15)
                .totalCompleted(35).totalCancelled(5).lateArrivalCount(3)
                .preferredTime("AFTERNOON")
                .primaryBranch(hcm).build());

        Employee emp3 = employeeRepository.save(Employee.builder()
                .fullName("Phạm Khánh Ly")
                .email("ly@gmail.com")
                .passwordHash(hash)
                .maxHoursPerWeek(29)
                .totalCompleted(20).totalCancelled(8).lateArrivalCount(5)
                .preferredTime("EVENING")
                .primaryBranch(hcm).build());

        Employee emp4 = employeeRepository.save(Employee.builder()
                .fullName("Nguyễn Sơn Vũ")
                .email("vu@gmail.com")
                .passwordHash(hash)
                .maxHoursPerWeek(34)
                .totalCompleted(45).totalCancelled(1).lateArrivalCount(0)
                .preferredTime("MORNING")
                .primaryBranch(hn).build());

        Employee emp5 = employeeRepository.save(Employee.builder()
                .fullName("Đặng Thị Phương Nga")
                .email("nga@gmail.com")
                .passwordHash(hash)
                .maxHoursPerWeek(17)
                .totalCompleted(10).totalCancelled(0).lateArrivalCount(0)
                .preferredTime("MORNING")
                .primaryBranch(hn).build());

        // ── 4. Employee-Skill (N-N) ──
        List<EmployeeSkill> skills = List.of(
                // An: Barista L4, Cashier L3, Kitchen L5
                EmployeeSkill.builder().employee(emp1).skill(barista).skillLevel(4).build(),
                EmployeeSkill.builder().employee(emp1).skill(cashier).skillLevel(3).build(),
                EmployeeSkill.builder().employee(emp1).skill(kitchen).skillLevel(5).build(),
                // Bình: Cashier L5, Kitchen L5
                EmployeeSkill.builder().employee(emp2).skill(cashier).skillLevel(5).build(),
                EmployeeSkill.builder().employee(emp2).skill(kitchen).skillLevel(5).build(),
                // Cường: Kitchen L5, Barista L2
                EmployeeSkill.builder().employee(emp3).skill(kitchen).skillLevel(5).build(),
                EmployeeSkill.builder().employee(emp3).skill(barista).skillLevel(2).build(),
                // Đức: Barista L5, Cashier L4, Kitchen L5
                EmployeeSkill.builder().employee(emp4).skill(barista).skillLevel(5).build(),
                EmployeeSkill.builder().employee(emp4).skill(cashier).skillLevel(4).build(),
                EmployeeSkill.builder().employee(emp4).skill(kitchen).skillLevel(5).build(),
                // Ema: Cashier L3, Barista L1, Kitchen L5
                EmployeeSkill.builder().employee(emp5).skill(cashier).skillLevel(3).build(),
                EmployeeSkill.builder().employee(emp5).skill(barista).skillLevel(1).build(),
                EmployeeSkill.builder().employee(emp5).skill(kitchen).skillLevel(5).build()
        );
        employeeSkillRepository.saveAll(skills);

        // ── 5. Ca làm việc mẫu (dùng plusDays(2) và plusDays(3) để đảm bảo còn hiệu lực) ──
        //     ★ Tất cả ca ban đầu đều được gán cho nhân viên (ASSIGNED)
        LocalDateTime dayAfterTomorrow = LocalDateTime.now().plusDays(2).withHour(0).withMinute(0).withSecond(0).withNano(0);

        // Ca 1: ASSIGNED cho An (sáng ngày kia - HCM)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Pha chế")
                .startTime(dayAfterTomorrow.withHour(7))
                .endTime(dayAfterTomorrow.withHour(15))
                .requiredLevel(3).branch(hcm).requiredSkill(barista)
                .assignedTo(emp1).status(ShiftStatus.ASSIGNED).build());

        // Ca 2: ASSIGNED cho Bình (chiều ngày kia - HCM)
        shiftRepository.save(Shift.builder()
                .title("Ca chiều - Thu ngân")
                .startTime(dayAfterTomorrow.withHour(13))
                .endTime(dayAfterTomorrow.withHour(21))
                .requiredLevel(2).branch(hcm).requiredSkill(cashier)
                .assignedTo(emp2).status(ShiftStatus.ASSIGNED).build());

        // Ca 3: ASSIGNED cho Cường (sáng ngày tiếp theo - HCM)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Bếp")
                .startTime(dayAfterTomorrow.plusDays(1).withHour(6))
                .endTime(dayAfterTomorrow.plusDays(1).withHour(14))
                .requiredLevel(3).branch(hcm).requiredSkill(kitchen)
                .assignedTo(emp3).status(ShiftStatus.ASSIGNED).build());

        // Ca 4: ASSIGNED cho Đức (sáng ngày kia - Hà Nội)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Pha chế HN")
                .startTime(dayAfterTomorrow.withHour(8))
                .endTime(dayAfterTomorrow.withHour(16))
                .requiredLevel(4).branch(hn).requiredSkill(barista)
                .assignedTo(emp4).status(ShiftStatus.ASSIGNED).build());

        // Ca 5: ASSIGNED cho Ema (chiều ngày kia - Hà Nội)
        shiftRepository.save(Shift.builder()
                .title("Ca chiều - Thu ngân HN")
                .startTime(dayAfterTomorrow.withHour(14))
                .endTime(dayAfterTomorrow.withHour(22))
                .requiredLevel(2).branch(hn).requiredSkill(cashier)
                .assignedTo(emp5).status(ShiftStatus.ASSIGNED).build());

        // Ca 6: ASSIGNED cho An (tối ngày tiếp theo - HCM)
        shiftRepository.save(Shift.builder()
                .title("Ca tối - Bếp")
                .startTime(dayAfterTomorrow.plusDays(1).withHour(17))
                .endTime(dayAfterTomorrow.plusDays(1).withHour(23))
                .requiredLevel(2).branch(hcm).requiredSkill(kitchen)
                .assignedTo(emp1).status(ShiftStatus.ASSIGNED).build());

        // Ca 7: ASSIGNED cho Đức (chiều 2 ngày sau - Hà Nội)
        shiftRepository.save(Shift.builder()
                .title("Ca chiều - Pha chế HN")
                .startTime(dayAfterTomorrow.plusDays(2).withHour(13))
                .endTime(dayAfterTomorrow.plusDays(2).withHour(21))
                .requiredLevel(1).branch(hn).requiredSkill(barista)
                .assignedTo(emp4).status(ShiftStatus.ASSIGNED).build());

        // ── 6. Lịch bận mẫu (BusySchedule) ──
        // Cường bận buổi sáng ngày kia (nghỉ phép)
        busyScheduleRepository.save(BusySchedule.builder()
                .employee(emp3)
                .startTime(dayAfterTomorrow.withHour(6))
                .endTime(dayAfterTomorrow.withHour(14))
                .reason("Nghỉ phép - việc gia đình")
                .build());

        // Bình bận buổi chiều ngày kia (đi khám)
        busyScheduleRepository.save(BusySchedule.builder()
                .employee(emp2)
                .startTime(dayAfterTomorrow.withHour(14))
                .endTime(dayAfterTomorrow.withHour(18))
                .reason("Đi khám bệnh")
                .build());

        log.info("[SEEDER] ✅ Seed hoàn tất: 2 branches, 3 skills, 5 employees, 7 shifts (tất cả 7 ca đều đã được GÁN cho NV), 2 busy schedules");
    }
}

