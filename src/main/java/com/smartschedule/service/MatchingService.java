package com.smartschedule.service;

import com.smartschedule.dto.*;
import com.smartschedule.entity.Branch;
import com.smartschedule.entity.Employee;
import com.smartschedule.entity.Shift;
import com.smartschedule.entity.ShiftStatus;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.NotFoundException;
import com.smartschedule.repository.BusyScheduleRepository;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MODULE 2: AI Matchmaking — lọc ứng viên đủ điều kiện rồi nhờ AI chấm điểm.
 *
 * Không giữ transaction trong lúc chờ HTTP: phần đọc DB nằm trong một transaction ngắn (read-only),
 * phần gọi Python nằm ngoài transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    /** Số ca tối đa chấm điểm cho người dùng trong một lần mở Chợ ca. */
    public static final int MAX_MARKET_SHIFTS = 50;

    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final BusyScheduleRepository busyScheduleRepository;
    private final AIService aiService;
    private final ShiftRules rules;
    private final PlatformTransactionManager transactionManager;

    /** Kết quả bước chuẩn bị: có request để gửi AI, hoặc lý do không có ứng viên. */
    public record Prepared(AIMatchRequest request, String emptyReason) {}

    private TransactionTemplate readOnlyTx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setReadOnly(true);
        return t;
    }

    /**
     * Dựng request gửi AI cho một ca: lọc ứng viên đủ điều kiện (kỹ năng, không trùng ca, không bận, trong giới hạn
     * giờ/tuần theo TỪNG tuần ca chạm tới, đúng chi nhánh nếu bật ràng buộc, chi nhánh còn sức chứa).
     * Chạy trong transaction, KHÔNG gọi HTTP.
     */
    public Prepared prepareMatchRequest(Long shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ca #" + shiftId));

        LocalDateTime start = shift.getStartTime();
        LocalDateTime end = shift.getEndTime();
        if (!start.isAfter(rules.now())) {
            return new Prepared(null, "Ca đã bắt đầu hoặc đã qua nên không thể gợi ý/gán thêm người.");
        }
        Branch branch = shift.getBranch();
        if (!rules.branchHasCapacity(branch, start, end, shift.getId())) {
            return new Prepared(null, "Chi nhánh đã đạt sức chứa tối đa trong khung giờ này.");
        }

        // excludeEmployeeId = 0 nếu không có người cần loại trừ
        Long excludeId = (shift.getAssignedTo() != null) ? shift.getAssignedTo().getId() : 0L;

        // LOG-03: ca không yêu cầu kỹ năng dùng truy vấn riêng (không JOIN employee_skills)
        List<Employee> eligible;
        if (shift.getRequiredSkill() == null) {
            eligible = employeeRepository.findEligibleEmployeesNoSkill(start, end, excludeId);
        } else {
            eligible = employeeRepository.findEligibleEmployees(
                    shift.getRequiredSkill().getId(), shift.getRequiredLevel(), start, end, excludeId);
        }
        log.info("[MODULE 2] Ca #{}: {} nhân viên đủ kỹ năng và không trùng ca (trước khi lọc lịch bận/giờ tuần)",
                shiftId, eligible.size());

        if (!eligible.isEmpty()) {
            List<Long> ids = eligible.stream().map(Employee::getId).toList();
            Set<Long> busy = new HashSet<>(busyScheduleRepository.findBusyAmong(ids, start, end));
            eligible = eligible.stream().filter(e -> !busy.contains(e.getId())).toList();
        }
        eligible = rules.withinWeeklyLimit(eligible, start, end, shift.getId());
        if (rules.restrictsToPrimaryBranch() && branch != null) {
            eligible = eligible.stream()
                    .filter(e -> e.getPrimaryBranch() != null && e.getPrimaryBranch().getId().equals(branch.getId()))
                    .toList();
        }

        if (eligible.isEmpty()) {
            return new Prepared(null, "Không có nhân viên nào đủ điều kiện (kỹ năng, lịch, giờ làm/tuần).");
        }
        return new Prepared(aiService.buildRequest(shift, eligible), null);
    }

    /** ★ Lọc ứng viên + gọi AI chấm điểm (dành cho ADMIN). */
    public AIMatchResponse getMatchingCandidates(Long shiftId) {
        log.info("[MODULE 2] Tìm ứng viên phù hợp cho ca #{}", shiftId);

        Prepared prepared = readOnlyTx().execute(status -> prepareMatchRequest(shiftId));
        if (prepared == null || prepared.request() == null) {
            return AIMatchResponse.builder()
                    .shift_id(shiftId)
                    .ranked_candidates(List.of())
                    .total_candidates(0)
                    .source("none")
                    .message(prepared != null ? prepared.emptyReason() : null)
                    .build();
        }
        return aiService.score(prepared.request());   // HTTP, ngoài transaction
    }

    /**
     * Điểm phù hợp của CHÍNH một nhân viên cho nhiều ca ở Chợ ca (B4).
     *
     * Khác trước đây (mỗi ca một lần chấm điểm CẢ danh sách ứng viên bằng Gemini):
     *   - chỉ chấm cho đúng người đang xem, bằng bộ quy tắc, TẤT CẢ ca trong MỘT lời gọi → không đốt quota Gemini;
     *   - ca mà nhân viên không đủ điều kiện nhận thì trả eligible=false + lý do, không có điểm.
     */
    public MyMatchScoresResponse getMyMatchScores(Long employeeId, List<Long> shiftIds) {
        List<Long> ids = shiftIds == null ? List.of()
                : shiftIds.stream().filter(Objects::nonNull).distinct().limit(MAX_MARKET_SHIFTS).toList();
        if (ids.isEmpty()) {
            return MyMatchScoresResponse.builder().available(true).scores(List.of()).build();
        }

        List<MyMatchScoreDTO> rows = new ArrayList<>();
        List<AIQuickItem> items = new ArrayList<>();

        readOnlyTx().executeWithoutResult(status -> {
            Employee me = employeeRepository.findByIdWithBranch(employeeId)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + employeeId));
            Map<Long, Shift> byId = shiftRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Shift::getId, s -> s));
            for (Long id : ids) {
                Shift s = byId.get(id);
                if (s == null) {
                    continue;
                }
                if (s.getStatus() != ShiftStatus.OPEN) {
                    rows.add(notEligible(id, "Ca không còn mở để nhận"));
                    continue;
                }
                try {
                    rules.validateAssignment(me, s.getBranch(), s.getRequiredSkill(), s.getRequiredLevel(),
                            s.getStartTime(), s.getEndTime(), s.getId());
                } catch (BusinessException e) {
                    rows.add(notEligible(id, e.getMessage()));
                    continue;
                }
                rows.add(MyMatchScoreDTO.builder().shiftId(id).eligible(true).build());
                items.add(AIQuickItem.builder()
                        .shift(aiService.buildShiftData(s))
                        .candidate(aiService.buildCandidateData(s, List.of(me)).get(0))
                        .build());
            }
        });

        if (items.isEmpty()) {
            return MyMatchScoresResponse.builder().available(true).scores(rows).build();
        }

        AIService.QuickOutcome outcome = aiService.quickScores(items);   // HTTP, ngoài transaction
        if (!outcome.available()) {
            return MyMatchScoresResponse.builder().available(false).message(outcome.message()).scores(rows).build();
        }
        for (MyMatchScoreDTO row : rows) {
            AIQuickResponse.Result r = outcome.byShift().get(row.getShiftId());
            if (row.isEligible() && r != null) {
                row.setMatchScore(r.getMatch_score());
                row.setConfidence(r.getConfidence());
                row.setSource(r.getSource() != null ? r.getSource() : "heuristic");
            }
        }
        return MyMatchScoresResponse.builder().available(true).scores(rows).build();
    }

    private static MyMatchScoreDTO notEligible(Long shiftId, String reason) {
        return MyMatchScoreDTO.builder().shiftId(shiftId).eligible(false).reason(reason).build();
    }
}
