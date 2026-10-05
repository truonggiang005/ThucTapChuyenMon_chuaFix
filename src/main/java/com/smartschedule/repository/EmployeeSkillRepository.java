package com.smartschedule.repository;

import com.smartschedule.entity.EmployeeSkill;
import com.smartschedule.entity.EmployeeSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository cho bảng trung gian Employee_Skills.
 * Composite Key = EmployeeSkillId(employee_id, skill_id).
 */
@Repository
public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, EmployeeSkillId> {

    /**
     * Tìm tất cả kỹ năng của một nhân viên.
     * Dùng để hiển thị danh sách kỹ năng trên profile.
     */
    @Query("SELECT es FROM EmployeeSkill es " +
           "JOIN FETCH es.skill " +
           "WHERE es.employee.id = :employeeId")
    List<EmployeeSkill> findByEmployeeId(@Param("employeeId") Long employeeId);

    /**
     * Tìm bản ghi kỹ năng cụ thể của nhân viên (kiểm tra nhân viên X có skill Y không).
     */
    @Query("SELECT es FROM EmployeeSkill es " +
           "WHERE es.employee.id = :employeeId AND es.skill.id = :skillId")
    Optional<EmployeeSkill> findByEmployeeIdAndSkillId(
            @Param("employeeId") Long employeeId,
            @Param("skillId") Long skillId
    );
}
