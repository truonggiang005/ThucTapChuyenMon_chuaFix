package com.smartschedule.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thông báo lưu ở SERVER, gửi tới từng nhân viên (D4, A7).
 * Thay cho thông báo tự sinh ở localStorage trình duyệt: người bị cron gán ca, hoặc admin khi có người nhả ca,
 * đều nhận được thông báo và đồng bộ giữa các thiết bị.
 */
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notification_employee_created", columnList = "employee_id, created_at")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** success | warning | error | info */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** "read" là từ khóa của MySQL nên đặt tên cột là is_read. */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;
}
