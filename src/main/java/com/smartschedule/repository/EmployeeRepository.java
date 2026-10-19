package com.smartschedule.repository;

import com.smartschedule.entity.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho Entity Employee.
 *
 * Giờ làm/tuần KHÔNG còn nằm trong SQL lọc ứng viên (A4): ca vắt qua tuần phải chia theo từng tuần, logic đó
 * nằm ở một chỗ duy nhất là util.WorkWeek + ShiftRules, và số giờ đã làm được lấy bằng truy vấn gộp
 * {@link #sumWorkedHours} (một câu cho cả danh sách ứng viên - A11).
 *
 * Mọi truy vấn giờ/trùng lịch dùng native query (MySQL: TIMESTAMPDIFF, GREATEST, LEAST).
 */
@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /** Số giờ đã làm của một nhân viên trong một tuần (kết quả truy vấn gộp). */
    interface WorkedHours {
        Number getEmployeeId();
        Number getHours();
    }

    /** Tìm nhân viên theo email (dùng cho login) */
    Optional<Employee> findByEmail(String email);

    /** Kiểm tra email đã tồn tại chưa (dùng khi tạo nhân viên) */
    boolean existsByEmail(String email);

    boolean existsByRole(com.smartschedule.entity.Role role);

    /** Danh sách nhân viên kèm chi nhánh (tránh N+1 khi dựng DTO), có phân trang/giới hạn. */
    @EntityGraph(attributePaths = "primaryBranch")
    @Query("SELECT e FROM Employee e ORDER BY e.fullName ASC, e.id ASC")
    List<Employee> findPageWithBranch(Pageable pageable);

    @EntityGraph(attributePaths = "primaryBranch")
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> findByIdWithBranch(@Param("id") Long id);

    /** Admin đang hoạt động: nhận thông báo khi có người nhả ca / ca bỏ trống. */
    @Query("SELECT e.id FROM Employee e WHERE e.role = com.smartschedule.entity.Role.ADMIN AND e.active = true")
    List<Long> findActiveAdminIds();

    /**
     * Khóa dòng nhân viên (SELECT ... FOR UPDATE) để tuần tự hóa các lần nhận/gán ca/đăng ký lịch bận của CÙNG một người,
     * tránh hai thao tác chồng giờ cùng vượt qua kiểm tra trùng lịch (LOG-05, A9).
     * Phải gọi trong @Transactional và là câu truy vấn đầu tiên của giao dịch.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);

    /**
     * ★ QUERY LỌC ỨNG VIÊN - MODULE 2 (có kỹ năng yêu cầu):
     *   1. ĐỦ KỸ NĂNG: skill_level >= required_level
     *   2. KHÔNG TRÙNG CA: không có ca nào overlap với [shiftStart, shiftEnd)
     *   3. Tài khoản còn hoạt động
     * Giới hạn giờ/tuần và lịch bận được lọc tiếp ở service (dùng chung quy tắc với lúc gán ca).
     */
    @Query(value =
            "SELECT DISTINCT e.* FROM employees e " +
            "INNER JOIN employee_skills es ON e.id = es.employee_id " +
            "WHERE es.skill_id = :skillId " +
            "AND es.skill_level >= :requiredLevel " +
            "AND e.active = true " +
            "AND e.id <> :excludeEmployeeId " +
            "AND e.id NOT IN ( " +
            "    SELECT s.assigned_to FROM shifts s " +
            "    WHERE s.assigned_to IS NOT NULL " +
            "    AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED') " +
            "    AND s.start_time < :shiftEnd " +
            "    AND s.end_time > :shiftStart " +
            ")",
            nativeQuery = true)
    List<Employee> findEligibleEmployees(
            @Param("skillId") Long skillId,
            @Param("requiredLevel") Integer requiredLevel,
            @Param("shiftStart") LocalDateTime shiftStart,
            @Param("shiftEnd") LocalDateTime shiftEnd,
            @Param("excludeEmployeeId") Long excludeEmployeeId
    );

    /** Ca KHÔNG yêu cầu kỹ năng (LOG-03): không JOIN employee_skills. */
    @Query(value =
            "SELECT e.* FROM employees e " +
            "WHERE e.active = true " +
            "AND e.id <> :excludeEmployeeId " +
            "AND e.id NOT IN ( " +
            "    SELECT s.assigned_to FROM shifts s " +
            "    WHERE s.assigned_to IS NOT NULL " +
            "    AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED') " +
            "    AND s.start_time < :shiftEnd " +
            "    AND s.end_time > :shiftStart " +
            ")",
            nativeQuery = true)
    List<Employee> findEligibleEmployeesNoSkill(
            @Param("shiftStart") LocalDateTime shiftStart,
            @Param("shiftEnd") LocalDateTime shiftEnd,
            @Param("excludeEmployeeId") Long excludeEmployeeId
    );

    /**
     * Tổng giờ làm của MỘT nhân viên trong tuần [weekStart, weekEnd) — chỉ tính phần ca NẰM TRONG tuần (A4),
     * nên ca vắt qua nửa đêm/tuần được chia đúng. Tính theo PHÚT rồi chia 60.0 (TIMESTAMPDIFF(HOUR) cắt phần lẻ).
     * Bỏ qua ca excludeShiftId (khi sửa ca); truyền 0 nếu không cần.
     */
    @Query(value =
            "SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, GREATEST(s.start_time, :weekStart), LEAST(s.end_time, :weekEnd))), 0) / 60.0 " +
            "FROM shifts s " +
            "WHERE s.assigned_to = :employeeId " +
            "AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED') " +
            "AND s.start_time < :weekEnd " +
            "AND s.end_time > :weekStart " +
            "AND s.id <> :excludeShiftId",
            nativeQuery = true)
    Double calculateHoursWorkedInWeek(
            @Param("employeeId") Long employeeId,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd,
            @Param("excludeShiftId") Long excludeShiftId
    );

    /**
     * Giờ đã làm trong tuần [weekStart, weekEnd) của NHIỀU nhân viên trong một câu truy vấn (A11).
     * Nhân viên chưa có ca nào sẽ không có dòng trong kết quả (coi là 0 giờ).
     */
    @Query(value =
            "SELECT s.assigned_to AS employeeId, " +
            "       SUM(TIMESTAMPDIFF(MINUTE, GREATEST(s.start_time, :weekStart), LEAST(s.end_time, :weekEnd))) / 60.0 AS hours " +
            "FROM shifts s " +
            "WHERE s.assigned_to IN (:employeeIds) " +
            "AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED') " +
            "AND s.start_time < :weekEnd " +
            "AND s.end_time > :weekStart " +
            "AND s.id <> :excludeShiftId " +
            "GROUP BY s.assigned_to",
            nativeQuery = true)
    List<WorkedHours> sumWorkedHours(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd,
            @Param("excludeShiftId") Long excludeShiftId
    );

    // ============ Chỉ số độ tin cậy (A5): cập nhật nguyên tử, không mất cập nhật khi chạy song song ============

    @Modifying
    @Query("UPDATE Employee e SET e.totalCompleted = COALESCE(e.totalCompleted, 0) + 1 WHERE e.id = :id")
    int incrementCompleted(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Employee e SET e.totalCancelled = COALESCE(e.totalCancelled, 0) + 1 WHERE e.id = :id")
    int incrementCancelled(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Employee e SET e.lateArrivalCount = COALESCE(e.lateArrivalCount, 0) + 1 WHERE e.id = :id")
    int incrementLate(@Param("id") Long id);

    // ============ Khóa tài khoản khi đăng nhập sai nhiều lần (C4) ============

    @Modifying
    @Query("UPDATE Employee e SET e.failedLoginCount = e.failedLoginCount + 1 WHERE e.email = :email")
    int registerFailedLogin(@Param("email") String email);

    @Modifying
    @Query("UPDATE Employee e SET e.lockedUntil = :until, e.failedLoginCount = 0 " +
           "WHERE e.email = :email AND e.failedLoginCount >= :maxFailures")
    int lockIfTooManyFailures(@Param("email") String email, @Param("until") LocalDateTime until,
                              @Param("maxFailures") int maxFailures);

    @Modifying
    @Query("UPDATE Employee e SET e.failedLoginCount = 0, e.lockedUntil = null WHERE e.id = :id")
    int clearLoginFailures(@Param("id") Long id);
}
