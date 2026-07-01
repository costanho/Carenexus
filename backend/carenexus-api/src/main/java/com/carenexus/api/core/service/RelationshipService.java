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
    public boolean isDoctorAssignedToPatient(Integer doctorId, Integer patientId) {
        return careTeamRepository.findActiveDoctorPatientRelationship(doctorId, patientId).isPresent();
    }

    /**
     * Get all patients assigned to a doctor
     * Returns patient IDs that doctor can access
     */
    public List<Integer> getPatientsForDoctor(Integer doctorId) {
        return careTeamRepository.findAllPatientsForDoctor(doctorId)
                .stream()
                .map(team -> team.getPatientId())
                .collect(Collectors.toList());
    }

    /**
     * Get all doctors assigned to a patient
     */
    public List<Integer> getDoctorsForPatient(Integer patientId) {
        return careTeamRepository.findAllDoctorsForPatient(patientId)
                .stream()
                .map(team -> team.getDoctorId())
                .collect(Collectors.toList());
    }

    // ==================== CAREGIVER-PATIENT RELATIONSHIPS ====================

    /**
     * Check if caregiver is assigned to patient
     * Caregivers can only see patients in care_team with their caregiverId
     */
    public boolean isCaregiverAssignedToPatient(Integer caregiverId, Integer patientId) {
        return careTeamRepository.findActiveCaregiverPatientRelationship(caregiverId, patientId).isPresent();
    }

    /**
     * Get all patients assigned to a caregiver
     */
    public List<Integer> getPatientsForCaregiver(Integer caregiverId) {
        return careTeamRepository.findAllPatientsForCaregiver(caregiverId)
                .stream()
                .map(team -> team.getPatientId())
                .collect(Collectors.toList());
    }

    /**
     * Get all caregivers assigned to a patient
     */
    public List<Integer> getCaregiversForPatient(Integer patientId) {
        return careTeamRepository.findAllCaregiversForPatient(patientId)
                .stream()
                .map(team -> team.getCaregiverId())
                .collect(Collectors.toList());
    }

    // ==================== DEPENDENT ACCESS (for caregivers/parents) ====================

    /**
     * Check if caregiver has access to dependent's data
     * Different from care_team: used for informal caregivers, family members, parents
     * Parent (guardian) grants access to caregiver for dependent child
     */
    public boolean hasAccessToDependent(Integer caregiverId, Integer dependentId) {
        return dependentAccessRepository.findActiveCaregiverAccessToDependents(caregiverId, dependentId).isPresent();
    }

    /**
     * Check if caregiver has specific permission type for dependent
     * e.g., "FULL", "HEALTH_RECORDS_ONLY", "ALERTS_ONLY"
     */
    public boolean hasPermissionTypeForDependent(Integer caregiverId, Integer dependentId, String permissionType) {
        return dependentAccessRepository.findAccessWithPermissionType(caregiverId, dependentId, permissionType).isPresent();
    }

    /**
     * Get all dependents a caregiver has access to
     */
    public List<Integer> getDependentsForCaregiver(Integer caregiverId) {
        return dependentAccessRepository.findAllDependentsForCaregiver(caregiverId)
                .stream()
                .map(access -> access.getDependentId())
                .collect(Collectors.toList());
    }

    /**
     * Get all caregivers with access to a dependent
     */
    public List<Integer> getCaregiversForDependent(Integer dependentId) {
        return dependentAccessRepository.findAllCaregiversForDependent(dependentId)
                .stream()
                .map(access -> access.getCaregiverId())
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
    public boolean canAccessPatientData(Integer userId, Integer patientId) {
        // Rule 1: User IS the patient
        if (userId.equals(patientId)) {
            return true;
        }

        // Rule 2: User is doctor assigned to patient
        if (isDoctorAssignedToPatient(userId, patientId)) {
            return true;
        }

        // Rule 3: User is caregiver assigned to patient (formal care_team)
        if (isCaregiverAssignedToPatient(userId, patientId)) {
            return true;
        }

        // Rule 4: User has dependent access (informal caregiver/parent)
        if (hasAccessToDependent(userId, patientId)) {
            return true;
        }

        return false;
    }

    /**
     * Revoke doctor access to patient
     * Mark care_team relationship as inactive
     */
    public void revokeDoctorAccessToPatient(Integer doctorId, Integer patientId) {
        careTeamRepository.findActiveDoctorPatientRelationship(doctorId, patientId)
                .ifPresent(relationship -> {
                    relationship.setIsActive(false);
                    relationship.setUnassignedAt(java.time.LocalDateTime.now());
                    careTeamRepository.save(relationship);
                });
    }

    /**
     * Revoke caregiver access to dependent
     */
    public void revokeCaregiverAccessToDependent(Integer caregiverId, Integer dependentId) {
        dependentAccessRepository.findActiveCaregiverAccessToDependents(caregiverId, dependentId)
                .ifPresent(access -> {
                    access.setIsActive(false);
                    access.setRevokedAt(java.time.LocalDateTime.now());
                    dependentAccessRepository.save(access);
                });
    }
}
