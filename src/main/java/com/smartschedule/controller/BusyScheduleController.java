package com.smartschedule.controller;

import com.smartschedule.dto.BusyScheduleDTO;
import com.smartschedule.dto.BusyScheduleRequest;
import com.smartschedule.service.BusyScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller cho Lịch bận (BusySchedule).
 *
 * Endpoints:
 *   POST /api/busy-schedules                    → Đăng ký lịch bận
 *   GET  /api/busy-schedules/employee/{id}      → Lấy lịch bận của nhân viên
 *   DELETE /api/busy-schedules/{id}?employeeId=  → Xóa lịch bận
 */
@RestController
@RequestMapping("/api/busy-schedules")
@RequiredArgsConstructor
@Slf4j
public class BusyScheduleController {

    private final BusyScheduleService busyScheduleService;

    /**
     * Đăng ký lịch bận mới.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createBusySchedule(@RequestBody BusyScheduleRequest request) {
        log.info("[CONTROLLER] POST /api/busy-schedules | employeeId={}", request.getEmployeeId());

        BusyScheduleDTO result = busyScheduleService.createBusySchedule(request);
        return ResponseEntity.ok(Map.of(
                "message", "Đăng ký lịch bận thành công!",
                "busySchedule", result
        ));
    }

    /**
     * Lấy danh sách lịch bận sắp tới của nhân viên.
     */
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<BusyScheduleDTO>> getEmployeeBusySchedules(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(busyScheduleService.getUpcomingBusySchedules(employeeId));
    }

    /**
     * Xóa lịch bận.
     */
    @DeleteMapping("/{busyId}")
    public ResponseEntity<Map<String, Object>> deleteBusySchedule(
            @PathVariable Long busyId,
            @RequestParam Long employeeId) {
        log.info("[CONTROLLER] DELETE /api/busy-schedules/{} | employeeId={}", busyId, employeeId);

        busyScheduleService.deleteBusySchedule(busyId, employeeId);
        return ResponseEntity.ok(Map.of(
                "message", "Đã xóa lịch bận thành công!"
        ));
    }
}
