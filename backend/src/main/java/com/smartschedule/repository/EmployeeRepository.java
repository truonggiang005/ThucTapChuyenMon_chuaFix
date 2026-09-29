package com.smartschedule.repository;

import com.smartschedule.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho Entity Employee.
 *
 * Chứa các truy vấn phục vụ Module 2 (AI Matchmaking):
 * - Lọc nhân viên đủ kỹ năng (skill_level >= required_level)
 * - Loại nhân viên trùng lịch (shift overlap)
 * - Loại nhân viên quá giờ/tuần (> max_hours_per_week)
 *
 * Sử dụng Native Query (MySQL) vì JPQL không hỗ trợ TIMESTAMPDIFF.
 */
@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /** Tìm nhân viên theo email (dùng cho login) */
    Optional<Employee> findByEmail(String email);

    /** Kiểm tra email đã tồn tại chưa (dùng khi đăng ký) */
    boolean existsByEmail(String email);

    /**
     * ★ QUERY CỐT LÕI - MODULE 2: Lọc nhân viên đủ điều kiện nhận ca.
     *
     * Điều kiện lọc (3 tầng):
     *   1. ĐỦ KỸ NĂNG: Nhân viên phải có kỹ năng yêu cầu với skill_level >= required_level
     *   2. KHÔNG TRÙNG LỊCH: Nhân viên không có ca nào overlap với [shiftStart, shiftEnd]
     *   3. CHƯA QUÁ GIỜ/TUẦN: Tổng giờ đã làm trong tuần + thời lượng ca mới <= max_hours_per_week
     *
     * Sử dụng nativeQuery = true vì TIMESTAMPDIFF là MySQL function.
     */
    @Query(value =
            "SELECT DISTINCT e.* FROM employees e " +
            "INNER JOIN employee_skills es ON e.id = es.employee_id " +
            // ── Điều kiện 1: Đủ kỹ năng ──
            "WHERE es.skill_id = :skillId " +
            "AND es.skill_level >= :requiredLevel " +
            // ── Loại trừ nhân viên cụ thể ──
            "AND e.id <> :excludeEmployeeId " +
            // ── Điều kiện 2: Không trùng lịch ──
            "AND e.id NOT IN ( " +
            "    SELECT s.assigned_to FROM shifts s " +
            "    WHERE s.assigned_to IS NOT NULL " +
            "    AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED') " +
            "    AND s.start_time < :shiftEnd " +
            "    AND s.end_time > :shiftStart " +
            ") " +
            // ── Điều kiện 3: Chưa quá giờ/tuần ──
            "AND ( " +
            "    COALESCE(( " +
            "        SELECT SUM(TIMESTAMPDIFF(HOUR, s2.start_time, s2.end_time)) " +
            "        FROM shifts s2 " +
            "        WHERE s2.assigned_to = e.id " +
            "        AND s2.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED') " +
            "        AND s2.start_time >= :weekStart " +
            "        AND s2.end_time <= :weekEnd " +
            "    ), 0) + :shiftDurationHours <= e.max_hours_per_week " +
            ")",
            nativeQuery = true)
    List<Employee> findEligibleEmployees(
            @Param("skillId") Long skillId,
            @Param("requiredLevel") Integer requiredLevel,
            @Param("shiftStart") LocalDateTime shiftStart,
            @Param("shiftEnd") LocalDateTime shiftEnd,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd,
            @Param("shiftDurationHours") double shiftDurationHours,
            @Param("excludeEmployeeId") Long excludeEmployeeId
    );

    /**
     * Tính tổng số giờ làm việc của nhân viên trong một khoảng thời gian.
     * Sử dụng Native Query vì TIMESTAMPDIFF là MySQL function.
     *
     * @param employeeId ID nhân viên
     * @param weekStart  Bắt đầu khoảng thời gian
     * @param weekEnd    Kết thúc khoảng thời gian
     * @return Tổng số giờ (Double), trả về 0 nếu không có ca nào
     */
    @Query(value =
            "SELECT COALESCE(SUM(TIMESTAMPDIFF(HOUR, s.start_time, s.end_time)), 0) " +
            "FROM shifts s " +
            "WHERE s.assigned_to = :employeeId " +
            "AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED') " +
            "AND s.start_time >= :weekStart " +
            "AND s.end_time <= :weekEnd",
            nativeQuery = true)
    Double calculateHoursWorkedInWeek(
            @Param("employeeId") Long employeeId,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd
    );
}
