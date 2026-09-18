package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Integer> {
    List<EmergencyContact> findByPatientId(Integer patientId);
    Optional<EmergencyContact> findByPatientIdAndIsPrimaryTrue(Integer patientId);
    List<EmergencyContact> findByPatientIdAndIsCaregiver(Integer patientId, Boolean isCaregiver);
    List<EmergencyContact> findByPatientIdAndIsPrimary(Integer patientId, Boolean isPrimary);
    Optional<EmergencyContact> findByContactIdAndPatientId(Integer contactId, Integer patientId);
}
