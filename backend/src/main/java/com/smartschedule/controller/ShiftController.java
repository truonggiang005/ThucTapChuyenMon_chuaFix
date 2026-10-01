package com.smartschedule.controller;

import com.smartschedule.dto.AIMatchResponse;
import com.smartschedule.dto.CreateShiftRequest;
import com.smartschedule.dto.ShiftDTO;
import com.smartschedule.entity.Branch;
import com.smartschedule.entity.Skill;
import com.smartschedule.service.ShiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ★ REST Controller cho Ca làm việc (Shift).
 *
 * Endpoints:
 *   GET  /api/shifts                          → Tất cả ca (admin)
 *   GET  /api/shifts/open                     → Ca đang mở
 *   GET  /api/shifts/open?branchId=1          → Ca mở theo chi nhánh
 *   GET  /api/shifts/employee/{id}            → Lịch làm của NV
 *   GET  /api/shifts/{id}/candidates          → AI Matchmaking (Module 2)
 *   POST /api/shifts                          → Tạo ca mới
 *   POST /api/shifts/{id}/release?employeeId= → Nhả ca (Module 1)
 *   POST /api/shifts/{id}/take?employeeId=    → Nhận ca (Module 1 + Tương tranh)
 *   GET  /api/shifts/branches                 → Danh sách chi nhánh
 *   GET  /api/shifts/skills                   → Danh sách kỹ năng
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
@Slf4j
public class ShiftController {

    private final ShiftService shiftService;

    /**
     * Lấy tất cả ca (admin dashboard).
     */
    @GetMapping
    public ResponseEntity<List<ShiftDTO>> getAllShifts() {
        return ResponseEntity.ok(shiftService.getAllShifts());
    }

    /**
     * Lấy danh sách ca OPEN (có thể lọc theo branchId).
     */
    @GetMapping("/open")
    public ResponseEntity<List<ShiftDTO>> getOpenShifts(
            @RequestParam(required = false) Long branchId) {
        if (branchId != null) {
            return ResponseEntity.ok(shiftService.getOpenShiftsByBranch(branchId));
        }
        return ResponseEntity.ok(shiftService.getOpenShifts());
    }

    /**
     * Lấy lịch làm việc của nhân viên.
     */
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<ShiftDTO>> getEmployeeSchedule(@PathVariable Long employeeId) {
        return ResponseEntity.ok(shiftService.getEmployeeSchedule(employeeId));
    }

    /**
     * ★ TẠO CA MỚI.
     *
     * Ví dụ: POST /api/shifts
     * Body: { title, startTime, endTime, requiredLevel, branchId, requiredSkillId, assignedToId }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createShift(@RequestBody CreateShiftRequest request) {
        log.info("[CONTROLLER] POST /api/shifts | title={}", request.getTitle());

        ShiftDTO result = shiftService.createShift(request);
        return ResponseEntity.ok(Map.of(
                "message", "Tạo ca thành công!",
                "shift", result
        ));
    }

    /**
     * ★ MODULE 1: Nhả ca (ASSIGNED → OPEN).
     *
     * Ví dụ: POST /api/shifts/1/release?employeeId=1
     */
    @PostMapping("/{shiftId}/release")
    public ResponseEntity<Map<String, Object>> releaseShift(
            @PathVariable Long shiftId,
            @RequestParam Long employeeId) {
        log.info("[CONTROLLER] POST /api/shifts/{}/release?employeeId={}", shiftId, employeeId);

        ShiftDTO result = shiftService.releaseShift(shiftId, employeeId);
        return ResponseEntity.ok(Map.of(
                "message", "Nhả ca thành công! Ca đã chuyển sang trạng thái OPEN.",
                "shift", result
        ));
    }

    /**
     * ★ MODULE 1: Nhận ca (OPEN → TAKEN) — có Optimistic Locking.
     *
     * Ví dụ: POST /api/shifts/2/take?employeeId=3
     *
     * Nếu 2 người gọi cùng lúc:
     *   - Người đầu: 200 OK
     *   - Người sau: 409 Conflict (ca đã bị nhận)
     */
    @PostMapping("/{shiftId}/take")
    public ResponseEntity<Map<String, Object>> takeShift(
            @PathVariable Long shiftId,
            @RequestParam Long employeeId) {
        log.info("[CONTROLLER] POST /api/shifts/{}/take?employeeId={}", shiftId, employeeId);

        ShiftDTO result = shiftService.takeShift(shiftId, employeeId);
        return ResponseEntity.ok(Map.of(
                "message", "Nhận ca thành công!",
                "shift", result
        ));
    }

    /**
     * ★ MODULE 2: AI Matchmaking — Tìm ứng viên phù hợp cho ca OPEN.
     *
     * Ví dụ: GET /api/shifts/2/candidates
     *
     * Trả về danh sách nhân viên đã chấm điểm Match Score (0-100%),
     * sắp xếp giảm dần.
     */
    @GetMapping("/{shiftId}/candidates")
    public ResponseEntity<AIMatchResponse> getCandidates(@PathVariable Long shiftId) {
        log.info("[CONTROLLER] GET /api/shifts/{}/candidates", shiftId);

        AIMatchResponse response = shiftService.getMatchingCandidates(shiftId);
        return ResponseEntity.ok(response);
    }

    /**
     * Lấy danh sách chi nhánh (dùng cho form tạo ca).
     */
    @GetMapping("/branches")
    public ResponseEntity<List<Map<String, Object>>> getBranches() {
        List<Branch> branches = shiftService.getAllBranches();
        List<Map<String, Object>> result = branches.stream()
                .map(b -> Map.<String, Object>of("id", b.getId(), "name", b.getName()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Lấy danh sách kỹ năng (dùng cho form tạo ca).
     */
    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> getSkills() {
        List<Skill> skills = shiftService.getAllSkills();
        List<Map<String, Object>> result = skills.stream()
                .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName()))
                .toList();
        return ResponseEntity.ok(result);
    }
}

