package com.carenexus.api.core.service;

import com.carenexus.api.core.repository.CareTeamRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RelationshipService {

    private final CareTeamRepository careTeamRepository;
    private final DependentAccessRepository dependentAccessRepository;

    // ==================== DOCTOR-PATIENT RELATIONSHIPS ====================

    /**
     * Check if doctor is assigned to patient
     * Doctors can only see patients in their care_team
     */
    public boolean isDoctorAssignedToPatient(String doctorSystemId, String patientSystemId) {
        return careTeamRepository.findActiveDoctorPatientRelationship(doctorSystemId, patientSystemId).isPresent();
    }

    /**
     * Get all patients assigned to a doctor
     * Returns patient systemIds that doctor can access
     */
    public List<String> getPatientsForDoctor(String doctorSystemId) {
        return careTeamRepository.findAllPatientsForDoctor(doctorSystemId)
                .stream()
                .map(team -> team.getPatientSystemId())
                .collect(Collectors.toList());
    }

    /**
     * Get all doctors assigned to a patient
     */
    public List<String> getDoctorsForPatient(String patientSystemId) {
        return careTeamRepository.findAllDoctorsForPatient(patientSystemId)
                .stream()
                .map(team -> team.getProviderSystemId())
                .collect(Collectors.toList());
    }

    // ==================== CAREGIVER-PATIENT RELATIONSHIPS ====================

    /**
     * Check if caregiver is assigned to patient
     * Caregivers can only see patients in care_team with their caregiverSystemId
     */
    public boolean isCaregiverAssignedToPatient(String caregiverSystemId, String patientSystemId) {
        return careTeamRepository.findActiveCaregiverPatientRelationship(caregiverSystemId, patientSystemId).isPresent();
    }

    /**
     * Get all patients assigned to a caregiver
     */
    public List<String> getPatientsForCaregiver(String caregiverSystemId) {
        return careTeamRepository.findAllPatientsForCaregiver(caregiverSystemId)
                .stream()
                .map(team -> team.getPatientSystemId())
                .collect(Collectors.toList());
    }

    /**
     * Get all caregivers assigned to a patient
     */
    public List<String> getCaregiversForPatient(String patientSystemId) {
        return careTeamRepository.findAllCaregiversForPatient(patientSystemId)
                .stream()
                .map(team -> team.getProviderSystemId())
                .collect(Collectors.toList());
    }

    // ==================== DEPENDENT ACCESS (for caregivers/parents) ====================

    /**
     * Check if caregiver has access to dependent's data
     * Different from care_team: used for informal caregivers, family members, parents
     * Parent (guardian) grants access to caregiver for dependent child
     */
    public boolean hasAccessToDependent(String caregiverSystemId, String dependentSystemId) {
        return dependentAccessRepository.findActiveCaregiverAccessToDependents(caregiverSystemId, dependentSystemId).isPresent();
    }

    /**
     * Check if caregiver has specific permission type for dependent
     * e.g., "FULL", "HEALTH_RECORDS_ONLY", "ALERTS_ONLY"
     */
    public boolean hasPermissionTypeForDependent(String caregiverSystemId, String dependentSystemId, String permissionType) {
        return dependentAccessRepository.findAccessWithPermissionType(caregiverSystemId, dependentSystemId, permissionType).isPresent();
    }

    /**
     * Get all dependents a caregiver has access to
     */
    public List<String> getDependentsForCaregiver(String caregiverSystemId) {
        return dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId)
                .stream()
                .map(access -> access.getPatientSystemId())
                .collect(Collectors.toList());
    }

    /**
     * Get all caregivers with access to a dependent
     */
    public List<String> getCaregiversForDependent(String dependentSystemId) {
        return dependentAccessRepository.findAllCaregiversForDependent(dependentSystemId)
                .stream()
                .map(access -> access.getCaregiverSystemId())
                .collect(Collectors.toList());
    }

    // ==================== COMBINED RELATIONSHIP CHECKS ====================

    /**
     * Comprehensive check: Can this person access this patient's data?
     *
     * Returns true if ANY of these conditions are met:
     * 1. Person IS the patient (owns their own data)
     * 2. Person is doctor assigned in care_team
     * 3. Person is caregiver assigned in care_team
     * 4. Person is caregiver with dependent_access (family/informal caregiver)
     */
    public boolean canAccessPatientData(String userSystemId, String patientSystemId) {
        // Rule 1: User IS the patient
        if (userSystemId.equals(patientSystemId)) {
            return true;
        }

        // Rule 2: User is doctor assigned to patient
        if (isDoctorAssignedToPatient(userSystemId, patientSystemId)) {
            return true;
        }

        // Rule 3: User is caregiver assigned to patient (formal care_team)
        if (isCaregiverAssignedToPatient(userSystemId, patientSystemId)) {
            return true;
        }

        // Rule 4: User has dependent access (informal caregiver/parent)
        if (hasAccessToDependent(userSystemId, patientSystemId)) {
            return true;
        }

        return false;
    }

    /**
     * Revoke doctor access to patient
     * Mark care_team relationship as inactive
     */
    public void revokeDoctorAccessToPatient(String doctorSystemId, String patientSystemId) {
        careTeamRepository.findActiveDoctorPatientRelationship(doctorSystemId, patientSystemId)
                .ifPresent(relationship -> {
                    relationship.setIsActive(false);
                    relationship.setUnassignedAt(java.time.LocalDateTime.now());
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
                    access.setRevokedAt(java.time.LocalDateTime.now());
                    dependentAccessRepository.save(access);
                });
    }
}
