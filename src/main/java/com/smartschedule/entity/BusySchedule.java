package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity đại diện cho Lịch bận (BusySchedule) của nhân viên.
 *
 * Nhân viên có thể đăng ký các khoảng thời gian bận (nghỉ phép, việc cá nhân, v.v.)
 * để hệ thống không gán ca vào khung giờ đó.
 *
 * Được sử dụng bởi:
 *   - Module 1: Kiểm tra trùng lịch khi nhận ca
 *   - Module 2: AI lọc ứng viên (loại nhân viên đang bận)
 *   - Module 3: Cron Job loại nhân viên bận khi auto-assign
 */
@Entity
@Table(name = "busy_schedules", indexes = {
    @Index(name = "idx_busy_employee", columnList = "employee_id"),
    @Index(name = "idx_busy_time", columnList = "start_time, end_time")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusySchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nhân viên đăng ký lịch bận.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    @NotNull(message = "Nhân viên không được để trống")
    private Employee employee;

    /**
     * Thời gian bắt đầu bận.
     */
    @NotNull(message = "Thời gian bắt đầu không được để trống")
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /**
     * Thời gian kết thúc bận.
     */
    @NotNull(message = "Thời gian kết thúc không được để trống")
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    /**
     * Lý do bận (tùy chọn).
     */
    @Column(length = 300)
    private String reason;
}
