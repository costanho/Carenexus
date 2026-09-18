package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.MedicalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Integer> {
    List<MedicalRecord> findByPatientIdAndIsDeletedFalse(Integer patientId);
    List<MedicalRecord> findByDoctorIdAndIsDeletedFalse(Integer doctorId);
    List<MedicalRecord> findByRecordTypeAndIsDeletedFalse(String recordType);
    List<MedicalRecord> findByPatientIdAndRecordTypeAndIsDeletedFalse(Integer patientId, String recordType);
    List<MedicalRecord> findByDoctorIdAndRecordTypeAndIsDeletedFalse(Integer doctorId, String recordType);
    List<MedicalRecord> findByConsultationIdAndIsDeletedFalse(Integer consultationId);
    Optional<MedicalRecord> findByRecordIdAndIsDeletedFalse(Integer recordId);
}
