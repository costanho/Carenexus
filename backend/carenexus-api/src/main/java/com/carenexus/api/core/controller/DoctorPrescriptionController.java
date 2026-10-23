package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.PrescriptionResponse;
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

    @GetMapping
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(systemId, "DOCTOR");
        List<PrescriptionResponse> onHold = prescriptionService.getOnHoldForUser(systemId, "DOCTOR");
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptions);
        all.addAll(onHold);
        all.addAll(prescriptionService.getCompletedForUser(systemId, "DOCTOR"));
        all.addAll(prescriptionService.getCancelledForUser(systemId, "DOCTOR"));
        all.addAll(prescriptionService.getExpiredForUser(systemId, "DOCTOR"));
        return ResponseEntity.ok(all);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> onHold = prescriptionService.getOnHoldForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptions);
        all.addAll(onHold);
        all.addAll(prescriptionService.getCompletedForUser(doctorSystemId, "DOCTOR"));
        all.addAll(prescriptionService.getCancelledForUser(doctorSystemId, "DOCTOR"));
        all.addAll(prescriptionService.getExpiredForUser(doctorSystemId, "DOCTOR"));
        return ResponseEntity.ok(all);
    }

    @GetMapping("/status/active")
    public ResponseEntity<List<PrescriptionResponse>> getMyActivePrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/active")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorActivePrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getActiveForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/status/completed")
    public ResponseEntity<List<PrescriptionResponse>> getMyCompletedPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCompletedForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/completed")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorCompletedPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCompletedForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/status/cancelled")
    public ResponseEntity<List<PrescriptionResponse>> getMyCancelledPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCancelledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/cancelled")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorCancelledPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getCancelledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/status/expired")
    public ResponseEntity<List<PrescriptionResponse>> getMyExpiredPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getExpiredForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/expired")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorExpiredPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getExpiredForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/status/on-hold")
    public ResponseEntity<List<PrescriptionResponse>> getMyOnHoldPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getOnHoldForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/on-hold")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorOnHoldPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getOnHoldForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptionsByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = switch (status.toUpperCase()) {
            case "ACTIVE" -> prescriptionService.getActiveForUser(systemId, "DOCTOR");
            case "COMPLETED" -> prescriptionService.getCompletedForUser(systemId, "DOCTOR");
            case "CANCELLED" -> prescriptionService.getCancelledForUser(systemId, "DOCTOR");
            case "EXPIRED" -> prescriptionService.getExpiredForUser(systemId, "DOCTOR");
            case "ON_HOLD" -> prescriptionService.getOnHoldForUser(systemId, "DOCTOR");
            default -> throw new RuntimeException("Invalid status: " + status);
        };
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/{doctorSystemId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptionsByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = switch (status.toUpperCase()) {
            case "ACTIVE" -> prescriptionService.getActiveForUser(doctorSystemId, "DOCTOR");
            case "COMPLETED" -> prescriptionService.getCompletedForUser(doctorSystemId, "DOCTOR");
            case "CANCELLED" -> prescriptionService.getCancelledForUser(doctorSystemId, "DOCTOR");
            case "EXPIRED" -> prescriptionService.getExpiredForUser(doctorSystemId, "DOCTOR");
            case "ON_HOLD" -> prescriptionService.getOnHoldForUser(doctorSystemId, "DOCTOR");
            default -> throw new RuntimeException("Invalid status: " + status);
        };
        return ResponseEntity.ok(prescriptions);
    }

    @GetMapping("/route/oral")
    public ResponseEntity<List<PrescriptionResponse>> getMyOralPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "ORAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/oral")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorOralPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "ORAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/topical")
    public ResponseEntity<List<PrescriptionResponse>> getMyTopicalPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "TOPICAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/topical")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorTopicalPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "TOPICAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/injection")
    public ResponseEntity<List<PrescriptionResponse>> getMyInjectionPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INJECTION".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/injection")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorInjectionPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INJECTION".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/inhaled")
    public ResponseEntity<List<PrescriptionResponse>> getMyInhaledPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INHALED".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/inhaled")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorInhaledPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "INHALED".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/sublingual")
    public ResponseEntity<List<PrescriptionResponse>> getMySublingualPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "SUBLINGUAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/sublingual")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorSublingualPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "SUBLINGUAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/rectal")
    public ResponseEntity<List<PrescriptionResponse>> getMyRectalPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "RECTAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/rectal")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorRectalPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "RECTAL".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/iv")
    public ResponseEntity<List<PrescriptionResponse>> getMyIVPrescriptions(
            @RequestAttribute("systemId") String systemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "IV".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/iv")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorIVPrescriptions(@PathVariable String doctorSystemId) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> "IV".equalsIgnoreCase(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/route/{route}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptionsByRoute(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String route) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(systemId);
        String upperRoute = route.toUpperCase();
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> upperRoute.equals(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/route/{route}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptionsByRoute(
            @PathVariable String doctorSystemId,
            @PathVariable String route) {
        List<PrescriptionResponse> allPrescriptions = getAllDoctorPrescriptions(doctorSystemId);
        String upperRoute = route.toUpperCase();
        List<PrescriptionResponse> filtered = allPrescriptions.stream()
                .filter(p -> upperRoute.equals(p.getRoute()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPatientPrescriptions(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(systemId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId))
                .toList();
        return ResponseEntity.ok(patientPrescriptions);
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPatientPrescriptions(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId))
                .toList();
        return ResponseEntity.ok(patientPrescriptions);
    }

    @GetMapping("/patient/{patientSystemId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getMyPatientPrescriptionsByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(systemId);
        String upperStatus = status.toUpperCase();

        List<PrescriptionResponse> filtered = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId) && p.getStatus().equalsIgnoreCase(upperStatus))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}/status/{status}")
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPatientPrescriptionsByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId,
            @PathVariable String status) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorSystemId);
        String upperStatus = status.toUpperCase();

        List<PrescriptionResponse> filtered = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId) && p.getStatus().equalsIgnoreCase(upperStatus))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyPrescriptionStats(
            @RequestAttribute("systemId") String systemId) {
        Map<String, Object> stats = buildPrescriptionStats(systemId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPrescriptionStats(@PathVariable String doctorSystemId) {
        Map<String, Object> stats = buildPrescriptionStats(doctorSystemId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/patient/{patientSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getMyPatientPrescriptionStats(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(systemId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId))
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

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorPatientPrescriptionStats(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        List<PrescriptionResponse> prescriptions = prescriptionService.getDoctorPrescriptions(doctorSystemId);
        List<PrescriptionResponse> patientPrescriptions = prescriptions.stream()
                .filter(p -> p.getPatientSystemId().equals(patientSystemId))
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

    private List<PrescriptionResponse> getAllDoctorPrescriptions(String doctorSystemId) {
        List<PrescriptionResponse> all = new java.util.ArrayList<>();
        all.addAll(prescriptionService.getDoctorPrescriptions(doctorSystemId));
        return all;
    }

    private Map<String, Object> buildPrescriptionStats(String doctorSystemId) {
        List<PrescriptionResponse> activePrescriptions = prescriptionService.getActiveForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> completedPrescriptions = prescriptionService.getCompletedForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> cancelledPrescriptions = prescriptionService.getCancelledForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> expiredPrescriptions = prescriptionService.getExpiredForUser(doctorSystemId, "DOCTOR");
        List<PrescriptionResponse> onHoldPrescriptions = prescriptionService.getOnHoldForUser(doctorSystemId, "DOCTOR");

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
