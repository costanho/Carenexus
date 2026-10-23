package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.service.MedicalRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/medical-records")
@RequiredArgsConstructor
public class MedicalRecordController {

    private final MedicalRecordService medicalRecordService;

    @PostMapping
    public ResponseEntity<MedicalRecordResponse> createRecord(@RequestBody CreateMedicalRecordRequest request) {
        MedicalRecordResponse response = medicalRecordService.createRecord(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MedicalRecordResponse> getRecordById(@PathVariable Integer id) {
        MedicalRecordResponse response = medicalRecordService.getRecordById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<MedicalRecordResponse>> getPatientRecords(@PathVariable String patientSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getPatientRecords(patientSystemId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorRecords(@PathVariable String doctorSystemId) {
        List<MedicalRecordResponse> records = medicalRecordService.getDoctorRecords(doctorSystemId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/type/{recordType}")
    public ResponseEntity<List<MedicalRecordResponse>> getRecordsByType(@PathVariable String recordType) {
        List<MedicalRecordResponse> records = medicalRecordService.getRecordsByType(recordType);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/consultation/{consultationId}")
    public ResponseEntity<List<MedicalRecordResponse>> getConsultationRecords(@PathVariable Integer consultationId) {
        List<MedicalRecordResponse> records = medicalRecordService.getConsultationRecords(consultationId);
        return ResponseEntity.ok(records);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MedicalRecordResponse> updateRecord(
            @PathVariable Integer id,
            @RequestBody CreateMedicalRecordRequest request) {
        MedicalRecordResponse response = medicalRecordService.updateRecord(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDeleteRecord(@PathVariable Integer id) {
        medicalRecordService.softDeleteRecord(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/consult")
    public ResponseEntity<List<MedicalRecordResponse>> getMyConsultRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getConsultRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/lab")
    public ResponseEntity<List<MedicalRecordResponse>> getMyLabRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getLabRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/imaging")
    public ResponseEntity<List<MedicalRecordResponse>> getMyImagingRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getImagingRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/prescription")
    public ResponseEntity<List<MedicalRecordResponse>> getMyPrescriptionRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getPrescriptionRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/referral")
    public ResponseEntity<List<MedicalRecordResponse>> getMyReferralRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getReferralRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/discharge")
    public ResponseEntity<List<MedicalRecordResponse>> getMyDischargeRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getDischargeRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/note")
    public ResponseEntity<List<MedicalRecordResponse>> getMyNoteRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getNoteRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/me/operation")
    public ResponseEntity<List<MedicalRecordResponse>> getMyOperationRecords(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<MedicalRecordResponse> records = medicalRecordService.getOperationRecordsForUser(systemId, userRole);
        return ResponseEntity.ok(records);
    }
}
