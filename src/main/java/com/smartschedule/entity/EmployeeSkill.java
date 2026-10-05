package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

/**
 * Entity đại diện cho bảng trung gian Employee-Skill (quan hệ N-N).
 *
 * Sử dụng Composite Key (employee_id + skill_id) thông qua @IdClass.
 * Lý do dùng Entity riêng thay vì @ManyToMany: cần lưu thêm trường skill_level
 * để biết mức độ thành thạo kỹ năng của nhân viên.
 *
 * skill_level: 1 (Cơ bản) → 5 (Chuyên gia)
 */
@Entity
@Table(name = "employee_skills")
@IdClass(EmployeeSkillId.class)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeSkill {

    /**
     * Khóa chính thứ nhất: ID nhân viên.
     * Đồng thời là khóa ngoại tham chiếu đến bảng employees.
     */
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /**
     * Khóa chính thứ hai: ID kỹ năng.
     * Đồng thời là khóa ngoại tham chiếu đến bảng skills.
     */
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    /**
     * Mức độ thành thạo kỹ năng: 1 (Cơ bản) → 5 (Chuyên gia).
     * Shift yêu cầu required_level, nhân viên cần có skill_level >= required_level.
     */
    @Min(value = 1, message = "Mức kỹ năng tối thiểu là 1")
    @Max(value = 5, message = "Mức kỹ năng tối đa là 5")
    @Column(name = "skill_level", nullable = false)
    @Builder.Default
    private Integer skillLevel = 1;
}
