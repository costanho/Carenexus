package com.carenexus.api.communications.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.dto.response.UnreadCountResponse;
import com.carenexus.api.communications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The caller's own in-app notifications (any role, including ADMIN). Expired notifications and MESSAGE
 * notifications for conversations the caller can no longer access are left out of the list and the counts.
 *
 * - GET  /api/notifications?unreadOnly=false&type=&page=0&size=20
 * - GET  /api/notifications/unread-count
 * - POST /api/notifications/{notificationId}/read   204
 * - POST /api/notifications/read-all                204
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "The signed-in user's in-app notifications: list, unread counts, read state")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List the caller's notifications, newest first",
            description = "Any signed-in user. Returns only the caller's own notifications; expired rows and message "
                    + "notifications of conversations the caller can no longer access are left out. The page size is "
                    + "clamped to 1..50. Audited: one READ NOTIFICATION entry per patient referenced on the page. "
                    + "Does not change the read state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of notifications"),
            @ApiResponse(responseCode = "400", description = "Unknown notification type, or a parameter of the wrong type",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @Parameter(description = "Only unread notifications")
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @Parameter(description = "Only this notification type (any case); omit for every type",
                    schema = @Schema(allowableValues = {"APPOINTMENT", "MEDICATION", "LAB_RESULT", "MESSAGE", "AI_ALERT",
                            "SYSTEM", "PAYMENT", "REFERRAL"}))
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..50")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationService.list(unreadOnly, type, page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count the caller's unread notifications",
            description = "Any signed-in user. unreadCount covers every type, unreadMessageCount only MESSAGE "
                    + "notifications; both use the same visibility rule as the list. Polled by the clients; not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The two unread counts"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(notificationService.unreadCount());
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one of the caller's notifications read",
            description = "Any signed-in user, own notifications only. Idempotent: a repeat keeps the original read "
                    + "time. Audited (UPDATE NOTIFICATION) when the notification changes from unread to read.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The notification is read"),
            @ApiResponse(responseCode = "400", description = "The id is not a number",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such notification, or it belongs to another user",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> markRead(
            @Parameter(description = "Id of the notification", example = "214") @PathVariable Integer notificationId) {
        notificationService.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all of the caller's notifications read",
            description = "Any signed-in user. Marks every unread notification of the caller, of every type. "
                    + "Idempotent. Audited (UPDATE NOTIFICATION) when at least one notification changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Every notification of the caller is read"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.noContent().build();
    }
}
