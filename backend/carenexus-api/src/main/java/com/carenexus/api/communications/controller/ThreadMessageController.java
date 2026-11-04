package com.carenexus.api.communications.controller;

import com.carenexus.api.communications.dto.request.MarkThreadReadRequest;
import com.carenexus.api.communications.dto.request.SendMessageRequest;
import com.carenexus.api.communications.dto.response.MessageResponse;
import com.carenexus.api.communications.dto.response.ThreadMessagesResponse;
import com.carenexus.api.communications.dto.response.ThreadReadResponse;
import com.carenexus.api.communications.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Messages of a conversation.
 *
 * - GET  /api/communications/threads/{threadId}/messages?before=&after=&limit=30   ascending by messageId
 * - POST /api/communications/threads/{threadId}/messages   201 new / 200 when clientMessageId was already used
 * - POST /api/communications/threads/{threadId}/read       { lastReadMessageId }
 */
@RestController
@RequestMapping("/api/communications/threads/{threadId}")
@RequiredArgsConstructor
@Slf4j
public class ThreadMessageController {

    private final MessageService messageService;

    @GetMapping("/messages")
    public ResponseEntity<ThreadMessagesResponse> listMessages(
            @PathVariable Integer threadId,
            @RequestParam(value = "before", required = false) Integer before,
            @RequestParam(value = "after", required = false) Integer after,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(messageService.listMessages(threadId, before, after, limit));
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable Integer threadId,
                                                       @RequestBody SendMessageRequest request) {
        try {
            MessageService.SendResult result = messageService.sendMessage(threadId, request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
        } catch (DataIntegrityViolationException race) {
            // Two concurrent sends with the same clientMessageId: the unique index rejected the second insert
            // (its transaction rolled back). Return the stored message, looked up in a new transaction.
            String clientMessageId = request != null ? request.getClientMessageId() : null;
            return messageService.findOwnMessageByClientId(threadId, clientMessageId)
                    .map(existing -> {
                        log.info("Duplicate send resolved to existing message {} in thread {}", existing.getMessageId(), threadId);
                        return ResponseEntity.ok(existing);
                    })
                    .orElseThrow(() -> race);
        }
    }

    @PostMapping("/read")
    public ResponseEntity<ThreadReadResponse> markRead(@PathVariable Integer threadId,
                                                       @RequestBody MarkThreadReadRequest request) {
        return ResponseEntity.ok(messageService.markRead(threadId, request));
    }
}
