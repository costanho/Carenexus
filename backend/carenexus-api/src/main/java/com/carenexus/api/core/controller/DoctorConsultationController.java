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

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    @GetMapping
    public ResponseEntity<List<ConsultationResponse>> getMyConsultations(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(userId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(userId, "DOCTOR");
        List<ConsultationResponse> draft = consultationService.getDraftForUser(userId, "DOCTOR");

        List<ConsultationResponse> all = new java.util.ArrayList<>();
        all.addAll(inProgress);
        all.addAll(draft);
        all.addAll(consultations);

        return ResponseEntity.ok(all);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultations(@PathVariable Integer doctorId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByDoctorId(doctorId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/draft")
    public ResponseEntity<List<ConsultationResponse>> getMyDraftConsultations(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> consultations = consultationService.getDraftForUser(userId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorId}/draft")
    public ResponseEntity<List<ConsultationResponse>> getDoctorDraftConsultations(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<ConsultationResponse> consultations = consultationService.getDraftForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/in-progress")
    public ResponseEntity<List<ConsultationResponse>> getMyInProgressConsultations(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> consultations = consultationService.getInProgressForUser(userId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorId}/in-progress")
    public ResponseEntity<List<ConsultationResponse>> getDoctorInProgressConsultations(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<ConsultationResponse> consultations = consultationService.getInProgressForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<ConsultationResponse>> getMyCompletedConsultations(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(userId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorId}/completed")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCompletedConsultations(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<ConsultationResponse> consultations = consultationService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<ConsultationResponse>> getMyCancelledConsultations(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> consultations = consultationService.getCancelledForUser(userId, "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/{doctorId}/cancelled")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCancelledConsultations(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<ConsultationResponse> consultations = consultationService.getCancelledForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<ConsultationResponse>> getMyConsultationsWithPatient(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        List<ConsultationResponse> allConsultations = consultationService.getCompletedForUser(userId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(userId, "DOCTOR");
        List<ConsultationResponse> draft = consultationService.getDraftForUser(userId, "DOCTOR");

        List<ConsultationResponse> all = new java.util.ArrayList<>();
        all.addAll(inProgress);
        all.addAll(draft);
        all.addAll(allConsultations);

        return ResponseEntity.ok(all.stream()
                .filter(c -> c.getPatientId().equals(patientId))
                .toList());
    }

    @GetMapping("/{doctorId}/patient/{patientId}")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultationsWithPatient(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<ConsultationResponse> consultations = consultationService.getConsultationsByDoctorId(doctorId);
        return ResponseEntity.ok(consultations.stream()
                .filter(c -> c.getPatientId().equals(patientId))
                .toList());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyConsultationStats(
            @RequestAttribute("userId") Integer userId) {
        List<ConsultationResponse> draft = consultationService.getDraftForUser(userId, "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(userId, "DOCTOR");
        List<ConsultationResponse> completed = consultationService.getCompletedForUser(userId, "DOCTOR");
        List<ConsultationResponse> cancelled = consultationService.getCancelledForUser(userId, "DOCTOR");

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

    @GetMapping("/{doctorId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorConsultationStats(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<ConsultationResponse> draft = consultationService.getDraftForUser(doctor.getUserId(), "DOCTOR");
        List<ConsultationResponse> inProgress = consultationService.getInProgressForUser(doctor.getUserId(), "DOCTOR");
        List<ConsultationResponse> completed = consultationService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
        List<ConsultationResponse> cancelled = consultationService.getCancelledForUser(doctor.getUserId(), "DOCTOR");

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
