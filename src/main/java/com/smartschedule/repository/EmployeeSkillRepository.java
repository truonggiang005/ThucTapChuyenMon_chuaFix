package com.smartschedule.repository;

import com.smartschedule.entity.EmployeeSkill;
import com.smartschedule.entity.EmployeeSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
    /** Kỹ năng của NHIỀU nhân viên trong MỘT truy vấn (danh sách nhân viên của admin, không N+1). */
    @Query("SELECT es FROM EmployeeSkill es " +
           "JOIN FETCH es.skill JOIN FETCH es.employee " +
           "WHERE es.employee.id IN :employeeIds")
    List<EmployeeSkill> findAllForEmployees(@Param("employeeIds") Collection<Long> employeeIds);

    @Query("SELECT es FROM EmployeeSkill es " +
           "WHERE es.employee.id = :employeeId AND es.skill.id = :skillId")
    Optional<EmployeeSkill> findByEmployeeIdAndSkillId(
            @Param("employeeId") Long employeeId,
            @Param("skillId") Long skillId
    );

    /**
     * Mức kỹ năng của NHIỀU nhân viên cho một kỹ năng trong một câu truy vấn (A11).
     * Mỗi dòng: {employeeId, skillLevel}. Không nạp entity nên không phát sinh truy vấn lazy.
     */
    @Query("SELECT es.employee.id, es.skillLevel FROM EmployeeSkill es " +
           "WHERE es.skill.id = :skillId AND es.employee.id IN :employeeIds")
    List<Object[]> findLevelsForEmployees(
            @Param("skillId") Long skillId,
            @Param("employeeIds") Collection<Long> employeeIds
    );

    /** Xóa toàn bộ kỹ năng của một nhân viên (dùng khi admin đặt lại danh sách kỹ năng). */
    @Modifying
    @Query("DELETE FROM EmployeeSkill es WHERE es.employee.id = :employeeId")
    int deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
