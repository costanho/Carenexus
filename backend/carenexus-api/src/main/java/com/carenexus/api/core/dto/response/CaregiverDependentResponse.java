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
public class CaregiverDependentResponse {

    @JsonProperty("accessId")
    private Integer accessId;

    @JsonProperty("caregiverSystemId")
    private String caregiverSystemId;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("relationship")
    private String relationship;

    @JsonProperty("accessLevel")
    private String accessLevel;

    @JsonProperty("canViewRecords")
    private Boolean canViewRecords;

    @JsonProperty("canViewAppointments")
    private Boolean canViewAppointments;

    @JsonProperty("canViewMedications")
    private Boolean canViewMedications;

    @JsonProperty("canViewLabResults")
    private Boolean canViewLabResults;

    @JsonProperty("canViewImaging")
    private Boolean canViewImaging;

    @JsonProperty("canMessageCareTeam")
    private Boolean canMessageCareTeam;

    @JsonProperty("canViewConsultations")
    private Boolean canViewConsultations;

    @JsonProperty("canScheduleAppointments")
    private Boolean canScheduleAppointments;

    @JsonProperty("canManageCarePlan")
    private Boolean canManageCarePlan;

    @JsonProperty("authorizedBySystemId")
    private String authorizedBySystemId;

    @JsonProperty("authorizedDate")
    private LocalDateTime authorizedDate;

    @JsonProperty("expiresAt")
    private LocalDateTime expiresAt;

    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("revokedAt")
    private LocalDateTime revokedAt;

    // Enriched patient fields
    @JsonProperty("patientName")
    private String patientName;

    @JsonProperty("patientEmail")
    private String patientEmail;

    @JsonProperty("patientHealthStatus")
    private String patientHealthStatus;

    @JsonProperty("patientDateOfBirth")
    private LocalDate patientDateOfBirth;

    @JsonProperty("patientGender")
    private String patientGender;

    @JsonProperty("patientBloodType")
    private String patientBloodType;
}
