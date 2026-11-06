package com.carenexus.api.common.service;

import com.carenexus.api.core.model.DependentAccess;

import java.util.function.Function;

/**
 * What a caller wants to do with a patient's data. Used by
 * {@link AuthorizationService#requirePatientAccess(String, PatientPermission)}.
 *
 * Each permission maps to the dependent_access flag a CAREGIVER needs. Patients (self), related doctors
 * and admins are not restricted by these flags.
 *
 * Caregiver rule: access_level FULL_ACCESS grants everything; otherwise the mapped flag must be TRUE, and for
 * write permissions ({@link #isWrite()}) access_level must also not be VIEW_ONLY.
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
    /** Message the patient's care team. */
    MESSAGE_CARE_TEAM("MESSAGE", false, DependentAccess::getCanMessageCareTeam);

    private final String resourceType;
    private final boolean write;
    private final Function<DependentAccess, Boolean> flag;

    PatientPermission(String resourceType, boolean write, Function<DependentAccess, Boolean> flag) {
        this.resourceType = resourceType;
        this.write = write;
        this.flag = flag;
    }

    /** Default resource type used in audit rows for this permission. */
    public String getResourceType() {
        return resourceType;
    }

    /** True for permissions that change data. */
    public boolean isWrite() {
        return write;
    }

    /**
     * Whether this dependent_access row grants the permission. Does NOT check is_active / revoked_at /
     * expires_at; callers pass rows from the expiry-aware repository queries.
     */
    public boolean isGrantedBy(DependentAccess access) {
        if (access == null) {
            return false;
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
