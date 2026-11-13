package com.carenexus.api.health.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body of {@code POST /api/symptom-logs}. The source (PATIENT_REPORTED or DOCTOR_ENTERED) and the time are set by
 * the server; unknown properties are ignored.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Symptoms to log")
public class CreateSymptomLogRequest {

    /** Accepts the four values in any case and surrounding spaces, and blank (left to @NotBlank). */
    public static final String SEVERITY_PATTERN = "(?i)^\\s*(MILD|MODERATE|SEVERE|CRITICAL)?\\s*$";

    @Schema(description = "The patient the log is about. Optional for a patient (defaults to the caller, and if sent "
            + "must be the caller); required for doctors and caregivers", example = "PAT-00001")
    @Pattern(regexp = RecordHealthMetricsRequest.PATIENT_ID_PATTERN, message = "must be a valid system id")
    private String patientSystemId;

    @ArraySchema(arraySchema = @Schema(description = "1 to 20 symptoms, kept in order and trimmed; duplicates are kept"),
            schema = @Schema(description = "One symptom, at most 100 characters", example = "Headache"))
    @NotNull(message = "must contain 1 to 20 symptoms")
    @Size(min = 1, max = 20, message = "must contain 1 to 20 symptoms")
    private List<@NotBlank @Size(max = 100) String> symptoms;

    @Schema(description = "MILD, MODERATE, SEVERE or CRITICAL (case-insensitive). SEVERE and CRITICAL notify the "
            + "patient's doctors", example = "SEVERE")
    @NotBlank
    @Pattern(regexp = SEVERITY_PATTERN, message = "must be one of MILD, MODERATE, SEVERE, CRITICAL")
    private String severity;

    @Schema(description = "How many days the symptoms have lasted; 0 = started today", example = "2")
    @Min(0)
    @Max(3650)
    private Integer durationDays;

    @Schema(description = "Optional free text; blank is stored as null", example = "Worse in the evening")
    @Size(max = 2000)
    private String notes;
}
