package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateConsultationRequest;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService consultationService;

    @PostMapping
    public ResponseEntity<ConsultationResponse> createConsultation(@RequestBody CreateConsultationRequest request) {
        ConsultationResponse response = consultationService.createConsultation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConsultationResponse> getConsultationById(@PathVariable Integer id) {
        ConsultationResponse response = consultationService.getConsultationById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getPatientConsultations(@PathVariable String patientSystemId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByPatientSystemId(patientSystemId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/appointment/{appointmentId}")
    public ResponseEntity<List<ConsultationResponse>> getAppointmentConsultations(@PathVariable Integer appointmentId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByAppointmentId(appointmentId);
        return ResponseEntity.ok(consultations);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConsultationResponse> updateConsultation(
            @PathVariable Integer id,
            @RequestBody CreateConsultationRequest request) {
        ConsultationResponse response = consultationService.updateConsultation(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ConsultationResponse> updateConsultationStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        ConsultationResponse response = consultationService.updateConsultationStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConsultation(@PathVariable Integer id) {
        consultationService.deleteConsultation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/completed")
    public ResponseEntity<List<ConsultationResponse>> getMyCompletedConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(systemId, userRole);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/me/in-progress")
    public ResponseEntity<List<ConsultationResponse>> getMyInProgressConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ConsultationResponse> consultations = consultationService.getInProgressForUser(systemId, userRole);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/me/cancelled")
    public ResponseEntity<List<ConsultationResponse>> getMyCancelledConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ConsultationResponse> consultations = consultationService.getCancelledForUser(systemId, userRole);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/me/draft")
    public ResponseEntity<List<ConsultationResponse>> getMyDraftConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ConsultationResponse> consultations = consultationService.getDraftForUser(systemId, userRole);
        return ResponseEntity.ok(consultations);
    }
}
