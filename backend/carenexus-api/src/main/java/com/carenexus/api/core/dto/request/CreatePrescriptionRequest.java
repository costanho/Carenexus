package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePrescriptionRequest {

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("doctorId")
    private Integer doctorId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("medicationName")
    private String medicationName;

    @JsonProperty("genericName")
    private String genericName;

    @JsonProperty("dosage")
    private String dosage;

    @JsonProperty("frequency")
    private String frequency;

    @JsonProperty("route")
    private String route;

    @JsonProperty("quantity")
    private String quantity;

    @JsonProperty("refillsAllowed")
    private Integer refillsAllowed;

    @JsonProperty("specialInstructions")
    private String specialInstructions;

    @JsonProperty("status")
    private String status;

    @JsonProperty("prescribedDate")
    private LocalDate prescribedDate;

    @JsonProperty("expiryDate")
    private LocalDate expiryDate;
}
