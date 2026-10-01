package com.smartschedule.config;

import com.smartschedule.entity.*;
import com.smartschedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSeeder: Tạo dữ liệu mẫu khi ứng dụng khởi động.
 * Chỉ seed nếu DB trống (kiểm tra bảng branches).
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
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final ShiftRepository shiftRepository;
    private final BusyScheduleRepository busyScheduleRepository;

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
        Employee emp1 = employeeRepository.save(Employee.builder()
                .fullName("Nguyễn Văn An")
                .email("an@smartschedule.com")
                .passwordHash("$2a$10$hash1") // BCrypt placeholder
                .maxHoursPerWeek(40)
                .totalCompleted(50).totalCancelled(2).lateArrivalCount(1)
                .preferredTime("MORNING")
                .primaryBranch(hcm).build());

        Employee emp2 = employeeRepository.save(Employee.builder()
                .fullName("Trần Thị Bình")
                .email("binh@smartschedule.com")
                .passwordHash("$2a$10$hash2")
                .maxHoursPerWeek(40)
                .totalCompleted(35).totalCancelled(5).lateArrivalCount(3)
                .preferredTime("AFTERNOON")
                .primaryBranch(hcm).build());

        Employee emp3 = employeeRepository.save(Employee.builder()
                .fullName("Lê Hoàng Cường")
                .email("cuong@smartschedule.com")
                .passwordHash("$2a$10$hash3")
                .maxHoursPerWeek(36)
                .totalCompleted(20).totalCancelled(8).lateArrivalCount(5)
                .preferredTime("EVENING")
                .primaryBranch(hcm).build());

        Employee emp4 = employeeRepository.save(Employee.builder()
                .fullName("Phạm Minh Đức")
                .email("duc@smartschedule.com")
                .passwordHash("$2a$10$hash4")
                .maxHoursPerWeek(40)
                .totalCompleted(45).totalCancelled(1).lateArrivalCount(0)
                .preferredTime("MORNING")
                .primaryBranch(hn).build());

        Employee emp5 = employeeRepository.save(Employee.builder()
                .fullName("Hoàng Thị Ema")
                .email("ema@smartschedule.com")
                .passwordHash("$2a$10$hash5")
                .maxHoursPerWeek(32)
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
        LocalDateTime dayAfterTomorrow = LocalDateTime.now().plusDays(2).withHour(0).withMinute(0).withSecond(0).withNano(0);

        // Ca 1: ASSIGNED cho An (sáng ngày kia)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Pha chế")
                .startTime(dayAfterTomorrow.withHour(7))
                .endTime(dayAfterTomorrow.withHour(15))
                .requiredLevel(3).branch(hcm).requiredSkill(barista)
                .assignedTo(emp1).status(ShiftStatus.ASSIGNED).build());

        // Ca 2: OPEN (An đã nhả) - chờ người nhận
        shiftRepository.save(Shift.builder()
                .title("Ca chiều - Thu ngân")
                .startTime(dayAfterTomorrow.withHour(13))
                .endTime(dayAfterTomorrow.withHour(21))
                .requiredLevel(2).branch(hcm).requiredSkill(cashier)
                .assignedTo(null).status(ShiftStatus.OPEN).build());

        // Ca 3: OPEN (sáng ngày tiếp theo)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Bếp")
                .startTime(dayAfterTomorrow.plusDays(1).withHour(6))
                .endTime(dayAfterTomorrow.plusDays(1).withHour(14))
                .requiredLevel(3).branch(hcm).requiredSkill(kitchen)
                .assignedTo(null).status(ShiftStatus.OPEN).build());

        // Ca 4: ASSIGNED cho Đức (Hà Nội)
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Pha chế HN")
                .startTime(dayAfterTomorrow.withHour(8))
                .endTime(dayAfterTomorrow.withHour(16))
                .requiredLevel(4).branch(hn).requiredSkill(barista)
                .assignedTo(emp4).status(ShiftStatus.ASSIGNED).build());

        // Ca 5: OPEN (Hà Nội - chiều ngày kia)
        shiftRepository.save(Shift.builder()
                .title("Ca chiều - Thu ngân HN")
                .startTime(dayAfterTomorrow.withHour(14))
                .endTime(dayAfterTomorrow.withHour(22))
                .requiredLevel(2).branch(hn).requiredSkill(cashier)
                .assignedTo(null).status(ShiftStatus.OPEN).build());

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

        log.info("[SEEDER] ✅ Seed hoàn tất: 2 branches, 3 skills, 5 employees, 5 shifts, 2 busy schedules");
    }
}

