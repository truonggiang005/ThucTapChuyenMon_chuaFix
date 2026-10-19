package com.smartschedule.controller;

import com.smartschedule.dto.AIMatchResponse;
import com.smartschedule.dto.AssignRequest;
import com.smartschedule.dto.CreateShiftRequest;
import com.smartschedule.dto.MyMatchScoresResponse;
import com.smartschedule.dto.ShiftDTO;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.MatchingService;
import com.smartschedule.service.ShiftService;
import com.smartschedule.util.LogSanitizer;
import com.smartschedule.util.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ★ REST Controller cho Ca làm việc (Shift).
 *
 * Mọi endpoint yêu cầu đăng nhập. employeeId của người thao tác luôn lấy từ phiên đăng nhập (SEC-03).
 *
 *   GET  /api/shifts?from=&to=&page=&size=    → Ca trong cửa sổ thời gian, có phân trang (ADMIN)
 *   GET  /api/shifts/open[?branchId=]         → Ca đang mở (chưa bắt đầu)
 *   GET  /api/shifts/employee/{id}            → Lịch làm của NV (ADMIN hoặc chính chủ)
 *   POST /api/shifts, PUT /api/shifts/{id}    → Tạo / sửa ca (ADMIN)
 *   POST /api/shifts/{id}/release             → Nhả ca CỦA MÌNH
 *   POST /api/shifts/{id}/take                → MÌNH nhận ca
 *   POST /api/shifts/{id}/assign              → Admin gán ca cho một NV (body: {employeeId})
 *   GET  /api/shifts/{id}/candidates          → AI Matchmaking (ADMIN)
 *   GET  /api/shifts/my-scores?shiftIds=1,2   → Điểm phù hợp của CHÍNH mình cho nhiều ca (một lời gọi, không tốn quota Gemini)
 *   POST /api/shifts/{id}/late                → Admin ghi nhận nhân viên đến muộn ở ca
 *   (Danh mục chi nhánh/kỹ năng: xem CatalogController)
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
@Slf4j
public class ShiftController {

    private final ShiftService shiftService;
    private final MatchingService matchingService;
    private final RateLimiter rateLimiter;

    /** Giới hạn số lần admin gọi AI Gemini mỗi người mỗi phút (SEC-09) */
    @Value("${ai.rate-limit.per-minute:60}")
    private int aiLimitPerMinute;

    /** Giới hạn riêng cho Chợ ca (không dùng chung hạn mức với admin - B4) */
    @Value("${ai.rate-limit.market-per-minute:30}")
    private int marketLimitPerMinute;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ShiftDTO>> getShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(shiftService.getAdminShifts(from, to, page, size));
    }

    @GetMapping("/open")
    public ResponseEntity<List<ShiftDTO>> getOpenShifts(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(shiftService.getOpenShifts(branchId));
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

        return shiftOk("Tạo ca thành công!", shiftService.createShift(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateShift(
            @PathVariable Long id,
            @Valid @RequestBody CreateShiftRequest request) {
        log.info("[CONTROLLER] PUT /api/shifts/{} | title={}", id, LogSanitizer.clean(request.getTitle()));

        return shiftOk("Cập nhật ca thành công!", shiftService.updateShift(id, request));
    }

    /** ★ MODULE 1: Nhả ca của chính mình (ASSIGNED/TAKEN → OPEN). */
    @PostMapping("/{shiftId}/release")
    public ResponseEntity<Map<String, Object>> releaseShift(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] POST /api/shifts/{}/release | employeeId={}", shiftId, me.getEmployeeId());

        return shiftOk("Nhả ca thành công! Ca đã chuyển sang trạng thái OPEN.", shiftService.releaseShift(shiftId, me.getEmployeeId()));
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

        return shiftOk("Nhận ca thành công!", shiftService.takeShift(shiftId, me.getEmployeeId()));
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

        return shiftOk("Gán ca thành công!", shiftService.assignShift(shiftId, request.getEmployeeId(), me.getEmployeeId()));
    }

    /** Admin ghi nhận nhân viên ĐẾN MUỘN ở ca đã bắt đầu (cộng lateArrivalCount - A5). */
    @PostMapping("/{shiftId}/late")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> markLate(@PathVariable Long shiftId) {
        log.info("[CONTROLLER] POST /api/shifts/{}/late", shiftId);
        return shiftOk("Đã ghi nhận nhân viên đến muộn.", shiftService.markLate(shiftId));
    }

    /** ★ MODULE 2: AI Matchmaking — danh sách ứng viên đã chấm điểm (ADMIN). */
    @GetMapping("/{shiftId}/candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AIMatchResponse> getCandidates(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] GET /api/shifts/{}/candidates", shiftId);
        rateLimiter.check("ai-candidates:" + me.getEmployeeId(), aiLimitPerMinute, Duration.ofMinutes(1));

        return ResponseEntity.ok(matchingService.getMatchingCandidates(shiftId));
    }

    /**
     * Điểm phù hợp của CHÍNH người đăng nhập cho nhiều ca một lúc (Chợ ca). Nhân viên không xem được điểm của người khác.
     * Một lời gọi cho cả trang, chấm bằng quy tắc nên không tốn quota Gemini (B4).
     */
    @GetMapping("/my-scores")
    public ResponseEntity<MyMatchScoresResponse> getMyScores(
            @RequestParam(required = false) List<Long> shiftIds,
            @AuthenticationPrincipal AppUserDetails me) {
        rateLimiter.check("ai-market:" + me.getEmployeeId(), marketLimitPerMinute, Duration.ofMinutes(1));
        return ResponseEntity.ok(matchingService.getMyMatchScores(me.getEmployeeId(), shiftIds));
    }

    private static ResponseEntity<Map<String, Object>> shiftOk(String message, ShiftDTO shift) {
        return ResponseEntity.ok(Map.of("message", message, "shift", shift));
    }
}
