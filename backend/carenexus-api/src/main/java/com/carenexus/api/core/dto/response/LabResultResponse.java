package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultResponse {

    @JsonProperty("resultId")
    private Integer resultId;

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

    @JsonProperty("reviewedAt")
    private LocalDateTime reviewedAt;

    @JsonProperty("fileUrl")
    private String fileUrl;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
