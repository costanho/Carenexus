package com.carenexus.api.communications.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.communications.dto.request.CreateSupportThreadRequest;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.service.SupportThreadService;
import com.carenexus.api.communications.service.ThreadService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contact support: POST /api/communications/support-thread returns the caller's open support conversation (200) or
 * creates one (201). The conversation is then used with the normal conversation endpoints.
 */
@RestController
@RequestMapping("/api/communications/support-thread")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Support", description = "Support conversations between users and the clinic's administrators")
public class SupportThreadController {

    private final SupportThreadService supportThreadService;

    @PostMapping
    @Operation(summary = "Open the caller's support conversation",
            description = "PATIENT, CAREGIVER or DOCTOR, for themselves; no patient context and no caregiver grant is "
                    + "involved. Returns the caller's open support conversation (200, its archive flag cleared, the "
                    + "subject ignored) or creates one with the caller as its only member (201). Body optional. Nobody "
                    + "is notified until the first message. Administrators get 403. Not rate limited (at most one open "
                    + "conversation per user). Audited on create (CREATE SUPPORT_THREAD).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's open support conversation"),
            @ApiResponse(responseCode = "201", description = "A support conversation was created"),
            @ApiResponse(responseCode = "400", description = "subject: must be at most 200 characters",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Administrators cannot open a support conversation",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ThreadResponse> openSupportThread(
            @Valid @RequestBody(required = false) CreateSupportThreadRequest request) {
        try {
            ThreadService.ThreadResult result = supportThreadService.openOrGet(request);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.thread());
        } catch (DataIntegrityViolationException race) {
            // Two concurrent first calls: ux_message_threads_open_support rejected the second insert (its transaction
            // rolled back). Answer with the winner's thread, looked up in a new transaction.
            return supportThreadService.findOpenOwn()
                    .map(existing -> {
                        log.info("Concurrent support open resolved to existing thread {}", existing.getThreadId());
                        return ResponseEntity.ok(existing);
                    })
                    .orElseThrow(() -> race);
        }
    }
}
