package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyContactResponse {

    @JsonProperty("contactId")
    private Integer contactId;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

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
    @Schema(description = "Informational flag: this person also cares for the patient. Grants nothing")
    private Boolean isCaregiver;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
