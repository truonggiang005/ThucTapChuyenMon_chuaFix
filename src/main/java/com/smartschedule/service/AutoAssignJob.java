package com.smartschedule.service;

import com.smartschedule.dto.AIEmployeeScore;
import com.smartschedule.dto.AIMatchResponse;
import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Shift;
import com.smartschedule.entity.ShiftStatus;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.SchedulerLockRepository;
import com.smartschedule.repository.ShiftRepository;
import com.smartschedule.util.AiScoreGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MODULE 3: CRON JOB (mỗi 5 phút).
 *
 *   1. Đánh dấu ca đã kết thúc → COMPLETED và cộng totalCompleted (A5).
 *   2. Đánh dấu ca OPEN đã quá giờ → UNFILLED và báo admin.
 *   3. Tự gán ca OPEN sắp đến giờ (trong N giờ tới) cho người có điểm AI cao nhất → FORCE_ASSIGNED, và BÁO cho người được gán (A7).
 *   4. Dọn thông báo cũ.
 *
 * - KHÔNG bọc @Transactional ở cấp cron: mỗi ca dùng transaction ngắn riêng; lời gọi AI (HTTP) nằm ngoài transaction.
 * - Khóa phân tán bằng DB (SchedulerLockRepository): chạy nhiều instance cũng chỉ một instance làm việc mỗi lượt,
 *   không gán trùng/đốt quota (A10). app.scheduler.enabled=false vẫn tắt hẳn cron trên một instance.
 * - Mỗi lượt chỉ xử lý tối đa app.scheduler.max-shifts-per-run ca; AI service không trả điểm nào (tắt/không kết nối)
 *   thì dừng cả lượt thay vì lần lượt chờ timeout từng ca (A10, B1).
 * - Gemini hết quota/lỗi: Python trả điểm theo CÔNG THỨC có trọng số (đánh dấu fallback). Mặc định cron VẪN tự gán theo
 *   điểm đó và ghi rõ trong thông báo cho admin; đặt app.scheduler.allow-formula-fallback=false để quay lại kiểu "không gán".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoAssignJob {

    static final String LOCK_NAME = "auto-assign-open-shifts";

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final MatchingService matchingService;
    private final AIService aiService;
    private final ShiftRules rules;
    private final NotificationService notifications;
    private final SchedulerLockRepository lockRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Value("${app.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    /** Quét ca OPEN bắt đầu trong N giờ tới */
    @Value("${app.scheduler.auto-assign-window-hours:2}")
    private long windowHours;

    @Value("${app.scheduler.max-shifts-per-run:20}")
    private int maxShiftsPerRun;

    /** true: Gemini hết quota/lỗi vẫn tự gán theo điểm công thức dự phòng; false: không gán khi chỉ có điểm dự phòng */
    @Value("${app.scheduler.allow-formula-fallback:true}")
    private boolean allowFormulaFallback;

    /** Khóa hết hạn sau N phút (đủ dài cho một lượt chạy, đủ ngắn để instance khác tiếp quản khi instance giữ khóa sập) */
    @Value("${app.scheduler.lock-minutes:10}")
    private long lockMinutes;

    @Value("${app.notifications.retention-days:30}")
    private int notificationRetentionDays;

    private final String instanceId = UUID.randomUUID().toString();

    private enum Outcome { ASSIGNED, SKIPPED, AI_DOWN }

    @Scheduled(fixedRateString = "${app.scheduler.fixed-rate-ms:300000}")
    public void run() {
        if (!schedulerEnabled) {
            return;
        }
        if (!tryLock()) {
            log.info("[MODULE 3] Instance khác đang chạy cron, bỏ qua lượt này");
            return;
        }
        try {
            log.info("[MODULE 3] ⏰ CRON JOB bắt đầu");
            markCompletedShifts();      // rẻ và chắc chắn: làm trước phần gọi AI
            markExpiredShifts();
            autoAssignOpenShifts();
            notifications.prune(notificationRetentionDays);
            log.info("[MODULE 3] ⏰ CRON JOB hoàn tất");
        } catch (Exception e) {
            log.error("[MODULE 3] ❌ Cron lỗi: {}", e.getMessage(), e);
        } finally {
            unlock();
        }
    }

    // 1) Hoàn thành ca → cập nhật độ tin cậy (A5)

    void markCompletedShifts() {
        List<Shift> ended = tx().execute(s -> shiftRepository.findEndedShifts(
                List.of(ShiftStatus.ASSIGNED, ShiftStatus.TAKEN, ShiftStatus.FORCE_ASSIGNED),
                LocalDateTime.now(clock), PageRequest.of(0, 500)));
        if (ended == null || ended.isEmpty()) {
            return;
        }
        int done = 0;
        for (Shift ref : ended) {
            try {
                Boolean ok = tx().execute(s -> {
                    Shift shift = shiftRepository.findById(ref.getId()).orElse(null);
                    if (shift == null || shift.getAssignedTo() == null
                            || shift.getStatus() == ShiftStatus.COMPLETED
                            || shift.getStatus() == ShiftStatus.OPEN || shift.getStatus() == ShiftStatus.UNFILLED) {
                        return false;
                    }
                    shift.setStatus(ShiftStatus.COMPLETED);
                    shiftRepository.save(shift);
                    employeeRepository.incrementCompleted(shift.getAssignedTo().getId());
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("[MODULE 3] Không đánh dấu hoàn thành được ca #{}: {}", ref.getId(), e.getMessage());
            }
        }
        log.info("[MODULE 3] Đã đánh dấu {} ca COMPLETED", done);
    }

    // 2) Ca quá giờ không ai nhận → UNFILLED

    void markExpiredShifts() {
        List<Shift> expired = tx().execute(s ->
                shiftRepository.findExpiredOpenShifts(ShiftStatus.OPEN, LocalDateTime.now(clock)));
        if (expired == null || expired.isEmpty()) {
            return;
        }
        int done = 0;
        for (Shift ref : expired) {
            try {
                tx().executeWithoutResult(s -> {
                    Shift shift = shiftRepository.findById(ref.getId()).orElse(null);
                    if (shift == null || shift.getStatus() != ShiftStatus.OPEN) {
                        return;
                    }
                    shift.setStatus(ShiftStatus.UNFILLED);
                    shiftRepository.save(shift);
                    notifications.sendToAdmins("error",
                            "Ca #" + shift.getId() + " \"" + shift.getTitle() + "\" đã quá giờ mà chưa có ai nhận (BỎ TRỐNG). "
                                    + "Sửa giờ ca sang tương lai để mở lại.", null);
                });
                done++;
            } catch (Exception e) {
                log.warn("[MODULE 3] Không đánh dấu UNFILLED được ca #{}: {}", ref.getId(), e.getMessage());
            }
        }
        log.info("[MODULE 3] Đã đánh dấu {} ca UNFILLED", done);
    }

    // 3) Tự gán ca sắp đến giờ

    void autoAssignOpenShifts() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> shiftIds = tx().execute(s -> shiftRepository.findUpcomingOpenShiftIds(
                ShiftStatus.OPEN, now, now.plusHours(windowHours), PageRequest.of(0, Math.max(1, maxShiftsPerRun))));
        if (shiftIds == null || shiftIds.isEmpty()) {
            log.info("[MODULE 3] Không có ca OPEN nào sắp đến giờ");
            return;
        }
        log.info("[MODULE 3] Tìm thấy {} ca OPEN sắp đến giờ", shiftIds.size());

        for (Long shiftId : shiftIds) {
            try {
                if (autoAssignSingleShift(shiftId) == Outcome.AI_DOWN) {
                    log.warn("[MODULE 3] AI không dùng được → dừng lượt này, các ca còn lại để lượt sau");
                    return;
                }
            } catch (Exception e) {
                log.error("[MODULE 3] ❌ Lỗi auto-assign ca #{}: {}", shiftId, e.getMessage());
            }
        }
    }

    /** Tự gán 1 ca OPEN cho người phù hợp nhất (lọc + AI + loại người bận giống luồng gợi ý của admin). */
    private Outcome autoAssignSingleShift(Long shiftId) {
        MatchingService.Prepared prepared = readOnlyTx().execute(s -> matchingService.prepareMatchRequest(shiftId));
        if (prepared == null || prepared.request() == null) {
            log.warn("[MODULE 3] ⚠️ Ca #{}: {}", shiftId, prepared != null ? prepared.emptyReason() : "không có dữ liệu");
            return Outcome.SKIPPED;
        }

        AIMatchResponse response = aiService.score(prepared.request());   // HTTP, ngoài transaction

        // B1/LOG-08: không có điểm nào (AI service tắt/không kết nối) → không gán.
        // Điểm dự phòng do CÔNG THỨC có trọng số tính (không phải ngẫu nhiên) → gán nếu được cho phép, nhưng ghi rõ cho admin.
        AiScoreGate.Decision gate = AiScoreGate.decide(response.isAvailable(), response.isFallback(), allowFormulaFallback);
        if (gate == AiScoreGate.Decision.SKIP) {
            log.warn("[MODULE 3] Ca #{}: AI không khả dụng/không được phép dùng điểm dự phòng ({}), bỏ qua tự gán",
                    shiftId, response.getError_code() != null ? response.getError_code() : response.getFallback_reason());
            return Outcome.AI_DOWN;
        }
        boolean byFormula = gate == AiScoreGate.Decision.PROCEED_BY_FORMULA;
        if (byFormula) {
            log.info("[MODULE 3] Ca #{}: Gemini không dùng được ({}), tự gán theo điểm công thức dự phòng",
                    shiftId, response.getFallback_reason());
        }
        if (response.getRanked_candidates() == null || response.getRanked_candidates().isEmpty()) {
            log.warn("[MODULE 3] AI không trả về kết quả cho ca #{}", shiftId);
            return Outcome.SKIPPED;
        }

        // Thử lần lượt từ người điểm cao nhất; người nào không qua kiểm tra lại thì xét người kế tiếp
        for (AIEmployeeScore candidate : response.getRanked_candidates()) {
            try {
                if (forceAssign(shiftId, candidate.getEmployee_id(), byFormula)) {
                    log.info("[MODULE 3] ✅ FORCE_ASSIGNED ca #{} → nhân viên #{} (Score: {}%)",
                            shiftId, candidate.getEmployee_id(), candidate.getMatch_score());
                    return Outcome.ASSIGNED;
                }
                return Outcome.SKIPPED; // ca không còn OPEN (đã có người nhận) → dừng
            } catch (BusinessException e) {
                log.info("[MODULE 3] Nhân viên #{} không đủ điều kiện cho ca #{}: {}",
                        candidate.getEmployee_id(), shiftId, e.getMessage());
            }
        }
        log.warn("[MODULE 3] Không gán được ca #{} cho ứng viên nào", shiftId);
        return Outcome.SKIPPED;
    }

    /**
     * Gán FORCE_ASSIGNED trong một transaction ngắn, kiểm tra lại mọi điều kiện (kể cả ca chưa bắt đầu) ở thời điểm gán,
     * rồi thông báo cho người được gán (A7).
     * @return false nếu ca không còn OPEN
     */
    private boolean forceAssign(Long shiftId, Long employeeId, boolean byFormula) {
        return Boolean.TRUE.equals(tx().execute(status -> {
            Employee employee = employeeRepository.findByIdForUpdate(employeeId).orElse(null);
            if (employee == null) {
                throw new BusinessException("Không tìm thấy nhân viên #" + employeeId);
            }
            Shift shift = shiftRepository.findById(shiftId).orElse(null);
            if (shift == null || shift.getStatus() != ShiftStatus.OPEN) {
                return false;
            }
            rules.validateAssignment(employee, shift.getBranch(), shift.getRequiredSkill(), shift.getRequiredLevel(),
                    shift.getStartTime(), shift.getEndTime(), shift.getId());
            shift.setAssignedTo(employee);
            shift.setStatus(ShiftStatus.FORCE_ASSIGNED);
            shiftRepository.save(shift);

            String label = "#" + shift.getId() + " \"" + shift.getTitle() + "\" (" + shift.getStartTime().toString().replace('T', ' ') + ")";
            notifications.send(employeeId, "warning",
                    "Hệ thống đã tự động gán ca " + label + " cho bạn vì ca sắp bắt đầu. Ca này không thể nhả; liên hệ quản lý nếu có việc đột xuất.");
            notifications.sendToAdmins("info", employee.getFullName() + " được hệ thống tự động gán ca " + label + "."
                    + (byFormula ? " (Gemini không dùng được nên chọn theo điểm công thức dự phòng.)" : ""), employeeId);
            return true;
        }));
    }

    // Khóa phân tán (A10)

    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(tx().execute(s -> {
                LocalDateTime now = LocalDateTime.now(clock);
                LocalDateTime until = now.plusMinutes(lockMinutes);
                if (lockRepository.tryAcquire(LOCK_NAME, now, until, instanceId) > 0) {
                    return true;
                }
                return lockRepository.tryCreate(LOCK_NAME, until, instanceId) > 0;
            }));
        } catch (Exception e) {
            log.error("[MODULE 3] Không chiếm được khóa cron (kiểm tra bảng scheduler_locks): {}", e.getMessage());
            return false;
        }
    }

    private void unlock() {
        try {
            tx().executeWithoutResult(s -> lockRepository.release(LOCK_NAME, LocalDateTime.now(clock), instanceId));
        } catch (Exception e) {
            log.warn("[MODULE 3] Không nhả được khóa cron (sẽ tự hết hạn): {}", e.getMessage());
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate readOnlyTx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setReadOnly(true);
        return t;
    }
}
