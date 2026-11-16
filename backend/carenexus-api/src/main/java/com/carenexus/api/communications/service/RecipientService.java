package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.communications.dto.response.RecipientResponse;
import com.carenexus.api.communications.dto.response.RecipientsResponse;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who the caller can message about a patient (GET /api/communications/recipients). Not paginated: an array with a
 * documented hard cap of {@value #MAX_RECIPIENTS} entries (the patient, related doctors, then caregivers with an
 * approved MESSAGING grant, in that order). Administrators are never listed and never call it (403). Audited as
 * READ MESSAGE_RECIPIENT for the patient.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientService {

    /** Hard cap of the recipients array. */
    public static final int MAX_RECIPIENTS = 200;
    static final String RESOURCE = "MESSAGE_RECIPIENT";

    private final CommunicationsAccessService accessService;
    private final AuditService auditService;

    public RecipientsResponse getRecipients(String patientSystemIdParam) {
        Caller caller = accessService.requireMessagingCaller();
        String patientSystemId = resolvePatient(caller, patientSystemIdParam);
        accessService.requireCanActFor(patientSystemId);

        Map<String, User> eligible = accessService.eligibleMembers(patientSystemId);
        User patient = eligible.get(patientSystemId);
        if (patient == null) {
            patient = accessService.usersById(List.of(patientSystemId)).get(patientSystemId);
        }
        String patientName = CommunicationLabels.name(patient, patientSystemId);

        List<RecipientResponse> recipients = new ArrayList<>();
        for (User user : eligible.values()) {
            if (recipients.size() >= MAX_RECIPIENTS) {
                break;
            }
            if (user.getSystemId().equals(caller.systemId())) {
                continue;
            }
            String role = CommunicationLabels.role(user);
            String name = CommunicationLabels.name(user, user.getSystemId());
            recipients.add(RecipientResponse.builder()
                    .systemId(user.getSystemId())
                    .name(name)
                    .role(role)
                    .label(CommunicationLabels.label(name, role, patientName))
                    .canStartPrivate(isPrivatePairAllowed(caller.role(), role))
                    // May be included when the caller STARTS a care team conversation (any role that can act for P)
                    .canAddToCareTeam(true)
                    // May be added to an EXISTING care team conversation: only patients and doctors manage members
                    // (ThreadService.addMembers answers 403 for caregivers)
                    .canAddToExistingCareTeam(caller.canManageMembers())
                    .build());
        }
        RecipientsResponse response = RecipientsResponse.builder()
                .patientSystemId(patientSystemId)
                .patientName(patientName)
                .recipients(recipients)
                .build();
        // One READ row for the patient whose circle was listed (also when the list is empty)
        auditService.logListRead(RESOURCE, List.of(response), RecipientsResponse::getPatientSystemId);
        return response;
    }

    /** PRIVATE = one DOCTOR and the patient, or one DOCTOR and one of the patient's caregivers. */
    static boolean isPrivatePairAllowed(String roleA, String roleB) {
        return (AuthorizationService.DOCTOR.equals(roleA) && isPatientSide(roleB))
                || (AuthorizationService.DOCTOR.equals(roleB) && isPatientSide(roleA));
    }

    private static boolean isPatientSide(String role) {
        return AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role);
    }

    static String resolvePatient(Caller caller, String patientSystemIdParam) {
        String patientSystemId = patientSystemIdParam != null ? patientSystemIdParam.trim() : "";
        if (patientSystemId.isEmpty()) {
            if (caller.is(AuthorizationService.PATIENT)) {
                return caller.systemId();
            }
            throw new BadRequestException(ThreadService.PATIENT_REQUIRED);
        }
        return patientSystemId;
    }
}
