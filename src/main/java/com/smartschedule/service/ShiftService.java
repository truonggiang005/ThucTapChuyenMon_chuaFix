package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.*;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ★ SERVICE TRUNG TÂM — Xử lý toàn bộ business logic cho 3 Module.
 *
 * Module 1 (Đổi ca & Tương tranh):
 *   - releaseShift(): Nhân viên nhả ca (ASSIGNED → OPEN)
 *   - takeShift(): Nhận/gán ca OPEN (OPEN → TAKEN) + Optimistic Locking + khóa dòng nhân viên
 *
 * Module 2 (AI Matchmaking):
 *   - getMatchingCandidates(): Lọc + Gọi AI chấm điểm + Loại nhân viên bận
 *
 * Module 3 (Auto-assign Job):
 *   - autoAssignOpenShifts(): Cron Job quét ca OPEN sắp tới, tự gán FORCE_ASSIGNED
 *   - markExpiredShifts(): Đánh dấu ca quá hạn UNFILLED
 *
 * Mọi nơi gán ca (nhận / admin gán / tạo / sửa / cron) dùng CHUNG validateAssignment() (LOG-01, 02, 07).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;
    private final BusyScheduleRepository busyScheduleRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final AIService aiService;
    private final PlatformTransactionManager transactionManager;

    /** Phải nhả ca trước giờ bắt đầu ít nhất bấy nhiêu giờ (LOG-11) - cấu hình theo quy định nghiệp vụ */
    @Value("${app.shift.min-release-hours:2}")
    private long minReleaseHours;

    /** Tắt cron ở các instance phụ khi chạy nhiều instance (LOG-08) */
    @Value("${app.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    // ============================================================
    // TIỆN ÍCH DÙNG CHUNG
    // ============================================================

    /** Khoảng [thứ 2 00:00, chủ nhật 23:59:59] của tuần chứa mốc thời gian ref (ARC-02, LOG-06). */
    public static LocalDateTime[] weekRange(LocalDateTime ref) {
        LocalDateTime weekStart = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
        LocalDateTime weekEnd = ref.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);
        return new LocalDateTime[]{weekStart, weekEnd};
    }

    private static double hoursBetween(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toMinutes() / 60.0;
    }

    private static void validateTimes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new InvalidRequestException("Thiếu thời gian bắt đầu hoặc kết thúc");
        }
        if (!end.isAfter(start)) {
            throw new InvalidRequestException("Thời gian kết thúc phải sau thời gian bắt đầu");
        }
    }

    private TransactionTemplate readOnlyTx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setReadOnly(true);
        return t;
    }

    /**
     * ★ KIỂM TRA CHUNG khi gán một ca cho nhân viên (LOG-01, LOG-02, LOG-07):
     *   1) không trùng ca khác  2) không trùng lịch bận  3) không vượt giờ/tuần  4) đủ kỹ năng + level.
     *
     * @param excludeShiftId ca đang xét (khi sửa ca) để không tự trùng với chính nó; null nếu là ca mới
     */
    private void validateAssignment(Employee employee, Skill skill, int requiredLevel,
                                    LocalDateTime start, LocalDateTime end, Long excludeShiftId) {
        Long employeeId = employee.getId();
        long excludeId = excludeShiftId != null ? excludeShiftId : 0L;

        Long conflictCount = shiftRepository.countScheduleConflict(employeeId, start, end, excludeId);
        if (conflictCount != null && conflictCount > 0) {
            throw new IllegalStateException("Nhân viên đã có ca trùng lịch trong khung giờ này");
        }

        Long busyCount = busyScheduleRepository.countBusyConflict(employeeId, start, end);
        if (busyCount != null && busyCount > 0) {
            throw new IllegalStateException(
                    "Nhân viên đã đăng ký lịch bận trong khung giờ này. Hãy xóa lịch bận trước khi nhận ca.");
        }

        LocalDateTime[] week = weekRange(start);
        Double hoursWorked = employeeRepository.calculateHoursWorkedInWeek(employeeId, week[0], week[1], excludeId);
        double current = hoursWorked != null ? hoursWorked : 0.0;
        double shiftHours = hoursBetween(start, end);
        int maxHours = employee.getMaxHoursPerWeek() != null ? employee.getMaxHoursPerWeek() : 40;
        if (current + shiftHours > maxHours) {
            throw new IllegalStateException(String.format(
                    "Nhận ca này sẽ vượt giới hạn giờ làm/tuần (đã %.1fh + ca %.1fh > tối đa %dh)",
                    current, shiftHours, maxHours));
        }

        if (skill != null) {
            int level = employeeSkillRepository.findByEmployeeIdAndSkillId(employeeId, skill.getId())
                    .map(EmployeeSkill::getSkillLevel)
                    .orElse(0);
            if (level < requiredLevel) {
                throw new IllegalStateException("Nhân viên chưa đủ kỹ năng/cấp độ cho ca này");
            }
        }
    }

    // ============================================================
    // TẠO / SỬA CA
    // ============================================================

    /**
     * Tạo ca làm việc mới. Nếu gán sẵn nhân viên thì phải qua kiểm tra giống khi nhận ca.
     */
    @Transactional
    public ShiftDTO createShift(CreateShiftRequest request) {
        log.info("[SHIFT] Tạo ca mới");

        validateTimes(request.getStartTime(), request.getEndTime());
        int level = request.getRequiredLevel() != null ? request.getRequiredLevel() : 1;

        Employee assignedTo = null;
        ShiftStatus status = ShiftStatus.OPEN;
        if (request.getAssignedToId() != null) {
            // khóa dòng nhân viên trước mọi truy vấn khác (LOG-05)
            assignedTo = employeeRepository.findByIdForUpdate(request.getAssignedToId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + request.getAssignedToId()));
            status = ShiftStatus.ASSIGNED;
        }

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh #" + request.getBranchId()));

        Skill skill = null;
        if (request.getRequiredSkillId() != null) {
            skill = skillRepository.findById(request.getRequiredSkillId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỹ năng #" + request.getRequiredSkillId()));
        }

        if (assignedTo != null) {
            validateAssignment(assignedTo, skill, level, request.getStartTime(), request.getEndTime(), null);
        }

        Shift shift = Shift.builder()
                .title(request.getTitle().trim())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .requiredLevel(level)
                .branch(branch)
                .requiredSkill(skill)
                .assignedTo(assignedTo)
                .status(status)
                .build();

        Shift saved = shiftRepository.save(shift);
        log.info("[SHIFT] ✅ Đã tạo ca #{} ({})", saved.getId(), status);
        return convertToDTO(saved);
    }

    /**
     * Cập nhật ca làm việc. Không cho sửa ca đã bắt đầu / đã qua (dữ liệu lịch sử).
     */
    @Transactional
    public ShiftDTO updateShift(Long id, CreateShiftRequest request) {
        log.info("[SHIFT] Cập nhật ca #{}", id);

        validateTimes(request.getStartTime(), request.getEndTime());
        int level = request.getRequiredLevel() != null ? request.getRequiredLevel() : 1;

        Employee assignedTo = null;
        if (request.getAssignedToId() != null) {
            assignedTo = employeeRepository.findByIdForUpdate(request.getAssignedToId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + request.getAssignedToId()));
        }

        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + id));

        if (!shift.getStartTime().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("Ca đã bắt đầu hoặc đã qua, không thể chỉnh sửa");
        }

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh #" + request.getBranchId()));

        Skill skill = null;
        if (request.getRequiredSkillId() != null) {
            skill = skillRepository.findById(request.getRequiredSkillId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỹ năng #" + request.getRequiredSkillId()));
        }

        ShiftStatus status = shift.getStatus();
        if (assignedTo != null) {
            validateAssignment(assignedTo, skill, level, request.getStartTime(), request.getEndTime(), shift.getId());
            if (status == ShiftStatus.OPEN || status == ShiftStatus.UNFILLED) {
                status = ShiftStatus.ASSIGNED;
            }
        } else if (status == ShiftStatus.ASSIGNED || status == ShiftStatus.TAKEN || status == ShiftStatus.FORCE_ASSIGNED) {
            status = ShiftStatus.OPEN;
        }

        shift.setTitle(request.getTitle().trim());
        shift.setStartTime(request.getStartTime());
        shift.setEndTime(request.getEndTime());
        shift.setRequiredLevel(level);
        shift.setBranch(branch);
        shift.setRequiredSkill(skill);
        shift.setAssignedTo(assignedTo);
        shift.setStatus(status);

        Shift saved = shiftRepository.save(shift);
        log.info("[SHIFT] ✅ Đã cập nhật ca #{} ({})", saved.getId(), status);
        return convertToDTO(saved);
    }

    // ============================================================
    // MODULE 1: ĐỔI CA & TƯƠNG TRANH
    // ============================================================

    /**
     * Nhân viên nhả ca: ASSIGNED/TAKEN → OPEN.
     *
     * @param shiftId    ID ca cần nhả
     * @param employeeId ID nhân viên đang giữ ca (lấy từ người đăng nhập, không phải từ client)
     * @throws IllegalStateException nếu ca không ở trạng thái hợp lệ, NV không có quyền, hoặc quá sát giờ
     */
    @Transactional
    public ShiftDTO releaseShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhả ca #{}", employeeId, shiftId);

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        if (shift.getStatus() != ShiftStatus.ASSIGNED && shift.getStatus() != ShiftStatus.TAKEN) {
            throw new IllegalStateException(
                    "Chỉ có thể nhả ca đang ở trạng thái ASSIGNED hoặc TAKEN. Trạng thái hiện tại: " + shift.getStatus());
        }

        // Kiểm tra quyền: chỉ người được gán mới nhả được
        if (shift.getAssignedTo() == null || !shift.getAssignedTo().getId().equals(employeeId)) {
            throw new IllegalStateException("Bạn không có quyền nhả ca này");
        }

        // LOG-11: không cho nhả ca đã/đang bắt đầu hoặc quá sát giờ
        if (LocalDateTime.now().plusHours(minReleaseHours).isAfter(shift.getStartTime())) {
            throw new IllegalStateException(
                    "Quá sát giờ bắt đầu ca (phải nhả trước ít nhất " + minReleaseHours + " giờ), không thể nhả ca");
        }

        shift.setAssignedTo(null);
        shift.setStatus(ShiftStatus.OPEN);
        Shift saved = shiftRepository.save(shift);

        log.info("[MODULE 1] ✅ Ca #{} đã chuyển sang OPEN", shiftId);
        return convertToDTO(saved);
    }

    /**
     * ★ Nhận ca OPEN → TAKEN (Optimistic Locking trên ca + khóa dòng nhân viên).
     *
     * - @Version trên Shift: hai người cùng nhận MỘT ca → người sau nhận lỗi 409 (xử lý ở GlobalExceptionHandler).
     * - Khóa dòng nhân viên (LOG-05): hai ca chồng giờ không thể được CÙNG một nhân viên nhận đồng thời.
     *
     * Dùng cho cả nhân viên tự nhận lẫn admin gán (employeeId do server quyết định ở controller).
     */
    @Transactional
    public ShiftDTO takeShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhận ca #{}", employeeId, shiftId);

        // Câu truy vấn đầu tiên của giao dịch: khóa dòng nhân viên
        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + employeeId));

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        if (shift.getStatus() != ShiftStatus.OPEN && shift.getStatus() != ShiftStatus.UNFILLED) {
            throw new IllegalStateException(
                    "Ca này không còn mở để nhận. Trạng thái hiện tại: " + shift.getStatus());
        }

        validateAssignment(employee, shift.getRequiredSkill(), shift.getRequiredLevel(),
                shift.getStartTime(), shift.getEndTime(), shift.getId());

        // Nếu version không khớp lúc commit → ObjectOptimisticLockingFailureException,
        // được GlobalExceptionHandler.handleOptimisticLock trả HTTP 409 (LOG-10: bỏ try/catch vô hiệu)
        shift.setAssignedTo(employee);
        shift.setStatus(ShiftStatus.TAKEN);
        Shift saved = shiftRepository.save(shift);

        log.info("[MODULE 1] ✅ Nhân viên #{} đã nhận ca #{} thành công", employeeId, shiftId);
        return convertToDTO(saved);
    }

    // ============================================================
    // MODULE 2: AI MATCHMAKING
    // ============================================================

    /**
     * Dựng request gửi AI cho một ca: lọc ứng viên đủ điều kiện, loại người bận.
     * Chạy trong transaction (đọc DB), KHÔNG gọi HTTP. Trả null nếu không có ứng viên.
     */
    private AIMatchRequest prepareMatchRequest(Long shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        LocalDateTime[] week = weekRange(shift.getStartTime());
        double shiftHours = hoursBetween(shift.getStartTime(), shift.getEndTime());

        // excludeEmployeeId = 0 nếu không có người cần loại trừ
        Long excludeId = (shift.getAssignedTo() != null) ? shift.getAssignedTo().getId() : 0L;

        // LOG-03: ca không yêu cầu kỹ năng dùng truy vấn riêng (không JOIN employee_skills)
        List<Employee> eligible;
        if (shift.getRequiredSkill() == null) {
            eligible = employeeRepository.findEligibleEmployeesNoSkill(
                    shift.getStartTime(), shift.getEndTime(), week[0], week[1], shiftHours, excludeId);
        } else {
            eligible = employeeRepository.findEligibleEmployees(
                    shift.getRequiredSkill().getId(), shift.getRequiredLevel(),
                    shift.getStartTime(), shift.getEndTime(), week[0], week[1], shiftHours, excludeId);
        }
        log.info("[MODULE 2] Ca #{}: lọc được {} nhân viên đủ điều kiện (trước khi lọc lịch bận)", shiftId, eligible.size());

        // Loại bỏ nhân viên đang bận
        List<Long> busyEmployeeIds = busyScheduleRepository.findBusyEmployeeIds(
                shift.getStartTime(), shift.getEndTime());
        if (!busyEmployeeIds.isEmpty()) {
            eligible = eligible.stream()
                    .filter(emp -> !busyEmployeeIds.contains(emp.getId()))
                    .collect(Collectors.toList());
        }

        if (eligible.isEmpty()) {
            return null;
        }
        return aiService.buildRequest(shift, eligible);
    }

    /**
     * ★ Lọc nhân viên đủ điều kiện + Gọi AI chấm điểm.
     *
     * Không giữ transaction trong lúc chờ HTTP: phần đọc DB nằm trong một transaction ngắn,
     * phần gọi Python nằm ngoài transaction.
     */
    public AIMatchResponse getMatchingCandidates(Long shiftId) {
        log.info("[MODULE 2] Tìm ứng viên phù hợp cho ca #{}", shiftId);

        AIMatchRequest request = readOnlyTx().execute(status -> prepareMatchRequest(shiftId));
        if (request == null) {
            return AIMatchResponse.builder()
                    .shift_id(shiftId)
                    .ranked_candidates(List.of())
                    .total_candidates(0)
                    .build();
        }
        return aiService.score(request);
    }

    /**
     * Điểm AI của CHÍNH một nhân viên cho một ca (nhân viên chỉ được xem điểm của mình, không xem của người khác).
     * @return điểm 0-100 hoặc null nếu nhân viên không nằm trong danh sách đủ điều kiện
     */
    public Double getMyMatchScore(Long shiftId, Long employeeId) {
        AIMatchResponse response = getMatchingCandidates(shiftId);
        if (response == null || response.getRanked_candidates() == null) {
            return null;
        }
        return response.getRanked_candidates().stream()
                .filter(c -> employeeId.equals(c.getEmployee_id()))
                .map(AIEmployeeScore::getMatch_score)
                .findFirst()
                .orElse(null);
    }

    // ============================================================
    // MODULE 3: CRON JOB — AUTO ASSIGN
    // ============================================================

    /**
     * ★ CRON JOB: Tự động gán ca OPEN sắp đến giờ (mỗi 5 phút).
     *
     * KHÔNG bọc @Transactional ở cấp cron: mỗi ca dùng các transaction ngắn riêng, và việc gọi AI
     * (HTTP, tối đa 30 giây) nằm ngoài transaction để không giữ kết nối DB (LOG-08).
     */
    @Scheduled(fixedRate = 300000) // 5 phút
    public void autoAssignOpenShifts() {
        if (!schedulerEnabled) {
            return;
        }
        log.info("[MODULE 3] ⏰ CRON JOB bắt đầu quét ca OPEN sắp đến giờ...");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusHours(2); // Quét ca trong 2 giờ tới

        List<Long> shiftIds = shiftRepository.findUpcomingOpenShifts(ShiftStatus.OPEN, now, threshold)
                .stream().map(Shift::getId).collect(Collectors.toList());

        log.info("[MODULE 3] Tìm thấy {} ca OPEN sắp đến giờ", shiftIds.size());

        for (Long shiftId : shiftIds) {
            try {
                autoAssignSingleShift(shiftId);
            } catch (Exception e) {
                log.error("[MODULE 3] ❌ Lỗi auto-assign ca #{}: {}", shiftId, e.getMessage());
            }
        }

        // Đánh dấu ca đã quá giờ → UNFILLED
        markExpiredShifts();

        log.info("[MODULE 3] ⏰ CRON JOB hoàn tất");
    }

    /**
     * Tự động gán 1 ca OPEN cho người phù hợp nhất (lọc + AI + loại người bận như luồng gợi ý).
     */
    private void autoAssignSingleShift(Long shiftId) {
        log.info("[MODULE 3] Đang xử lý ca #{}", shiftId);

        AIMatchRequest request = readOnlyTx().execute(status -> prepareMatchRequest(shiftId));
        if (request == null) {
            log.warn("[MODULE 3] ⚠️ Không có nhân viên rảnh cho ca #{}", shiftId);
            return;
        }

        AIMatchResponse aiResponse = aiService.score(request);   // HTTP, ngoài transaction

        if (aiResponse == null || aiResponse.getRanked_candidates() == null
                || aiResponse.getRanked_candidates().isEmpty()) {
            log.warn("[MODULE 3] AI không trả về kết quả cho ca #{}", shiftId);
            return;
        }

        // LOG-08: AI lỗi → điểm chỉ là điểm dự phòng, KHÔNG tự gán (tránh gán gần như ngẫu nhiên)
        if (aiResponse.isFallback()) {
            log.warn("[MODULE 3] AI không khả dụng, bỏ qua tự gán ca #{}", shiftId);
            return;
        }

        // Thử lần lượt từ người điểm cao nhất; người nào không qua kiểm tra lại thì xét người kế tiếp
        for (AIEmployeeScore candidate : aiResponse.getRanked_candidates()) {
            try {
                boolean assigned = forceAssign(shiftId, candidate.getEmployee_id());
                if (assigned) {
                    log.info("[MODULE 3] ✅ FORCE_ASSIGNED ca #{} → nhân viên #{} (Score: {}%)",
                            shiftId, candidate.getEmployee_id(), candidate.getMatch_score());
                    return;
                }
                return; // ca không còn OPEN (đã có người nhận) → dừng
            } catch (IllegalStateException e) {
                log.info("[MODULE 3] Nhân viên #{} không đủ điều kiện cho ca #{}: {}",
                        candidate.getEmployee_id(), shiftId, e.getMessage());
            }
        }
        log.warn("[MODULE 3] Không gán được ca #{} cho ứng viên nào", shiftId);
    }

    /**
     * Gán FORCE_ASSIGNED trong một transaction ngắn, kiểm tra lại điều kiện ở thời điểm gán.
     * @return false nếu ca không còn OPEN
     */
    private boolean forceAssign(Long shiftId, Long employeeId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy nhân viên #" + employeeId));
            Shift shift = shiftRepository.findById(shiftId)
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy ca #" + shiftId));
            if (shift.getStatus() != ShiftStatus.OPEN) {
                return false;
            }
            validateAssignment(employee, shift.getRequiredSkill(), shift.getRequiredLevel(),
                    shift.getStartTime(), shift.getEndTime(), shift.getId());
            shift.setAssignedTo(employee);
            shift.setStatus(ShiftStatus.FORCE_ASSIGNED);
            shiftRepository.save(shift);
            return true;
        }));
    }

    /**
     * Đánh dấu ca OPEN đã quá giờ bắt đầu → UNFILLED.
     */
    private void markExpiredShifts() {
        Integer count = new TransactionTemplate(transactionManager).execute(status -> {
            List<Shift> expiredShifts = shiftRepository.findExpiredOpenShifts(ShiftStatus.OPEN, LocalDateTime.now());
            for (Shift shift : expiredShifts) {
                shift.setStatus(ShiftStatus.UNFILLED);
                shiftRepository.save(shift);
                log.info("[MODULE 3] Ca #{} đã quá giờ → UNFILLED", shift.getId());
            }
            return expiredShifts.size();
        });
        if (count != null && count > 0) {
            log.info("[MODULE 3] Đã đánh dấu {} ca UNFILLED", count);
        }
    }

    // ============================================================
    // CÁC PHƯƠNG THỨC TIỆN ÍCH
    // ============================================================

    /** Lấy tất cả ca OPEN (cho Frontend hiển thị). */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getOpenShifts() {
        return shiftRepository.findByStatusOrderByStartTimeAsc(ShiftStatus.OPEN)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /** Lấy tất cả ca OPEN theo chi nhánh. */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getOpenShiftsByBranch(Long branchId) {
        return shiftRepository.findByStatusAndBranchIdOrderByStartTimeAsc(ShiftStatus.OPEN, branchId)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /** Lấy lịch làm việc của nhân viên (các ca ASSIGNED, TAKEN, FORCE_ASSIGNED). */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getEmployeeSchedule(Long employeeId) {
        List<ShiftStatus> activeStatuses = List.of(
                ShiftStatus.ASSIGNED, ShiftStatus.TAKEN, ShiftStatus.FORCE_ASSIGNED);
        return shiftRepository.findByAssignedToAndStatuses(employeeId, activeStatuses)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /** Lấy tất cả ca (admin view). */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getAllShifts() {
        return shiftRepository.findAll()
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /** Lấy danh sách chi nhánh. */
    @Transactional(readOnly = true)
    public List<Branch> getAllBranches() {
        return branchRepository.findAll();
    }

    /** Lấy danh sách kỹ năng. */
    @Transactional(readOnly = true)
    public List<Skill> getAllSkills() {
        return skillRepository.findAll();
    }

    /** Chuyển Shift entity → ShiftDTO cho API response. */
    private ShiftDTO convertToDTO(Shift shift) {
        return ShiftDTO.builder()
                .id(shift.getId())
                .title(shift.getTitle())
                .startTime(shift.getStartTime().toString())
                .endTime(shift.getEndTime().toString())
                .status(shift.getStatus().name())
                .requiredLevel(shift.getRequiredLevel())
                .branchId(shift.getBranch() != null ? shift.getBranch().getId() : null)
                .branchName(shift.getBranch() != null ? shift.getBranch().getName() : null)
                .requiredSkillId(shift.getRequiredSkill() != null ? shift.getRequiredSkill().getId() : null)
                .requiredSkillName(shift.getRequiredSkill() != null ? shift.getRequiredSkill().getName() : null)
                .assignedToId(shift.getAssignedTo() != null ? shift.getAssignedTo().getId() : null)
                .assignedToName(shift.getAssignedTo() != null ? shift.getAssignedTo().getFullName() : null)
                .version(shift.getVersion())
                .build();
    }
}
