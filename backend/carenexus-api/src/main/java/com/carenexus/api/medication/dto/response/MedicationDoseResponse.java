package com.carenexus.api.medication.dto.response;

import com.carenexus.api.medication.model.MedicationSchedule;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One scheduled dose. Every field is always present; nullable ones are sent as JSON null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "MedicationDoseResponse", description = "One dose of a medication on one clinic day")
public record MedicationDoseResponse(
        @Schema(description = "Dose id; the {id} of the taken / missed / undo endpoints", example = "9412")
        Integer scheduleId,

        @Schema(description = "Prescription the dose belongs to", example = "57")
        Integer prescriptionId,

        @Schema(description = "Patient the dose belongs to", example = "PAT-00001")
        String patientSystemId,

        @Schema(description = "Medication name copied from the prescription when the dose was created",
                example = "Metformin")
        String medicationName,

        @Schema(description = "Dosage copied from the prescription when the dose was created", example = "850mg")
        String dosage,

        @Schema(description = "Clinic calendar day of the dose", example = "2026-11-10", type = "string",
                format = "date")
        LocalDate scheduleDate,

        @JsonFormat(pattern = "HH:mm")
        @Schema(description = "Clinic wall-clock time of the dose, 24-hour HH:mm", example = "08:00", type = "string",
                pattern = "^([01]\\d|2[0-3]):[0-5]\\d$")
        LocalTime scheduledTime,

        @Schema(description = "PENDING (not marked yet), TAKEN or MISSED", example = "TAKEN",
                allowableValues = {"PENDING", "TAKEN", "MISSED"})
        String status,

        @Schema(description = "Clinic wall-clock date-time the dose was marked taken; null unless TAKEN",
                example = "2026-11-10T08:12:44", nullable = true, type = "string", format = "date-time")
        LocalDateTime takenAt,

        @Schema(description = "Who set the current status (patient or caregiver); null while PENDING and for a dose "
                + "the server marked MISSED automatically", example = "PAT-00001", nullable = true)
        String markedBySystemId,

        @Schema(description = "True when the dose is of the clinic's today or yesterday, the only days on which it "
                + "can be marked. Says nothing about the caller's permission.", example = "true")
        boolean markable) {

    public static MedicationDoseResponse from(MedicationSchedule dose, LocalDate today) {
        LocalDate day = dose.getScheduleDate();
        boolean markable = day != null && (day.equals(today) || day.equals(today.minusDays(1)));
        return new MedicationDoseResponse(dose.getScheduleId(), dose.getPrescriptionId(), dose.getPatientSystemId(),
                dose.getMedicationName(), dose.getDosage(), day, dose.getScheduledTime(), dose.status(),
                MedicationSchedule.TAKEN.equals(dose.status()) ? dose.getTakenAt() : null,
                dose.getMarkedBySystemId(), markable);
    }
}
