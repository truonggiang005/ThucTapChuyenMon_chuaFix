package com.smartschedule.controller;

import com.smartschedule.dto.NotificationDTO;
import com.smartschedule.security.AppUserDetails;
import com.smartschedule.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Thông báo của CHÍNH người đăng nhập (D4). Không có endpoint xem thông báo của người khác.
 *
 *   GET    /api/notifications            → 30 thông báo mới nhất
 *   POST   /api/notifications/{id}/read  → đánh dấu đã đọc
 *   POST   /api/notifications/read-all   → đánh dấu tất cả đã đọc
 *   DELETE /api/notifications            → xóa tất cả của mình
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<NotificationDTO>> list(@AuthenticationPrincipal AppUserDetails me) {
        return ResponseEntity.ok(notificationService.latest(me.getEmployeeId()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me) {
        notificationService.markRead(me.getEmployeeId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal AppUserDetails me) {
        notificationService.markAllRead(me.getEmployeeId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal AppUserDetails me) {
        notificationService.clear(me.getEmployeeId());
        return ResponseEntity.noContent().build();
    }
}
