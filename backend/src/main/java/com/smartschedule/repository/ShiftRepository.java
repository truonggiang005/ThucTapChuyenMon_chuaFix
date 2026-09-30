package com.smartschedule.repository;

import com.smartschedule.entity.Shift;
import com.smartschedule.entity.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository cho Entity Shift.
 *
 * Phục vụ:
 *   - Module 1: Tìm ca OPEN để hiển thị cho nhân viên nhận.
 *   - Module 3: Cron Job quét ca OPEN sắp đến giờ để auto-assign.
 *   - UI: Hiển thị danh sách ca theo trạng thái, chi nhánh, nhân viên.
 */
@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long> {

    // ============================================================
    // MODULE 1: Đổi Ca
    // ============================================================

    /**
     * Lấy tất cả ca đang OPEN (chờ người nhận).
     * Dùng để hiển thị danh sách "Ca đang mở" trên giao diện.
     */
    List<Shift> findByStatusOrderByStartTimeAsc(ShiftStatus status);

    /**
     * Lấy tất cả ca OPEN thuộc một chi nhánh cụ thể.
     * Nhân viên thường chỉ xem ca trong chi nhánh của mình.
     */
    List<Shift> findByStatusAndBranchIdOrderByStartTimeAsc(ShiftStatus status, Long branchId);

    /**
     * Lấy tất cả ca đã gán cho một nhân viên (lịch làm việc cá nhân).
     */
    @Query("SELECT s FROM Shift s " +
           "WHERE s.assignedTo.id = :employeeId " +
           "AND s.status IN (:statuses) " +
           "ORDER BY s.startTime ASC")
    List<Shift> findByAssignedToAndStatuses(
            @Param("employeeId") Long employeeId,
            @Param("statuses") List<ShiftStatus> statuses
    );

    // ============================================================
    // MODULE 3: Cron Job - Auto Assign
    // ============================================================

    /**
     * ★ QUERY CHO CRON JOB: Tìm ca OPEN sắp đến giờ.
     *
     * Logic: Quét ca có status = OPEN và start_time nằm trong khoảng [now, now + threshold].
     * Threshold thường là 2 giờ (cấu hình ở Service layer).
     *
     * Ví dụ: Bây giờ là 14:00, threshold = 2h
     *   → Tìm ca OPEN có start_time từ 14:00 đến 16:00
     *   → Cron Job sẽ tự động gán (FORCE_ASSIGNED) cho người AI score cao nhất.
     *
     * @param status   Trạng thái cần quét (OPEN)
     * @param fromTime Bắt đầu khoảng thời gian (thường là now)
     * @param toTime   Kết thúc khoảng thời gian (thường là now + 2h)
     * @return Danh sách ca cần auto-assign
     */
    @Query("SELECT s FROM Shift s " +
           "WHERE s.status = :status " +
           "AND s.startTime >= :fromTime " +
           "AND s.startTime <= :toTime " +
           "ORDER BY s.startTime ASC")
    List<Shift> findUpcomingOpenShifts(
            @Param("status") ShiftStatus status,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime
    );

    /**
     * Tìm ca OPEN đã quá giờ bắt đầu (chưa ai nhận, cần đánh dấu UNFILLED).
     *
     * @param status  Trạng thái OPEN
     * @param cutoff  Thời điểm cắt (thường là now)
     * @return Danh sách ca quá hạn
     */
    @Query("SELECT s FROM Shift s " +
           "WHERE s.status = :status " +
           "AND s.startTime < :cutoff")
    List<Shift> findExpiredOpenShifts(
            @Param("status") ShiftStatus status,
            @Param("cutoff") LocalDateTime cutoff
    );

    // ============================================================
    // THỐNG KÊ & UI
    // ============================================================

    /**
     * Đếm số ca theo trạng thái (dùng cho dashboard).
     */
    long countByStatus(ShiftStatus status);

    /**
     * Lấy tất cả ca trong một khoảng thời gian (dùng cho calendar view).
     */
    @Query("SELECT s FROM Shift s " +
           "WHERE s.startTime >= :from AND s.startTime <= :to " +
           "ORDER BY s.startTime ASC")
    List<Shift> findShiftsInRange(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Kiểm tra nhân viên có bị trùng lịch không (dùng khi nhận ca thủ công - Module 1).
     * Trả về số lượng ca overlap. Caller cần check > 0.
     *
     * Overlap condition: existingStart < newEnd AND existingEnd > newStart
     * Sử dụng Native Query để tránh lỗi JPQL enum parsing.
     * Trả về Long thay vì boolean vì MySQL native query trả Long cho CASE WHEN.
     */
    @Query(value =
            "SELECT COUNT(*) " +
            "FROM shifts s " +
            "WHERE s.assigned_to = :employeeId " +
            "AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED') " +
            "AND s.start_time < :newEnd " +
            "AND s.end_time > :newStart",
            nativeQuery = true)
    Long countScheduleConflict(
            @Param("employeeId") Long employeeId,
            @Param("newStart") LocalDateTime newStart,
            @Param("newEnd") LocalDateTime newEnd
    );
}
