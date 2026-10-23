package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.MedicalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Integer> {
    List<MedicalRecord> findByPatientSystemIdAndIsDeletedFalse(String patientSystemId);
    List<MedicalRecord> findByDoctorSystemIdAndIsDeletedFalse(String doctorSystemId);
    List<MedicalRecord> findByRecordTypeAndIsDeletedFalse(String recordType);
    List<MedicalRecord> findByPatientSystemIdAndRecordTypeAndIsDeletedFalse(String patientSystemId, String recordType);
    List<MedicalRecord> findByDoctorSystemIdAndRecordTypeAndIsDeletedFalse(String doctorSystemId, String recordType);
    List<MedicalRecord> findByConsultationIdAndIsDeletedFalse(Integer consultationId);
    Optional<MedicalRecord> findByRecordIdAndIsDeletedFalse(Integer recordId);
}
