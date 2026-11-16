package com.carenexus.api.corrections.service;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.corrections.model.CorrectionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-app notifications about correction requests. All are category SYSTEM (never switched off by preferences),
 * type SYSTEM, priority NORMAL, target CORRECTIONS, resource CORRECTION_REQUEST, with the patient set; the
 * publisher computes the link per recipient role. Texts are generic: who, what kind of record, whose. They never
 * contain the requested text, the resolution note or any clinical value.
 *
 * Runs inside the caller's transaction (the publisher joins it), so a rollback removes the notifications too.
 */
@Component
@RequiredArgsConstructor
class CorrectionNotifier {

    static final String RESOURCE_TYPE = "CORRECTION_REQUEST";

    private final NotificationPublisher publisher;
    private final AuthorizationService authorizationService;

    /** A new request reached its assignee (the authoring doctor). */
    void created(CorrectionRequest request, String requesterName, String patientName, String actorSystemId) {
        publisher.publish(base(request, actorSystemId)
                .recipientSystemId(request.getAssignedDoctorSystemId())
                .title("New correction request")
                .message(requesterName + " asked for a correction to " + recordOf(request, patientName) + ".")
                .dedupeKey("CORRECTION_CREATED:" + request.getRequestId())
                .build());
    }

    /** A new request has no doctor (the author is inactive or missing): every active administrator is told. */
    void unassigned(CorrectionRequest request, List<String> adminSystemIds, String requesterName, String patientName,
                    String actorSystemId) {
        for (String adminSystemId : adminSystemIds) {
            publisher.publish(base(request, actorSystemId)
                    .recipientSystemId(adminSystemId)
                    .title("Correction request needs a doctor")
                    .message(requesterName + " asked for a correction to " + recordOf(request, patientName)
                            + ". The doctor who wrote it is not available, so it needs to be assigned.")
                    .dedupeKey("CORRECTION_UNASSIGNED:" + request.getRequestId())
                    .build());
        }
    }

    /** An administrator assigned (or reassigned) the request to a doctor. */
    void assigned(CorrectionRequest request, String patientName, String actorSystemId) {
        publisher.publish(base(request, actorSystemId)
                .recipientSystemId(request.getAssignedDoctorSystemId())
                .title("Correction request assigned to you")
                .message("A correction request for " + phrase(request.getResourceType()) + " of " + patientName
                        + " was assigned to you.")
                .dedupeKey("CORRECTION_ASSIGNED:" + request.getRequestId() + ":" + request.getAssignedDoctorSystemId())
                .build());
    }

    /**
     * The request was accepted or rejected: the requester is told. A caregiver who no longer holds an active grant
     * with RECORDS for the patient is not told; the patient is told instead.
     */
    void resolved(CorrectionRequest request, String patientName, String actorSystemId) {
        boolean accepted = CorrectionRequest.ACCEPTED.equals(request.getStatus());
        String outcome = accepted ? "accepted" : "rejected";
        String requester = request.getRequestedBySystemId();
        String patient = request.getPatientSystemId();

        String recipient;
        String message;
        if (requester.equals(patient)
                || authorizationService.hasCaregiverPermission(requester, patient, PatientPermission.RECORDS)) {
            recipient = requester;
            message = requester.equals(patient)
                    ? "Your correction request for " + phrase(request.getResourceType()) + " in your record was "
                    + outcome + "."
                    : "Your correction request for " + phrase(request.getResourceType()) + " of " + patientName
                    + " was " + outcome + ".";
        } else {
            recipient = patient;
            message = "A correction request for " + phrase(request.getResourceType()) + " in your record was "
                    + outcome + ".";
        }
        publisher.publish(base(request, actorSystemId)
                .recipientSystemId(recipient)
                .title(accepted ? "Correction request accepted" : "Correction request rejected")
                .message(message)
                .dedupeKey("CORRECTION_RESOLVED:" + request.getRequestId())
                .build());
    }

    // ==================== HELPERS ====================

    private static NotificationCommand.Builder base(CorrectionRequest request, String actorSystemId) {
        return NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .target(NotificationTarget.CORRECTIONS)
                .resourceType(RESOURCE_TYPE)
                .resourceId(request.getRequestId())
                .patientSystemId(request.getPatientSystemId())
                .actorSystemId(actorSystemId);
    }

    /** "a lab result of Tariro Moyo", or "a lab result in their record" when the patient filed it. */
    private static String recordOf(CorrectionRequest request, String patientName) {
        if (request.getRequestedBySystemId().equals(request.getPatientSystemId())) {
            return phrase(request.getResourceType()) + " in their record";
        }
        return phrase(request.getResourceType()) + " of " + patientName;
    }

    /** Generic phrase for a kind of record. */
    static String phrase(String resourceType) {
        return switch (resourceType) {
            case CorrectionRequest.MEDICAL_RECORD -> "a medical record";
            case CorrectionRequest.LAB_RESULT -> "a lab result";
            case CorrectionRequest.IMAGING_RESULT -> "an imaging result";
            case CorrectionRequest.PRESCRIPTION -> "a prescription";
            case CorrectionRequest.CONSULTATION -> "a consultation";
            default -> "a record";
        };
    }
}
