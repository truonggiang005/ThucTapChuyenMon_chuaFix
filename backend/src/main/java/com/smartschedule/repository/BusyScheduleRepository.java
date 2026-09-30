package com.smartschedule.repository;

import com.smartschedule.entity.BusySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
     * Lấy tất cả lịch bận của một nhân viên, sắp xếp theo thời gian.
     */
    @Query("SELECT b FROM BusySchedule b " +
           "WHERE b.employee.id = :employeeId " +
           "ORDER BY b.startTime ASC")
    List<BusySchedule> findByEmployeeId(@Param("employeeId") Long employeeId);

    /**
     * Lấy lịch bận của nhân viên từ một thời điểm trở đi (chỉ lịch chưa qua).
     */
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
            "SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END " +
            "FROM busy_schedules b " +
            "WHERE b.employee_id = :employeeId " +
            "AND b.start_time < :newEnd " +
            "AND b.end_time > :newStart",
            nativeQuery = true)
    boolean hasBusyConflict(
            @Param("employeeId") Long employeeId,
            @Param("newStart") LocalDateTime newStart,
            @Param("newEnd") LocalDateTime newEnd
    );

    /**
     * Lấy danh sách employee_id đang bận trong khoảng thời gian [shiftStart, shiftEnd].
     * Dùng để loại trừ khỏi danh sách ứng viên AI.
     */
    @Query(value =
            "SELECT DISTINCT b.employee_id FROM busy_schedules b " +
            "WHERE b.start_time < :shiftEnd " +
            "AND b.end_time > :shiftStart",
            nativeQuery = true)
    List<Long> findBusyEmployeeIds(
            @Param("shiftStart") LocalDateTime shiftStart,
            @Param("shiftEnd") LocalDateTime shiftEnd
    );
}
