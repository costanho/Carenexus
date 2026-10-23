package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreatePrescriptionRequest;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    @PostMapping
    public ResponseEntity<PrescriptionResponse> createPrescription(@RequestBody CreatePrescriptionRequest request) {
        PrescriptionResponse response = prescriptionService.createPrescription(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrescriptionResponse> getPrescriptionById(@PathVariable Integer id) {
        PrescriptionResponse response = prescriptionService.getPrescriptionById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<PrescriptionResponse>> getPatientPrescriptions(@PathVariable String patientSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getPatientPrescriptions(patientSystemId);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorSystemId);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/consultation/{consultationId}")
    public ResponseEntity<List<PrescriptionResponse>> getConsultationPrescriptions(@PathVariable Integer consultationId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getConsultationPrescriptions(consultationId);
        return ResponseEntity.ok(prescriptions);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PrescriptionResponse> updatePrescription(
            @PathVariable Integer id,
            @RequestBody CreatePrescriptionRequest request) {
        PrescriptionResponse response = prescriptionService.updatePrescription(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<PrescriptionResponse> updatePrescriptionStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrescription(@PathVariable Integer id) {
        prescriptionService.deletePrescription(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/active")
    public ResponseEntity<List<PrescriptionResponse>> getMyActivePrescriptions(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(systemId, userRole);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/me/completed")
    public ResponseEntity<List<PrescriptionResponse>> getMyCompletedPrescriptions(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCompletedForUser(systemId, userRole);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/me/cancelled")
    public ResponseEntity<List<PrescriptionResponse>> getMyCancelledPrescriptions(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCancelledForUser(systemId, userRole);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/me/expired")
    public ResponseEntity<List<PrescriptionResponse>> getMyExpiredPrescriptions(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getExpiredForUser(systemId, userRole);
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/me/on-hold")
    public ResponseEntity<List<PrescriptionResponse>> getMyOnHoldPrescriptions(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getOnHoldForUser(systemId, userRole);
        return ResponseEntity.ok(prescriptions);
    }
}
