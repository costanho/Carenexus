package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.event.DocumentUploadedEvent;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Patient-circle notifications about new clinical content (api/be-clinical-notifications.md N5 to N11): a completed
 * consultation, an issued, expired or cancelled prescription, an available or critical lab or imaging result, and a
 * document added by someone other than the patient.
 *
 * Doctors are never recipients here: they learn about critical results only through the AI alert that
 * be-ai-generators raises (one AI_ALERT notification per related doctor), so no doctor is notified twice.
 *
 * Plain {@link EventListener} methods that run in the publisher's transaction; dedupe keys are per resource, so a
 * repeated or later event for the same resource stores nothing.
 */
@Component
@RequiredArgsConstructor
public class ClinicalRecordNotificationListener {

    static final String CONSULTATION_RESOURCE = "CONSULTATION";
    static final String PRESCRIPTION_RESOURCE = "PRESCRIPTION";
    static final String LAB_RESOURCE = "LAB_RESULT";
    static final String IMAGING_RESOURCE = "IMAGING_RESULT";
    static final String DOCUMENT_RESOURCE = "DOCUMENT";

    static final String CONSULTATION_PREFIX = "CONSULT_COMPLETED:";
    static final String RX_ISSUED_PREFIX = "RX_ISSUED:";
    static final String RX_EXPIRED_PREFIX = "RX_EXPIRED:";
    static final String RX_CANCELLED_PREFIX = "RX_CANCELLED:";
    static final String RESULT_AVAILABLE_PREFIX = "RESULT_AVAILABLE:";
    static final String RESULT_CRITICAL_PREFIX = "RESULT_CRITICAL:";
    static final String DOCUMENT_PREFIX = "DOC_UPLOADED:";

    static final String STATUS_EXPIRED = "EXPIRED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_COMPLETED = "COMPLETED";

    private final ClinicalNotifier notifier;

    /** N5: patient and caregivers with CONSULTATIONS; the completing doctor is the actor. */
    @EventListener
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        if (event.consultationId() == null || event.patientSystemId() == null) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        NotificationCommand template = NotificationCommand.builder()
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.CONSULTATION)
                .priority(NotificationPriority.NORMAL)
                .title(ClinicalNotificationTexts.TITLE_CONSULTATION_COMPLETED)
                .message(ClinicalNotificationTexts.TITLE_CONSULTATION_COMPLETED)
                .target(NotificationTarget.CONSULTATIONS)
                .resourceType(CONSULTATION_RESOURCE)
                .resourceId(event.consultationId())
                .patientSystemId(event.patientSystemId())
                .actorSystemId(event.doctorSystemId())
                .dedupeKey(CONSULTATION_PREFIX + event.consultationId())
                .build();
        notifier.publishEach(circle(event.patientSystemId(), PatientPermission.CONSULTATIONS), template,
                audience -> ClinicalNotificationTexts.consultationCompleted(audience, patient, doctor,
                        event.followUpRequired(), event.followUpDate()));
    }

    /** N6: patient and caregivers with MEDICATIONS. The medication name is never used. */
    @EventListener
    public void onPrescriptionIssued(PrescriptionIssuedEvent event) {
        if (event.prescriptionId() == null || event.patientSystemId() == null) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        NotificationCommand template = prescriptionTemplate(event.prescriptionId(), event.patientSystemId(),
                event.doctorSystemId(), ClinicalNotificationTexts.TITLE_PRESCRIPTION_ISSUED,
                RX_ISSUED_PREFIX + event.prescriptionId());
        notifier.publishEach(circle(event.patientSystemId(), PatientPermission.MEDICATIONS), template,
                audience -> ClinicalNotificationTexts.prescriptionIssued(audience, patient, doctor));
    }

    /** N7 (EXPIRED, no actor) and N8 (CANCELLED, the prescriber as actor): the patient only. */
    @EventListener
    public void onPrescriptionStatusChanged(PrescriptionStatusChangedEvent event) {
        if (event.prescriptionId() == null || event.patientSystemId() == null || event.status() == null
                || event.status().equals(event.previousStatus())) {
            return;
        }
        Map<String, Audience> patientOnly = notifier.recipients(null, event.patientSystemId(), true, null);
        if (STATUS_EXPIRED.equals(event.status())) {
            NotificationCommand template = prescriptionTemplate(event.prescriptionId(), event.patientSystemId(), null,
                    ClinicalNotificationTexts.TITLE_PRESCRIPTION_EXPIRED, RX_EXPIRED_PREFIX + event.prescriptionId());
            notifier.publishEach(patientOnly, template, audience -> ClinicalNotificationTexts.prescriptionExpired());
        } else if (STATUS_CANCELLED.equals(event.status())) {
            String doctor = notifier.names(event.doctorSystemId()).doctor(event.doctorSystemId());
            NotificationCommand template = prescriptionTemplate(event.prescriptionId(), event.patientSystemId(),
                    event.doctorSystemId(), ClinicalNotificationTexts.TITLE_PRESCRIPTION_CANCELLED,
                    RX_CANCELLED_PREFIX + event.prescriptionId());
            notifier.publishEach(patientOnly, template,
                    audience -> ClinicalNotificationTexts.prescriptionCancelled(doctor));
        }
    }

    /**
     * N9 / N10, at most one kind per event: newly critical gives the HIGH "Important result" notification; otherwise
     * COMPLETED and not critical gives "New result"; anything else (PENDING, REQUIRES_REVIEW, CANCELLED, a result
     * that was already critical) gives nothing. Patient and caregivers with LAB_RESULTS or IMAGING; never a doctor.
     */
    @EventListener
    public void onResultRecorded(ClinicalResultRecordedEvent event) {
        if (event.resultId() == null || event.patientSystemId() == null || event.kind() == null) {
            return;
        }
        boolean imaging = ClinicalResultRecordedEvent.IMAGING.equals(event.kind());
        if (!imaging && !ClinicalResultRecordedEvent.LAB.equals(event.kind())) {
            return;
        }
        boolean critical;
        if (event.newlyCritical()) {
            critical = true;
        } else if (STATUS_COMPLETED.equals(event.status()) && !event.critical()) {
            critical = false;
        } else {
            return;
        }
        String patient = notifier.names(event.patientSystemId()).patient(event.patientSystemId());
        String title = critical ? ClinicalNotificationTexts.resultCriticalTitle(imaging)
                : ClinicalNotificationTexts.resultAvailableTitle(imaging);
        NotificationCommand template = NotificationCommand.builder()
                .type(NotificationType.LAB_RESULT)
                .category(imaging ? NotificationCategory.IMAGING_RESULT : NotificationCategory.LAB_RESULT)
                .priority(critical ? NotificationPriority.HIGH : NotificationPriority.NORMAL)
                .title(title)
                .message(title)
                .target(imaging ? NotificationTarget.IMAGING_RESULTS : NotificationTarget.LAB_RESULTS)
                .resourceType(imaging ? IMAGING_RESOURCE : LAB_RESOURCE)
                .resourceId(event.resultId())
                .patientSystemId(event.patientSystemId())
                .actorSystemId(event.actorSystemId())
                .dedupeKey((critical ? RESULT_CRITICAL_PREFIX : RESULT_AVAILABLE_PREFIX) + event.kind() + ":"
                        + event.resultId())
                .build();
        PatientPermission permission = imaging ? PatientPermission.IMAGING : PatientPermission.LAB_RESULTS;
        notifier.publishEach(circle(event.patientSystemId(), permission), template,
                audience -> critical
                        ? ClinicalNotificationTexts.resultCritical(audience, imaging, patient)
                        : ClinicalNotificationTexts.resultAvailable(audience, imaging, patient));
    }

    /**
     * N11: the patient, when someone else added a document. Category SYSTEM bypasses the publisher's actor rule, so
     * an upload by the patient is skipped here (D8). The document title and type are never shown.
     */
    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (event.documentId() == null || event.patientSystemId() == null) {
            return;
        }
        String patientId = event.patientSystemId().trim();
        String uploaderId = ClinicalNotifier.trimToNull(event.uploadedBySystemId());
        if (patientId.equals(uploaderId)) {
            return;
        }
        String uploader = notifier.names(uploaderId).uploader(uploaderId);
        NotificationCommand template = NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title(ClinicalNotificationTexts.TITLE_DOCUMENT_UPLOADED)
                .message(ClinicalNotificationTexts.TITLE_DOCUMENT_UPLOADED)
                .target(NotificationTarget.DOCUMENTS)
                .resourceType(DOCUMENT_RESOURCE)
                .resourceId(event.documentId())
                .patientSystemId(patientId)
                .actorSystemId(uploaderId)
                .dedupeKey(DOCUMENT_PREFIX + event.documentId())
                .build();
        notifier.publishEach(notifier.recipients(null, patientId, true, null), template,
                audience -> ClinicalNotificationTexts.documentUploaded(uploader));
    }

    private Map<String, Audience> circle(String patientSystemId, PatientPermission permission) {
        return notifier.recipients(null, patientSystemId, true, permission);
    }

    private static NotificationCommand prescriptionTemplate(Integer prescriptionId, String patientSystemId,
                                                            String actorSystemId, String title, String dedupeKey) {
        return NotificationCommand.builder()
                .type(NotificationType.MEDICATION)
                .category(NotificationCategory.PRESCRIPTION)
                .priority(NotificationPriority.NORMAL)
                .title(title)
                .message(title)
                .target(NotificationTarget.PRESCRIPTIONS)
                .resourceType(PRESCRIPTION_RESOURCE)
                .resourceId(prescriptionId)
                .patientSystemId(patientSystemId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey)
                .build();
    }
}
