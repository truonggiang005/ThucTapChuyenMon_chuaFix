package com.smartschedule.service;

import com.smartschedule.entity.*;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.repository.BusyScheduleRepository;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.EmployeeSkillRepository;
import com.smartschedule.repository.ShiftRepository;
import com.smartschedule.util.CapacityRules;
import com.smartschedule.util.WorkWeek;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ★ QUY TẮC GÁN CA DÙNG CHUNG cho mọi nơi gán ca: nhân viên tự nhận, admin gán, tạo/sửa ca, cron tự gán,
 * và bộ lọc ứng viên của AI. Chỉ có MỘT bản cài đặt nên các đường đi không thể lệch nhau.
 *
 * validateAssignment kiểm tra: tài khoản hoạt động → ca CHƯA bắt đầu (A1) → ràng buộc chi nhánh (A14)
 * → không trùng ca → không trùng lịch bận → giờ/tuần theo từng tuần ca chạm tới (A4) → kỹ năng/level
 * → sức chứa chi nhánh (A14).
 *
 * Các phương thức chạy trong transaction của nơi gọi.
 */
@Component
@RequiredArgsConstructor
public class ShiftRules {

    /** Trạng thái ca đang "giữ chỗ" của một nhân viên (tính vào trùng lịch, giờ/tuần, sức chứa). */
    public static final List<ShiftStatus> OCCUPYING = List.of(
            ShiftStatus.ASSIGNED, ShiftStatus.TAKEN, ShiftStatus.FORCE_ASSIGNED, ShiftStatus.COMPLETED);

    private static final double EPS = 1e-9;
    private static final int DEFAULT_MAX_HOURS = 40;

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final BusyScheduleRepository busyScheduleRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final Clock clock;

    /** Độ dài tối đa của một ca (giờ). Python AI từ chối ca > 48h nên đừng đặt cao hơn 48 (A3, B10). */
    @Value("${app.shift.max-hours:24}")
    private double maxShiftHours;

    @Value("${app.shift.min-minutes:15}")
    private long minShiftMinutes;

    /** true: nhân viên chỉ được nhận ca thuộc chi nhánh chính của mình (A14). Mặc định false: được nhận ở mọi chi nhánh. */
    @Value("${app.shift.restrict-to-primary-branch:false}")
    private boolean restrictToPrimaryBranch;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Kiểm tra thời gian khi TẠO/SỬA ca: đủ dữ liệu, kết thúc sau bắt đầu, bắt đầu ở tương lai, độ dài hợp lý (A3). */
    public void validateTimes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new InvalidRequestException("Thiếu thời gian bắt đầu hoặc kết thúc");
        }
        if (!end.isAfter(start)) {
            throw new InvalidRequestException("Thời gian kết thúc phải sau thời gian bắt đầu");
        }
        if (!start.isAfter(now())) {
            throw new InvalidRequestException("Thời gian bắt đầu phải ở tương lai");
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < minShiftMinutes) {
            throw new InvalidRequestException("Ca phải dài tối thiểu " + minShiftMinutes + " phút");
        }
        if (minutes > Math.round(maxShiftHours * 60)) {
            throw new InvalidRequestException("Ca quá dài (tối đa " + trim(maxShiftHours) + " giờ)");
        }
    }

    /**
     * ★ Kiểm tra một nhân viên có được gán vào ca này không. Ném BusinessException (HTTP 409) nếu không.
     *
     * @param excludeShiftId ca đang xét (khi sửa/nhận ca) để không tự trùng với chính nó; null nếu là ca mới
     */
    public void validateAssignment(Employee employee, Branch branch, Skill skill, int requiredLevel,
                                   LocalDateTime start, LocalDateTime end, Long excludeShiftId) {
        Long employeeId = employee.getId();
        long excludeId = excludeShiftId != null ? excludeShiftId : 0L;

        if (!employee.isActive()) {
            throw new BusinessException("Tài khoản nhân viên đã bị vô hiệu hóa");
        }

        // A1: tuyệt đối không nhận/gán ca đã bắt đầu hoặc đã qua (kể cả ca UNFILLED, gọi API trực tiếp)
        if (!start.isAfter(now())) {
            throw new BusinessException("Ca đã bắt đầu hoặc đã qua, không thể nhận/gán");
        }

        if (restrictToPrimaryBranch && branch != null
                && (employee.getPrimaryBranch() == null
                    || !employee.getPrimaryBranch().getId().equals(branch.getId()))) {
            throw new BusinessException("Nhân viên chỉ được nhận ca thuộc chi nhánh chính của mình");
        }

        Long conflictCount = shiftRepository.countScheduleConflict(employeeId, start, end, excludeId);
        if (conflictCount != null && conflictCount > 0) {
            throw new BusinessException("Nhân viên đã có ca trùng lịch trong khung giờ này");
        }

        Long busyCount = busyScheduleRepository.countBusyConflict(employeeId, start, end);
        if (busyCount != null && busyCount > 0) {
            throw new BusinessException(
                    "Nhân viên đã đăng ký lịch bận trong khung giờ này. Hãy xóa lịch bận trước khi nhận ca.");
        }

        // A4: ca vắt qua tuần/nửa đêm được chia theo từng tuần; mỗi tuần kiểm tra riêng
        int maxHours = maxHoursOf(employee);
        for (WorkWeek.Slice slice : WorkWeek.slices(start, end)) {
            Double worked = employeeRepository.calculateHoursWorkedInWeek(
                    employeeId, slice.weekStart(), slice.weekEnd(), excludeId);
            double current = worked != null ? worked : 0.0;
            if (current + slice.hours() > maxHours + EPS) {
                throw new BusinessException(String.format(Locale.ROOT,
                        "Nhận ca này sẽ vượt giới hạn giờ làm/tuần (đã %.1fh + ca %.1fh > tối đa %dh)",
                        current, slice.hours(), maxHours));
            }
        }

        if (skill != null) {
            int level = employeeSkillRepository.findByEmployeeIdAndSkillId(employeeId, skill.getId())
                    .map(EmployeeSkill::getSkillLevel)
                    .orElse(0);
            if (level < requiredLevel) {
                throw new BusinessException("Nhân viên chưa đủ kỹ năng/cấp độ cho ca này");
            }
        }

        checkBranchCapacity(branch, start, end, excludeId);
    }

    /** Sức chứa chi nhánh = số người làm ĐỒNG THỜI tối đa (Branch.maxCapacity). null/<=0 nghĩa là không giới hạn. */
    private void checkBranchCapacity(Branch branch, LocalDateTime start, LocalDateTime end, long excludeId) {
        if (!branchHasCapacity(branch, start, end, excludeId)) {
            throw new BusinessException("Chi nhánh đã đạt sức chứa tối đa (" + branch.getMaxCapacity()
                    + " người làm đồng thời) trong khung giờ này");
        }
    }

    /** Thêm MỘT người nữa vào chi nhánh trong [start, end) có vượt Branch.maxCapacity không? */
    public boolean branchHasCapacity(Branch branch, LocalDateTime start, LocalDateTime end, long excludeId) {
        if (branch == null || branch.getMaxCapacity() == null || branch.getMaxCapacity() <= 0) {
            return true;
        }
        List<LocalDateTime[]> intervals = shiftRepository
                .findActiveIntervalsInBranch(branch.getId(), OCCUPYING, start, end, excludeId).stream()
                .map(r -> new LocalDateTime[]{(LocalDateTime) r[0], (LocalDateTime) r[1]})
                .toList();
        return CapacityRules.peakConcurrency(intervals, start, end) + 1 <= branch.getMaxCapacity();
    }

    // Dùng cho bộ lọc ứng viên AI (truy vấn GỘP, không N+1)

    /** Giữ lại các nhân viên mà nhận ca [start,end) không vượt giới hạn giờ/tuần ở TUẦN NÀO ca chạm tới. */
    public List<Employee> withinWeeklyLimit(List<Employee> candidates, LocalDateTime start, LocalDateTime end,
                                            Long excludeShiftId) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        long excludeId = excludeShiftId != null ? excludeShiftId : 0L;
        List<Long> ids = candidates.stream().map(Employee::getId).toList();
        List<WorkWeek.Slice> slices = WorkWeek.slices(start, end);
        List<Map<Long, Double>> workedPerSlice = new ArrayList<>();
        for (WorkWeek.Slice slice : slices) {
            workedPerSlice.add(workedHours(ids, slice.weekStart(), slice.weekEnd(), excludeId));
        }
        return candidates.stream().filter(e -> {
            for (int i = 0; i < slices.size(); i++) {
                double current = workedPerSlice.get(i).getOrDefault(e.getId(), 0.0);
                if (current + slices.get(i).hours() > maxHoursOf(e) + EPS) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    /** Giờ đã làm của nhiều nhân viên trong tuần [weekStart, weekEnd): một truy vấn. Thiếu khóa = 0 giờ. */
    public Map<Long, Double> workedHours(Collection<Long> employeeIds, LocalDateTime weekStart,
                                         LocalDateTime weekEnd, long excludeShiftId) {
        Map<Long, Double> out = new HashMap<>();
        if (employeeIds.isEmpty()) {
            return out;
        }
        for (EmployeeRepository.WorkedHours row :
                employeeRepository.sumWorkedHours(employeeIds, weekStart, weekEnd, excludeShiftId)) {
            out.put(row.getEmployeeId().longValue(), row.getHours() != null ? row.getHours().doubleValue() : 0.0);
        }
        return out;
    }

    public boolean restrictsToPrimaryBranch() {
        return restrictToPrimaryBranch;
    }

    private static int maxHoursOf(Employee e) {
        return e.getMaxHoursPerWeek() != null ? e.getMaxHoursPerWeek() : DEFAULT_MAX_HOURS;
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
