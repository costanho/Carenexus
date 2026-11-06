package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doctor-facing consultation views. Access is enforced in ConsultationService: every /{doctorSystemId}/...
 * route requires the caller to be that doctor (or an admin), the self routes require the DOCTOR role, and the
 * status changes require the authoring doctor.
 */
@RestController
@RequestMapping("/api/doctors/consultations")
@RequiredArgsConstructor
public class DoctorConsultationController {

    private static final List<String> ACTIVE_ORDER = List.of("IN_PROGRESS", "DRAFT", "COMPLETED");

    private final ConsultationService consultationService;

    @GetMapping
    public ResponseEntity<List<ConsultationResponse>> getMyConsultations(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(activeInDisplayOrder(consultationService.getConsultationsByDoctorSystemId(systemId)));
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
        return ResponseEntity.ok(activeInDisplayOrder(
                consultationService.getDoctorPatientConsultations(systemId, patientSystemId)));
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultationsWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(consultationService.getDoctorPatientConsultations(doctorSystemId, patientSystemId));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyConsultationStats(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(buildStats(consultationService.getConsultationsByDoctorSystemId(systemId)));
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorConsultationStats(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(buildStats(consultationService.getConsultationsByDoctorSystemId(doctorSystemId)));
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

    /** Non-cancelled consultations: in progress first, then drafts, then completed. */
    private static List<ConsultationResponse> activeInDisplayOrder(List<ConsultationResponse> consultations) {
        return consultations.stream()
                .filter(c -> ACTIVE_ORDER.contains(c.getStatus()))
                .sorted(Comparator.comparingInt(c -> ACTIVE_ORDER.indexOf(c.getStatus())))
                .toList();
    }

    private static Map<String, Object> buildStats(List<ConsultationResponse> consultations) {
        long draft = countStatus(consultations, "DRAFT");
        long inProgress = countStatus(consultations, "IN_PROGRESS");
        long completed = countStatus(consultations, "COMPLETED");
        long cancelled = countStatus(consultations, "CANCELLED");

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConsultations", (int) (draft + inProgress + completed + cancelled));
        stats.put("draftCount", (int) draft);
        stats.put("inProgressCount", (int) inProgress);
        stats.put("completedCount", (int) completed);
        stats.put("cancelledCount", (int) cancelled);
        stats.put("completionRate", completed == 0 ? 0 : (completed * 100.0) / (completed + cancelled));
        return stats;
    }

    private static long countStatus(List<ConsultationResponse> consultations, String status) {
        return consultations.stream().filter(c -> status.equals(c.getStatus())).count();
    }
}
