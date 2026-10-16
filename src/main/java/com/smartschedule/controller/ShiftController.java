package com.smartschedule.controller;

import com.smartschedule.dto.AIMatchResponse;
import com.smartschedule.dto.AssignRequest;
import com.smartschedule.dto.CreateShiftRequest;
import com.smartschedule.dto.ShiftDTO;
import com.smartschedule.entity.Branch;
import com.smartschedule.entity.Skill;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.ShiftService;
import com.smartschedule.util.LogSanitizer;
import com.smartschedule.util.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ REST Controller cho Ca làm việc (Shift).
 *
 * Mọi endpoint yêu cầu đăng nhập. employeeId của người thao tác luôn lấy từ phiên đăng nhập (SEC-03).
 *
 *   GET  /api/shifts                          → Tất cả ca (ADMIN)
 *   GET  /api/shifts/open[?branchId=]         → Ca đang mở
 *   GET  /api/shifts/employee/{id}            → Lịch làm của NV (ADMIN hoặc chính chủ)
 *   POST /api/shifts, PUT /api/shifts/{id}    → Tạo / sửa ca (ADMIN)
 *   POST /api/shifts/{id}/release             → Nhả ca CỦA MÌNH
 *   POST /api/shifts/{id}/take                → MÌNH nhận ca
 *   POST /api/shifts/{id}/assign              → Admin gán ca cho một NV (body: {employeeId})
 *   GET  /api/shifts/{id}/candidates          → AI Matchmaking (ADMIN)
 *   GET  /api/shifts/{id}/my-score            → Điểm AI của chính mình cho ca
 *   GET  /api/shifts/branches, /skills        → Danh mục cho form
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
@Slf4j
public class ShiftController {

    private final ShiftService shiftService;
    private final RateLimiter rateLimiter;

    /** Giới hạn số lần gọi AI mỗi người mỗi phút (SEC-09) */
    @Value("${ai.rate-limit.per-minute:60}")
    private int aiLimitPerMinute;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ShiftDTO>> getAllShifts() {
        return ResponseEntity.ok(shiftService.getAllShifts());
    }

    @GetMapping("/open")
    public ResponseEntity<List<ShiftDTO>> getOpenShifts(
            @RequestParam(required = false) Long branchId) {
        if (branchId != null) {
            return ResponseEntity.ok(shiftService.getOpenShiftsByBranch(branchId));
        }
        return ResponseEntity.ok(shiftService.getOpenShifts());
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public ResponseEntity<List<ShiftDTO>> getEmployeeSchedule(@PathVariable Long employeeId) {
        return ResponseEntity.ok(shiftService.getEmployeeSchedule(employeeId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createShift(@Valid @RequestBody CreateShiftRequest request) {
        log.info("[CONTROLLER] POST /api/shifts | title={}", LogSanitizer.clean(request.getTitle()));

        ShiftDTO result = shiftService.createShift(request);
        return ResponseEntity.ok(Map.of(
                "message", "Tạo ca thành công!",
                "shift", result
        ));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateShift(
            @PathVariable Long id,
            @Valid @RequestBody CreateShiftRequest request) {
        log.info("[CONTROLLER] PUT /api/shifts/{} | title={}", id, LogSanitizer.clean(request.getTitle()));

        ShiftDTO result = shiftService.updateShift(id, request);
        return ResponseEntity.ok(Map.of(
                "message", "Cập nhật ca thành công!",
                "shift", result
        ));
    }

    /** ★ MODULE 1: Nhả ca của chính mình (ASSIGNED/TAKEN → OPEN). */
    @PostMapping("/{shiftId}/release")
    public ResponseEntity<Map<String, Object>> releaseShift(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] POST /api/shifts/{}/release | employeeId={}", shiftId, me.getEmployeeId());

        ShiftDTO result = shiftService.releaseShift(shiftId, me.getEmployeeId());
        return ResponseEntity.ok(Map.of(
                "message", "Nhả ca thành công! Ca đã chuyển sang trạng thái OPEN.",
                "shift", result
        ));
    }

    /**
     * ★ MODULE 1: Nhận ca (OPEN → TAKEN) cho chính người đăng nhập — có Optimistic Locking.
     * Hai người nhận cùng lúc: người đầu 200, người sau 409.
     */
    @PostMapping("/{shiftId}/take")
    public ResponseEntity<Map<String, Object>> takeShift(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] POST /api/shifts/{}/take | employeeId={}", shiftId, me.getEmployeeId());

        ShiftDTO result = shiftService.takeShift(shiftId, me.getEmployeeId());
        return ResponseEntity.ok(Map.of(
                "message", "Nhận ca thành công!",
                "shift", result
        ));
    }

    /** Admin gán ca cho một nhân viên cụ thể (dùng từ bảng gợi ý AI). */
    @PostMapping("/{shiftId}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> assignShift(
            @PathVariable Long shiftId,
            @Valid @RequestBody AssignRequest request,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] POST /api/shifts/{}/assign | admin={} | employeeId={}",
                shiftId, me.getEmployeeId(), request.getEmployeeId());

        ShiftDTO result = shiftService.takeShift(shiftId, request.getEmployeeId());
        return ResponseEntity.ok(Map.of(
                "message", "Gán ca thành công!",
                "shift", result
        ));
    }

    /** ★ MODULE 2: AI Matchmaking — danh sách ứng viên đã chấm điểm (ADMIN). */
    @GetMapping("/{shiftId}/candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AIMatchResponse> getCandidates(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] GET /api/shifts/{}/candidates", shiftId);
        rateLimiter.check("ai:" + me.getEmployeeId(), aiLimitPerMinute, Duration.ofMinutes(1));

        return ResponseEntity.ok(shiftService.getMatchingCandidates(shiftId));
    }

    /** Điểm AI của CHÍNH người đăng nhập cho một ca (nhân viên không xem được điểm của người khác). */
    @GetMapping("/{shiftId}/my-score")
    public ResponseEntity<Map<String, Object>> getMyScore(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        rateLimiter.check("ai:" + me.getEmployeeId(), aiLimitPerMinute, Duration.ofMinutes(1));

        Double score = shiftService.getMyMatchScore(shiftId, me.getEmployeeId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shift_id", shiftId);
        body.put("match_score", score);   // null nếu không nằm trong danh sách đủ điều kiện
        return ResponseEntity.ok(body);
    }

    @GetMapping("/branches")
    public ResponseEntity<List<Map<String, Object>>> getBranches() {
        List<Branch> branches = shiftService.getAllBranches();
        List<Map<String, Object>> result = branches.stream()
                .map(b -> Map.<String, Object>of("id", b.getId(), "name", b.getName()))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> getSkills() {
        List<Skill> skills = shiftService.getAllSkills();
        List<Map<String, Object>> result = skills.stream()
                .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName()))
                .toList();
        return ResponseEntity.ok(result);
    }
}
