package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity đại diện cho Ca làm việc (Shift).
 *
 * ĐÂY LÀ ENTITY TRUNG TÂM của toàn bộ hệ thống, tham gia vào cả 3 Module:
 *
 * Module 1 (Đổi ca & Tương tranh):
 *   - Trường 'version' được đánh dấu @Version để JPA tự động kiểm tra Optimistic Locking.
 *   - Khi 2 người cùng nhận 1 ca OPEN, người thứ hai sẽ nhận ObjectOptimisticLockingFailureException.
 *
 * Module 2 (AI Matchmaking):
 *   - Trường 'requiredSkill' và 'requiredLevel' dùng để lọc nhân viên đủ điều kiện.
 *   - Danh sách lọc được gửi sang Python để chấm điểm Match Score.
 *
 * Module 3 (Auto-assign Job):
 *   - Cron Job quét các shift có status = OPEN và start_time sắp đến.
 *   - Tự động chuyển status sang FORCE_ASSIGNED và gán assigned_to.
 */
@Entity
@Table(name = "shifts", indexes = {
    @Index(name = "idx_shift_status", columnList = "status"),
    @Index(name = "idx_shift_start_time", columnList = "start_time"),
    @Index(name = "idx_shift_status_start", columnList = "status, start_time")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tiêu đề ca không được để trống")
    @Column(nullable = false, length = 200)
    private String title;

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @NotNull(message = "Thời gian kết thúc không được để trống")
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    /**
     * Mức kỹ năng yêu cầu tối thiểu để nhận ca này.
     * Nhân viên cần có skill_level >= required_level cho required_skill.
     */
    @Min(value = 1, message = "Mức kỹ năng yêu cầu tối thiểu là 1")
    @Column(name = "required_level")
    @Builder.Default
    private Integer requiredLevel = 1;

    /**
     * Trạng thái ca làm việc (ASSIGNED, OPEN, TAKEN, FORCE_ASSIGNED, UNFILLED).
     * Sử dụng @Enumerated(STRING) để lưu tên enum dạng chuỗi trong DB,
     * tránh lỗi khi thêm/xóa enum value sau này.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftStatus status = ShiftStatus.ASSIGNED;

    /**
     * ★ OPTIMISTIC LOCKING ★
     *
     * @Version khiến JPA tự động:
     * 1. Tăng giá trị 'version' mỗi khi UPDATE.
     * 2. Thêm "WHERE version = ?" vào câu UPDATE.
     * 3. Nếu version trong DB khác version đang giữ → ném ObjectOptimisticLockingFailureException.
     *
     * → Đảm bảo KHÔNG CÓ 2 người nhận cùng 1 ca (Module 1).
     */
    @Version
    @Column(nullable = false)
    private Long version;

    // ===== QUAN HỆ =====

    /**
     * ManyToOne: Nhiều ca thuộc về một chi nhánh.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /**
     * ManyToOne: Ca yêu cầu một kỹ năng cụ thể.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "required_skill_id")
    private Skill requiredSkill;

    /**
     * ManyToOne: Ca được gán cho một nhân viên.
     * Nullable vì ca OPEN chưa có người nhận.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private Employee assignedTo;
}
