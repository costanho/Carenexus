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

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("doctorId")
    private Integer doctorId;

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

    @JsonProperty("reviewedBy")
    private Integer reviewedBy;

    @JsonProperty("fileUrl")
    private String fileUrl;
}
