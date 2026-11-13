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
 * Body of {@code POST /api/lab-results}. Every string is trimmed when it is read; an optional string that is blank
 * is stored as null. The author is always the calling doctor and the reviewer is set only by the review endpoint, so
 * {@code doctorSystemId}, {@code reviewedBy} and {@code fileUrl} in a body are ignored like any unknown property.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A lab result recorded by the treating doctor")
public class CreateLabResultRequest {

    static final String TEST_TYPE_PATTERN = "(?i)^(BLOOD|URINE|STOOL|CULTURE|BIOPSY|GENETIC|SWAB|OTHER)$";
    static final String TEST_TYPE_MESSAGE = "must be one of BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, OTHER";
    static final String WRITABLE_STATUS_MESSAGE = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL";

    @JsonProperty("patientSystemId")
    @NotBlank(message = "is required")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    @Schema(description = "Patient the result belongs to", example = "PAT-00021", requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientSystemId;

    @JsonProperty("consultationId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Consultation of this patient the result belongs to", example = "318")
    private Integer consultationId;

    @JsonProperty("testName")
    @NotBlank(message = "is required")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(example = "Full Blood Count (FBC)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String testName;

    @JsonProperty("testType")
    @NotBlank(message = "is required")
    @Pattern(regexp = TEST_TYPE_PATTERN, message = TEST_TYPE_MESSAGE)
    @Schema(description = "BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB or OTHER (case-insensitive)",
            example = "BLOOD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String testType;

    @JsonProperty("resultValue")
    @Size(max = 500, message = "must be at most 500 characters")
    @Schema(description = "Headline value")
    private String resultValue;

    @JsonProperty("unit")
    @Size(max = 50, message = "must be at most 50 characters")
    private String unit;

    @JsonProperty("referenceRange")
    @Size(max = 100, message = "must be at most 100 characters")
    private String referenceRange;

    @JsonProperty("resultData")
    @Size(max = 20000, message = "must be at most 20000 characters")
    @Schema(description = "JSON text of an object: {\"<sub-test>\": {value, unit, reference, flag, interpretation}}")
    private String resultData;

    @JsonProperty("status")
    @Pattern(regexp = "(?i)^(|PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL)$", message = WRITABLE_STATUS_MESSAGE)
    @Schema(description = "PENDING (default), REQUIRES_REVIEW, COMPLETED or CRITICAL; CANCELLED only through DELETE")
    private String status;

    @JsonProperty("isCritical")
    @Schema(description = "Critical flag (default false), normalised with status: COMPLETED + true becomes CRITICAL")
    private Boolean isCritical;

    @JsonProperty("testDate")
    @PastOrPresent(message = "must not be in the future")
    @Schema(description = "Clinic calendar day of the sample (YYYY-MM-DD); defaults to clinic today", example = "2026-11-09")
    private LocalDate testDate;

    @JsonProperty("documentId")
    @Positive(message = "must be greater than 0")
    @Schema(description = "Attached document (an existing document of the same patient)", example = "412")
    private Integer documentId;

    public void setPatientSystemId(String patientSystemId) {
        this.patientSystemId = trim(patientSystemId);
    }

    public void setTestName(String testName) {
        this.testName = trim(testName);
    }

    public void setTestType(String testType) {
        this.testType = trim(testType);
    }

    public void setResultValue(String resultValue) {
        this.resultValue = trim(resultValue);
    }

    public void setUnit(String unit) {
        this.unit = trim(unit);
    }

    public void setReferenceRange(String referenceRange) {
        this.referenceRange = trim(referenceRange);
    }

    public void setResultData(String resultData) {
        this.resultData = trim(resultData);
    }

    public void setStatus(String status) {
        this.status = trim(status);
    }

    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
