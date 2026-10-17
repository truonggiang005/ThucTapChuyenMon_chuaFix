package com.smartschedule.service;

import com.smartschedule.dto.BusyScheduleDTO;
import com.smartschedule.dto.BusyScheduleRequest;
import com.smartschedule.entity.BusySchedule;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.entity.Employee;
import com.smartschedule.repository.BusyScheduleRepository;
import com.smartschedule.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service quản lý lịch bận (BusySchedule) của nhân viên.
 *
 * Chức năng:
 *   - Đăng ký khoảng thời gian bận
 *   - Xem danh sách lịch bận
 *   - Xóa lịch bận
 *   - Kiểm tra trùng lịch bận khi nhận ca
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusyScheduleService {

    private final BusyScheduleRepository busyScheduleRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * Đăng ký lịch bận mới cho nhân viên.
     */
    @Transactional
    public BusyScheduleDTO createBusySchedule(Long employeeId, BusyScheduleRequest request) {
        // employeeId lấy từ người đăng nhập (SEC-03), không đọc từ body
        log.info("[BUSY] Nhân viên #{} đăng ký lịch bận: {} → {}",
                employeeId, request.getStartTime(), request.getEndTime());

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy nhân viên #" + employeeId));

        LocalDateTime startTime = request.getStartTime();
        LocalDateTime endTime = request.getEndTime();

        // Validate thời gian
        if (endTime.isBefore(startTime) || endTime.isEqual(startTime)) {
            throw new InvalidRequestException("Thời gian kết thúc phải sau thời gian bắt đầu");
        }

        if (startTime.isBefore(LocalDateTime.now())) {
            throw new InvalidRequestException("Không thể đăng ký lịch bận trong quá khứ");
        }

        // Kiểm tra trùng lịch bận đã đăng ký trước đó
        Long busyCount = busyScheduleRepository.countBusyConflict(
                employeeId, startTime, endTime);
        boolean hasBusyConflict = busyCount != null && busyCount > 0;
        if (hasBusyConflict) {
            throw new IllegalStateException("Bạn đã có lịch bận trùng trong khoảng thời gian này");
        }

        String reason = request.getReason() != null ? request.getReason().trim() : null;
        BusySchedule busySchedule = BusySchedule.builder()
                .employee(employee)
                .startTime(startTime)
                .endTime(endTime)
                .reason(reason)
                .build();

        BusySchedule saved = busyScheduleRepository.save(busySchedule);
        log.info("[BUSY] ✅ Đã đăng ký lịch bận #{} cho nhân viên {}", saved.getId(), employee.getFullName());

        return convertToDTO(saved);
    }

    /**
     * Lấy danh sách lịch bận của nhân viên (chỉ lịch chưa qua).
     */
    @Transactional(readOnly = true)
    public List<BusyScheduleDTO> getUpcomingBusySchedules(Long employeeId) {
        return busyScheduleRepository.findUpcomingByEmployeeId(employeeId, LocalDateTime.now())
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Lấy tất cả lịch bận của nhân viên.
     */
    @Transactional(readOnly = true)
    public List<BusyScheduleDTO> getAllBusySchedules(Long employeeId) {
        return busyScheduleRepository.findByEmployeeId(employeeId)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    /**
     * Xóa lịch bận.
     */
    @Transactional
    public void deleteBusySchedule(Long busyId, Long employeeId) {
        BusySchedule busy = busyScheduleRepository.findById(busyId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch bận #" + busyId));

        if (!busy.getEmployee().getId().equals(employeeId)) {
            throw new IllegalStateException("Bạn không có quyền xóa lịch bận này");
        }

        busyScheduleRepository.delete(busy);
        log.info("[BUSY] 🗑️ Đã xóa lịch bận #{}", busyId);
    }

    /**
     * Kiểm tra nhân viên có bận trong khoảng thời gian hay không.
     */
    @Transactional(readOnly = true)
    public boolean isBusy(Long employeeId, LocalDateTime start, LocalDateTime end) {
        Long count = busyScheduleRepository.countBusyConflict(employeeId, start, end);
        return count != null && count > 0;
    }

    /**
     * Chuyển BusySchedule entity → BusyScheduleDTO.
     */
    private BusyScheduleDTO convertToDTO(BusySchedule bs) {
        return BusyScheduleDTO.builder()
                .id(bs.getId())
                .employeeId(bs.getEmployee().getId())
                .employeeName(bs.getEmployee().getFullName())
                .startTime(bs.getStartTime().toString())
                .endTime(bs.getEndTime().toString())
                .reason(bs.getReason())
                .build();
    }
}
