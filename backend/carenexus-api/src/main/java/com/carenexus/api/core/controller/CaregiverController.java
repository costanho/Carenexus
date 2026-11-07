package com.carenexus.api.core.controller;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.service.CaregiverService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Caregiver (proxy) access.
 *
 * Caregiver side (role CAREGIVER, caregiver = caller from the JWT):
 * - GET   /api/caregiver/dependents              active dependents
 * - GET   /api/caregiver/dependents/activity     appointments + consultations the grants allow
 * - POST  /api/caregiver/dependents              create a dependent patient account + grant (atomic, 201)
 * - PATCH /api/caregiver/dependents/{accessId}   narrow own grant only (403 on any widening)
 *
 * Patient side, legacy (role PATIENT for their own grants, or ADMIN). The new patient screen uses
 * /api/patients/me/caregiver-access (PatientCaregiverAccessController); both share CaregiverService rules:
 * - GET    /api/patients/me/caregivers             caregivers with active access to me
 * - PATCH  /api/patients/me/caregivers/{accessId}  change permissions / level / expiry (only the patient's own
 *                                                  change approves messaging / joining calls; admin never does)
 * - DELETE /api/patients/me/caregivers/{accessId}  revoke (204)
 *
 * Communication permissions (canMessageCareTeam, canJoinCalls) are never raised by a caregiver: createDependent
 * stores them as unapproved requests and the caregiver PATCH rejects turning them on.
 */
@RestController
@RequiredArgsConstructor
public class CaregiverController {

    private final CaregiverService caregiverService;
    private final AuthorizationService authorizationService;

    @GetMapping("/api/caregiver/dependents")
    public ResponseEntity<List<CaregiverDependentResponse>> getDependents(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.getDependentsForCaregiver(systemId));
    }

    @GetMapping("/api/caregiver/dependents/activity")
    public ResponseEntity<CaregiverDependentActivityResponse> getDependentActivity(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.getActivityForDependents(systemId));
    }

    @PostMapping("/api/caregiver/dependents")
    public ResponseEntity<CaregiverDependentResponse> createDependent(
            @RequestAttribute("systemId") String systemId,
            @RequestBody CreateDependentRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.status(HttpStatus.CREATED).body(caregiverService.createDependent(systemId, request));
    }

    @PatchMapping("/api/caregiver/dependents/{accessId}")
    public ResponseEntity<CaregiverDependentResponse> updateDependentAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @RequestBody UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.updateDependentAccess(systemId, accessId, request));
    }

    @GetMapping("/api/patients/me/caregivers")
    public ResponseEntity<List<CaregiverDependentResponse>> getMyCaregivers(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.getCaregiversForPatient(systemId));
    }

    @PatchMapping("/api/patients/me/caregivers/{accessId}")
    public ResponseEntity<CaregiverDependentResponse> updateMyCaregiverAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @RequestBody UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.ADMIN);
        return ResponseEntity.ok(caregiverService.updateCaregiverAccessAsPatient(
                systemId, authorizationService.isAdmin(), accessId, request));
    }

    @DeleteMapping("/api/patients/me/caregivers/{accessId}")
    public ResponseEntity<Void> revokeMyCaregiverAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.ADMIN);
        caregiverService.revokeCaregiverAccessAsPatient(systemId, authorizationService.isAdmin(), accessId);
        return ResponseEntity.noContent().build();
    }
}
