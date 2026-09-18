package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.MedicalRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors/medical-records")
@RequiredArgsConstructor
public class DoctorMedicalRecordController {

    private final MedicalRecordService medicalRecordService;
    private final DoctorRepository doctorRepository;

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    // Get All Records
    @GetMapping
    public ResponseEntity<List<MedicalRecordResponse>> getMyMedicalRecords(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorMedicalRecords(@PathVariable Integer doctorId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorId);
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - CONSULT
    @GetMapping("/type/consult")
    public ResponseEntity<List<MedicalRecordResponse>> getMyConsultRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getConsultRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/consult")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorConsultRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getConsultRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - LAB
    @GetMapping("/type/lab")
    public ResponseEntity<List<MedicalRecordResponse>> getMyLabRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getLabRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/lab")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorLabRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getLabRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - IMAGING
    @GetMapping("/type/imaging")
    public ResponseEntity<List<MedicalRecordResponse>> getMyImagingRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getImagingRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/imaging")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorImagingRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getImagingRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - PRESCRIPTION
    @GetMapping("/type/prescription")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPrescriptionRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPrescriptionRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/prescription")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPrescriptionRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getPrescriptionRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - REFERRAL
    @GetMapping("/type/referral")
    public ResponseEntity<List<MedicalRecordResponse>> getMyReferralRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getReferralRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/referral")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorReferralRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getReferralRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - DISCHARGE
    @GetMapping("/type/discharge")
    public ResponseEntity<List<MedicalRecordResponse>> getMyDischargeRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDischargeRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/discharge")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorDischargeRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getDischargeRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - NOTE
    @GetMapping("/type/note")
    public ResponseEntity<List<MedicalRecordResponse>> getMyNoteRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getNoteRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/note")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorNoteRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getNoteRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - OPERATION
    @GetMapping("/type/operation")
    public ResponseEntity<List<MedicalRecordResponse>> getMyOperationRecords(
            @RequestAttribute("userId") Integer userId) {
        List<MedicalRecordResponse> records = medicalRecordService.getOperationRecordsForUser(userId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorId}/type/operation")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorOperationRecords(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<MedicalRecordResponse> records = medicalRecordService.getOperationRecordsForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Generic Filter by Record Type
    @GetMapping("/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyRecordsByType(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String recordType) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorRecordsByType(
            @PathVariable Integer doctorId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    // Get Records for Specific Patient
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPatientRecords(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);

        List<MedicalRecordResponse> doctorRecords = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId))
                .toList();

        return ResponseEntity.ok(doctorRecords);
    }

    @GetMapping("/{doctorId}/patient/{patientId}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPatientRecords(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);

        List<MedicalRecordResponse> doctorRecords = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId))
                .toList();

        return ResponseEntity.ok(doctorRecords);
    }

    // Get Records for Specific Patient by Type
    @GetMapping("/patient/{patientId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPatientRecordsByType(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId,
            @PathVariable String recordType) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId) && r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/patient/{patientId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPatientRecordsByType(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId) && r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    // Get Specific Record
    @GetMapping("/record/{recordId}")
    public ResponseEntity<MedicalRecordResponse> getMyRecord(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer recordId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        MedicalRecordResponse record = medicalRecordService.getRecordById(recordId);

        if (!record.getDoctorId().equals(doctorId)) {
            throw new RuntimeException("You don't have access to this record");
        }

        return ResponseEntity.ok(record);
    }

    @GetMapping("/{doctorId}/record/{recordId}")
    public ResponseEntity<MedicalRecordResponse> getDoctorRecord(
            @PathVariable Integer doctorId,
            @PathVariable Integer recordId) {
        MedicalRecordResponse record = medicalRecordService.getRecordById(recordId);

        if (!record.getDoctorId().equals(doctorId)) {
            throw new RuntimeException("This record doesn't belong to this doctor");
        }

        return ResponseEntity.ok(record);
    }

    // Medical Record Statistics
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyMedicalRecordStats(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> allRecords = medicalRecordService.getDoctorRecords(doctorId);

        Map<String, Object> stats = buildRecordStats(allRecords);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorMedicalRecordStats(@PathVariable Integer doctorId) {
        List<MedicalRecordResponse> allRecords = medicalRecordService.getDoctorRecords(doctorId);

        Map<String, Object> stats = buildRecordStats(allRecords);
        return ResponseEntity.ok(stats);
    }

    // Patient Medical Record Statistics
    @GetMapping("/patient/{patientId}/stats")
    public ResponseEntity<Map<String, Object>> getMyPatientRecordStats(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);

        List<MedicalRecordResponse> doctorRecords = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId))
                .toList();

        Map<String, Object> stats = buildRecordStats(doctorRecords);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorId}/patient/{patientId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPatientRecordStats(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientId);

        List<MedicalRecordResponse> doctorRecords = records.stream()
                .filter(r -> r.getDoctorId().equals(doctorId))
                .toList();

        Map<String, Object> stats = buildRecordStats(doctorRecords);
        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> buildRecordStats(List<MedicalRecordResponse> records) {
        Map<String, Object> stats = new HashMap<>();

        long consultCount = records.stream().filter(r -> "CONSULT".equals(r.getRecordType())).count();
        long labCount = records.stream().filter(r -> "LAB".equals(r.getRecordType())).count();
        long imagingCount = records.stream().filter(r -> "IMAGING".equals(r.getRecordType())).count();
        long prescriptionCount = records.stream().filter(r -> "PRESCRIPTION".equals(r.getRecordType())).count();
        long referralCount = records.stream().filter(r -> "REFERRAL".equals(r.getRecordType())).count();
        long dischargeCount = records.stream().filter(r -> "DISCHARGE".equals(r.getRecordType())).count();
        long noteCount = records.stream().filter(r -> "NOTE".equals(r.getRecordType())).count();
        long operationCount = records.stream().filter(r -> "OPERATION".equals(r.getRecordType())).count();

        stats.put("totalRecords", records.size());
        stats.put("consultCount", consultCount);
        stats.put("labCount", labCount);
        stats.put("imagingCount", imagingCount);
        stats.put("prescriptionCount", prescriptionCount);
        stats.put("referralCount", referralCount);
        stats.put("dischargeCount", dischargeCount);
        stats.put("noteCount", noteCount);
        stats.put("operationCount", operationCount);

        return stats;
    }
}
