package com.smartschedule.repository;

import com.smartschedule.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Thông báo mới nhất của một nhân viên (dùng Pageable để giới hạn số dòng). */
    @Query("SELECT n FROM Notification n WHERE n.employee.id = :employeeId ORDER BY n.createdAt DESC, n.id DESC")
    List<Notification> findLatest(@Param("employeeId") Long employeeId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.employee.id = :employeeId AND n.read = false")
    long countUnread(@Param("employeeId") Long employeeId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.employee.id = :employeeId")
    int markRead(@Param("id") Long id, @Param("employeeId") Long employeeId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.employee.id = :employeeId AND n.read = false")
    int markAllRead(@Param("employeeId") Long employeeId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.employee.id = :employeeId")
    int deleteAllFor(@Param("employeeId") Long employeeId);

    /** Dọn thông báo cũ để bảng không phình mãi. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
