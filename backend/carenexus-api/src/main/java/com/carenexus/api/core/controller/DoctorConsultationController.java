package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors/consultations")
@RequiredArgsConstructor
public class DoctorConsultationController {

    private final ConsultationService consultationService;
    private final DoctorRepository doctorRepository;

    @GetMapping
    public ResponseEntity<List<ConsultationResponse>> getMyConsultations(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(systemId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(systemId, "DOCTOR");
        List<ConsultationResponse> draft = consultationService.getDraftForUser(systemId, "DOCTOR");

        List<ConsultationResponse> all = new java.util.ArrayList<>();
        all.addAll(inProgress);
        all.addAll(draft);
        all.addAll(consultations);

        return ResponseEntity.ok(all);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/draft")
    public ResponseEntity<List<ConsultationResponse>> getMyDraftConsultations(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> consultations = consultationService.getDraftForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorSystemId}/draft")
    public ResponseEntity<List<ConsultationResponse>> getDoctorDraftConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getDraftForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/in-progress")
    public ResponseEntity<List<ConsultationResponse>> getMyInProgressConsultations(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> consultations = consultationService.getInProgressForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorSystemId}/in-progress")
    public ResponseEntity<List<ConsultationResponse>> getDoctorInProgressConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getInProgressForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<ConsultationResponse>> getMyCompletedConsultations(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorSystemId}/completed")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCompletedConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<ConsultationResponse>> getMyCancelledConsultations(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> consultations = consultationService.getCancelledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorSystemId}/cancelled")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCancelledConsultations(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> consultations = consultationService.getCancelledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getMyConsultationsWithPatient(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        List<ConsultationResponse> allConsultations = consultationService.getCompletedForUser(systemId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(systemId, "DOCTOR");
        List<ConsultationResponse> draft = consultationService.getDraftForUser(systemId, "DOCTOR");

        List<ConsultationResponse> all = new java.util.ArrayList<>();
        all.addAll(inProgress);
        all.addAll(draft);
        all.addAll(allConsultations);

        return ResponseEntity.ok(all.stream()
                .filter(c -> c.getPatientSystemId().equals(patientSystemId))
                .toList());
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultationsWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(consultations.stream()
                .filter(c -> c.getPatientSystemId().equals(patientSystemId))
                .toList());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyConsultationStats(
            @RequestAttribute("systemId") String systemId) {
        List<ConsultationResponse> draft = consultationService.getDraftForUser(systemId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(systemId, "DOCTOR");
        List<ConsultationResponse> completed = consultationService.getCompletedForUser(systemId, "DOCTOR");
        List<ConsultationResponse> cancelled = consultationService.getCancelledForUser(systemId, "DOCTOR");

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConsultations", draft.size() + inProgress.size() + completed.size() + cancelled.size());
        stats.put("draftCount", draft.size());
        stats.put("inProgressCount", inProgress.size());
        stats.put("completedCount", completed.size());
        stats.put("cancelledCount", cancelled.size());
        stats.put("completionRate", completed.isEmpty() ? 0 :
            (completed.size() * 100.0) / (completed.size() + cancelled.size()));

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorConsultationStats(@PathVariable String doctorSystemId) {
        List<ConsultationResponse> draft = consultationService.getDraftForUser(doctorSystemId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(doctorSystemId, "DOCTOR");
        List<ConsultationResponse> completed = consultationService.getCompletedForUser(doctorSystemId, "DOCTOR");
        List<ConsultationResponse> cancelled = consultationService.getCancelledForUser(doctorSystemId, "DOCTOR");

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConsultations", draft.size() + inProgress.size() + completed.size() + cancelled.size());
        stats.put("draftCount", draft.size());
        stats.put("inProgressCount", inProgress.size());
        stats.put("completedCount", completed.size());
        stats.put("cancelledCount", cancelled.size());
        stats.put("completionRate", completed.isEmpty() ? 0 :
            (completed.size() * 100.0) / (completed.size() + cancelled.size()));

        return ResponseEntity.ok(stats);
    }

    @PatchMapping("/{consultationId}/mark-in-progress")
    public ResponseEntity<ConsultationResponse> markConsultationInProgress(
            @PathVariable Integer consultationId) {
        ConsultationResponse response = consultationService.updateConsultationStatus(consultationId, "IN_PROGRESS");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{consultationId}/mark-completed")
    public ResponseEntity<ConsultationResponse> markConsultationCompleted(
            @PathVariable Integer consultationId) {
        ConsultationResponse response = consultationService.updateConsultationStatus(consultationId, "COMPLETED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{consultationId}/mark-cancelled")
    public ResponseEntity<ConsultationResponse> markConsultationCancelled(
            @PathVariable Integer consultationId) {
        ConsultationResponse response = consultationService.updateConsultationStatus(consultationId, "CANCELLED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{consultationId}/status")
    public ResponseEntity<ConsultationResponse> updateConsultationStatus(
            @PathVariable Integer consultationId,
            @RequestParam String status) {
        ConsultationResponse response = consultationService.updateConsultationStatus(consultationId, status);
        return ResponseEntity.ok(response);
    }
}
