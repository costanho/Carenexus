package com.carenexus.api.communications.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.communications.dto.response.SupportThreadResponse;
import com.carenexus.api.communications.service.SupportThreadService;
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
 * Administrator support inbox. Route security limits /api/admin/** to ADMIN; the service checks the role again.
 * Administrators read and answer a conversation with the normal conversation endpoints
 * (/api/communications/threads/{threadId}/...), which make them a member on the first request.
 *
 * - GET  /api/admin/support/threads?open=&page=0&size=20
 * - POST /api/admin/support/threads/{threadId}/close
 */
@RestController
@RequestMapping("/api/admin/support/threads")
@RequiredArgsConstructor
@Tag(name = "Support", description = "Support conversations between users and the clinic's administrators")
public class AdminSupportController {

    private final SupportThreadService supportThreadService;

    @GetMapping
    @Operation(summary = "List support conversations, newest activity first",
            description = "ADMIN only. Every support conversation with at least one message, whoever opened it and "
                    + "whether or not the caller joined it; unread is relative to the calling administrator. Size clamped "
                    + "to 1..100. Listing neither joins nor marks anything read. Audited (one READ SUPPORT_THREAD row "
                    + "per request).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of support conversations"),
            @ApiResponse(responseCode = "400", description = "A parameter of the wrong type",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<SupportThreadResponse>> listSupportThreads(
            @Parameter(description = "Omit for open and closed conversations; true: only open; false: only closed")
            @RequestParam(value = "open", required = false) Boolean open,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(supportThreadService.listForAdministrators(open, page, size));
    }

    @PostMapping("/{threadId}/close")
    @Operation(summary = "Close a support conversation",
            description = "ADMIN only, member of the conversation or not. The conversation stays readable and refuses new "
                    + "messages; the opener can open a new one. Closing a closed conversation changes nothing. Nobody is "
                    + "notified. Audited (UPDATE SUPPORT_THREAD) when the state changes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The conversation, closed"),
            @ApiResponse(responseCode = "400", description = "A parameter of the wrong type",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversation not found (unknown id or not a support conversation)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SupportThreadResponse> close(@PathVariable Integer threadId) {
        return ResponseEntity.ok(supportThreadService.close(threadId));
    }
}
