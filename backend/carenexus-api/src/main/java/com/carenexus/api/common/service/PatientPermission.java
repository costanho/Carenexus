package com.carenexus.api.common.service;

import com.carenexus.api.core.model.DependentAccess;

import java.time.Instant;
import java.util.function.Function;

/**
 * What a caller wants to do with a patient's data. Used by
 * {@link AuthorizationService#requirePatientAccess(String, PatientPermission)}.
 *
 * Each permission maps to the dependent_access flag a CAREGIVER needs. Patients (self), related doctors
 * and admins are not restricted by these flags.
 *
 * Caregiver rule for data permissions: access_level FULL_ACCESS grants everything; otherwise the mapped flag must
 * be TRUE, and for write permissions ({@link #isWrite()}) access_level must also not be VIEW_ONLY.
 *
 * Caregiver rule for communication permissions ({@link #isCommunication()}: MESSAGING, JOIN_CALLS and the
 * deprecated MESSAGE_CARE_TEAM): the flag is only a request. It takes effect only with the patient's approval
 * stamp (approved_at set AND approved_by_system_id = the patient). The access level is ignored: FULL_ACCESS does
 * not grant them and VIEW_ONLY does not block them.
 */
public enum PatientPermission {

    /** Basic demographics / profile view (name, contact). Any active caregiver grant is enough. */
    PROFILE("PROFILE", false, access -> Boolean.TRUE),
    /** Edit the patient's profile / emergency contacts. Caregiver needs can_manage_care_plan. */
    MANAGE_PROFILE("PROFILE", true, DependentAccess::getCanManageCarePlan),

    /** Medical records, health summary (blood type, allergies, conditions, health status). */
    RECORDS("MEDICAL_RECORD", false, DependentAccess::getCanViewRecords),
    /** Prescriptions / medication schedules. */
    MEDICATIONS("PRESCRIPTION", false, DependentAccess::getCanViewMedications),
    LAB_RESULTS("LAB_RESULT", false, DependentAccess::getCanViewLabResults),
    IMAGING("IMAGING_RESULT", false, DependentAccess::getCanViewImaging),
    APPOINTMENTS("APPOINTMENT", false, DependentAccess::getCanViewAppointments),
    CONSULTATIONS("CONSULTATION", false, DependentAccess::getCanViewConsultations),

    /** Book / cancel / reschedule appointments on the patient's behalf. */
    MANAGE_APPOINTMENTS("APPOINTMENT", true, DependentAccess::getCanScheduleAppointments),
    /** Change care plans / clinical data on the patient's behalf. */
    MANAGE_CARE_PLAN("CARE_PLAN", true, DependentAccess::getCanManageCarePlan),

    // ---- Billing ----
    /** View invoices, payments and insurance claims. Caregiver needs can_view_billing. */
    BILLING("INVOICE", false, DependentAccess::getCanViewBilling),
    /** Pay invoices, manage payment methods and claims on the patient's behalf. Caregiver needs can_manage_billing. */
    MANAGE_BILLING("INVOICE", true, DependentAccess::getCanManageBilling),

    // ---- Care plans, health tracking, referrals, documents, AI alerts, care team ----
    /** View care plans. Caregiver needs can_view_records or can_manage_care_plan (changes: {@link #MANAGE_CARE_PLAN}). */
    CARE_PLANS("CARE_PLAN", false, access -> Boolean.TRUE.equals(access.getCanViewRecords())
            || Boolean.TRUE.equals(access.getCanManageCarePlan())),
    /** View vitals, readings and symptom logs. Caregiver needs can_view_records. */
    HEALTH_METRICS("HEALTH_METRIC", false, DependentAccess::getCanViewRecords),
    /** Record vitals, readings and symptoms on the patient's behalf. Caregiver needs can_manage_care_plan. */
    MANAGE_HEALTH_METRICS("HEALTH_METRIC", true, DependentAccess::getCanManageCarePlan),
    /** View referrals. Caregiver needs can_view_records. */
    REFERRALS("REFERRAL", false, DependentAccess::getCanViewRecords),
    /** View and download documents. Caregiver needs can_view_records. */
    DOCUMENTS("DOCUMENT", false, DependentAccess::getCanViewRecords),
    /** Upload or remove documents on the patient's behalf. Caregiver needs can_manage_care_plan. */
    MANAGE_DOCUMENTS("DOCUMENT", true, DependentAccess::getCanManageCarePlan),
    /** View confirmed AI alerts. Caregiver needs can_view_records. */
    AI_ALERTS("AI_ALERT", false, DependentAccess::getCanViewRecords),
    /** See who is on the patient's care team. Any active caregiver grant is enough. */
    CARE_TEAM("CARE_TEAM", false, access -> Boolean.TRUE),

    /**
     * Deprecated alias of {@link #MESSAGING} (same rule: can_message_care_team + patient approval).
     * @deprecated use {@link #MESSAGING}
     */
    @Deprecated
    MESSAGE_CARE_TEAM("MESSAGE", false, DependentAccess::getCanMessageCareTeam,
            DependentAccess::getMessagingApprovedAt, DependentAccess::getMessagingApprovedBySystemId),

    /** Send/read saved messages about the patient. Caregiver: can_message_care_team + patient approval. */
    MESSAGING("MESSAGE", false, DependentAccess::getCanMessageCareTeam,
            DependentAccess::getMessagingApprovedAt, DependentAccess::getMessagingApprovedBySystemId),
    /** Request to join / join the patient's video calls. Caregiver: can_join_calls + patient approval. */
    JOIN_CALLS("CALL", false, DependentAccess::getCanJoinCalls,
            DependentAccess::getCallsApprovedAt, DependentAccess::getCallsApprovedBySystemId);

    private final String resourceType;
    private final boolean write;
    private final Function<DependentAccess, Boolean> flag;
    private final Function<DependentAccess, Instant> approvedAt;
    private final Function<DependentAccess, String> approvedBy;

    PatientPermission(String resourceType, boolean write, Function<DependentAccess, Boolean> flag) {
        this(resourceType, write, flag, null, null);
    }

    PatientPermission(String resourceType, boolean write, Function<DependentAccess, Boolean> flag,
                      Function<DependentAccess, Instant> approvedAt, Function<DependentAccess, String> approvedBy) {
        this.resourceType = resourceType;
        this.write = write;
        this.flag = flag;
        this.approvedAt = approvedAt;
        this.approvedBy = approvedBy;
    }

    /** Default resource type used in audit rows for this permission. */
    public String getResourceType() {
        return resourceType;
    }

    /** True for permissions that change data. */
    public boolean isWrite() {
        return write;
    }

    /** True for MESSAGING, JOIN_CALLS and MESSAGE_CARE_TEAM: they need the patient's approval stamp. */
    public boolean isCommunication() {
        return approvedAt != null;
    }

    /** Whether the caregiver asked for (or holds) the flag, ignoring approval. */
    public boolean isRequestedBy(DependentAccess access) {
        return access != null && Boolean.TRUE.equals(flag.apply(access));
    }

    /**
     * Whether this dependent_access row grants the permission. Does NOT check is_active / revoked_at /
     * expires_at; callers pass rows from the expiry-aware repository queries.
     */
    public boolean isGrantedBy(DependentAccess access) {
        if (access == null) {
            return false;
        }
        if (isCommunication()) {
            return Boolean.TRUE.equals(flag.apply(access))
                    && approvedAt.apply(access) != null
                    && access.getPatientSystemId() != null
                    && access.getPatientSystemId().equals(approvedBy.apply(access));
        }
        if ("FULL_ACCESS".equals(access.getAccessLevel())) {
            return true;
        }
        if (write && "VIEW_ONLY".equals(access.getAccessLevel())) {
            return false;
        }
        return Boolean.TRUE.equals(flag.apply(access));
    }
}
