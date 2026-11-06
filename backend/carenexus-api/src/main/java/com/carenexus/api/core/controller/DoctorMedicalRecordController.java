package com.carenexus.api.core.controller;

import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.service.MedicalRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doctor-facing medical record views. Access is enforced in MedicalRecordService (every list is scoped to a
 * doctor and requires the caller to be that doctor or an admin); the by-id routes additionally check here that
 * the record was written by that doctor.
 */
@RestController
@RequestMapping("/api/doctors/medical-records")
@RequiredArgsConstructor
public class DoctorMedicalRecordController {

    private final MedicalRecordService medicalRecordService;
    private final AuthorizationService authorizationService;

    // Get All Records
    @GetMapping
    public ResponseEntity<List<MedicalRecordResponse>> getMyMedicalRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(systemId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorMedicalRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorSystemId);
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - CONSULT
    @GetMapping("/type/consult")
    public ResponseEntity<List<MedicalRecordResponse>> getMyConsultRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getConsultRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/consult")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorConsultRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getConsultRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - LAB
    @GetMapping("/type/lab")
    public ResponseEntity<List<MedicalRecordResponse>> getMyLabRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getLabRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/lab")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorLabRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getLabRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - IMAGING
    @GetMapping("/type/imaging")
    public ResponseEntity<List<MedicalRecordResponse>> getMyImagingRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getImagingRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/imaging")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorImagingRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getImagingRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - PRESCRIPTION
    @GetMapping("/type/prescription")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPrescriptionRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPrescriptionRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/prescription")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPrescriptionRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPrescriptionRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - REFERRAL
    @GetMapping("/type/referral")
    public ResponseEntity<List<MedicalRecordResponse>> getMyReferralRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getReferralRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/referral")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorReferralRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getReferralRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - DISCHARGE
    @GetMapping("/type/discharge")
    public ResponseEntity<List<MedicalRecordResponse>> getMyDischargeRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDischargeRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/discharge")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorDischargeRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDischargeRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - NOTE
    @GetMapping("/type/note")
    public ResponseEntity<List<MedicalRecordResponse>> getMyNoteRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getNoteRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/note")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorNoteRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getNoteRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Filter by Record Type - OPERATION
    @GetMapping("/type/operation")
    public ResponseEntity<List<MedicalRecordResponse>> getMyOperationRecords(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getOperationRecordsForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    @GetMapping("/{doctorSystemId}/type/operation")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorOperationRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getOperationRecordsForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(records);
    }

    // Generic Filter by Record Type
    @GetMapping("/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyRecordsByType(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(systemId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorRecordsByType(
            @PathVariable String doctorSystemId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorSystemId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    // Get Records for Specific Patient
    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPatientRecords(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(medicalRecordService.getDoctorPatientRecords(systemId, patientSystemId));
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPatientRecords(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(medicalRecordService.getDoctorPatientRecords(doctorSystemId, patientSystemId));
    }

    // Get Records for Specific Patient by Type
    @GetMapping("/patient/{patientSystemId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPatientRecordsByType(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorPatientRecords(systemId, patientSystemId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorPatientRecordsByType(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId,
            @PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorPatientRecords(doctorSystemId, patientSystemId);
        String upperType = recordType.toUpperCase();

        List<MedicalRecordResponse> filtered = records.stream()
                .filter(r -> r.getRecordType().equalsIgnoreCase(upperType))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    // Get Specific Record
    @GetMapping("/record/{recordId}")
    public ResponseEntity<MedicalRecordResponse> getMyRecord(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer recordId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        MedicalRecordResponse record = medicalRecordService.getRecordById(recordId);

        if (!record.getDoctorSystemId().equals(systemId)) {
            throw new AuthorizationException("You don't have access to this record", "MEDICAL_RECORD", recordId);
        }

        return ResponseEntity.ok(record);
    }

    @GetMapping("/{doctorSystemId}/record/{recordId}")
    public ResponseEntity<MedicalRecordResponse> getDoctorRecord(
            @PathVariable String doctorSystemId,
            @PathVariable Integer recordId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        MedicalRecordResponse record = medicalRecordService.getRecordById(recordId);

        if (!record.getDoctorSystemId().equals(doctorSystemId)) {
            throw new AuthorizationException("This record doesn't belong to this doctor", "MEDICAL_RECORD", recordId);
        }

        return ResponseEntity.ok(record);
    }

    // Medical Record Statistics
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyMedicalRecordStats(
            @RequestAttribute("systemId") String systemId) {
        List<MedicalRecordResponse> allRecords = medicalRecordService.getDoctorRecords(systemId);

        Map<String, Object> stats = buildRecordStats(allRecords);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorMedicalRecordStats(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> allRecords = medicalRecordService.getDoctorRecords(doctorSystemId);

        Map<String, Object> stats = buildRecordStats(allRecords);
        return ResponseEntity.ok(stats);
    }

    // Patient Medical Record Statistics
    @GetMapping("/patient/{patientSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getMyPatientRecordStats(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        List<MedicalRecordResponse> doctorRecords = medicalRecordService.getDoctorPatientRecords(systemId, patientSystemId);

        Map<String, Object> stats = buildRecordStats(doctorRecords);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPatientRecordStats(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        List<MedicalRecordResponse> doctorRecords = medicalRecordService.getDoctorPatientRecords(doctorSystemId, patientSystemId);

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
