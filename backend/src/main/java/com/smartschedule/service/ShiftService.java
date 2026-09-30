package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.*;
import com.smartschedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   - takeShift(): Nhân viên nhận ca OPEN (OPEN → TAKEN) + Optimistic Locking
 *
 * Module 2 (AI Matchmaking):
 *   - getMatchingCandidates(): Lọc + Gọi AI chấm điểm + Loại nhân viên bận
 *
 * Module 3 (Auto-assign Job):
 *   - autoAssignOpenShifts(): Cron Job quét ca OPEN sắp tới, tự gán FORCE_ASSIGNED
 *   - markExpiredShifts(): Đánh dấu ca quá hạn UNFILLED
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
    private final AIService aiService;

    // ============================================================
    // TẠO CA MỚI
    // ============================================================

    /**
     * Tạo ca làm việc mới.
     *
     * @param request Thông tin ca cần tạo
     * @return ShiftDTO sau khi tạo
     */
    @Transactional
    public ShiftDTO createShift(CreateShiftRequest request) {
        log.info("[SHIFT] Tạo ca mới: {}", request.getTitle());

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chi nhánh #" + request.getBranchId()));

        Skill skill = null;
        if (request.getRequiredSkillId() != null) {
            skill = skillRepository.findById(request.getRequiredSkillId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỹ năng #" + request.getRequiredSkillId()));
        }

        LocalDateTime startTime = LocalDateTime.parse(request.getStartTime());
        LocalDateTime endTime = LocalDateTime.parse(request.getEndTime());

        if (endTime.isBefore(startTime) || endTime.isEqual(startTime)) {
            throw new IllegalStateException("Thời gian kết thúc phải sau thời gian bắt đầu");
        }

        Employee assignedTo = null;
        ShiftStatus status = ShiftStatus.OPEN;

        if (request.getAssignedToId() != null) {
            assignedTo = employeeRepository.findById(request.getAssignedToId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + request.getAssignedToId()));
            status = ShiftStatus.ASSIGNED;
        }

        Shift shift = Shift.builder()
                .title(request.getTitle())
                .startTime(startTime)
                .endTime(endTime)
                .requiredLevel(request.getRequiredLevel() != null ? request.getRequiredLevel() : 1)
                .branch(branch)
                .requiredSkill(skill)
                .assignedTo(assignedTo)
                .status(status)
                .build();

        Shift saved = shiftRepository.save(shift);
        log.info("[SHIFT] ✅ Đã tạo ca #{} ({})", saved.getId(), status);
        return convertToDTO(saved);
    }

    // ============================================================
    // MODULE 1: ĐỔI CA & TƯƠNG TRANH
    // ============================================================

    /**
     * Nhân viên nhả ca: ASSIGNED → OPEN.
     * Gỡ assignedTo, cho phép người khác nhận.
     *
     * @param shiftId    ID ca cần nhả
     * @param employeeId ID nhân viên đang giữ ca (xác nhận quyền nhả)
     * @return ShiftDTO sau khi nhả
     * @throws IllegalStateException nếu ca không ở trạng thái ASSIGNED hoặc NV không có quyền
     */
    @Transactional
    public ShiftDTO releaseShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhả ca #{}", employeeId, shiftId);

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        // Kiểm tra trạng thái
        if (shift.getStatus() != ShiftStatus.ASSIGNED) {
            throw new IllegalStateException(
                    "Chỉ có thể nhả ca đang ở trạng thái ASSIGNED. Trạng thái hiện tại: " + shift.getStatus());
        }

        // Kiểm tra quyền: chỉ người được gán mới nhả được
        if (shift.getAssignedTo() == null || !shift.getAssignedTo().getId().equals(employeeId)) {
            throw new IllegalStateException("Bạn không có quyền nhả ca này");
        }

        // Nhả ca
        shift.setAssignedTo(null);
        shift.setStatus(ShiftStatus.OPEN);
        Shift saved = shiftRepository.save(shift);

        log.info("[MODULE 1] ✅ Ca #{} đã chuyển sang OPEN", shiftId);
        return convertToDTO(saved);
    }

    /**
     * ★ Nhân viên nhận ca OPEN → TAKEN (có Optimistic Locking).
     *
     * LUỒNG TƯƠNG TRANH:
     *   1. NV-A và NV-B cùng xem ca #5 (version=1)
     *   2. NV-A bấm "Nhận ca" → DB update thành công (version 1→2)
     *   3. NV-B bấm "Nhận ca" → JPA kiểm tra version (đang giữ 1, DB là 2)
     *      → Ném ObjectOptimisticLockingFailureException
     *   4. Catch exception → Trả về thông báo lỗi thân thiện
     *
     * @param shiftId    ID ca cần nhận
     * @param employeeId ID nhân viên muốn nhận
     * @return ShiftDTO nếu nhận thành công
     * @throws IllegalStateException nếu ca không OPEN, trùng lịch, lịch bận, hoặc bị người khác nhận trước
     */
    @Transactional
    public ShiftDTO takeShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhận ca #{}", employeeId, shiftId);

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        // Kiểm tra trạng thái OPEN
        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new IllegalStateException(
                    "Ca này không còn mở. Trạng thái hiện tại: " + shift.getStatus());
        }

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + employeeId));

        // Kiểm tra trùng lịch ca
        boolean hasConflict = shiftRepository.hasScheduleConflict(
                employeeId, shift.getStartTime(), shift.getEndTime());
        if (hasConflict) {
            throw new IllegalStateException("Bạn đã có ca trùng lịch trong khung giờ này");
        }

        // ★ Kiểm tra lịch bận
        boolean isBusy = busyScheduleRepository.hasBusyConflict(
                employeeId, shift.getStartTime(), shift.getEndTime());
        if (isBusy) {
            throw new IllegalStateException("Bạn đã đăng ký lịch bận trong khung giờ này. Hãy xóa lịch bận trước khi nhận ca.");
        }

        // Kiểm tra giờ/tuần
        LocalDateTime weekStart = shift.getStartTime()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
        LocalDateTime weekEnd = shift.getStartTime()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);

        Double hoursWorked = employeeRepository.calculateHoursWorkedInWeek(
                employeeId, weekStart, weekEnd);
        double shiftHours = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes() / 60.0;

        if ((hoursWorked != null ? hoursWorked : 0) + shiftHours > employee.getMaxHoursPerWeek()) {
            throw new IllegalStateException(
                    String.format("Vượt quá giới hạn giờ/tuần. Đã làm: %.1fh + Ca mới: %.1fh > Tối đa: %dh",
                            hoursWorked, shiftHours, employee.getMaxHoursPerWeek()));
        }

        // ★ GÁN CA (Optimistic Locking tự động kiểm tra @Version)
        try {
            shift.setAssignedTo(employee);
            shift.setStatus(ShiftStatus.TAKEN);
            Shift saved = shiftRepository.save(shift);
            // JPA sẽ tự thêm: UPDATE shifts SET ... WHERE id=? AND version=?
            // Nếu version không khớp → ném ObjectOptimisticLockingFailureException

            log.info("[MODULE 1] ✅ Nhân viên {} đã nhận ca #{} thành công", employee.getFullName(), shiftId);
            return convertToDTO(saved);

        } catch (ObjectOptimisticLockingFailureException e) {
            // ★ TƯƠNG TRANH: Người khác đã nhận ca này trước!
            log.warn("[MODULE 1] ⚠️ TƯƠNG TRANH: Ca #{} đã bị người khác nhận trước", shiftId);
            throw new IllegalStateException(
                    "Ca này vừa bị người khác nhận trước bạn. Vui lòng chọn ca khác.");
        }
    }

    // ============================================================
    // MODULE 2: AI MATCHMAKING
    // ============================================================

    /**
     * ★ Lọc nhân viên đủ điều kiện + Gọi AI chấm điểm.
     *
     * Flow:
     *   1. Repository lọc 3 điều kiện (skill, lịch, giờ/tuần)
     *   2. Loại bỏ nhân viên đang bận (BusySchedule)
     *   3. AIService gửi sang Python chấm điểm
     *   4. Trả về danh sách ranked theo Match Score giảm dần
     *
     * @param shiftId ID ca cần tìm người phù hợp
     * @return AIMatchResponse chứa danh sách xếp hạng
     */
    @Transactional(readOnly = true)
    public AIMatchResponse getMatchingCandidates(Long shiftId) {
        log.info("[MODULE 2] Tìm ứng viên phù hợp cho ca #{}", shiftId);

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca #" + shiftId));

        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new IllegalStateException("Chỉ tìm ứng viên cho ca OPEN. Trạng thái hiện tại: " + shift.getStatus());
        }

        // ── Bước 1: Lọc nhân viên đủ điều kiện (Repository query) ──
        LocalDateTime weekStart = shift.getStartTime()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
        LocalDateTime weekEnd = shift.getStartTime()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);
        double shiftHours = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes() / 60.0;

        // excludeEmployeeId = 0 nếu không có người cần loại trừ
        Long excludeId = (shift.getAssignedTo() != null) ? shift.getAssignedTo().getId() : 0L;

        List<Employee> eligible = employeeRepository.findEligibleEmployees(
                shift.getRequiredSkill() != null ? shift.getRequiredSkill().getId() : 0L,
                shift.getRequiredLevel(),
                shift.getStartTime(),
                shift.getEndTime(),
                weekStart,
                weekEnd,
                shiftHours,
                excludeId
        );

        log.info("[MODULE 2] Lọc được {} nhân viên đủ điều kiện (trước khi lọc lịch bận)", eligible.size());

        // ── Bước 1.5: Loại bỏ nhân viên đang bận ──
        List<Long> busyEmployeeIds = busyScheduleRepository.findBusyEmployeeIds(
                shift.getStartTime(), shift.getEndTime());

        if (!busyEmployeeIds.isEmpty()) {
            eligible = eligible.stream()
                    .filter(emp -> !busyEmployeeIds.contains(emp.getId()))
                    .collect(Collectors.toList());
            log.info("[MODULE 2] Sau khi loại {} nhân viên bận → còn {} ứng viên",
                    busyEmployeeIds.size(), eligible.size());
        }

        if (eligible.isEmpty()) {
            return AIMatchResponse.builder()
                    .shift_id(shiftId)
                    .ranked_candidates(List.of())
                    .total_candidates(0)
                    .build();
        }

        // ── Bước 2: Gọi Python AI chấm điểm ──
        return aiService.getMatchScores(shift, eligible);
    }

    // ============================================================
    // MODULE 3: CRON JOB — AUTO ASSIGN
    // ============================================================

    /**
     * ★ CRON JOB: Tự động gán ca OPEN sắp đến giờ (mỗi 5 phút).
     *
     * Logic:
     *   1. Quét ca OPEN có start_time trong 2 giờ tới
     *   2. Với mỗi ca, gọi AI tìm người phù hợp nhất
     *   3. Gán cho người có Match Score cao nhất → FORCE_ASSIGNED
     *
     * @Scheduled: fixedRate = 300000ms = 5 phút
     *   Cron Job chạy mỗi 5 phút, quét liên tục.
     */
    @Scheduled(fixedRate = 300000) // 5 phút
    @Transactional
    public void autoAssignOpenShifts() {
        log.info("[MODULE 3] ⏰ CRON JOB bắt đầu quét ca OPEN sắp đến giờ...");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusHours(2); // Quét ca trong 2 giờ tới

        List<Shift> upcomingOpenShifts = shiftRepository.findUpcomingOpenShifts(
                ShiftStatus.OPEN, now, threshold);

        log.info("[MODULE 3] Tìm thấy {} ca OPEN sắp đến giờ", upcomingOpenShifts.size());

        for (Shift shift : upcomingOpenShifts) {
            try {
                autoAssignSingleShift(shift);
            } catch (Exception e) {
                log.error("[MODULE 3] ❌ Lỗi auto-assign ca #{}: {}", shift.getId(), e.getMessage());
            }
        }

        // Đánh dấu ca đã quá giờ → UNFILLED
        markExpiredShifts();

        log.info("[MODULE 3] ⏰ CRON JOB hoàn tất");
    }

    /**
     * Tự động gán 1 ca OPEN cho người phù hợp nhất.
     * ★ Tích hợp lọc lịch bận: loại nhân viên đang bận khỏi ứng viên.
     */
    private void autoAssignSingleShift(Shift shift) {
        log.info("[MODULE 3] Đang xử lý ca #{} (start: {})", shift.getId(), shift.getStartTime());

        // Lọc + AI scoring
        LocalDateTime weekStart = shift.getStartTime()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
        LocalDateTime weekEnd = shift.getStartTime()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .toLocalDate().atTime(23, 59, 59);
        double shiftHours = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes() / 60.0;

        List<Employee> eligible = employeeRepository.findEligibleEmployees(
                shift.getRequiredSkill() != null ? shift.getRequiredSkill().getId() : 0L,
                shift.getRequiredLevel(),
                shift.getStartTime(),
                shift.getEndTime(),
                weekStart,
                weekEnd,
                shiftHours,
                0L // Không loại trừ ai
        );

        // ★ Loại bỏ nhân viên đang bận
        List<Long> busyEmployeeIds = busyScheduleRepository.findBusyEmployeeIds(
                shift.getStartTime(), shift.getEndTime());
        if (!busyEmployeeIds.isEmpty()) {
            eligible = eligible.stream()
                    .filter(emp -> !busyEmployeeIds.contains(emp.getId()))
                    .collect(Collectors.toList());
        }

        if (eligible.isEmpty()) {
            log.warn("[MODULE 3] ⚠️ Không có nhân viên rảnh cho ca #{}", shift.getId());
            return;
        }

        // Gọi AI chấm điểm
        AIMatchResponse aiResponse = aiService.getMatchScores(shift, eligible);

        if (aiResponse == null || aiResponse.getRanked_candidates() == null
                || aiResponse.getRanked_candidates().isEmpty()) {
            log.warn("[MODULE 3] AI không trả về kết quả cho ca #{}", shift.getId());
            return;
        }

        // Lấy người có score cao nhất
        AIEmployeeScore bestCandidate = aiResponse.getRanked_candidates().get(0);
        Employee bestEmployee = employeeRepository.findById(bestCandidate.getEmployee_id())
                .orElse(null);

        if (bestEmployee == null) {
            log.error("[MODULE 3] Không tìm thấy nhân viên #{}", bestCandidate.getEmployee_id());
            return;
        }

        // ★ FORCE ASSIGN
        shift.setAssignedTo(bestEmployee);
        shift.setStatus(ShiftStatus.FORCE_ASSIGNED);
        shiftRepository.save(shift);

        log.info("[MODULE 3] ✅ FORCE_ASSIGNED ca #{} → {} (Score: {}%)",
                shift.getId(), bestEmployee.getFullName(), bestCandidate.getMatch_score());
    }

    /**
     * Đánh dấu ca OPEN đã quá giờ bắt đầu → UNFILLED.
     * Chạy trong Cron Job, sau phần auto-assign.
     */
    private void markExpiredShifts() {
        LocalDateTime now = LocalDateTime.now();
        List<Shift> expiredShifts = shiftRepository.findExpiredOpenShifts(ShiftStatus.OPEN, now);

        for (Shift shift : expiredShifts) {
            shift.setStatus(ShiftStatus.UNFILLED);
            shiftRepository.save(shift);
            log.info("[MODULE 3] Ca #{} đã quá giờ → UNFILLED", shift.getId());
        }

        if (!expiredShifts.isEmpty()) {
            log.info("[MODULE 3] Đã đánh dấu {} ca UNFILLED", expiredShifts.size());
        }
    }

    // ============================================================
    // CÁC PHƯƠNG THỨC TIỆN ÍCH
    // ============================================================

    /**
     * Lấy tất cả ca OPEN (cho Frontend hiển thị).
     */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getOpenShifts() {
        return shiftRepository.findByStatusOrderByStartTimeAsc(ShiftStatus.OPEN)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy tất cả ca OPEN theo chi nhánh.
     */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getOpenShiftsByBranch(Long branchId) {
        return shiftRepository.findByStatusAndBranchIdOrderByStartTimeAsc(ShiftStatus.OPEN, branchId)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy lịch làm việc của nhân viên (các ca ASSIGNED, TAKEN, FORCE_ASSIGNED).
     */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getEmployeeSchedule(Long employeeId) {
        List<ShiftStatus> activeStatuses = List.of(
                ShiftStatus.ASSIGNED, ShiftStatus.TAKEN, ShiftStatus.FORCE_ASSIGNED);
        return shiftRepository.findByAssignedToAndStatuses(employeeId, activeStatuses)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy tất cả ca (admin view).
     */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getAllShifts() {
        return shiftRepository.findAll()
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy danh sách chi nhánh.
     */
    @Transactional(readOnly = true)
    public List<Branch> getAllBranches() {
        return branchRepository.findAll();
    }

    /**
     * Lấy danh sách kỹ năng.
     */
    @Transactional(readOnly = true)
    public List<Skill> getAllSkills() {
        return skillRepository.findAll();
    }

    /**
     * Chuyển Shift entity → ShiftDTO cho API response.
     */
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

