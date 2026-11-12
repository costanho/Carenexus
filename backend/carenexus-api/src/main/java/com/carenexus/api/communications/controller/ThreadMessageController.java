package com.carenexus.api.communications.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.communications.dto.request.MarkThreadReadRequest;
import com.carenexus.api.communications.dto.request.SendMessageRequest;
import com.carenexus.api.communications.dto.response.MessageResponse;
import com.carenexus.api.communications.dto.response.ThreadMessagesResponse;
import com.carenexus.api.communications.dto.response.ThreadReadResponse;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.MessageService;
import com.carenexus.api.communications.service.SupportThreadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Messages of a conversation (clinical and SUPPORT).
 *
 * - GET  /api/communications/threads/{threadId}/messages?before=&after=&limit=30   ascending by messageId
 * - POST /api/communications/threads/{threadId}/messages   201 new / 200 when clientMessageId was already used;
 *                                                          bucket message-send
 * - POST /api/communications/threads/{threadId}/read       { lastReadMessageId }
 *
 * SUPPORT rules applied here before delegating to MessageService: an administrator joins on the first request, the
 * urgent flag is stored as false, a closed conversation refuses new messages (409) while a retry of a message stored
 * before the close still answers 200, and the opener's stored message notifies the administrators.
 */
@RestController
@RequestMapping("/api/communications/threads/{threadId}")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Messaging", description = "Conversations between patients, their caregivers and their doctors, and the "
        + "participant side of support conversations")
public class ThreadMessageController {

    static final String BUCKET_MESSAGE_SEND = "message-send";

    private final MessageService messageService;
    private final SupportThreadService supportThreadService;
    private final CommunicationsAccessService accessService;
    private final RateLimitGuard rateLimitGuard;

    @GetMapping("/messages")
    @Operation(summary = "Read the messages of a conversation",
            description = "Thread access (members who can still act; SUPPORT: the opener and administrators, who join "
                    + "on their first request). Only messages after the caller's history boundary, ascending by id. "
                    + "Audited (READ MESSAGE_THREAD) unless polling with after.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A slice of the conversation"),
            @ApiResponse(responseCode = "400", description = "Use either before or after, not both",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadMessagesResponse> listMessages(
            @PathVariable Integer threadId,
            @Parameter(description = "Messages older than this message id (newest first page of them)")
            @RequestParam(value = "before", required = false) Integer before,
            @Parameter(description = "Messages newer than this message id (polling)")
            @RequestParam(value = "after", required = false) Integer after,
            @Parameter(description = "Page size, clamped to 1..100 (default 30)")
            @RequestParam(value = "limit", required = false) Integer limit) {
        SupportJoin.joinIfAdministrator(supportThreadService, threadId);
        return ResponseEntity.ok(messageService.listMessages(threadId, before, after, limit));
    }

    @PostMapping("/messages")
    @Operation(summary = "Send a message",
            description = "Thread access. Retry-safe by clientMessageId (200 with the stored message). Rate limited per "
                    + "sender (bucket message-send). Other members who can still act are un-archived and notified; in a "
                    + "SUPPORT conversation the opener's message notifies every administrator and an administrator's "
                    + "message notifies the opener; the urgent flag is ignored there. Audited (CREATE MESSAGE).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "This clientMessageId was already stored; the stored message"),
            @ApiResponse(responseCode = "201", description = "The message was stored"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "This support conversation is closed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many messages (Retry-After header)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable Integer threadId,
                                                       @Valid @RequestBody SendMessageRequest request) {
        String senderSystemId = accessService.caller().systemId();
        rateLimitGuard.check(BUCKET_MESSAGE_SEND, senderSystemId);
        SupportJoin.joinIfAdministrator(supportThreadService, threadId);

        SupportThreadService.SendContext context = supportThreadService.sendContext(threadId);
        if (context.support()) {
            request.setUrgent(false);
            if (context.closed()) {
                // Access is checked by the lookup; only a message stored before the close is answered
                return messageService.findOwnMessageByClientId(threadId, request.getClientMessageId())
                        .map(ResponseEntity::ok)
                        .orElseThrow(() -> new ConflictException(SupportThreadService.CLOSED));
            }
        }

        MessageService.SendResult result;
        try {
            result = messageService.sendMessage(threadId, request);
        } catch (DataIntegrityViolationException race) {
            // Two concurrent sends with the same clientMessageId: the unique index rejected the second insert
            // (its transaction rolled back). Return the stored message, looked up in a new transaction.
            return messageService.findOwnMessageByClientId(threadId, request.getClientMessageId())
                    .map(existing -> {
                        log.info("Duplicate send resolved to existing message {} in thread {}", existing.getMessageId(), threadId);
                        return ResponseEntity.ok(existing);
                    })
                    .orElseThrow(() -> race);
        }

        if (result.created() && context.support()) {
            try {
                supportThreadService.notifyAdministrators(threadId, senderSystemId);
            } catch (RuntimeException notifyFailure) {
                // The message is stored and visible in the administrator inbox; a failed notification must not turn
                // the send into an error the client would retry.
                log.error("Support notification for thread {} failed: {}", threadId, notifyFailure.getClass().getName());
            }
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
    }

    @PostMapping("/read")
    @Operation(summary = "Move the caller's read position forward",
            description = "Thread access (also in a closed SUPPORT conversation). The caller's message notifications of "
                    + "this conversation up to the value are marked read; for an administrator also their support "
                    + "notifications of this conversation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The read position after the call and the remaining unread count"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadReadResponse> markRead(@PathVariable Integer threadId,
                                                       @Valid @RequestBody MarkThreadReadRequest request) {
        SupportJoin.joinIfAdministrator(supportThreadService, threadId);
        ThreadReadResponse response = messageService.markRead(threadId, request);
        supportThreadService.markAdministratorNotificationsRead(threadId);
        return ResponseEntity.ok(response);
    }
}
