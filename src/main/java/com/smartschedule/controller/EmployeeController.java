package com.smartschedule.controller;

import com.smartschedule.dto.CreateEmployeeRequest;
import com.smartschedule.dto.EmployeeDTO;
import com.smartschedule.dto.ResetPasswordRequest;
import com.smartschedule.dto.UpdateEmployeeRequest;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller cho Nhân viên (Employee).
 *
 *   GET  /api/employees?page=&size=          → Danh sách nhân viên (ADMIN)
 *   GET  /api/employees/{id}                 → Chi tiết kèm kỹ năng (ADMIN hoặc chính người đó)
 *   POST /api/employees                      → Tạo nhân viên (ADMIN)
 *   PUT  /api/employees/{id}                 → Sửa thông tin/kỹ năng/vô hiệu hóa (ADMIN)
 *   POST /api/employees/{id}/reset-password  → Đặt lại mật khẩu (ADMIN)
 */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<EmployeeDTO>> getAllEmployees(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(employeeService.getEmployees(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.employeeId")
    public ResponseEntity<EmployeeDTO> getEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getEmployeeById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EmployeeDTO> createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EmployeeDTO> updateEmployee(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateEmployeeRequest request,
                                                      @AuthenticationPrincipal AppUserDetails me) {
        return ResponseEntity.ok(employeeService.update(id, request, me.getEmployeeId()));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable Long id,
                                                              @Valid @RequestBody ResetPasswordRequest request) {
        employeeService.resetPassword(id, request);
        return ResponseEntity.ok(Map.of("message", "Đã đặt lại mật khẩu"));
    }
}
