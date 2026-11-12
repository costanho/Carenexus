package com.carenexus.api.caregiveraccess.controller;

import com.carenexus.api.caregiveraccess.dto.response.ConsentRecordResponse;
import com.carenexus.api.caregiveraccess.service.ConsentLedgerService;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/patients/me/consents: the caller's consent ledger (PATIENT only). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Consent ledger",
        description = "One immutable record per change of a caregiver's access to the patient, with a snapshot of the "
                + "access after the change")
public class ConsentLedgerController {

    private final ConsentLedgerService consentLedgerService;

    @GetMapping("/api/patients/me/consents")
    @Operation(summary = "List my consent ledger",
            description = "PATIENT only. One row per lifecycle step of every caregiver grant about the caller "
                    + "(granted, permissions changed, communication approved, extended, narrowed by the caregiver, "
                    + "revoked, expired), each with a snapshot of the grant right after the step, newest first. "
                    + "accessId narrows it to one grant (an id that is not the caller's gives an empty page). "
                    + "Audited as one READ CONSENT row when the page is not empty.")
    @ApiResponse(responseCode = "200", description = "A page of ledger rows")
    @StandardErrors
    public ResponseEntity<PageResponse<ConsentRecordResponse>> list(
            @Parameter(description = "Only the rows of this grant") @RequestParam(required = false) Integer accessId,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(consentLedgerService.listForCaller(accessId, page, size));
    }
}
