package com.smartschedule.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Khóa phân tán đơn giản cho cron job (A10): nhiều instance cùng chạy thì chỉ một instance giữ khóa mỗi lượt,
 * không cần tắt cờ app.scheduler.enabled thủ công. Việc chiếm khóa là một câu UPDATE nguyên tử (xem SchedulerLockRepository).
 */
@Entity
@Table(name = "scheduler_locks")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerLock {

    @Id
    @Column(length = 100)
    private String name;

    @Column(name = "locked_until", nullable = false)
    private LocalDateTime lockedUntil;

    @Column(name = "locked_by", nullable = false, length = 100)
    private String lockedBy;
}
