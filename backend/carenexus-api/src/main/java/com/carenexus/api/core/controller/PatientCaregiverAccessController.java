package com.carenexus.api.core.controller;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.service.CaregiverService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The patient's own caregiver grants ("My caregivers"). The patient is always the caller from the JWT.
 *
 * - GET    /api/patients/me/caregiver-access             every unrevoked grant (active and expired), newest first
 * - POST   /api/patients/me/caregiver-access             grant an existing caregiver account access (201)
 * - PATCH  /api/patients/me/caregiver-access/{accessId}  change permissions / level / expiry; turning on
 *                                                        messaging or joining calls approves it
 * - DELETE /api/patients/me/caregiver-access/{accessId}  revoke (204, idempotent); ADMIN may revoke any grant
 *
 * Caregivers can never raise their own access; they use PATCH /api/caregiver/dependents/{accessId} (narrow only).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/patients/me/caregiver-access")
public class PatientCaregiverAccessController {

    private final CaregiverService caregiverService;
    private final AuthorizationService authorizationService;

    @GetMapping
    public ResponseEntity<List<CaregiverDependentResponse>> list(@RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.getCaregiverAccessForPatient(systemId));
    }

    @PostMapping
    public ResponseEntity<CaregiverDependentResponse> grant(
            @RequestAttribute("systemId") String systemId,
            @RequestBody(required = false) GrantCaregiverAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(caregiverService.grantCaregiverAccessAsPatient(systemId, request));
    }

    @PatchMapping("/{accessId}")
    public ResponseEntity<CaregiverDependentResponse> update(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @RequestBody(required = false) UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.updateCaregiverAccessAsPatient(systemId, false, accessId, request));
    }

    @DeleteMapping("/{accessId}")
    public ResponseEntity<Void> revoke(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.ADMIN);
        caregiverService.revokeCaregiverAccessAsPatient(systemId, authorizationService.isAdmin(), accessId);
        return ResponseEntity.noContent().build();
    }
}
