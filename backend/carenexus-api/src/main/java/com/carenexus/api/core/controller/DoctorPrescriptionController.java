package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors/prescriptions")
@RequiredArgsConstructor
public class DoctorPrescriptionController {

    private final PrescriptionService prescriptionService;
    private final DoctorRepository doctorRepository;

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    // Get All Prescriptions
    @GetMapping
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(userId, "DOCTOR");
        List<PrescriptionResponse> onHold = prescriptionService.getOnHoldForUser(userId, "DOCTOR");
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptions);
        all.addAll(onHold);
        all.addAll(prescriptionService.getCompletedForUser(userId, "DOCTOR"));
        all.addAll(prescriptionService.getCancelledForUser(userId, "DOCTOR"));
        all.addAll(prescriptionService.getExpiredForUser(userId, "DOCTOR"));
        return ResponseEntity.ok(all);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(doctor.getUserId(), "DOCTOR");
        List<PrescriptionResponse> onHold = prescriptionService.getOnHoldForUser(doctor.getUserId(), "DOCTOR");
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptions);
        all.addAll(onHold);
        all.addAll(prescriptionService.getCompletedForUser(doctor.getUserId(), "DOCTOR"));
        all.addAll(prescriptionService.getCancelledForUser(doctor.getUserId(), "DOCTOR"));
        all.addAll(prescriptionService.getExpiredForUser(doctor.getUserId(), "DOCTOR"));
        return ResponseEntity.ok(all);
    }

    // Filter by Status - ACTIVE
    @GetMapping("/status/active")
    public ResponseEntity<List<PrescriptionResponse>> getMyActivePrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(userId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/active")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorActivePrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    // Filter by Status - COMPLETED
    @GetMapping("/status/completed")
    public ResponseEntity<List<PrescriptionResponse>> getMyCompletedPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCompletedForUser(userId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/completed")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorCompletedPrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    // Filter by Status - CANCELLED
    @GetMapping("/status/cancelled")
    public ResponseEntity<List<PrescriptionResponse>> getMyCancelledPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCancelledForUser(userId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/cancelled")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorCancelledPrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getCancelledForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    // Filter by Status - EXPIRED
    @GetMapping("/status/expired")
    public ResponseEntity<List<PrescriptionResponse>> getMyExpiredPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getExpiredForUser(userId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/expired")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorExpiredPrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getExpiredForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    // Filter by Status - ON_HOLD
    @GetMapping("/status/on-hold")
    public ResponseEntity<List<PrescriptionResponse>> getMyOnHoldPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getOnHoldForUser(userId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/on-hold")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorOnHoldPrescriptions(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<PrescriptionResponse> prescriptions = prescriptionService.getOnHoldForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    // Generic Status Filter
    @GetMapping("/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptionsByStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = switch (status.toUpperCase()) {
            case "ACTIVE" -> prescriptionService.getActiveForUser(userId, "DOCTOR");
            case "COMPLETED" -> prescriptionService.getCompletedForUser(userId, "DOCTOR");
            case "CANCELLED" -> prescriptionService.getCancelledForUser(userId, "DOCTOR");
            case "EXPIRED" -> prescriptionService.getExpiredForUser(userId, "DOCTOR");
            case "ON_HOLD" -> prescriptionService.getOnHoldForUser(userId, "DOCTOR");
            default -> throw new RuntimeException("Invalid status: " + status);
        };
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptionsByStatus(
            @PathVariable Integer doctorId,
            @PathVariable String status) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<PrescriptionResponse> prescriptions = switch (status.toUpperCase()) {
            case "ACTIVE" -> prescriptionService.getActiveForUser(doctor.getUserId(), "DOCTOR");
            case "COMPLETED" -> prescriptionService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
            case "CANCELLED" -> prescriptionService.getCancelledForUser(doctor.getUserId(), "DOCTOR");
            case "EXPIRED" -> prescriptionService.getExpiredForUser(doctor.getUserId(), "DOCTOR");
            case "ON_HOLD" -> prescriptionService.getOnHoldForUser(doctor.getUserId(), "DOCTOR");
            default -> throw new RuntimeException("Invalid status: " + status);
        };
        return ResponseEntity.ok(prescriptions);
    }

    // Filter by Route (Medication Delivery Method)
    @GetMapping("/route/oral")
    public ResponseEntity<List<PrescriptionResponse>> getMyOralPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "ORAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/oral")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorOralPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "ORAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/topical")
    public ResponseEntity<List<PrescriptionResponse>> getMyTopicalPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "TOPICAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/topical")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorTopicalPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "TOPICAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/injection")
    public ResponseEntity<List<PrescriptionResponse>> getMyInjectionPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INJECTION".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/injection")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorInjectionPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INJECTION".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/inhaled")
    public ResponseEntity<List<PrescriptionResponse>> getMyInhaledPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INHALED".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/inhaled")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorInhaledPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INHALED".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/sublingual")
    public ResponseEntity<List<PrescriptionResponse>> getMySublingualPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "SUBLINGUAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/sublingual")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorSublingualPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "SUBLINGUAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/rectal")
    public ResponseEntity<List<PrescriptionResponse>> getMyRectalPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "RECTAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/rectal")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorRectalPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "RECTAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/iv")
    public ResponseEntity<List<PrescriptionResponse>> getMyIVPrescriptions(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "IV".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/iv")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorIVPrescriptions(@PathVariable Integer doctorId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "IV".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/{route}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptionsByRoute(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String route) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        String upperRoute = route.toUpperCase();
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> upperRoute.equals(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/route/{route}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptionsByRoute(
            @PathVariable Integer doctorId,
            @PathVariable String route) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorId);
        String upperRoute = route.toUpperCase();
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> upperRoute.equals(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    // Get Prescriptions for Specific Patient
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPatientPrescriptions(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId))
                .toList();
        return ResponseEntity.ok(patientPrescriptions);
    }

    @GetMapping("/{doctorId}/patient/{patientId}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPatientPrescriptions(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId))
                .toList();
        return ResponseEntity.ok(patientPrescriptions);
    }

    // Get Patient Prescriptions by Status
    @GetMapping("/patient/{patientId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPatientPrescriptionsByStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId,
            @PathVariable String status) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        String upperStatus = status.toUpperCase();

        List<PrescriptionResponse> filtered = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId) && p.getStatus().equalsIgnoreCase(upperStatus))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/patient/{patientId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPatientPrescriptionsByStatus(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        String upperStatus = status.toUpperCase();

        List<PrescriptionResponse> filtered = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId) && p.getStatus().equalsIgnoreCase(upperStatus))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    // Prescription Statistics
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyPrescriptionStats(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        Map<String, Object> stats = buildPrescriptionStats(doctorId, userId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPrescriptionStats(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        Map<String, Object> stats = buildPrescriptionStats(doctorId, doctor.getUserId());
        return ResponseEntity.ok(stats);
    }

    // Patient Prescription Statistics
    @GetMapping("/patient/{patientId}/stats")
    public ResponseEntity<Map<String, Object>> getMyPatientPrescriptionStats(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId))
                .toList();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPrescriptions", patientPrescriptions.size());
        stats.put("activePrescriptions", patientPrescriptions.stream().filter(p -> "ACTIVE".equals(p.getStatus())).count());
        stats.put("completedPrescriptions", patientPrescriptions.stream().filter(p -> "COMPLETED".equals(p.getStatus())).count());
        stats.put("cancelledPrescriptions", patientPrescriptions.stream().filter(p -> "CANCELLED".equals(p.getStatus())).count());
        stats.put("expiredPrescriptions", patientPrescriptions.stream().filter(p -> "EXPIRED".equals(p.getStatus())).count());
        stats.put("onHoldPrescriptions", patientPrescriptions.stream().filter(p -> "ON_HOLD".equals(p.getStatus())).count());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorId}/patient/{patientId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPatientPrescriptionStats(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientId().equals(patientId))
                .toList();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPrescriptions", patientPrescriptions.size());
        stats.put("activePrescriptions", patientPrescriptions.stream().filter(p -> "ACTIVE".equals(p.getStatus())).count());
        stats.put("completedPrescriptions", patientPrescriptions.stream().filter(p -> "COMPLETED".equals(p.getStatus())).count());
        stats.put("cancelledPrescriptions", patientPrescriptions.stream().filter(p -> "CANCELLED".equals(p.getStatus())).count());
        stats.put("expiredPrescriptions", patientPrescriptions.stream().filter(p -> "EXPIRED".equals(p.getStatus())).count());
        stats.put("onHoldPrescriptions", patientPrescriptions.stream().filter(p -> "ON_HOLD".equals(p.getStatus())).count());

        return ResponseEntity.ok(stats);
    }

    // Quick Status Update Endpoints
    @PatchMapping("/{prescriptionId}/mark-completed")
    public ResponseEntity<PrescriptionResponse> markPrescriptionCompleted(
            @PathVariable Integer prescriptionId) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, "COMPLETED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{prescriptionId}/mark-cancelled")
    public ResponseEntity<PrescriptionResponse> markPrescriptionCancelled(
            @PathVariable Integer prescriptionId) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, "CANCELLED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{prescriptionId}/mark-expired")
    public ResponseEntity<PrescriptionResponse> markPrescriptionExpired(
            @PathVariable Integer prescriptionId) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, "EXPIRED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{prescriptionId}/mark-on-hold")
    public ResponseEntity<PrescriptionResponse> markPrescriptionOnHold(
            @PathVariable Integer prescriptionId) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, "ON_HOLD");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{prescriptionId}/mark-active")
    public ResponseEntity<PrescriptionResponse> markPrescriptionActive(
            @PathVariable Integer prescriptionId) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, "ACTIVE");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{prescriptionId}/status")
    public ResponseEntity<PrescriptionResponse> updatePrescriptionStatus(
            @PathVariable Integer prescriptionId,
            @RequestParam String status) {
        PrescriptionResponse response = prescriptionService.updatePrescriptionStatus(prescriptionId, status);
        return ResponseEntity.ok(response);
    }

    // Helper method
    private List<PrescriptionResponse> getAllDoctorPrescriptions(Integer doctorId) {
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptionService.getDoctorPrescriptions(doctorId));
        return all;
    }

    private Map<String, Object> buildPrescriptionStats(Integer doctorId, Integer userId) {
        List<PrescriptionResponse> activePrescriptions = prescriptionService.getActiveForUser(userId, "DOCTOR");
        List<PrescriptionResponse> completedPrescriptions = prescriptionService.getCompletedForUser(userId, "DOCTOR");
        List<PrescriptionResponse> cancelledPrescriptions = prescriptionService.getCancelledForUser(userId, "DOCTOR");
        List<PrescriptionResponse> expiredPrescriptions = prescriptionService.getExpiredForUser(userId, "DOCTOR");
        List<PrescriptionResponse> onHoldPrescriptions = prescriptionService.getOnHoldForUser(userId, "DOCTOR");

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPrescriptions", activePrescriptions.size() + completedPrescriptions.size() +
                  cancelledPrescriptions.size() + expiredPrescriptions.size() + onHoldPrescriptions.size());
        stats.put("activePrescriptions", activePrescriptions.size());
        stats.put("completedPrescriptions", completedPrescriptions.size());
        stats.put("cancelledPrescriptions", cancelledPrescriptions.size());
        stats.put("expiredPrescriptions", expiredPrescriptions.size());
        stats.put("onHoldPrescriptions", onHoldPrescriptions.size());

        return stats;
    }
}
