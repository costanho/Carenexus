package com.carenexus.api.communications.controller;

import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.dto.response.PageResponse;
import com.carenexus.api.communications.dto.response.UnreadCountResponse;
import com.carenexus.api.communications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The caller's own in-app notifications (any role, including ADMIN). MESSAGE notifications for conversations the
 * caller can no longer access are left out.
 *
 * - GET  /api/notifications?unreadOnly=false&page=0&size=20
 * - GET  /api/notifications/unread-count
 * - POST /api/notifications/{notificationId}/read   204
 * - POST /api/notifications/read-all                204
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationService.list(unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(notificationService.unreadCount());
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable Integer notificationId) {
        notificationService.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.noContent().build();
    }
}
