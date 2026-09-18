package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDoctorProfileRequest {

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("specialization")
    private String specialization;

    @JsonProperty("licenseNo")
    private String licenseNo;

    @JsonProperty("bio")
    private String bio;

    @JsonProperty("isActive")
    private Boolean isActive;
}
