package com.carenexus.api.health.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** One symptom log. Null fields are serialised as null. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A symptom log")
public class SymptomLogResponse {

    @Schema(description = "Row id", example = "57")
    private Integer symptomId;

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "The symptoms in the order sent (legacy rows that are not a list of strings come back empty)")
    private List<String> symptoms;

    @Schema(description = "MILD, MODERATE, SEVERE or CRITICAL", example = "SEVERE")
    private String severity;

    @Schema(description = "Days the symptoms have lasted; 0 = started today", nullable = true, example = "2")
    private Integer durationDays;

    @Schema(nullable = true)
    private String notes;

    @Schema(description = "PATIENT_REPORTED, DOCTOR_ENTERED, WEARABLE or INTEGRATION", example = "PATIENT_REPORTED")
    private String source;

    @Schema(description = "Clinic wall-clock time set by the server", example = "2026-11-10T19:42:10")
    private LocalDateTime loggedAt;

    @Schema(description = "Author; null on legacy rows", nullable = true, example = "PAT-00001")
    private String loggedBySystemId;

    @Schema(description = "First and last name of the author; null when unknown", nullable = true, example = "Mary Davis")
    private String loggedByName;

    @Schema(description = "Whether the caller may delete it: the caller is the author and fewer than 24 hours have passed")
    private Boolean canDelete;
}
