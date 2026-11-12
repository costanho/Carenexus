package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
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

    // Communication permissions. canMessageCareTeam / canJoinCalls are requests; they only take effect with the
    // patient's approval (messagingAllowed / joinCallsAllowed are the effective values right now).
    @JsonProperty("canJoinCalls")
    private Boolean canJoinCalls;

    @JsonProperty("messagingApprovedAt")
    private Instant messagingApprovedAt;

    @JsonProperty("callsApprovedAt")
    private Instant callsApprovedAt;

    @JsonProperty("messagingAllowed")
    private boolean messagingAllowed;

    @JsonProperty("joinCallsAllowed")
    private boolean joinCallsAllowed;

    /** Active and at least one requested communication permission is not approved yet. */
    @JsonProperty("pendingApproval")
    private boolean pendingApproval;

    /** ACTIVE | EXPIRED | REVOKED */
    @JsonProperty("accessStatus")
    private String accessStatus;

    // Enriched caregiver fields (filled for the patient-side view, GET /api/patients/me/caregivers)
    @JsonProperty("caregiverName")
    private String caregiverName;

    @JsonProperty("caregiverEmail")
    private String caregiverEmail;

    // Enriched patient fields (date of birth, blood type and health status are only filled when the
    // grant allows viewing records)
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
