package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicalRecordResponse {

    @JsonProperty("recordId")
    private Integer recordId;

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("doctorId")
    private Integer doctorId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("recordType")
    private String recordType;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("treatmentPlan")
    private String treatmentPlan;

    @JsonProperty("observations")
    private String observations;

    @JsonProperty("icdCodes")
    private String icdCodes;

    @JsonProperty("isDeleted")
    private Boolean isDeleted;

    @JsonProperty("deletedAt")
    private LocalDateTime deletedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
