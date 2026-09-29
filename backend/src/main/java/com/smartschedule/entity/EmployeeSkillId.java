package com.smartschedule.entity;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite Key class cho bảng trung gian employee_skills.
 *
 * JPA yêu cầu @IdClass phải:
 * 1. Implement Serializable
 * 2. Có constructor không tham số
 * 3. Override equals() và hashCode()
 * 4. Tên field phải khớp với tên field @Id trong EmployeeSkill entity
 */
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeSkillId implements Serializable {

    private Long employee; // Khớp với field 'employee' trong EmployeeSkill (lấy ID của Employee)
    private Long skill;    // Khớp với field 'skill' trong EmployeeSkill (lấy ID của Skill)

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmployeeSkillId that = (EmployeeSkillId) o;
        return Objects.equals(employee, that.employee) &&
               Objects.equals(skill, that.skill);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employee, skill);
    }
}
