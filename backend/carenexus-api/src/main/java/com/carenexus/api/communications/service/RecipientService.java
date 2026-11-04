package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
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
 * Who the caller can message about a patient (GET /api/communications/recipients).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientService {

    private final CommunicationsAccessService accessService;

    public RecipientsResponse getRecipients(String patientSystemIdParam) {
        Caller caller = accessService.requireCaller();
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
                    .canAddToCareTeam(true)
                    .build());
        }
        return RecipientsResponse.builder()
                .patientSystemId(patientSystemId)
                .patientName(patientName)
                .recipients(recipients)
                .build();
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
            throw new RuntimeException("Patient ID is required");
        }
        return patientSystemId;
    }
}
