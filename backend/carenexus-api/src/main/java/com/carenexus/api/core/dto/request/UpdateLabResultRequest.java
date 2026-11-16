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
 * Body of {@code PUT /api/lab-results/{id}}: a partial update by the author doctor. An absent or null field is left
 * unchanged; every string is trimmed when it is read, and an optional string that is blank clears the field. The
 * patient, the author and the consultation of a result never change.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Partial update of a lab result (author doctor only)")
public class UpdateLabResultRequest {

    @JsonProperty("testName")
    @Pattern(regexp = "(?s)^.*\\S.*$", message = "must not be blank")
    @Size(max = 200, message = "must be at most 200 characters")
    private String testName;

    @JsonProperty("testType")
    @Pattern(regexp = CreateLabResultRequest.TEST_TYPE_PATTERN, message = CreateLabResultRequest.TEST_TYPE_MESSAGE)
    @Schema(description = "BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB or OTHER (case-insensitive)")
    private String testType;

    @JsonProperty("resultValue")
    @Size(max = 500, message = "must be at most 500 characters")
    @Schema(description = "Blank clears it")
    private String resultValue;

    @JsonProperty("unit")
    @Size(max = 50, message = "must be at most 50 characters")
    @Schema(description = "Blank clears it")
    private String unit;

    @JsonProperty("referenceRange")
    @Size(max = 100, message = "must be at most 100 characters")
    @Schema(description = "Blank clears it")
    private String referenceRange;

    @JsonProperty("resultData")
    @Size(max = 20000, message = "must be at most 20000 characters")
    @Schema(description = "JSON text of an object; blank clears it")
    private String resultData;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)^(PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL)$",
            message = CreateLabResultRequest.WRITABLE_STATUS_MESSAGE)
    @Schema(description = "PENDING, REQUIRES_REVIEW, COMPLETED or CRITICAL; a recorded result cannot go back to PENDING")
    private String status;

    @JsonProperty("isCritical")
    @Schema(description = "Critical flag, normalised with the status")
    private Boolean isCritical;

    @JsonProperty("testDate")
    @PastOrPresent(message = "must not be in the future")
    private LocalDate testDate;

    @JsonProperty("documentId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Replaces the attached document (an existing document of the same patient)")
    private Integer documentId;

    public void setTestName(String testName) {
        this.testName = CreateLabResultRequest.trim(testName);
    }

    public void setTestType(String testType) {
        this.testType = CreateLabResultRequest.trim(testType);
    }

    public void setResultValue(String resultValue) {
        this.resultValue = CreateLabResultRequest.trim(resultValue);
    }

    public void setUnit(String unit) {
        this.unit = CreateLabResultRequest.trim(unit);
    }

    public void setReferenceRange(String referenceRange) {
        this.referenceRange = CreateLabResultRequest.trim(referenceRange);
    }

    public void setResultData(String resultData) {
        this.resultData = CreateLabResultRequest.trim(resultData);
    }

    public void setStatus(String status) {
        this.status = CreateLabResultRequest.trim(status);
    }
}
