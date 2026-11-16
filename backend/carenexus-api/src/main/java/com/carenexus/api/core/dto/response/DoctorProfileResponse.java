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
@Schema(description = "A doctor profile in one of three views. Full view (the doctor, an administrator): every field. "
        + "Public view (directory, active list, public profile of a listed doctor): doctorSystemId, userId, names, "
        + "specialization, bio and isActive = true; every other field null. Name-only view (public profile of a doctor "
        + "who is not listed): doctorSystemId, names and isActive = false; every other field null.")
public class DoctorProfileResponse {

    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

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
    @Schema(description = "Licence number; read-only for the doctor (full view only)")
    private String licenseNo;

    @JsonProperty("licensingBody")
    @Schema(description = "Regulator that issued the licence, set by an administrator (full view only)")
    private String licensingBody;

    @JsonProperty("licenseCountry")
    @Schema(description = "ISO 3166-1 alpha-2 country of the licence (full view only)", example = "ZW")
    private String licenseCountry;

    @JsonProperty("verificationStatus")
    @Schema(description = "PENDING, APPROVED, REJECTED or SUSPENDED (full view only)")
    private String verificationStatus;

    @JsonProperty("bio")
    private String bio;

    @JsonProperty("isActive")
    @Schema(description = "Full view: the clinician row is active. Public views: the doctor is listed (bookable)")
    private Boolean isActive;

    @JsonProperty("role")
    private String role;

    @JsonProperty("totalPatients")
    @Schema(description = "Patients related to the doctor (care team, non-cancelled appointment or consultation)")
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
