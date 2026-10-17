package com.smartschedule.controller;

import com.smartschedule.dto.BusyScheduleDTO;
import com.smartschedule.dto.BusyScheduleRequest;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.BusyScheduleService;
import com.smartschedule.util.LogSanitizer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller cho Lịch bận (BusySchedule).
 *
 * Endpoints (employeeId luôn lấy từ người đăng nhập - SEC-03):
 *   POST   /api/busy-schedules                → Đăng ký lịch bận cho CHÍNH MÌNH
 *   GET    /api/busy-schedules/employee/{id}  → Lịch bận (ADMIN hoặc chính chủ)
 *   DELETE /api/busy-schedules/{id}           → Xóa lịch bận CỦA MÌNH
 */
@RestController
@RequestMapping("/api/busy-schedules")
@RequiredArgsConstructor
@Slf4j
public class BusyScheduleController {

    private final BusyScheduleService busyScheduleService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createBusySchedule(
            @Valid @RequestBody BusyScheduleRequest request,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] POST /api/busy-schedules | employeeId={}", me.getEmployeeId());

        BusyScheduleDTO result = busyScheduleService.createBusySchedule(me.getEmployeeId(), request);
        return ResponseEntity.ok(Map.of(
                "message", "Đăng ký lịch bận thành công!",
                "busySchedule", result
        ));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public ResponseEntity<List<BusyScheduleDTO>> getEmployeeBusySchedules(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(busyScheduleService.getUpcomingBusySchedules(employeeId));
    }

    @DeleteMapping("/{busyId}")
    public ResponseEntity<Map<String, Object>> deleteBusySchedule(
            @PathVariable Long busyId,
            @AuthenticationPrincipal AppUserDetails me) {
        log.info("[CONTROLLER] DELETE /api/busy-schedules/{} | employeeId={}",
                LogSanitizer.clean(busyId), me.getEmployeeId());

        busyScheduleService.deleteBusySchedule(busyId, me.getEmployeeId());
        return ResponseEntity.ok(Map.of(
                "message", "Đã xóa lịch bận thành công!"
        ));
    }
}
