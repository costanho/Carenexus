package com.carenexus.api.account.datarights.controller;

import com.carenexus.api.account.datarights.dto.request.RejectAccountRequest;
import com.carenexus.api.account.datarights.dto.response.AdminAccountRequestResponse;
import com.carenexus.api.account.datarights.service.AccountRequestAdminService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account requests for administrators (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET  /api/admin/account-requests?type=&status=&page=&size=  every request, newest first (paged)
 * - GET  /api/admin/account-requests/{id}                        one request (notification deep link)
 * - POST /api/admin/account-requests/{id}/approve                approve a deletion request (anonymises the account)
 * - POST /api/admin/account-requests/{id}/reject                 decline a deletion request
 */
@RestController
@RequestMapping("/api/admin/account-requests")
@RequiredArgsConstructor
@Tag(name = "Admin: account requests", description = "Data exports, closures and deletion requests of every user; "
        + "decisions on deletion requests")
public class AccountRequestAdminController {

    private final AccountRequestAdminService service;

    @GetMapping
    @Operation(summary = "List account requests",
            description = "ADMIN only. Exports, closures and deletion requests of all users, newest first (fixed "
                    + "order), filtered in the database by the optional type and status (trimmed, any case). The queue "
                    + "that needs a decision is type=DELETION&status=REQUESTED. Size is clamped to 1..100. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of requests"),
            @ApiResponse(responseCode = "400", description = "Unknown type or status, or page/size not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<AdminAccountRequestResponse>> list(
            @Parameter(description = "EXPORT, CLOSURE or DELETION; blank = all") @RequestParam(required = false) String type,
            @Parameter(description = "REQUESTED, PROCESSING, READY, COMPLETED, REJECTED or CANCELLED; blank = all")
            @RequestParam(required = false) String status,
            @Parameter(description = "Zero-based page; negative is treated as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.list(type, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one account request",
            description = "ADMIN only. Used by the deep link of the new-deletion-request notification. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request with its account"),
            @ApiResponse(responseCode = "400", description = "The id is not an integer", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No request with this id", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AdminAccountRequestResponse> get(
            @Parameter(description = "requestId") @PathVariable("id") Integer id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a deletion request",
            description = "ADMIN only, never for the administrator's own request. Anonymises the account: name becomes "
                    + "Deleted User, email, phone and password are removed, the account is inactive and closed; a "
                    + "doctor also leaves the directory. Nothing else is changed or deleted: clinical records, messages "
                    + "and audit rows are retained. Revokes every session, publishes UserDeactivatedEvent (cause "
                    + "CLOSURE) when the account was still active, queues the email account-closed-deletion to the "
                    + "former address and writes the audit row ACCOUNT_DELETION_APPROVE / ACCOUNT. Already approved: "
                    + "returned unchanged. No body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request after the change"),
            @ApiResponse(responseCode = "400", description = "The id is not an integer", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No request with this id", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not a deletion request, already rejected or cancelled, or "
                    + "the administrator's own request", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AdminAccountRequestResponse> approve(
            @Parameter(description = "requestId of a deletion request") @PathVariable("id") Integer id) {
        return ResponseEntity.ok(service.approve(id));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Decline a deletion request",
            description = "ADMIN only, never for the administrator's own request. The account is not changed. The "
                    + "reason is stored as decisionReason and shown to the user, who receives a SYSTEM notification. "
                    + "Audit row ACCOUNT_DELETION_REJECT / ACCOUNT. Already rejected: returned unchanged (the first "
                    + "reason is kept).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request after the change"),
            @ApiResponse(responseCode = "400", description = "The id is not an integer, malformed body or invalid reason "
                    + "(VALIDATION_FAILED)", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No request with this id", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not a deletion request, already approved or cancelled, or "
                    + "the administrator's own request", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AdminAccountRequestResponse> reject(
            @Parameter(description = "requestId of a deletion request") @PathVariable("id") Integer id,
            @Valid @RequestBody RejectAccountRequest body) {
        return ResponseEntity.ok(service.reject(id, body));
    }
}
