package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity đại diện cho Nhân viên (Employee).
 *
 * Bao gồm các trường lịch sử phục vụ cho AI Matchmaking:
 * - totalCompleted: Tổng số ca đã hoàn thành
 * - totalCancelled: Tổng số ca đã hủy
 * - lateArrivalCount: Số lần đến muộn
 * - preferredTime: Khung giờ ưa thích (MORNING / AFTERNOON / EVENING / NIGHT)
 *
 * Các trường này được Python AI Microservice sử dụng để tính Match Score.
 */
@Entity
@Table(name = "employees")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Họ tên không được để trống")
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Email(message = "Email không hợp lệ")
    @NotBlank(message = "Email không được để trống")
    @Column(nullable = false, unique = true, length = 200)
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * Số giờ làm việc tối đa mỗi tuần.
     * Dùng để kiểm tra điều kiện "chưa quá 40h/tuần" trong Module 2.
     */
    @Column(name = "max_hours_per_week")
    @Builder.Default
    private Integer maxHoursPerWeek = 40;

    // ===== TRƯỜNG LỊCH SỬ CHO AI =====

    /** Tổng số ca đã hoàn thành thành công */
    @PositiveOrZero
    @Column(name = "total_completed")
    @Builder.Default
    private Integer totalCompleted = 0;

    /** Tổng số ca đã hủy/bỏ */
    @PositiveOrZero
    @Column(name = "total_cancelled")
    @Builder.Default
    private Integer totalCancelled = 0;

    /** Số lần đến muộn */
    @PositiveOrZero
    @Column(name = "late_arrival_count")
    @Builder.Default
    private Integer lateArrivalCount = 0;

    /**
     * Khung giờ ưa thích của nhân viên.
     * Giá trị: MORNING, AFTERNOON, EVENING, NIGHT
     * AI sẽ dùng trường này để đối chiếu với thời gian ca làm.
     */
    @Column(name = "preferred_time", length = 20)
    @Builder.Default
    private String preferredTime = "MORNING";

    // ===== QUAN HỆ =====

    /**
     * ManyToOne: Nhiều nhân viên thuộc về một chi nhánh chính.
     * @JoinColumn tạo cột 'primary_branch_id' trong bảng employees.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_branch_id")
    private Branch primaryBranch;

    /**
     * Một nhân viên có nhiều kỹ năng (qua bảng trung gian employee_skills).
     */
    @OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<EmployeeSkill> employeeSkills = new ArrayList<>();

    /**
     * Một nhân viên có thể được gán nhiều ca làm việc.
     */
    @OneToMany(mappedBy = "assignedTo", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Shift> assignedShifts = new ArrayList<>();
}
