package com.carenexus.api.communications.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.communications.dto.request.AddThreadMembersRequest;
import com.carenexus.api.communications.dto.request.ArchiveThreadRequest;
import com.carenexus.api.communications.dto.request.CreateThreadRequest;
import com.carenexus.api.communications.dto.response.RecipientsResponse;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import com.carenexus.api.communications.service.RecipientService;
import com.carenexus.api.communications.service.SupportThreadService;
import com.carenexus.api.communications.service.ThreadService;
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
 * Conversations (Care Direct + Care Proxy messaging, and the participant side of support conversations). Caller
 * identity comes from the JWT; every endpoint re-checks membership and the caller's current relationship / MESSAGING
 * permission. Administrators get 403 on recipients, the list and create, and reach SUPPORT conversations only.
 *
 * - GET    /api/communications/recipients?patientSystemId=
 * - GET    /api/communications/threads?patientSystemId=&archived=false&page=0&size=20
 * - POST   /api/communications/threads                          201 created / 200 existing; bucket thread-create
 * - GET    /api/communications/threads/{threadId}
 * - POST   /api/communications/threads/{threadId}/members       CARE_TEAM only
 * - DELETE /api/communications/threads/{threadId}/members/{systemId}   204
 * - PUT    /api/communications/threads/{threadId}/archive       204 (per-user)
 */
@RestController
@RequestMapping("/api/communications")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Messaging", description = "Conversations between patients, their caregivers and their doctors, and the "
        + "participant side of support conversations")
public class CommunicationsController {

    static final String BUCKET_THREAD_CREATE = "thread-create";

    private final RecipientService recipientService;
    private final ThreadService threadService;
    private final SupportThreadService supportThreadService;
    private final CommunicationsAccessService accessService;
    private final RateLimitGuard rateLimitGuard;

    @GetMapping("/recipients")
    @Operation(summary = "List the people the caller may message about a patient",
            description = "PATIENT (about themselves), DOCTOR related to the patient, CAREGIVER with an approved, "
                    + "unexpired MESSAGING grant. Administrators: 403. Order: the patient, related doctors, caregivers "
                    + "with an approved grant; the caller is left out; hard cap of 200 entries. Audited (READ "
                    + "MESSAGE_RECIPIENT for the patient).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The patient and the people the caller may message"),
            @ApiResponse(responseCode = "400", description = "Patient ID is required (callers other than a patient)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Administrator, or the caller cannot act for the patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RecipientsResponse> getRecipients(
            @Parameter(description = "Patient the conversation is about; optional for a patient (defaults to the caller)")
            @RequestParam(value = "patientSystemId", required = false) String patientSystemId) {
        return ResponseEntity.ok(recipientService.getRecipients(patientSystemId));
    }

    @GetMapping("/threads")
    @Operation(summary = "List the caller's conversations",
            description = "PATIENT, DOCTOR, CAREGIVER; administrators get 403 (they use GET /api/admin/support/threads). "
                    + "PRIVATE and CARE_TEAM conversations the caller is an active member of and can still act for the "
                    + "patient of; without a patientSystemId filter also the caller's own SUPPORT conversations. "
                    + "Newest activity first; size clamped to 1..50. Audited (one READ MESSAGE_THREAD row per patient "
                    + "on the page).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of conversations"),
            @ApiResponse(responseCode = "400", description = "A parameter of the wrong type",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Administrators cannot access conversations",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ThreadResponse>> listThreads(
            @Parameter(description = "Only conversations about this patient (a patient the caller cannot act for "
                    + "gives an empty page); SUPPORT conversations are left out when it is set")
            @RequestParam(value = "patientSystemId", required = false) String patientSystemId,
            @Parameter(description = "false: conversations the caller has not archived; true: only archived ones")
            @RequestParam(value = "archived", defaultValue = "false") boolean archived,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..50")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(threadService.listThreads(patientSystemId, archived, page, size));
    }

    @PostMapping("/threads")
    @Operation(summary = "Start a private or care team conversation",
            description = "PATIENT (about themselves), DOCTOR related to the patient, CAREGIVER with an approved, "
                    + "unexpired MESSAGING grant; administrators get 403. Returns the existing conversation (200) for a "
                    + "repeated clientThreadId or an existing private conversation of the same pair; otherwise 201. "
                    + "Rate limited per caller (bucket thread-create, every call counts). Audited (CREATE MESSAGE_THREAD).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "An existing conversation was returned"),
            @ApiResponse(responseCode = "201", description = "The conversation was created"),
            @ApiResponse(responseCode = "400", description = "Validation failed, wrong conversation shape or an ineligible member",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Administrator, or the caller cannot act for the patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many conversations started (Retry-After header)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadResponse> createThread(@Valid @RequestBody CreateThreadRequest request) {
        rateLimitGuard.check(BUCKET_THREAD_CREATE, accessService.caller().systemId());
        try {
            ThreadService.ThreadResult result = threadService.createThread(request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.thread());
        } catch (DataIntegrityViolationException race) {
            // Two concurrent creates with the same clientThreadId: ux_message_threads_client_thread rejected the
            // second insert (its transaction rolled back). Return the stored thread, looked up in a new transaction.
            String clientThreadId = request != null ? request.getClientThreadId() : null;
            return threadService.findOwnThreadByClientId(clientThreadId)
                    .map(existing -> {
                        log.info("Duplicate create resolved to existing thread {}", existing.getThreadId());
                        return ResponseEntity.ok(existing);
                    })
                    .orElseThrow(() -> race);
        }
    }

    @GetMapping("/threads/{threadId}")
    @Operation(summary = "Get one conversation",
            description = "Active members who can still act for the patient; for a SUPPORT conversation its opener and "
                    + "administrators. An administrator's first request to a SUPPORT conversation makes them a member "
                    + "(audited UPDATE SUPPORT_THREAD); administrators get 403 on every other conversation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The conversation"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadResponse> getThread(@PathVariable Integer threadId) {
        SupportJoin.joinIfAdministrator(supportThreadService, threadId);
        return ResponseEntity.ok(threadService.getThread(threadId));
    }

    @PostMapping("/threads/{threadId}/members")
    @Operation(summary = "Add people to a care team conversation",
            description = "The patient or a doctor member; CARE_TEAM conversations only. New and re-added members see "
                    + "only messages sent after they were added. Audited (UPDATE MESSAGE_THREAD).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated conversation"),
            @ApiResponse(responseCode = "400", description = "Validation failed or a person is not eligible",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access, or a caregiver member",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Private or support conversation (members cannot be changed)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadResponse> addMembers(@PathVariable Integer threadId,
                                                     @Valid @RequestBody AddThreadMembersRequest request) {
        return ResponseEntity.ok(threadService.addMembers(threadId, request));
    }

    @DeleteMapping("/threads/{threadId}/members/{systemId}")
    @Operation(summary = "Remove a member from a care team conversation, or leave it",
            description = "Leaving: any doctor or caregiver member. Removing someone else: the patient or a doctor member. "
                    + "CARE_TEAM conversations only; idempotent for a member who is already gone. Audited (UPDATE "
                    + "MESSAGE_THREAD).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed (or already gone)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access, or a caregiver removing someone else",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation or member not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Private or support conversation, the patient, or the last doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> removeMember(@PathVariable Integer threadId, @PathVariable String systemId) {
        threadService.removeMember(threadId, systemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/threads/{threadId}/archive")
    @Operation(summary = "Archive or restore a conversation for the caller",
            description = "Any member with access; the flag is the caller's own and is cleared by a new message from "
                    + "someone else. In a SUPPORT conversation only the opener may archive (administrators: 409).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Stored"),
            @ApiResponse(responseCode = "400", description = "archived: must be true or false",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Support conversations are closed, not archived (administrators)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> setArchived(@PathVariable Integer threadId,
                                            @Valid @RequestBody ArchiveThreadRequest request) {
        threadService.setArchived(threadId, request);
        return ResponseEntity.noContent().build();
    }
}
