package com.carenexus.api.caregiveraccess.controller;

import com.carenexus.api.caregiveraccess.dto.response.CaregiverActivityEntryResponse;
import com.carenexus.api.caregiveraccess.service.CaregiverActivityService;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/patients/me/caregiver-access/{accessId}/activity: what one caregiver did with the caller's data. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Caregiver activity", description = "What a caregiver viewed or did with the patient's data")
public class CaregiverActivityController {

    private final CaregiverActivityService caregiverActivityService;

    @GetMapping("/api/patients/me/caregiver-access/{accessId}/activity")
    @Operation(summary = "List a caregiver's activity on my data",
            description = "The PATIENT who owns the grant (active, expired or revoked). Successful audit rows whose "
                    + "actor is the grant's caregiver and whose patient is the caller, of all time, newest first: "
                    + "action, resource type and time only. Audited as one READ AUDIT_LOG row.")
    @ApiResponse(responseCode = "200", description = "A page of activity entries")
    @StandardErrors
    public ResponseEntity<PageResponse<CaregiverActivityEntryResponse>> activity(
            @Parameter(description = "The grant's access id") @PathVariable Integer accessId,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(caregiverActivityService.activityForGrant(accessId, page, size));
    }
}
