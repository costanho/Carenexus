package com.carenexus.api.corrections.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One correction request. Carries no clinical content of the record itself (no title, value, diagnosis or
 * medication): clients label it from {@code resourceType} + {@code resourceId}. Null fields are serialised as null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A request to correct one clinical record")
public class CorrectionRequestResponse {

    @Schema(description = "Id of the correction request", example = "41")
    private Integer requestId;

    @Schema(description = "Patient whose record is concerned", example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "\"<first> <last>\" of the patient", example = "Tariro Moyo")
    private String patientName;

    @Schema(description = "The patient, or the caregiver who filed the request", example = "CGV-00003")
    private String requestedBySystemId;

    @Schema(description = "\"<first> <last>\" of the requester", example = "Rudo Moyo")
    private String requestedByName;

    @Schema(description = "Role of the requester", allowableValues = {"PATIENT", "CAREGIVER"}, example = "CAREGIVER")
    private String requestedByRole;

    @Schema(description = "Kind of record",
            allowableValues = {"MEDICAL_RECORD", "LAB_RESULT", "IMAGING_RESULT", "PRESCRIPTION", "CONSULTATION"},
            example = "LAB_RESULT")
    private String resourceType;

    @Schema(description = "Id of the record (recordId, resultId, imagingId, prescriptionId or consultationId)",
            example = "318")
    private Integer resourceId;

    @Schema(description = "What the requester wants corrected (1 to 2000 characters)")
    private String requestedText;

    @Schema(description = "Status of the request", allowableValues = {"OPEN", "ACCEPTED", "REJECTED"}, example = "OPEN")
    private String status;

    @Schema(description = "Doctor who must review it; null = unassigned (waiting for an administrator)",
            nullable = true, example = "DOC-00002")
    private String assignedDoctorSystemId;

    @Schema(description = "\"<first> <last>\" of the assigned doctor (no title); null when unassigned",
            nullable = true, example = "Farai Ndlovu")
    private String assignedDoctorName;

    @Schema(description = "Doctor or administrator who resolved it; null while OPEN", nullable = true)
    private String resolverSystemId;

    @Schema(description = "\"<first> <last>\" of the resolver; null while OPEN", nullable = true)
    private String resolverName;

    @Schema(description = "The resolver's note (1 to 1000 characters); null while OPEN", nullable = true)
    private String resolutionNote;

    @Schema(description = "When it was filed (UTC instant)", example = "2026-11-10T08:15:30.412Z")
    private Instant createdAt;

    @Schema(description = "Last change: creation, assignment or resolution (UTC instant)",
            example = "2026-11-10T08:15:30.412Z")
    private Instant updatedAt;

    @Schema(description = "When it was resolved (UTC instant); null while OPEN", nullable = true)
    private Instant resolvedAt;
}
