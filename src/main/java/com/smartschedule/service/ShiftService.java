package com.smartschedule.service;

import com.smartschedule.dto.CreateShiftRequest;
import com.smartschedule.dto.ShiftDTO;
import com.smartschedule.entity.*;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.ForbiddenOperationException;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.exception.NotFoundException;
import com.smartschedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * ★ SERVICE TRUNG TÂM của vòng đời ca: tạo/sửa, nhận, nhả, gán, đánh dấu đi muộn, và các truy vấn danh sách.
 *
 * Module 1 (Đổi ca & Tương tranh): releaseShift(), takeShift() — Optimistic Locking trên Shift + khóa dòng nhân viên.
 * Module 2 (AI Matchmaking): xem MatchingService.
 * Module 3 (Cron auto-assign): xem AutoAssignJob.
 *
 * Mọi nơi gán ca dùng CHUNG ShiftRules.validateAssignment().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftService {

    private static final List<ShiftStatus> SCHEDULE_STATUSES = List.of(
            ShiftStatus.ASSIGNED, ShiftStatus.TAKEN, ShiftStatus.FORCE_ASSIGNED, ShiftStatus.COMPLETED);

    private static final int MAX_PAGE_SIZE = 500;

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final SkillRepository skillRepository;
    private final ShiftRules rules;
    private final NotificationService notifications;

    /** Phải nhả ca trước giờ bắt đầu ít nhất bấy nhiêu giờ (LOG-11) - cấu hình theo quy định nghiệp vụ */
    @Value("${app.shift.min-release-hours:2}")
    private long minReleaseHours;

    // TẠO / SỬA CA

    /** Tạo ca mới. Nếu gán sẵn nhân viên thì phải qua kiểm tra giống khi nhận ca. */
    @Transactional
    public ShiftDTO createShift(CreateShiftRequest request) {
        log.info("[SHIFT] Tạo ca mới");

        rules.validateTimes(request.getStartTime(), request.getEndTime());
        int level = request.getRequiredLevel() != null ? request.getRequiredLevel() : 1;

        Employee assignedTo = null;
        ShiftStatus status = ShiftStatus.OPEN;
        if (request.getAssignedToId() != null) {
            // khóa dòng nhân viên trước mọi truy vấn khác (LOG-05)
            assignedTo = lockEmployee(request.getAssignedToId());
            status = ShiftStatus.ASSIGNED;
        }

        Branch branch = findBranch(request.getBranchId());
        Skill skill = findSkill(request.getRequiredSkillId());

        if (assignedTo != null) {
            rules.validateAssignment(assignedTo, branch, skill, level,
                    request.getStartTime(), request.getEndTime(), null);
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
        if (assignedTo != null) {
            notifications.send(assignedTo.getId(), "info", "Bạn được gán ca " + label(saved) + ".");
        }
        log.info("[SHIFT] ✅ Đã tạo ca #{} ({})", saved.getId(), status);
        return convertToDTO(saved);
    }

    /**
     * Cập nhật ca.
     *  - Không sửa ca đã bắt đầu/đã qua (dữ liệu lịch sử), NGOẠI LỆ: ca UNFILLED được sửa sang giờ tương lai để
     *    MỞ LẠI (A2) — validateTimes bắt buộc giờ mới ở tương lai.
     *  - Phải gửi đúng version đang xem (A8): hai admin sửa cùng ca thì người sau nhận 409 thay vì ghi đè.
     *  - Đổi người được gán → trạng thái ASSIGNED (do admin gán), không giữ TAKEN/FORCE_ASSIGNED sai nghĩa (A8).
     */
    @Transactional
    public ShiftDTO updateShift(Long id, CreateShiftRequest request) {
        log.info("[SHIFT] Cập nhật ca #{}", id);

        rules.validateTimes(request.getStartTime(), request.getEndTime());
        if (request.getVersion() == null) {
            throw new InvalidRequestException("Thiếu version của ca. Hãy tải lại trang rồi thử lại.");
        }
        int level = request.getRequiredLevel() != null ? request.getRequiredLevel() : 1;

        Employee assignedTo = request.getAssignedToId() != null ? lockEmployee(request.getAssignedToId()) : null;

        Shift shift = findShift(id);

        if (!Objects.equals(shift.getVersion(), request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Shift.class, id);
        }

        boolean started = !shift.getStartTime().isAfter(rules.now());
        if (shift.getStatus() == ShiftStatus.COMPLETED
                || (started && shift.getStatus() != ShiftStatus.UNFILLED)) {
            throw new BusinessException("Ca đã bắt đầu hoặc đã qua, không thể chỉnh sửa "
                    + "(chỉ ca BỎ TRỐNG mới được sửa giờ để mở lại)");
        }

        Branch branch = findBranch(request.getBranchId());
        Skill skill = findSkill(request.getRequiredSkillId());

        Employee previous = shift.getAssignedTo();
        boolean assigneeChanged = assignedTo != null
                && (previous == null || !previous.getId().equals(assignedTo.getId()));

        ShiftStatus status = shift.getStatus();
        if (assignedTo != null) {
            rules.validateAssignment(assignedTo, branch, skill, level,
                    request.getStartTime(), request.getEndTime(), shift.getId());
            if (assigneeChanged || status == ShiftStatus.OPEN || status == ShiftStatus.UNFILLED) {
                status = ShiftStatus.ASSIGNED;
            }
        } else {
            status = ShiftStatus.OPEN;   // bỏ người được gán (hoặc mở lại ca UNFILLED) → ca mở
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

        if (assigneeChanged) {
            notifications.send(assignedTo.getId(), "info", "Bạn được gán ca " + label(saved) + ".");
        }
        if (previous != null && (assignedTo == null || assigneeChanged)) {
            notifications.send(previous.getId(), "warning", "Ca " + label(saved) + " đã được quản lý gỡ khỏi bạn.");
        }
        log.info("[SHIFT] ✅ Đã cập nhật ca #{} ({})", saved.getId(), status);
        return convertToDTO(saved);
    }

    // MODULE 1: ĐỔI CA & TƯƠNG TRANH

    /**
     * Nhân viên nhả ca: ASSIGNED/TAKEN → OPEN. Ca FORCE_ASSIGNED (hệ thống gán bắt buộc vì sát giờ) không nhả được (A7).
     * Mỗi lần nhả cộng 1 vào totalCancelled của người nhả (A5).
     *
     * @param employeeId ID nhân viên đang giữ ca (lấy từ người đăng nhập, không phải từ client)
     */
    @Transactional
    public ShiftDTO releaseShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhả ca #{}", employeeId, shiftId);

        Shift shift = findShift(shiftId);

        // Quyền trước, trạng thái sau; sai quyền là 403 (A12)
        if (shift.getAssignedTo() == null || !shift.getAssignedTo().getId().equals(employeeId)) {
            throw new ForbiddenOperationException("Bạn không có quyền nhả ca này");
        }

        if (shift.getStatus() == ShiftStatus.FORCE_ASSIGNED) {
            throw new BusinessException(
                    "Ca này do hệ thống gán bắt buộc vì sát giờ nên không thể nhả. Hãy liên hệ quản lý nếu có việc đột xuất.");
        }
        if (shift.getStatus() != ShiftStatus.ASSIGNED && shift.getStatus() != ShiftStatus.TAKEN) {
            throw new BusinessException(
                    "Chỉ có thể nhả ca đang ở trạng thái ASSIGNED hoặc TAKEN. Trạng thái hiện tại: " + shift.getStatus());
        }

        // LOG-11: không cho nhả ca đã/đang bắt đầu hoặc quá sát giờ
        if (rules.now().plusHours(minReleaseHours).isAfter(shift.getStartTime())) {
            throw new BusinessException(
                    "Quá sát giờ bắt đầu ca (phải nhả trước ít nhất " + minReleaseHours + " giờ), không thể nhả ca");
        }

        Long releaserId = shift.getAssignedTo().getId();
        String releaserName = shift.getAssignedTo().getFullName();
        shift.setAssignedTo(null);
        shift.setStatus(ShiftStatus.OPEN);
        Shift saved = shiftRepository.save(shift);

        employeeRepository.incrementCancelled(releaserId);
        notifications.sendToAdmins("warning", releaserName + " đã nhả ca " + label(saved) + ". Ca đang mở trên Chợ ca.", releaserId);

        log.info("[MODULE 1] ✅ Ca #{} đã chuyển sang OPEN", shiftId);
        return convertToDTO(saved);
    }

    /**
     * ★ Nhân viên TỰ nhận ca OPEN → TAKEN (Optimistic Locking trên ca + khóa dòng nhân viên).
     * Hai người cùng nhận MỘT ca → người sau nhận lỗi 409 (GlobalExceptionHandler).
     */
    @Transactional
    public ShiftDTO takeShift(Long shiftId, Long employeeId) {
        log.info("[MODULE 1] Nhân viên #{} nhận ca #{}", employeeId, shiftId);
        Shift saved = assign(shiftId, employeeId, ShiftStatus.TAKEN);
        notifications.sendToAdmins("info", saved.getAssignedTo().getFullName() + " đã nhận ca " + label(saved) + ".", employeeId);
        log.info("[MODULE 1] ✅ Nhân viên #{} đã nhận ca #{} thành công", employeeId, shiftId);
        return convertToDTO(saved);
    }

    /** Admin gán ca OPEN cho một nhân viên → ASSIGNED, và báo cho nhân viên đó. */
    @Transactional
    public ShiftDTO assignShift(Long shiftId, Long employeeId, Long adminId) {
        log.info("[MODULE 1] Admin #{} gán ca #{} cho nhân viên #{}", adminId, shiftId, employeeId);
        Shift saved = assign(shiftId, employeeId, ShiftStatus.ASSIGNED);
        if (!employeeId.equals(adminId)) {
            notifications.send(employeeId, "info", "Quản lý đã gán ca " + label(saved) + " cho bạn.");
        }
        return convertToDTO(saved);
    }

    /** Phần chung của nhận ca/gán ca: khóa nhân viên → kiểm tra trạng thái → validateAssignment → ghi. */
    private Shift assign(Long shiftId, Long employeeId, ShiftStatus newStatus) {
        // Câu truy vấn đầu tiên của giao dịch: khóa dòng nhân viên
        Employee employee = lockEmployee(employeeId);

        Shift shift = findShift(shiftId);

        // A1/A2: chỉ ca OPEN (chưa bắt đầu) mới nhận/gán được. Ca UNFILLED phải được admin sửa giờ để mở lại trước.
        if (shift.getStatus() != ShiftStatus.OPEN) {
            throw new BusinessException("Ca này không còn mở để nhận. Trạng thái hiện tại: " + shift.getStatus());
        }

        rules.validateAssignment(employee, shift.getBranch(), shift.getRequiredSkill(), shift.getRequiredLevel(),
                shift.getStartTime(), shift.getEndTime(), shift.getId());

        // Nếu version không khớp lúc commit → ObjectOptimisticLockingFailureException → HTTP 409 (handleOptimisticLock)
        shift.setAssignedTo(employee);
        shift.setStatus(newStatus);
        return shiftRepository.save(shift);
    }

    // ĐỘ TIN CẬY (A5)

    /**
     * Admin ghi nhận nhân viên ĐẾN MUỘN ở một ca đã bắt đầu → lateArrivalCount + 1 (mỗi ca chỉ một lần).
     * (Hệ thống chưa có chấm công tự động, nên đây là nguồn dữ liệu duy nhất cho chỉ số đi muộn.)
     */
    @Transactional
    public ShiftDTO markLate(Long shiftId) {
        Shift shift = findShift(shiftId);
        if (shift.getAssignedTo() == null || !SCHEDULE_STATUSES.contains(shift.getStatus())) {
            throw new BusinessException("Ca này không có người làm để ghi nhận đi muộn");
        }
        if (shift.getStartTime().isAfter(rules.now())) {
            throw new BusinessException("Ca chưa bắt đầu, chưa thể ghi nhận đi muộn");
        }
        if (shift.isLateMarked()) {
            throw new BusinessException("Ca này đã được ghi nhận đi muộn rồi");
        }
        shift.setLateMarked(true);
        Shift saved = shiftRepository.save(shift);
        employeeRepository.incrementLate(saved.getAssignedTo().getId());
        notifications.send(saved.getAssignedTo().getId(), "warning", "Bạn bị ghi nhận đến muộn ở ca " + label(saved) + ".");
        return convertToDTO(saved);
    }

    // TRUY VẤN DANH SÁCH

    /** Ca OPEN chưa bắt đầu (Chợ ca), tuỳ chọn lọc theo chi nhánh. */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getOpenShifts(Long branchId) {
        LocalDateTime now = rules.now();
        List<Shift> shifts = branchId != null
                ? shiftRepository.findOpenFutureByBranch(now, branchId)
                : shiftRepository.findOpenFuture(now);
        return shifts.stream().map(this::convertToDTO).toList();
    }

    /** Lịch làm việc của nhân viên: ca đã gán/đã nhận/đã hoàn thành, từ 7 ngày trước trở đi. */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getEmployeeSchedule(Long employeeId) {
        return shiftRepository.findByAssignedToAndStatuses(employeeId, SCHEDULE_STATUSES, rules.now().minusDays(7))
                .stream().map(this::convertToDTO).toList();
    }

    /**
     * Admin: ca trong cửa sổ thời gian, có phân trang (A11). Mặc định: từ 7 ngày trước đến 60 ngày tới.
     */
    @Transactional(readOnly = true)
    public List<ShiftDTO> getAdminShifts(LocalDateTime from, LocalDateTime to, Integer page, Integer size) {
        LocalDateTime now = rules.now();
        LocalDateTime start = from != null ? from : now.minusDays(7);
        LocalDateTime end = to != null ? to : now.plusDays(60);
        if (end.isBefore(start)) {
            throw new InvalidRequestException("Khoảng thời gian lọc không hợp lệ");
        }
        int pageNo = page != null && page > 0 ? page : 0;
        int pageSize = size != null && size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 200;
        return shiftRepository.findInWindow(start, end, PageRequest.of(pageNo, pageSize))
                .stream().map(this::convertToDTO).toList();
    }

    // TIỆN ÍCH

    private Shift findShift(Long id) {
        return shiftRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ca #" + id));
    }

    private Employee lockEmployee(Long id) {
        return employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + id));
    }

    private Branch findBranch(Long id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy chi nhánh #" + id));
    }

    private Skill findSkill(Long id) {
        if (id == null) {
            return null;
        }
        return skillRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy kỹ năng #" + id));
    }

    private static String label(Shift s) {
        return "#" + s.getId() + " \"" + s.getTitle() + "\" (" + s.getStartTime().toString().replace('T', ' ') + ")";
    }

    /** Chuyển Shift entity → ShiftDTO cho API response. */
    ShiftDTO convertToDTO(Shift shift) {
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
                .lateMarked(shift.isLateMarked())
                .build();
    }
}
