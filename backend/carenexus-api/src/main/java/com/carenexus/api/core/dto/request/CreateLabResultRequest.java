package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateLabResultRequest {

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    /** Ignored by the server: the author doctor is always the authenticated caller. Kept for compatibility. */
    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("testName")
    private String testName;

    @JsonProperty("testType")
    private String testType;

    @JsonProperty("resultValue")
    private String resultValue;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("referenceRange")
    private String referenceRange;

    @JsonProperty("resultData")
    private String resultData;

    @JsonProperty("status")
    private String status;

    @JsonProperty("isCritical")
    private Boolean isCritical;

    @JsonProperty("testDate")
    private LocalDate testDate;

    /** Ignored by the server: reviews are recorded via POST /{id}/review as the authenticated doctor. */
    @JsonProperty("reviewedBy")
    private String reviewedBy;

    @JsonProperty("fileUrl")
    private String fileUrl;
}
