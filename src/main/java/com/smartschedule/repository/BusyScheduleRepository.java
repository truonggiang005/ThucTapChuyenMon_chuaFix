package com.smartschedule.repository;

import com.smartschedule.entity.BusySchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository cho Entity BusySchedule.
 *
 * Phục vụ:
 *   - Đăng ký / xóa lịch bận của nhân viên
 *   - Kiểm tra trùng lịch bận khi nhận ca
 *   - Module 2: Lọc nhân viên đang bận ra khỏi danh sách ứng viên AI
 */
@Repository
public interface BusyScheduleRepository extends JpaRepository<BusySchedule, Long> {

    /**
     * Lấy lịch bận của nhân viên từ một thời điểm trở đi (chỉ lịch chưa qua).
     */
    @EntityGraph(attributePaths = "employee")
    @Query("SELECT b FROM BusySchedule b " +
           "WHERE b.employee.id = :employeeId " +
           "AND b.endTime > :fromTime " +
           "ORDER BY b.startTime ASC")
    List<BusySchedule> findUpcomingByEmployeeId(
            @Param("employeeId") Long employeeId,
            @Param("fromTime") LocalDateTime fromTime
    );

    /**
     * Kiểm tra nhân viên có bị trùng lịch bận không.
     * Overlap condition: busyStart < newEnd AND busyEnd > newStart
     */
    @Query(value =
            "SELECT COUNT(*) " +
            "FROM busy_schedules b " +
            "WHERE b.employee_id = :employeeId " +
            "AND b.start_time < :newEnd " +
            "AND b.end_time > :newStart",
            nativeQuery = true)
    Long countBusyConflict(
            @Param("employeeId") Long employeeId,
            @Param("newStart") LocalDateTime newStart,
            @Param("newEnd") LocalDateTime newEnd
    );

    /**
     * Trong số các nhân viên đã cho, những ai đang bận trong [shiftStart, shiftEnd).
     * Giới hạn theo danh sách ứng viên thay vì quét toàn bộ bảng.
     */
    @Query(value =
            "SELECT DISTINCT b.employee_id FROM busy_schedules b " +
            "WHERE b.employee_id IN (:employeeIds) " +
            "AND b.start_time < :shiftEnd " +
            "AND b.end_time > :shiftStart",
            nativeQuery = true)
    List<Long> findBusyAmong(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("shiftStart") LocalDateTime shiftStart,
            @Param("shiftEnd") LocalDateTime shiftEnd
    );
}
