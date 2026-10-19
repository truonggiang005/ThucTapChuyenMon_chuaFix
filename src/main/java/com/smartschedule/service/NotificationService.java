package com.smartschedule.service;

import com.smartschedule.dto.NotificationDTO;
import com.smartschedule.entity.Notification;
import com.smartschedule.repository.EmployeeRepository;
import com.smartschedule.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Thông báo phía SERVER (D4, A7). Được gọi trong cùng transaction với hành động gây ra thông báo
 * (gán ca, nhả ca, cron...) nên hành động và thông báo thành công/thất bại cùng nhau.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    public static final int LIST_LIMIT = 30;
    private static final Set<String> TYPES = Set.of("success", "warning", "error", "info");

    private final NotificationRepository notificationRepository;
    private final EmployeeRepository employeeRepository;
    private final Clock clock;

    /** Gửi một thông báo cho một nhân viên. */
    @Transactional
    public void send(Long employeeId, String type, String message) {
        if (employeeId == null || message == null || message.isBlank()) {
            return;
        }
        String safeType = TYPES.contains(type) ? type : "info";
        String text = message.length() > 500 ? message.substring(0, 499) + "…" : message;
        notificationRepository.save(Notification.builder()
                .employee(employeeRepository.getReferenceById(employeeId))
                .type(safeType)
                .message(text)
                .createdAt(LocalDateTime.now(clock))
                .build());
    }

    /** Gửi cho mọi admin đang hoạt động (trừ người thực hiện hành động, nếu có). */
    @Transactional
    public void sendToAdmins(String type, String message, Long exceptEmployeeId) {
        for (Long adminId : employeeRepository.findActiveAdminIds()) {
            if (!adminId.equals(exceptEmployeeId)) {
                send(adminId, type, message);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDTO> latest(Long employeeId) {
        return notificationRepository.findLatest(employeeId, PageRequest.of(0, LIST_LIMIT)).stream()
                .map(n -> NotificationDTO.builder()
                        .id(n.getId())
                        .type(n.getType())
                        .message(n.getMessage())
                        .createdAt(n.getCreatedAt().toString())
                        .read(n.isRead())
                        .build())
                .toList();
    }

    @Transactional
    public void markRead(Long employeeId, Long notificationId) {
        notificationRepository.markRead(notificationId, employeeId);
    }

    @Transactional
    public void markAllRead(Long employeeId) {
        notificationRepository.markAllRead(employeeId);
    }

    @Transactional
    public void clear(Long employeeId) {
        notificationRepository.deleteAllFor(employeeId);
    }

    /** Dọn thông báo cũ hơn N ngày (cron gọi mỗi lượt). */
    @Transactional
    public int prune(int olderThanDays) {
        int n = notificationRepository.deleteOlderThan(LocalDateTime.now(clock).minusDays(olderThanDays));
        if (n > 0) {
            log.info("[NOTIFY] Đã dọn {} thông báo cũ", n);
        }
        return n;
    }
}
