package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateMedicalRecordRequest {

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

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
}
