package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A lab result as every endpoint of {@code /api/lab-results} returns it. Every field is always present; nullable
 * fields are null, never omitted. Date-times are clinic wall-clock time without an offset.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A lab result")
public class LabResultResponse {

    @JsonProperty("resultId")
    private Integer resultId;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("patientName")
    @Schema(description = "\"First Last\" of the patient; null when the account has no name")
    private String patientName;

    @JsonProperty("doctorSystemId")
    @Schema(description = "System id of the doctor who recorded the result (author)")
    private String doctorSystemId;

    @JsonProperty("doctorName")
    @Schema(description = "\"Dr. First Last\" of the author; null when the account has no name")
    private String doctorName;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("testName")
    private String testName;

    @JsonProperty("testType")
    @Schema(description = "BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB or OTHER")
    private String testType;

    @JsonProperty("resultValue")
    private String resultValue;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("referenceRange")
    private String referenceRange;

    @JsonProperty("resultData")
    @Schema(description = "JSON text of an object as stored (key order and whitespace may differ from what was sent)")
    private String resultData;

    @JsonProperty("status")
    @Schema(description = "PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL or CANCELLED")
    private String status;

    @JsonProperty("isCritical")
    @Schema(description = "Critical flag: always true when CRITICAL, always false when COMPLETED or CANCELLED")
    private Boolean isCritical;

    @JsonProperty("testDate")
    private LocalDate testDate;

    @JsonProperty("reviewedBy")
    @Schema(description = "System id of the reviewing doctor")
    private String reviewedBy;

    @JsonProperty("reviewedByName")
    @Schema(description = "\"Dr. First Last\" of the reviewer; null when not reviewed")
    private String reviewedByName;

    @JsonProperty("reviewedAt")
    private LocalDateTime reviewedAt;

    @JsonProperty("reviewNotes")
    private String reviewNotes;

    @JsonProperty("documentId")
    @Schema(description = "Attached document (download: GET /api/documents/{documentId}/download); null when none "
            + "or when the document was deleted")
    private Integer documentId;

    @JsonProperty("fileUrl")
    @Schema(description = "Deprecated: always null (attachments use documentId)", deprecated = true)
    private String fileUrl;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
