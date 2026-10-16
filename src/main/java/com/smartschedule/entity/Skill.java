package com.smartschedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity đại diện cho Kỹ năng (Skill).
 * Dùng trong quan hệ N-N với Employee qua bảng trung gian employee_skills.
 * Shift cũng tham chiếu đến Skill để xác định kỹ năng yêu cầu.
 */
@Entity
@Table(name = "skills")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tên kỹ năng không được để trống")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    // ===== QUAN HỆ =====

    /**
     * Một kỹ năng có nhiều bản ghi employee_skills (quan hệ N-N thông qua EmployeeSkill).
     */
    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<EmployeeSkill> employeeSkills = new ArrayList<>();

    /**
     * Một kỹ năng có thể được yêu cầu bởi nhiều ca làm việc.
     */
    @OneToMany(mappedBy = "requiredSkill", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Shift> shifts = new ArrayList<>();
}
