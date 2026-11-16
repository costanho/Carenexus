package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An imaging result as every endpoint of {@code /api/imaging-results} returns it. Every field is always present;
 * nullable fields are null, never omitted. Date-times are clinic wall-clock time without an offset.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An imaging result")
public class ImagingResultResponse {

    @JsonProperty("imagingId")
    private Integer imagingId;

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

    @JsonProperty("imagingType")
    @Schema(description = "XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA or ECHO")
    private String imagingType;

    @JsonProperty("bodyPart")
    private String bodyPart;

    @JsonProperty("radiologistReport")
    private String radiologistReport;

    @JsonProperty("findings")
    private String findings;

    @JsonProperty("impression")
    private String impression;

    @JsonProperty("fileUrl")
    @Schema(description = "Deprecated: always null (attachments use documentId)", deprecated = true)
    private String fileUrl;

    @JsonProperty("thumbnailUrl")
    @Schema(description = "Deprecated: always null", deprecated = true)
    private String thumbnailUrl;

    @JsonProperty("status")
    @Schema(description = "PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL or CANCELLED")
    private String status;

    @JsonProperty("isCritical")
    @Schema(description = "Derived: status == CRITICAL")
    private Boolean isCritical;

    @JsonProperty("imageDate")
    private LocalDate imageDate;

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

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
