package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One caregiver grant (dependent_access row) with the people it links. The can* data flags are "
        + "the stored values; decide what a caregiver may do with the effective rule (FULL_ACCESS grants every data "
        + "permission, VIEW_ONLY blocks the write flags, expired or revoked grants give nothing)")
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

    /** View the patient's invoices, payments and insurance claims. */
    @JsonProperty("canViewBilling")
    private Boolean canViewBilling;

    /** Pay invoices and manage payment methods and claims for the patient. */
    @JsonProperty("canManageBilling")
    private Boolean canManageBilling;

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
    @Schema(description = "ACTIVE, EXPIRED or REVOKED (computed)")
    private String accessStatus;

    /** When the creating caregiver attested to being the parent or legal guardian; null when not attested. */
    @JsonProperty("guardianAttestedAt")
    @Schema(description = "When the caregiver attested to being the parent or legal guardian (UTC); null when not attested")
    private Instant guardianAttestedAt;

    /** True while the patient's account has no password (it cannot sign in). */
    @JsonProperty("managed")
    @Schema(description = "True while the patient's account has no password and cannot sign in")
    private boolean managed;

    /**
     * Caregiver view: managed AND the caller created the dependent AND the grant is active. Always false in the
     * patient view.
     */
    @JsonProperty("canInvite")
    @Schema(description = "Caregiver view: the caller may invite this managed dependent to sign in")
    private boolean canInvite;

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

    /** users.phone, else patients.contact_phone, else null. */
    @JsonProperty("patientPhone")
    @Schema(description = "The patient's phone: the account phone, else the contact phone")
    private String patientPhone;

    @JsonProperty("patientHealthStatus")
    private String patientHealthStatus;

    @JsonProperty("patientDateOfBirth")
    private LocalDate patientDateOfBirth;

    @JsonProperty("patientGender")
    private String patientGender;

    @JsonProperty("patientBloodType")
    private String patientBloodType;
}
