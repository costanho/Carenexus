package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/consultations}. The patient and the author doctor are taken from the appointment and the
 * caller; the follow-up rules (date required when a follow-up is required, not before clinic today) are checked in
 * ConsultationService because they depend on two fields and the clinic clock.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Open a consultation for one of the caller's appointments")
public class CreateConsultationRequest {

    public static final int MAX_NOTE_LENGTH = 10000;
    public static final String NOTE_TOO_LONG = "must be at most " + MAX_NOTE_LENGTH + " characters";
    public static final String DURATION_RANGE = "must be between 1 and 480";

    @JsonProperty("appointmentId")
    @NotNull(message = "is required")
    @Positive(message = "must be a positive number")
    @Schema(description = "Appointment this consultation documents; it must be the caller's own appointment",
            example = "412", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer appointmentId;

    @JsonProperty("patientSystemId")
    @Size(max = 20, message = "must be at most 20 characters")
    @Schema(description = "Optional; when sent it must be the appointment's patient", example = "PAT-00001")
    private String patientSystemId;

    /** Ignored by the server: the author doctor is always the authenticated caller. Kept for compatibility. */
    @JsonProperty("doctorSystemId")
    @Schema(description = "Ignored: the author is always the caller")
    private String doctorSystemId;

    @JsonProperty("chiefComplaint")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String chiefComplaint;

    @JsonProperty("historyOfPresentIllness")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String historyOfPresentIllness;

    @JsonProperty("physicalExamination")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String physicalExamination;

    @JsonProperty("clinicalNotes")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String clinicalNotes;

    @JsonProperty("diagnosis")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String diagnosis;

    @JsonProperty("treatmentPlan")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String treatmentPlan;

    @JsonProperty("followUpInstructions")
    @Size(max = MAX_NOTE_LENGTH, message = NOTE_TOO_LONG)
    private String followUpInstructions;

    @JsonProperty("followUpRequired")
    @Schema(description = "Default false")
    private Boolean followUpRequired;

    @JsonProperty("followUpDate")
    @Schema(description = "Required when followUpRequired is true, then not before today in the clinic zone; "
            + "ignored (stored null) otherwise", example = "2026-11-24")
    private LocalDate followUpDate;

    @JsonProperty("durationMinutes")
    @Min(value = 1, message = DURATION_RANGE)
    @Max(value = 480, message = DURATION_RANGE)
    private Integer durationMinutes;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)\\s*(DRAFT|IN_PROGRESS)\\s*", message = "must be DRAFT or IN_PROGRESS")
    @Schema(description = "DRAFT (default) or IN_PROGRESS; case-insensitive", example = "DRAFT")
    private String status;
}
