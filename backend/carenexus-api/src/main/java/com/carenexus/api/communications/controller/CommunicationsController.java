package com.carenexus.api.communications.controller;

import com.carenexus.api.communications.dto.request.AddThreadMembersRequest;
import com.carenexus.api.communications.dto.request.ArchiveThreadRequest;
import com.carenexus.api.communications.dto.request.CreateThreadRequest;
import com.carenexus.api.communications.dto.response.PageResponse;
import com.carenexus.api.communications.dto.response.RecipientsResponse;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.service.RecipientService;
import com.carenexus.api.communications.service.ThreadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Conversations (Care Direct + Care Proxy messaging). Caller identity comes from the JWT; every endpoint re-checks
 * membership and the caller's current relationship / MESSAGING permission. Administrators get 403.
 *
 * - GET    /api/communications/recipients?patientSystemId=
 * - GET    /api/communications/threads?patientSystemId=&archived=false&page=0&size=20
 * - POST   /api/communications/threads                          201 created / 200 existing
 * - GET    /api/communications/threads/{threadId}
 * - POST   /api/communications/threads/{threadId}/members       CARE_TEAM only
 * - DELETE /api/communications/threads/{threadId}/members/{systemId}   204
 * - PUT    /api/communications/threads/{threadId}/archive       204 (per-user)
 */
@RestController
@RequestMapping("/api/communications")
@RequiredArgsConstructor
public class CommunicationsController {

    private final RecipientService recipientService;
    private final ThreadService threadService;

    @GetMapping("/recipients")
    public ResponseEntity<RecipientsResponse> getRecipients(
            @RequestParam(value = "patientSystemId", required = false) String patientSystemId) {
        return ResponseEntity.ok(recipientService.getRecipients(patientSystemId));
    }

    @GetMapping("/threads")
    public ResponseEntity<PageResponse<ThreadResponse>> listThreads(
            @RequestParam(value = "patientSystemId", required = false) String patientSystemId,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(threadService.listThreads(patientSystemId, archived, page, size));
    }

    @PostMapping("/threads")
    public ResponseEntity<ThreadResponse> createThread(@RequestBody CreateThreadRequest request) {
        ThreadService.ThreadResult result = threadService.createThread(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.thread());
    }

    @GetMapping("/threads/{threadId}")
    public ResponseEntity<ThreadResponse> getThread(@PathVariable Integer threadId) {
        return ResponseEntity.ok(threadService.getThread(threadId));
    }

    @PostMapping("/threads/{threadId}/members")
    public ResponseEntity<ThreadResponse> addMembers(@PathVariable Integer threadId,
                                                     @RequestBody AddThreadMembersRequest request) {
        return ResponseEntity.ok(threadService.addMembers(threadId, request));
    }

    @DeleteMapping("/threads/{threadId}/members/{systemId}")
    public ResponseEntity<Void> removeMember(@PathVariable Integer threadId, @PathVariable String systemId) {
        threadService.removeMember(threadId, systemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/threads/{threadId}/archive")
    public ResponseEntity<Void> setArchived(@PathVariable Integer threadId, @RequestBody ArchiveThreadRequest request) {
        threadService.setArchived(threadId, request);
        return ResponseEntity.noContent().build();
    }
}
