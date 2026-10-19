package com.smartschedule.repository;

import com.smartschedule.entity.Shift;
import com.smartschedule.entity.ShiftStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository cho Entity Shift.
 *
 * Mọi truy vấn trả danh sách ca để dựng DTO đều kèm @EntityGraph nạp sẵn branch/requiredSkill/assignedTo,
 * nên convertToDTO không còn chạm lazy từng dòng (A11: N+1).
 */
@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long> {

    String GRAPH_BRANCH = "branch";
    String GRAPH_SKILL = "requiredSkill";
    String GRAPH_ASSIGNEE = "assignedTo";

    // MODULE 1: Đổi Ca

    /** Ca đang OPEN và CHƯA bắt đầu (chợ ca). Ca OPEN đã quá giờ sắp bị cron chuyển UNFILLED nên không hiện. */
    @EntityGraph(attributePaths = {GRAPH_BRANCH, GRAPH_SKILL})
    @Query("SELECT s FROM Shift s WHERE s.status = com.smartschedule.entity.ShiftStatus.OPEN " +
           "AND s.startTime > :now ORDER BY s.startTime ASC")
    List<Shift> findOpenFuture(@Param("now") LocalDateTime now);

    /** Như trên, lọc theo chi nhánh. */
    @EntityGraph(attributePaths = {GRAPH_BRANCH, GRAPH_SKILL})
    @Query("SELECT s FROM Shift s WHERE s.status = com.smartschedule.entity.ShiftStatus.OPEN " +
           "AND s.startTime > :now AND s.branch.id = :branchId ORDER BY s.startTime ASC")
    List<Shift> findOpenFutureByBranch(@Param("now") LocalDateTime now, @Param("branchId") Long branchId);

    /**
     * Lịch cá nhân: ca của nhân viên có trạng thái trong danh sách và chưa kết thúc trước mốc fromTime.
     * (Giới hạn theo thời gian để lịch không phình mãi theo lịch sử.)
     */
    @EntityGraph(attributePaths = {GRAPH_BRANCH, GRAPH_SKILL, GRAPH_ASSIGNEE})
    @Query("SELECT s FROM Shift s " +
           "WHERE s.assignedTo.id = :employeeId " +
           "AND s.status IN (:statuses) " +
           "AND s.endTime >= :fromTime " +
           "ORDER BY s.startTime ASC")
    List<Shift> findByAssignedToAndStatuses(
            @Param("employeeId") Long employeeId,
            @Param("statuses") Collection<ShiftStatus> statuses,
            @Param("fromTime") LocalDateTime fromTime
    );

    /**
     * Admin: ca nằm trong cửa sổ thời gian [from, to], có phân trang/giới hạn (A11 - không findAll()).
     * Ca được tính nếu giao với cửa sổ (kết thúc >= from và bắt đầu <= to).
     */
    @EntityGraph(attributePaths = {GRAPH_BRANCH, GRAPH_SKILL, GRAPH_ASSIGNEE})
    @Query("SELECT s FROM Shift s WHERE s.endTime >= :from AND s.startTime <= :to " +
           "ORDER BY s.startTime ASC, s.id ASC")
    List<Shift> findInWindow(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    // MODULE 3: Cron Job

    /**
     * ★ Ca OPEN sắp đến giờ: start_time nằm trong [fromTime, toTime]. Cron quét và tự gán (FORCE_ASSIGNED).
     * Chỉ lấy id để vòng lặp xử lý từng ca trong transaction riêng.
     */
    @Query("SELECT s.id FROM Shift s " +
           "WHERE s.status = :status " +
           "AND s.startTime >= :fromTime " +
           "AND s.startTime <= :toTime " +
           "ORDER BY s.startTime ASC")
    List<Long> findUpcomingOpenShiftIds(
            @Param("status") ShiftStatus status,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            Pageable pageable
    );

    /** Ca OPEN đã quá giờ bắt đầu (chưa ai nhận) → cron đánh dấu UNFILLED. */
    @EntityGraph(attributePaths = {GRAPH_BRANCH})
    @Query("SELECT s FROM Shift s " +
           "WHERE s.status = :status " +
           "AND s.startTime < :cutoff")
    List<Shift> findExpiredOpenShifts(
            @Param("status") ShiftStatus status,
            @Param("cutoff") LocalDateTime cutoff
    );

    /** Ca có người làm đã kết thúc (end_time <= cutoff) nhưng chưa được đánh dấu COMPLETED. */
    @EntityGraph(attributePaths = {GRAPH_ASSIGNEE})
    @Query("SELECT s FROM Shift s " +
           "WHERE s.status IN (:statuses) " +
           "AND s.assignedTo IS NOT NULL " +
           "AND s.endTime <= :cutoff")
    List<Shift> findEndedShifts(
            @Param("statuses") Collection<ShiftStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    // KIỂM TRA TRÙNG LỊCH / SỨC CHỨA

    /**
     * Số ca (đã gán) của nhân viên overlap với [newStart, newEnd). Overlap: existingStart < newEnd AND existingEnd > newStart.
     * Bỏ qua ca excludeShiftId (khi sửa ca); truyền 0 nếu không cần loại trừ.
     */
    @Query(value =
            "SELECT COUNT(*) " +
            "FROM shifts s " +
            "WHERE s.assigned_to = :employeeId " +
            "AND s.status IN ('ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED') " +
            "AND s.start_time < :newEnd " +
            "AND s.end_time > :newStart " +
            "AND s.id <> :excludeShiftId",
            nativeQuery = true)
    Long countScheduleConflict(
            @Param("employeeId") Long employeeId,
            @Param("newStart") LocalDateTime newStart,
            @Param("newEnd") LocalDateTime newEnd,
            @Param("excludeShiftId") Long excludeShiftId
    );

    /**
     * Khoảng thời gian {start, end} của các ca đã có người làm tại một chi nhánh, giao với [start, end).
     * Dùng để kiểm tra Branch.maxCapacity (số người làm đồng thời tối đa - A14).
     */
    @Query("SELECT s.startTime, s.endTime FROM Shift s " +
           "WHERE s.branch.id = :branchId " +
           "AND s.assignedTo IS NOT NULL " +
           "AND s.status IN (:statuses) " +
           "AND s.startTime < :end AND s.endTime > :start " +
           "AND s.id <> :excludeShiftId")
    List<Object[]> findActiveIntervalsInBranch(
            @Param("branchId") Long branchId,
            @Param("statuses") Collection<ShiftStatus> statuses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("excludeShiftId") Long excludeShiftId
    );
}
