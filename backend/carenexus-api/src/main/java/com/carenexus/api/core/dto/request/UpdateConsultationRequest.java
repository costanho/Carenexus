package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

import static com.carenexus.api.core.dto.request.CreateConsultationRequest.DURATION_RANGE;
import static com.carenexus.api.core.dto.request.CreateConsultationRequest.MAX_NOTE_LENGTH;
import static com.carenexus.api.core.dto.request.CreateConsultationRequest.NOTE_TOO_LONG;

/**
 * Body of {@code PUT /api/consultations/{id}}: a partial update. A field that is absent or {@code null} is left
 * unchanged; an empty or blank text clears that field. {@code appointmentId}, {@code patientSystemId} and
 * {@code doctorSystemId} are fixed at creation and are ignored if a client sends them (unknown fields are ignored).
 * An optional {@code status} is applied after the content with the transition rules of the PATCH routes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Partial update of a consultation's notes (absent or null = unchanged, blank text = cleared)")
public class UpdateConsultationRequest {

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
    @Schema(description = "false also clears followUpDate")
    private Boolean followUpRequired;

    @JsonProperty("followUpDate")
    @Schema(description = "Evaluated on the merged state: required while a follow-up is required; a date sent here "
            + "must not be before today in the clinic zone", example = "2026-11-24")
    private LocalDate followUpDate;

    @JsonProperty("durationMinutes")
    @Min(value = 1, message = DURATION_RANGE)
    @Max(value = 480, message = DURATION_RANGE)
    @Schema(description = "1 to 480; cannot be cleared once set, only replaced")
    private Integer durationMinutes;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)\\s*(DRAFT|IN_PROGRESS|COMPLETED|CANCELLED)\\s*",
            message = "must be one of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED")
    @Schema(description = "Optional transition applied after the content (same rules as PATCH .../status)")
    private String status;
}
