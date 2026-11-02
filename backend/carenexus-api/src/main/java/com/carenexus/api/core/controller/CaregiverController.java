package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.service.CaregiverService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/caregiver")
@RequiredArgsConstructor
public class CaregiverController {

    private final CaregiverService caregiverService;

    @GetMapping("/dependents")
    public ResponseEntity<List<CaregiverDependentResponse>> getDependents(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(caregiverService.getDependentsForCaregiver(systemId));
    }

    @GetMapping("/dependents/activity")
    public ResponseEntity<CaregiverDependentActivityResponse> getDependentActivity(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(caregiverService.getActivityForDependents(systemId));
    }

    @PatchMapping("/dependents/{accessId}")
    public ResponseEntity<CaregiverDependentResponse> updateDependentAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @RequestBody UpdateDependentAccessRequest request) {
        return ResponseEntity.ok(caregiverService.updateDependentAccess(systemId, accessId, request));
    }
}
