package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorProfileResponse {

    @JsonProperty("doctorId")
    private Integer doctorId;

    @JsonProperty("userId")
    private Integer userId;

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

    @JsonProperty("role")
    private String role;

    @JsonProperty("totalPatients")
    private Integer totalPatients;

    @JsonProperty("totalAppointments")
    private Integer totalAppointments;

    @JsonProperty("totalConsultations")
    private Integer totalConsultations;

    @JsonProperty("completedConsultations")
    private Integer completedConsultations;

    @JsonProperty("totalPrescriptions")
    private Integer totalPrescriptions;

    @JsonProperty("totalMedicalRecords")
    private Integer totalMedicalRecords;

    @JsonProperty("doctorCreatedAt")
    private LocalDateTime doctorCreatedAt;

    @JsonProperty("userCreatedAt")
    private LocalDateTime userCreatedAt;

    @JsonProperty("userUpdatedAt")
    private LocalDateTime userUpdatedAt;
}
