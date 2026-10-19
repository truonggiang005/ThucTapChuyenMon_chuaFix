package com.smartschedule.config;

import com.smartschedule.entity.*;
import com.smartschedule.repository.*;
import com.smartschedule.service.ShiftRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSeeder: tạo dữ liệu mẫu khi ứng dụng khởi động.
 *
 * Chỉ chạy khi app.seed.enabled=true (chỉ profile "dev" bật; mặc định và profile prod đều TẮT - C1)
 * và chỉ seed nếu DB trống (kiểm tra bảng branches). Mật khẩu mẫu lấy từ app.seed.password và được băm BCrypt.
 *
 * ★ Mọi ca được GÁN trong dữ liệu mẫu đều đi qua {@link ShiftRules#validateAssignment} (cùng bộ quy tắc với
 *   nhận ca/gán ca/cron) nên dữ liệu mẫu không thể tự mâu thuẫn (đè lịch bận, vượt giờ/tuần, thiếu kỹ năng...) (E3).
 *
 * Dữ liệu mẫu:
 *   - 2 chi nhánh, 3 kỹ năng
 *   - 5 nhân viên: Bách (ADMIN, vừa là nhân viên), Giang, Ly (HCM), Vũ, Nga (Hà Nội)
 *   - 1 ca đã hoàn thành (lịch sử), 7 ca đã gán, 4 ca OPEN để Chợ ca có dữ liệu ngay từ đầu
 *   - 2 lịch bận (không đè lên bất kỳ ca nào của chính người đó)
 */
@Component
@Order(10)
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
    private final ShiftRules shiftRules;
    private final Clock clock;

    @Value("${app.seed.password}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (branchRepository.count() > 0) {
            log.info("[SEEDER] Database đã có dữ liệu, bỏ qua seed.");
            return;
        }

        log.info("[SEEDER] Bắt đầu seed dữ liệu mẫu...");

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

        // ── 3. Nhân viên (lịch sử làm việc khác nhau để AI có dữ liệu so sánh) ──
        // Bách (id 1) là ADMIN nhưng cũng là nhân viên có kỹ năng; các tài khoản còn lại là EMPLOYEE.
        String hash = passwordEncoder.encode(seedPassword);
        Employee bach = employeeRepository.save(Employee.builder()
                .fullName("Trần Xuân Bách").email("bach@gmail.com").role(Role.ADMIN).passwordHash(hash)
                .maxHoursPerWeek(20)
                .totalCompleted(50).totalCancelled(2).lateArrivalCount(1)
                .preferredTime("MORNING").primaryBranch(hcm).build());

        Employee giang = employeeRepository.save(Employee.builder()
                .fullName("Lưu Trường Giang").email("giang@gmail.com").passwordHash(hash)
                .maxHoursPerWeek(15)
                .totalCompleted(35).totalCancelled(5).lateArrivalCount(3)
                .preferredTime("AFTERNOON").primaryBranch(hcm).build());

        Employee ly = employeeRepository.save(Employee.builder()
                .fullName("Phạm Khánh Ly").email("ly@gmail.com").passwordHash(hash)
                .maxHoursPerWeek(29)
                .totalCompleted(20).totalCancelled(8).lateArrivalCount(5)
                .preferredTime("EVENING").primaryBranch(hcm).build());

        Employee vu = employeeRepository.save(Employee.builder()
                .fullName("Nguyễn Sơn Vũ").email("vu@gmail.com").passwordHash(hash)
                .maxHoursPerWeek(34)
                .totalCompleted(45).totalCancelled(1).lateArrivalCount(0)
                .preferredTime("MORNING").primaryBranch(hn).build());

        Employee nga = employeeRepository.save(Employee.builder()
                .fullName("Đặng Thị Phương Nga").email("nga@gmail.com").passwordHash(hash)
                .maxHoursPerWeek(17)
                .totalCompleted(10).totalCancelled(0).lateArrivalCount(0)
                .preferredTime("MORNING").primaryBranch(hn).build());

        // ── 4. Kỹ năng của nhân viên (N-N) ──
        employeeSkillRepository.saveAll(List.of(
                // Bách: Barista L4, Cashier L3, Kitchen L5
                EmployeeSkill.builder().employee(bach).skill(barista).skillLevel(4).build(),
                EmployeeSkill.builder().employee(bach).skill(cashier).skillLevel(3).build(),
                EmployeeSkill.builder().employee(bach).skill(kitchen).skillLevel(5).build(),
                // Giang: Cashier L5, Kitchen L5
                EmployeeSkill.builder().employee(giang).skill(cashier).skillLevel(5).build(),
                EmployeeSkill.builder().employee(giang).skill(kitchen).skillLevel(5).build(),
                // Ly: Kitchen L5, Barista L2
                EmployeeSkill.builder().employee(ly).skill(kitchen).skillLevel(5).build(),
                EmployeeSkill.builder().employee(ly).skill(barista).skillLevel(2).build(),
                // Vũ: Barista L5, Cashier L4, Kitchen L5
                EmployeeSkill.builder().employee(vu).skill(barista).skillLevel(5).build(),
                EmployeeSkill.builder().employee(vu).skill(cashier).skillLevel(4).build(),
                EmployeeSkill.builder().employee(vu).skill(kitchen).skillLevel(5).build(),
                // Nga: Cashier L3, Barista L1, Kitchen L5
                EmployeeSkill.builder().employee(nga).skill(cashier).skillLevel(3).build(),
                EmployeeSkill.builder().employee(nga).skill(barista).skillLevel(1).build(),
                EmployeeSkill.builder().employee(nga).skill(kitchen).skillLevel(5).build()
        ));

        // ── 5. Ca làm việc ──
        // Mốc thời gian lấy từ Clock (cùng múi giờ nghiệp vụ). d2 = 00:00 của ngày kia; các ca đều ở tương lai
        // và cách xa cửa sổ tự gán của cron nên khởi động xong không bị cron đụng vào ngay.
        LocalDateTime today = LocalDateTime.now(clock).toLocalDate().atStartOfDay();
        LocalDateTime d2 = today.plusDays(2);
        LocalDateTime d3 = today.plusDays(3);
        LocalDateTime d4 = today.plusDays(4);
        LocalDateTime d5 = today.plusDays(5);

        // Lịch bận: tạo TRƯỚC các ca đã gán để validateAssignment thấy và đảm bảo không đè nhau.
        //   Ly bận sáng ngày kia (ca của Ly ở ngày d3 nên không đè).
        //   Giang bận chiều d4 (ca của Giang ở ngày d2 nên không đè) — E3: trước đây đè lên ca của chính Giang.
        busyScheduleRepository.save(BusySchedule.builder().employee(ly)
                .startTime(d2.withHour(6)).endTime(d2.withHour(14))
                .reason("Nghỉ phép - việc gia đình").build());
        busyScheduleRepository.save(BusySchedule.builder().employee(giang)
                .startTime(d4.withHour(14)).endTime(d4.withHour(18))
                .reason("Đi khám bệnh").build());

        // Lịch sử: 1 ca ĐÃ HOÀN THÀNH hôm qua của Vũ (hiển thị trong "lịch 7 ngày gần đây"; tính vào giờ/tuần)
        LocalDateTime yesterday = today.minusDays(1);
        shiftRepository.save(Shift.builder()
                .title("Ca sáng - Pha chế HN (đã xong)")
                .startTime(yesterday.withHour(8)).endTime(yesterday.withHour(16))
                .requiredLevel(4).branch(hn).requiredSkill(barista)
                .assignedTo(vu).status(ShiftStatus.COMPLETED).build());

        // Ca đã GÁN (7 ca) - đều đi qua cùng bộ quy tắc nghiệp vụ
        assigned("Ca sáng - Pha chế", bach, hcm, barista, 3, d2.withHour(7), d2.withHour(15));
        assigned("Ca chiều - Thu ngân", giang, hcm, cashier, 2, d2.withHour(13), d2.withHour(21));
        assigned("Ca sáng - Bếp", ly, hcm, kitchen, 3, d3.withHour(6), d3.withHour(14));
        assigned("Ca sáng - Pha chế HN", vu, hn, barista, 4, d2.withHour(8), d2.withHour(16));
        assigned("Ca chiều - Thu ngân HN", nga, hn, cashier, 2, d2.withHour(14), d2.withHour(22));
        assigned("Ca tối - Bếp", bach, hcm, kitchen, 2, d3.withHour(17), d3.withHour(23));
        assigned("Ca chiều - Pha chế HN", vu, hn, barista, 1, d4.withHour(13), d4.withHour(21));

        // Ca OPEN (4 ca) để trang Chợ ca có dữ liệu ngay; nhiều nhân viên đủ điều kiện nên AI có gì để xếp hạng
        open("Ca tối - Pha chế", hcm, barista, 3, d2.withHour(17), d2.withHour(22));
        open("Ca sáng - Thu ngân", hcm, cashier, 2, d3.withHour(7), d3.withHour(13));
        open("Ca sáng - Bếp HN", hn, kitchen, 4, d3.withHour(6), d3.withHour(12));
        open("Ca cuối tuần - Thu ngân HN", hn, cashier, 3, d5.withHour(9), d5.withHour(15));

        log.info("[SEEDER] Seed hoàn tất: 2 chi nhánh, 3 kỹ năng, 5 nhân viên, 8 ca đã gán/hoàn thành, 4 ca OPEN, 2 lịch bận");
        log.info("[SEEDER] Tài khoản mẫu: bach@gmail.com (ADMIN), giang@/ly@/vu@/nga@gmail.com (EMPLOYEE) - mật khẩu = app.seed.password");
    }

    /** Tạo ca đã gán sau khi kiểm tra bằng đúng bộ quy tắc nghiệp vụ; dữ liệu mẫu sai sẽ báo lỗi to ngay lúc khởi động. */
    private void assigned(String title, Employee employee, Branch branch, Skill skill, int level,
                          LocalDateTime start, LocalDateTime end) {
        shiftRules.validateAssignment(employee, branch, skill, level, start, end, null);
        shiftRepository.save(Shift.builder()
                .title(title).startTime(start).endTime(end)
                .requiredLevel(level).branch(branch).requiredSkill(skill)
                .assignedTo(employee).status(ShiftStatus.ASSIGNED).build());
    }

    private void open(String title, Branch branch, Skill skill, int level, LocalDateTime start, LocalDateTime end) {
        shiftRules.validateTimes(start, end);
        shiftRepository.save(Shift.builder()
                .title(title).startTime(start).endTime(end)
                .requiredLevel(level).branch(branch).requiredSkill(skill)
                .status(ShiftStatus.OPEN).build());
    }
}
