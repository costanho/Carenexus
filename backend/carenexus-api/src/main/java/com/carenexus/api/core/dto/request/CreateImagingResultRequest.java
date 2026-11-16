package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/imaging-results}. Every string is trimmed when it is read; an optional string that is
 * blank is stored as null. The author is always the calling doctor; {@code doctorSystemId}, {@code reviewedBy},
 * {@code fileUrl} and {@code thumbnailUrl} in a body are ignored like any unknown property. There is no critical
 * flag: a critical imaging result is recorded with status CRITICAL.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An imaging result recorded by the treating doctor")
public class CreateImagingResultRequest {

    static final String IMAGING_TYPE_PATTERN = "(?i)^(XRAY|MRI|CT_SCAN|ULTRASOUND|PET|MAMMOGRAPHY|DEXA|ECHO)$";
    static final String IMAGING_TYPE_MESSAGE = "must be one of XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, ECHO";

    @JsonProperty("patientSystemId")
    @NotBlank(message = "is required")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    @Schema(description = "Patient the result belongs to", example = "PAT-00021", requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientSystemId;

    @JsonProperty("consultationId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Consultation of this patient the result belongs to")
    private Integer consultationId;

    @JsonProperty("imagingType")
    @NotBlank(message = "is required")
    @Pattern(regexp = IMAGING_TYPE_PATTERN, message = IMAGING_TYPE_MESSAGE)
    @Schema(description = "XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA or ECHO (case-insensitive)",
            example = "XRAY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String imagingType;

    @JsonProperty("bodyPart")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(example = "Chest")
    private String bodyPart;

    @JsonProperty("radiologistReport")
    @Size(max = 10000, message = "must be at most 10000 characters")
    private String radiologistReport;

    @JsonProperty("findings")
    @Size(max = 5000, message = "must be at most 5000 characters")
    private String findings;

    @JsonProperty("impression")
    @Size(max = 5000, message = "must be at most 5000 characters")
    private String impression;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)^(|PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL)$",
            message = CreateLabResultRequest.WRITABLE_STATUS_MESSAGE)
    @Schema(description = "PENDING (default), REQUIRES_REVIEW, COMPLETED or CRITICAL; CANCELLED only through DELETE")
    private String status;

    @JsonProperty("imageDate")
    @PastOrPresent(message = "must not be in the future")
    @Schema(description = "Clinic calendar day the image was made (YYYY-MM-DD); defaults to clinic today", example = "2026-11-08")
    private LocalDate imageDate;

    @JsonProperty("documentId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Attached document (an existing document of the same patient)", example = "415")
    private Integer documentId;

    public void setPatientSystemId(String patientSystemId) {
        this.patientSystemId = CreateLabResultRequest.trim(patientSystemId);
    }

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
