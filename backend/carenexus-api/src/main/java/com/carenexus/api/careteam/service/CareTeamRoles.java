package com.carenexus.api.careteam.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Care-team roles as the API sees them, and how stored values map to them.
 *
 * <ul>
 *   <li>Writable roles: the six PRD roles {@link #WRITABLE}. The API never writes {@link #PRIMARY_DOCTOR}.</li>
 *   <li>Stored legacy {@code PRIMARY_DOCTOR} (test fixtures, V10) reads as {@code DOCTOR} with primary true, and
 *       keeps granting access (CareTeamRepository.DOCTOR_ROLES).</li>
 *   <li>A provider with an account (a doctor) may only hold {@link #ACCOUNT_PROVIDER_ROLES}.</li>
 * </ul>
 */
public final class CareTeamRoles {

    public static final String DOCTOR = "DOCTOR";
    public static final String NURSE = "NURSE";
    public static final String LAB = "LAB";
    public static final String PHARMACY = "PHARMACY";
    public static final String SPECIALIST = "SPECIALIST";
    public static final String THERAPIST = "THERAPIST";

    /** Legacy stored value: reads as DOCTOR + primary. */
    public static final String PRIMARY_DOCTOR = "PRIMARY_DOCTOR";

    public static final List<String> WRITABLE = List.of(DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST, THERAPIST);

    /** Roles a CareNexus doctor account may be assigned with. */
    public static final Set<String> ACCOUNT_PROVIDER_ROLES = Set.of(DOCTOR, SPECIALIST);

    private static final Map<String, String> LABELS = Map.of(
            DOCTOR, "Doctor",
            NURSE, "Nurse",
            LAB, "Lab",
            PHARMACY, "Pharmacy",
            SPECIALIST, "Specialist",
            THERAPIST, "Therapist");

    private CareTeamRoles() {
    }

    /** Trimmed, upper-cased value, or null for null / blank input. */
    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    /** The role the API shows for a stored role. */
    public static String readRole(String storedRole) {
        return PRIMARY_DOCTOR.equals(storedRole) ? DOCTOR : storedRole;
    }

    /** The primary flag the API shows: stored flag OR legacy PRIMARY_DOCTOR. */
    public static boolean readPrimary(String storedRole, Boolean storedPrimary) {
        return Boolean.TRUE.equals(storedPrimary) || PRIMARY_DOCTOR.equals(storedRole);
    }

    /**
     * Stored roles the care-team API shows (same set as CareTeamRepository.MEMBER_ROLES): the six writable roles and
     * legacy PRIMARY_DOCTOR. The legacy caregiver roles are not members.
     */
    public static boolean isMemberRole(String storedRole) {
        return PRIMARY_DOCTOR.equals(storedRole) || WRITABLE.contains(storedRole);
    }

    /** Display label used in notification text (Doctor, Nurse, Lab, Pharmacy, Specialist, Therapist). */
    public static String label(String role) {
        String readRole = readRole(role);
        return LABELS.getOrDefault(readRole, readRole);
    }
}
