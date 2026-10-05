package com.smartschedule.service;

import com.smartschedule.dto.EmployeeDTO;
import com.smartschedule.entity.Employee;
import com.smartschedule.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service quản lý nhân viên.
 * Cung cấp CRUD cơ bản và chuyển đổi Entity → DTO.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    /**
     * Lấy tất cả nhân viên.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDTO> getAllEmployees() {
        return employeeRepository.findAll()
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy nhân viên theo ID.
     */
    @Transactional(readOnly = true)
    public EmployeeDTO getEmployeeById(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên #" + id));
        return convertToDTO(emp);
    }

    /**
     * Tìm nhân viên theo email (dùng cho login giả lập).
     */
    @Transactional(readOnly = true)
    public EmployeeDTO getEmployeeByEmail(String email) {
        Employee emp = employeeRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên với email: " + email));
        return convertToDTO(emp);
    }

    /**
     * Chuyển Employee entity → EmployeeDTO.
     */
    private EmployeeDTO convertToDTO(Employee emp) {
        return EmployeeDTO.builder()
                .id(emp.getId())
                .fullName(emp.getFullName())
                .email(emp.getEmail())
                .maxHoursPerWeek(emp.getMaxHoursPerWeek())
                .totalCompleted(emp.getTotalCompleted())
                .totalCancelled(emp.getTotalCancelled())
                .lateArrivalCount(emp.getLateArrivalCount())
                .preferredTime(emp.getPreferredTime())
                .primaryBranchId(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getId() : null)
                .primaryBranchName(emp.getPrimaryBranch() != null ? emp.getPrimaryBranch().getName() : null)
                .build();
    }
}
