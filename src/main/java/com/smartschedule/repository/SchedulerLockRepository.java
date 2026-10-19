package com.smartschedule.repository;

import com.smartschedule.entity.SchedulerLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Chiếm/nhả khóa cron bằng câu lệnh SQL nguyên tử, an toàn khi nhiều instance cùng chạy (A10).
 * Dùng MySQL (INSERT IGNORE) giống phần còn lại của dự án.
 */
@Repository
public interface SchedulerLockRepository extends JpaRepository<SchedulerLock, String> {

    /** Chiếm khóa nếu nó đã hết hạn (hoặc đang do chính owner giữ). Trả 1 nếu chiếm được. */
    @Modifying
    @Query(value = "UPDATE scheduler_locks SET locked_until = :until, locked_by = :owner " +
            "WHERE name = :name AND (locked_until < :now OR locked_by = :owner)", nativeQuery = true)
    int tryAcquire(@Param("name") String name, @Param("now") LocalDateTime now,
                   @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** Tạo dòng khóa lần đầu. Trả 1 nếu tạo được, 0 nếu instance khác đã tạo trước. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO scheduler_locks (name, locked_until, locked_by) " +
            "VALUES (:name, :until, :owner)", nativeQuery = true)
    int tryCreate(@Param("name") String name, @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** Nhả khóa (đặt hết hạn ngay) nếu mình đang giữ. */
    @Modifying
    @Query(value = "UPDATE scheduler_locks SET locked_until = :now WHERE name = :name AND locked_by = :owner",
            nativeQuery = true)
    int release(@Param("name") String name, @Param("now") LocalDateTime now, @Param("owner") String owner);
}
