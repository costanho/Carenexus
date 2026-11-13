package com.carenexus.api.account.datarights.controller;

import com.carenexus.api.account.datarights.dto.request.CloseAccountRequest;
import com.carenexus.api.account.datarights.dto.request.DataExportRequest;
import com.carenexus.api.account.datarights.dto.request.DeletionRequest;
import com.carenexus.api.account.datarights.dto.response.AccountClosureResponse;
import com.carenexus.api.account.datarights.dto.response.AccountRequestResponse;
import com.carenexus.api.account.datarights.service.AccountDataRightsService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's own data rights. Every signed-in role (PATIENT, DOCTOR, CAREGIVER, ADMIN) may call these for their
 * OWN account only; identity comes from the access token, never from the body or the path.
 *
 * - POST /api/users/me/data-export                      build and store a JSON export of the caller's data
 * - GET  /api/users/me/data-export                      the caller's exports with their status
 * - GET  /api/users/me/data-export/{id}/download        download one of the caller's exports
 * - POST /api/users/me/close                            close the caller's account now
 * - POST /api/users/me/deletion-request                 ask an administrator to delete the account
 * - GET  /api/users/me/deletion-request                 the caller's deletion requests
 * - POST /api/users/me/deletion-request/{id}/cancel     withdraw a pending deletion request
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
@Tag(name = "Account: privacy and data", description = "Data export, account closure and deletion requests of the "
        + "caller's own account")
public class AccountDataRightsController {

    private final AccountDataRightsService service;

    @PostMapping("/data-export")
    @Operation(summary = "Export my data",
            description = "Any signed-in user, own account only. Re-authenticates with the password (bucket "
                    + "password-change, 5 per 15 minutes), then builds a JSON file of the caller's own data, stores it "
                    + "for 7 days and answers READY. Earlier exports become COMPLETED and their files are deleted. "
                    + "Limited to 3 accepted exports per 24 hours (bucket data-export). Queues the email "
                    + "account-export-ready, sends a SYSTEM notification and writes the audit row "
                    + "DATA_EXPORT_REQUEST / DATA_EXPORT. A wrong password is 400 PASSWORD_INCORRECT (audited as FAILURE).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The export is ready to download"),
            @ApiResponse(responseCode = "400", description = "Malformed body, invalid password field "
                    + "(VALIDATION_FAILED) or wrong password (PASSWORD_INCORRECT)", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Another export of the caller is being prepared",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many password confirmations or exports (RATE_LIMITED, "
                    + "Retry-After header)", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountRequestResponse> requestExport(@Valid @RequestBody DataExportRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestExport(body));
    }

    @GetMapping("/data-export")
    @Operation(summary = "List my data exports",
            description = "Any signed-in user, own rows only. A plain array newest first, at most 20 rows. At most one "
                    + "row is downloadable and it is the first. Read-only, not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's exports (empty when none)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<AccountRequestResponse>> listExports() {
        return ResponseEntity.ok(service.listExports());
    }

    @GetMapping("/data-export/{id}/download")
    @Operation(summary = "Download one of my data exports",
            description = "Owner only: any other caller, administrators included, gets 404. Streams the JSON file as "
                    + "an attachment (never a redirect). Repeatable until the export expires; every download writes "
                    + "the audit row DATA_EXPORT_DOWNLOAD / DATA_EXPORT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The export file", content = @Content(
                    mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "The id is not an integer", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No export of the caller with this id", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The export is not ready yet, expired or its file is gone",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "File storage unavailable (PROVIDER_UNAVAILABLE)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<InputStreamResource> downloadExport(
            @Parameter(description = "requestId of one of the caller's exports") @PathVariable("id") Integer id) {
        AccountDataRightsService.ExportDownload download = service.openExport(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename(download.fileName()).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    @PostMapping("/close")
    @Operation(summary = "Close my account",
            description = "Any signed-in user, own account only; refused (409) for the last active administrator. "
                    + "Re-authenticates with the password (bucket password-change). Deactivates the account at once "
                    + "(closed_at and closure_reason set; a doctor also leaves the directory), revokes every session, "
                    + "publishes UserDeactivatedEvent (cause CLOSURE), queues the email account-closed and writes the "
                    + "audit row ACCOUNT_CLOSE / ACCOUNT. Clinical records, messages and audit rows are retained. The "
                    + "access token stops working immediately; a later sign-in is refused with ACCOUNT_CLOSED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account is closed"),
            @ApiResponse(responseCode = "400", description = "Malformed body, invalid field (VALIDATION_FAILED) or "
                    + "wrong password (PASSWORD_INCORRECT)", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The caller is the only active administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many password confirmations (RATE_LIMITED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountClosureResponse> closeAccount(@Valid @RequestBody CloseAccountRequest body) {
        return ResponseEntity.ok(service.closeAccount(body));
    }

    @PostMapping("/deletion-request")
    @Operation(summary = "Ask for my account to be deleted",
            description = "Any signed-in user, own account only. Re-authenticates with the password (bucket "
                    + "password-change). Files a request for administrator review; nothing is deleted or deactivated "
                    + "until an administrator approves. Every other active administrator gets a SYSTEM notification. "
                    + "Audit row ACCOUNT_DELETION_REQUEST / ACCOUNT.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The request awaits review"),
            @ApiResponse(responseCode = "400", description = "Malformed body, invalid field (VALIDATION_FAILED) or "
                    + "wrong password (PASSWORD_INCORRECT)", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A deletion request of the caller already awaits review",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many password confirmations (RATE_LIMITED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountRequestResponse> requestDeletion(@Valid @RequestBody DeletionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestDeletion(body));
    }

    @GetMapping("/deletion-request")
    @Operation(summary = "List my deletion requests",
            description = "Any signed-in user, own rows only. A plain array newest first, at most 20 rows; a pending "
                    + "request is always the first element. Read-only, not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's deletion requests (empty when none)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<AccountRequestResponse>> listDeletionRequests() {
        return ResponseEntity.ok(service.listDeletionRequests());
    }

    @PostMapping("/deletion-request/{id}/cancel")
    @Operation(summary = "Withdraw my pending deletion request",
            description = "Owner only (another user's request is 404). No password and no body. REQUESTED becomes "
                    + "CANCELLED with audit row ACCOUNT_DELETION_CANCEL / ACCOUNT; an already cancelled request is "
                    + "returned unchanged; a decided request is 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request after the change"),
            @ApiResponse(responseCode = "400", description = "The id is not an integer", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No deletion request of the caller with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The request has already been decided", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountRequestResponse> cancelDeletion(
            @Parameter(description = "requestId of one of the caller's deletion requests") @PathVariable("id") Integer id) {
        return ResponseEntity.ok(service.cancelDeletion(id));
    }
}
