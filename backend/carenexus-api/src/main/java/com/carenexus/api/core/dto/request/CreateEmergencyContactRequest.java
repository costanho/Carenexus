package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEmergencyContactRequest {

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("relationship")
    private String relationship;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("email")
    private String email;

    @JsonProperty("isPrimary")
    private Boolean isPrimary;

    @JsonProperty("isCaregiver")
    private Boolean isCaregiver;
}
