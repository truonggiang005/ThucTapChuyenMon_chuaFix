package com.smartschedule.service;

import com.smartschedule.dto.BusyScheduleDTO;
import com.smartschedule.dto.BusyScheduleRequest;
import com.smartschedule.entity.BusySchedule;
import com.smartschedule.entity.Employee;
import com.smartschedule.exception.BusinessException;
import com.smartschedule.exception.ForbiddenOperationException;
import com.smartschedule.exception.InvalidRequestException;
import com.smartschedule.exception.NotFoundException;
import com.smartschedule.repository.BusyScheduleRepository;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Quản lý lịch bận (BusySchedule) của nhân viên.
 *
 * Đăng ký lịch bận KHÔNG được đè lên ca đã được gán (A9): nếu trong khung giờ đó nhân viên đang có ca thì
 * phải nhả ca trước. Thao tác khóa dòng nhân viên (cùng cách với nhận ca) nên đăng ký bận và nhận ca đồng thời
 * không thể cùng vượt qua kiểm tra trùng.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusyScheduleService {

    private final BusyScheduleRepository busyScheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final Clock clock;

    /** Một lịch bận tối đa bao nhiêu ngày (ngăn "bận" cả năm để né ca). */
    @Value("${app.busy.max-days:14}")
    private long maxDays;

    /** Đăng ký lịch bận mới (có thể qua nhiều ngày/qua đêm). */
    @Transactional
    public BusyScheduleDTO createBusySchedule(Long employeeId, BusyScheduleRequest request) {
        // employeeId lấy từ người đăng nhập (SEC-03), không đọc từ body
        log.info("[BUSY] Nhân viên #{} đăng ký lịch bận: {} → {}",
                employeeId, request.getStartTime(), request.getEndTime());

        // Khóa dòng nhân viên: tuần tự hóa với nhận ca / đăng ký bận khác của cùng người (A9)
        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên #" + employeeId));

        LocalDateTime startTime = request.getStartTime();
        LocalDateTime endTime = request.getEndTime();

        if (!endTime.isAfter(startTime)) {
            throw new InvalidRequestException("Thời gian kết thúc phải sau thời gian bắt đầu");
        }
        if (startTime.isBefore(LocalDateTime.now(clock))) {
            throw new InvalidRequestException("Không thể đăng ký lịch bận trong quá khứ");
        }
        if (Duration.between(startTime, endTime).toMinutes() > maxDays * 24 * 60) {
            throw new InvalidRequestException("Lịch bận tối đa " + maxDays + " ngày liên tục");
        }

        Long busyCount = busyScheduleRepository.countBusyConflict(employeeId, startTime, endTime);
        if (busyCount != null && busyCount > 0) {
            throw new BusinessException("Bạn đã có lịch bận trùng trong khoảng thời gian này");
        }

        Long shiftCount = shiftRepository.countScheduleConflict(employeeId, startTime, endTime, 0L);
        if (shiftCount != null && shiftCount > 0) {
            throw new BusinessException(
                    "Trong khoảng này bạn đang có ca làm việc được gán. Hãy nhả ca trước khi đăng ký lịch bận.");
        }

        String reason = request.getReason() != null ? request.getReason().trim() : null;
        BusySchedule saved = busyScheduleRepository.save(BusySchedule.builder()
                .employee(employee)
                .startTime(startTime)
                .endTime(endTime)
                .reason(reason == null || reason.isEmpty() ? null : reason)
                .build());
        log.info("[BUSY] ✅ Đã đăng ký lịch bận #{} cho nhân viên #{}", saved.getId(), employeeId);

        return convertToDTO(saved);
    }

    /** Lịch bận của nhân viên (chỉ lịch chưa qua). */
    @Transactional(readOnly = true)
    public List<BusyScheduleDTO> getUpcomingBusySchedules(Long employeeId) {
        return busyScheduleRepository.findUpcomingByEmployeeId(employeeId, LocalDateTime.now(clock))
                .stream().map(this::convertToDTO).toList();
    }

    /** Xóa lịch bận của chính mình. */
    @Transactional
    public void deleteBusySchedule(Long busyId, Long employeeId) {
        BusySchedule busy = busyScheduleRepository.findById(busyId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy lịch bận #" + busyId));

        if (!busy.getEmployee().getId().equals(employeeId)) {
            throw new ForbiddenOperationException("Bạn không có quyền xóa lịch bận này");
        }

        busyScheduleRepository.delete(busy);
        log.info("[BUSY] 🗑️ Đã xóa lịch bận #{}", busyId);
    }

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
