package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Integer> {
    List<EmergencyContact> findByPatientSystemId(String patientSystemId);
    Optional<EmergencyContact> findByPatientSystemIdAndIsPrimaryTrue(String patientSystemId);
    List<EmergencyContact> findByPatientSystemIdAndIsCaregiver(String patientSystemId, Boolean isCaregiver);
    List<EmergencyContact> findByPatientSystemIdAndIsPrimary(String patientSystemId, Boolean isPrimary);
    Optional<EmergencyContact> findByContactIdAndPatientSystemId(Integer contactId, String patientSystemId);
}
