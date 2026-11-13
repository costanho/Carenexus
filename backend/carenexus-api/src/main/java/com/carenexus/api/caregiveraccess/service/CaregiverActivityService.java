package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.dto.response.CaregiverActivityEntryResponse;
import com.carenexus.api.caregiveraccess.repository.AccessGrantRepository;
import com.carenexus.api.caregiveraccess.repository.CaregiverActivityRepository;
import com.carenexus.api.caregiveraccess.repository.CaregiverActivityRepository.ActivityRow;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.model.DependentAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the caregiver of one grant viewed or did with the caller's data: successful audit_log rows whose actor is
 * that caregiver and whose patient is the caller, of all time (earlier periods of a reused grant included). Built on
 * the existing audit trail; the legacy caregiver_activity_log table is not used.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverActivityService {

    static final String RESOURCE = "AUDIT_LOG";
    static final String GRANT_RESOURCE = "DEPENDENT_ACCESS";
    static final String NOT_FOUND_MESSAGE = "Access record not found";
    static final String NOT_OWNER_MESSAGE = "Not authorized to view this access record";

    private final AccessGrantRepository accessGrantRepository;
    private final CaregiverActivityRepository caregiverActivityRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /**
     * PATIENT only, on a grant of their own in any state (active, expired, revoked). Newest first. Audited as one
     * READ AUDIT_LOG row about the caller (the actor is the patient, so it never appears in the feed itself).
     */
    public PageResponse<CaregiverActivityEntryResponse> activityForGrant(Integer accessId, Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        DependentAccess grant = accessGrantRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        if (!patientSystemId.equals(grant.getPatientSystemId())) {
            throw new AuthorizationException(NOT_OWNER_MESSAGE, GRANT_RESOURCE, accessId);
        }
        Page<ActivityRow> rows = caregiverActivityRepository.findActivity(grant.getCaregiverSystemId(), patientSystemId,
                PageParams.of(page, size, Sort.unsorted()));
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return PageResponse.of(rows, row -> new CaregiverActivityEntryResponse(
                row.getAuditId(), row.getAction(), row.getResourceType(), row.getOccurredAt()));
    }
}
