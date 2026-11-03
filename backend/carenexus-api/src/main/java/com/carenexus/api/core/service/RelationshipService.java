package com.carenexus.api.core.service;

import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.CareTeamRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for "who is related to which patient".
 *
 * Doctor - patient: an active care_team assignment (role PRIMARY_DOCTOR / DOCTOR / SPECIALIST), OR any
 * non-cancelled appointment, OR any consultation between the two. Appointments/consultations are the links the
 * app actually creates today; DoctorPatientService builds the doctor's patient list from them as well.
 *
 * Caregiver - patient: an active dependent_access row (is_active, not revoked, not expired). Per-resource
 * permissions are on that row; see PatientPermission. care_team caregiver rows do NOT grant access.
 */
@Service
@RequiredArgsConstructor
public class RelationshipService {

    private final CareTeamRepository careTeamRepository;
    private final DependentAccessRepository dependentAccessRepository;

    // ==================== DOCTOR-PATIENT RELATIONSHIPS ====================

    /**
     * Check if doctor has a care relationship with the patient
     * (care_team assignment, non-cancelled appointment, or consultation).
     */
    public boolean isDoctorAssignedToPatient(String doctorSystemId, String patientSystemId) {
        if (doctorSystemId == null || patientSystemId == null) {
            return false;
        }
        return careTeamRepository.findActiveDoctorPatientRelationship(doctorSystemId, patientSystemId).isPresent()
                || careTeamRepository.existsAppointmentLink(doctorSystemId, patientSystemId)
                || careTeamRepository.existsConsultationLink(doctorSystemId, patientSystemId);
    }

    /**
     * Get all patients related to a doctor (care_team + appointments + consultations), de-duplicated.
     */
    public List<String> getPatientsForDoctor(String doctorSystemId) {
        Set<String> patients = new LinkedHashSet<>();
        careTeamRepository.findAllPatientsForDoctor(doctorSystemId)
                .forEach(team -> patients.add(team.getPatientSystemId()));
        patients.addAll(careTeamRepository.findAppointmentPatientSystemIdsForDoctor(doctorSystemId));
        patients.addAll(careTeamRepository.findConsultationPatientSystemIdsForDoctor(doctorSystemId));
        return new ArrayList<>(patients);
    }

    /**
     * Get all doctors formally assigned (care_team) to a patient
     */
    public List<String> getDoctorsForPatient(String patientSystemId) {
        return careTeamRepository.findAllDoctorsForPatient(patientSystemId)
                .stream()
                .map(CareTeam::getProviderSystemId)
                .distinct()
                .collect(Collectors.toList());
    }

    // ==================== CAREGIVER-PATIENT RELATIONSHIPS ====================

    /**
     * Check if caregiver has an active dependent_access grant for the patient.
     * (Kept for existing callers; same as {@link #hasAccessToDependent}.)
     */
    public boolean isCaregiverAssignedToPatient(String caregiverSystemId, String patientSystemId) {
        return hasAccessToDependent(caregiverSystemId, patientSystemId);
    }

    /**
     * Get all patients a caregiver has active access to
     */
    public List<String> getPatientsForCaregiver(String caregiverSystemId) {
        return getDependentsForCaregiver(caregiverSystemId);
    }

    /**
     * Get all caregivers with active access to a patient
     */
    public List<String> getCaregiversForPatient(String patientSystemId) {
        return getCaregiversForDependent(patientSystemId);
    }

    // ==================== DEPENDENT ACCESS (for caregivers/parents) ====================

    /**
     * The caregiver's active (is_active, not revoked, not expired) grant for this patient, if any.
     * Use {@code PatientPermission.isGrantedBy(access)} to check a specific permission.
     */
    public Optional<DependentAccess> getActiveDependentAccess(String caregiverSystemId, String dependentSystemId) {
        if (caregiverSystemId == null || dependentSystemId == null) {
            return Optional.empty();
        }
        return dependentAccessRepository.findActiveCaregiverAccessToDependents(caregiverSystemId, dependentSystemId);
    }

    /**
     * Check if caregiver has an active grant for the dependent's data (any permission).
     */
    public boolean hasAccessToDependent(String caregiverSystemId, String dependentSystemId) {
        return getActiveDependentAccess(caregiverSystemId, dependentSystemId).isPresent();
    }

    /**
     * Check if caregiver has an active grant with the given access level
     * (FULL_ACCESS, VIEW_ONLY, EDIT_ONLY, CUSTOM)
     */
    public boolean hasPermissionTypeForDependent(String caregiverSystemId, String dependentSystemId, String permissionType) {
        return dependentAccessRepository.findAccessWithPermissionType(caregiverSystemId, dependentSystemId, permissionType).isPresent();
    }

    /**
     * Get all dependents a caregiver has active access to
     */
    public List<String> getDependentsForCaregiver(String caregiverSystemId) {
        return dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId)
                .stream()
                .map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toList());
    }

    /**
     * Get all caregivers with active access to a dependent
     */
    public List<String> getCaregiversForDependent(String dependentSystemId) {
        return dependentAccessRepository.findAllCaregiversForDependent(dependentSystemId)
                .stream()
                .map(DependentAccess::getCaregiverSystemId)
                .collect(Collectors.toList());
    }

    // ==================== COMBINED RELATIONSHIP CHECKS ====================

    /**
     * Relationship-only check (ignores roles and per-resource caregiver flags).
     * For authorization use AuthorizationService.requirePatientAccess, which also checks the caller's role
     * and the caregiver's permission flags.
     */
    public boolean canAccessPatientData(String userSystemId, String patientSystemId) {
        if (userSystemId == null || patientSystemId == null) {
            return false;
        }
        return userSystemId.equals(patientSystemId)
                || isDoctorAssignedToPatient(userSystemId, patientSystemId)
                || hasAccessToDependent(userSystemId, patientSystemId);
    }

    /**
     * Revoke a doctor's care_team assignment to a patient.
     * Note: appointment/consultation history still links them (see isDoctorAssignedToPatient).
     */
    public void revokeDoctorAccessToPatient(String doctorSystemId, String patientSystemId) {
        careTeamRepository.findActiveDoctorPatientRelationship(doctorSystemId, patientSystemId)
                .ifPresent(relationship -> {
                    relationship.setIsActive(false);
                    relationship.setUnassignedAt(LocalDateTime.now());
                    careTeamRepository.save(relationship);
                });
    }

    /**
     * Revoke caregiver access to dependent
     */
    public void revokeCaregiverAccessToDependent(String caregiverSystemId, String dependentSystemId) {
        dependentAccessRepository.findActiveCaregiverAccessToDependents(caregiverSystemId, dependentSystemId)
                .ifPresent(access -> {
                    access.setIsActive(false);
                    access.setRevokedAt(LocalDateTime.now());
                    dependentAccessRepository.save(access);
                });
    }
}
