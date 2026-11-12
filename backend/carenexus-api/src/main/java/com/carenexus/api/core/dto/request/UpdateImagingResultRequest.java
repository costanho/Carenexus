package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

/**
 * Body of {@code PUT /api/imaging-results/{id}}: a partial update by the author doctor. An absent or null field is
 * left unchanged; every string is trimmed when it is read, and an optional string that is blank clears the field.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Partial update of an imaging result (author doctor only)")
public class UpdateImagingResultRequest {

    @JsonProperty("imagingType")
    @Pattern(regexp = CreateImagingResultRequest.IMAGING_TYPE_PATTERN,
            message = CreateImagingResultRequest.IMAGING_TYPE_MESSAGE)
    @Schema(description = "XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA or ECHO (case-insensitive)")
    private String imagingType;

    @JsonProperty("bodyPart")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "Blank clears it")
    private String bodyPart;

    @JsonProperty("radiologistReport")
    @Size(max = 10000, message = "must be at most 10000 characters")
    @Schema(description = "Blank clears it")
    private String radiologistReport;

    @JsonProperty("findings")
    @Size(max = 5000, message = "must be at most 5000 characters")
    @Schema(description = "Blank clears it")
    private String findings;

    @JsonProperty("impression")
    @Size(max = 5000, message = "must be at most 5000 characters")
    @Schema(description = "Blank clears it")
    private String impression;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)^(PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL)$",
            message = CreateLabResultRequest.WRITABLE_STATUS_MESSAGE)
    @Schema(description = "PENDING, REQUIRES_REVIEW, COMPLETED or CRITICAL; a recorded result cannot go back to PENDING")
    private String status;

    @JsonProperty("imageDate")
    @PastOrPresent(message = "must not be in the future")
    private LocalDate imageDate;

    @JsonProperty("documentId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Replaces the attached document (an existing document of the same patient)")
    private Integer documentId;

    public void setImagingType(String imagingType) {
        this.imagingType = CreateLabResultRequest.trim(imagingType);
    }

    public void setBodyPart(String bodyPart) {
        this.bodyPart = CreateLabResultRequest.trim(bodyPart);
    }

    public void setRadiologistReport(String radiologistReport) {
        this.radiologistReport = CreateLabResultRequest.trim(radiologistReport);
    }

    public void setFindings(String findings) {
        this.findings = CreateLabResultRequest.trim(findings);
    }

    public void setImpression(String impression) {
        this.impression = CreateLabResultRequest.trim(impression);
    }

    public void setStatus(String status) {
        this.status = CreateLabResultRequest.trim(status);
    }
}
