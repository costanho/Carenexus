package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
