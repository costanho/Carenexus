package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.DoctorPatientResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.DoctorPatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/doctors/patients")
@RequiredArgsConstructor
public class DoctorPatientController {

    private final DoctorPatientService doctorPatientService;
    private final DoctorRepository doctorRepository;

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    @GetMapping
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/scheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyScheduledPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorScheduledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/scheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorScheduledPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorScheduledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCompletedPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCompletedPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/completed")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCompletedPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCompletedPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCancelledPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCancelledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/cancelled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCancelledPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCancelledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/no-show")
    public ResponseEntity<List<DoctorPatientResponse>> getMyNoShowPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorNoShowPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/no-show")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorNoShowPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorNoShowPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/rescheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyRescheduledPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorRescheduledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/rescheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorRescheduledPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorRescheduledPatients(doctorId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String status) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatientsByStatus(doctorId, status.toUpperCase());
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByStatus(
            @PathVariable Integer doctorId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatientsByStatus(doctorId, status.toUpperCase());
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{patientId}/detail")
    public ResponseEntity<DoctorPatientResponse> getMyPatientDetail(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        DoctorPatientResponse patient = doctorPatientService.getDoctorPatientDetail(doctorId, patientId);
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/{doctorId}/{patientId}/detail")
    public ResponseEntity<DoctorPatientResponse> getDoctorPatientDetail(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        DoctorPatientResponse patient = doctorPatientService.getDoctorPatientDetail(doctorId, patientId);
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/search")
    public ResponseEntity<List<DoctorPatientResponse>> searchMyPatients(
            @RequestAttribute("userId") Integer userId,
            @RequestParam String query) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.searchDoctorPatients(doctorId, query);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/search")
    public ResponseEntity<List<DoctorPatientResponse>> searchDoctorPatients(
            @PathVariable Integer doctorId,
            @RequestParam String query) {
        List<DoctorPatientResponse> patients = doctorPatientService.searchDoctorPatients(doctorId, query);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/high-risk")
    public ResponseEntity<List<DoctorPatientResponse>> getMyHighRiskPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorId);

        List<DoctorPatientResponse> highRiskPatients = allPatients.stream()
                .filter(p -> "CRITICAL".equals(p.getHealthStatus()) || p.getNoShowCount() > 2)
                .toList();

        return ResponseEntity.ok(highRiskPatients);
    }

    @GetMapping("/{doctorId}/high-risk")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorHighRiskPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorId);

        List<DoctorPatientResponse> highRiskPatients = allPatients.stream()
                .filter(p -> "CRITICAL".equals(p.getHealthStatus()) || p.getNoShowCount() > 2)
                .toList();

        return ResponseEntity.ok(highRiskPatients);
    }

    @GetMapping("/frequent-visitors")
    public ResponseEntity<List<DoctorPatientResponse>> getMyFrequentVisitors(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorId);

        List<DoctorPatientResponse> frequentVisitors = allPatients.stream()
                .filter(p -> p.getTotalAppointments() >= 5)
                .sorted((p1, p2) -> Integer.compare(p2.getTotalAppointments(), p1.getTotalAppointments()))
                .toList();

        return ResponseEntity.ok(frequentVisitors);
    }

    @GetMapping("/{doctorId}/frequent-visitors")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorFrequentVisitors(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorId);

        List<DoctorPatientResponse> frequentVisitors = allPatients.stream()
                .filter(p -> p.getTotalAppointments() >= 5)
                .sorted((p1, p2) -> Integer.compare(p2.getTotalAppointments(), p1.getTotalAppointments()))
                .toList();

        return ResponseEntity.ok(frequentVisitors);
    }

    // Gender-Based Endpoints
    @GetMapping("/gender/male")
    public ResponseEntity<List<DoctorPatientResponse>> getMyMalePatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> malePatients = patients.stream()
                .filter(p -> "MALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(malePatients);
    }

    @GetMapping("/{doctorId}/gender/male")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorMalePatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> malePatients = patients.stream()
                .filter(p -> "MALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(malePatients);
    }

    @GetMapping("/gender/female")
    public ResponseEntity<List<DoctorPatientResponse>> getMyFemalePatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> femalePatients = patients.stream()
                .filter(p -> "FEMALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(femalePatients);
    }

    @GetMapping("/{doctorId}/gender/female")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorFemalePatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> femalePatients = patients.stream()
                .filter(p -> "FEMALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(femalePatients);
    }

    @GetMapping("/gender/other")
    public ResponseEntity<List<DoctorPatientResponse>> getMyOtherGenderPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> otherPatients = patients.stream()
                .filter(p -> "OTHER".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(otherPatients);
    }

    @GetMapping("/{doctorId}/gender/other")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorOtherGenderPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> otherPatients = patients.stream()
                .filter(p -> "OTHER".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(otherPatients);
    }

    @GetMapping("/gender/all")
    public ResponseEntity<List<DoctorPatientResponse>> getMyAllGenderPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorId));
    }

    @GetMapping("/{doctorId}/gender/all")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorAllGenderPatients(@PathVariable Integer doctorId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorId));
    }

    @GetMapping("/gender/{gender}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByGender(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String gender) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        String upperGender = gender.toUpperCase();

        if ("ALL".equals(upperGender)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperGender.equals(p.getGender()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/gender/{gender}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByGender(
            @PathVariable Integer doctorId,
            @PathVariable String gender) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        String upperGender = gender.toUpperCase();

        if ("ALL".equals(upperGender)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperGender.equals(p.getGender()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    // Health Status-Based Endpoints
    @GetMapping("/health-status/stable")
    public ResponseEntity<List<DoctorPatientResponse>> getMyStablePatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> stablePatients = patients.stream()
                .filter(p -> "STABLE".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(stablePatients);
    }

    @GetMapping("/{doctorId}/health-status/stable")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorStablePatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> stablePatients = patients.stream()
                .filter(p -> "STABLE".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(stablePatients);
    }

    @GetMapping("/health-status/monitor")
    public ResponseEntity<List<DoctorPatientResponse>> getMyMonitorPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> monitorPatients = patients.stream()
                .filter(p -> "MONITOR".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(monitorPatients);
    }

    @GetMapping("/{doctorId}/health-status/monitor")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorMonitorPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> monitorPatients = patients.stream()
                .filter(p -> "MONITOR".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(monitorPatients);
    }

    @GetMapping("/health-status/critical")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCriticalPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> criticalPatients = patients.stream()
                .filter(p -> "CRITICAL".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(criticalPatients);
    }

    @GetMapping("/{doctorId}/health-status/critical")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCriticalPatients(@PathVariable Integer doctorId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        List<DoctorPatientResponse> criticalPatients = patients.stream()
                .filter(p -> "CRITICAL".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(criticalPatients);
    }

    @GetMapping("/health-status/all")
    public ResponseEntity<List<DoctorPatientResponse>> getMyAllHealthStatusPatients(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorId));
    }

    @GetMapping("/{doctorId}/health-status/all")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorAllHealthStatusPatients(@PathVariable Integer doctorId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorId));
    }

    @GetMapping("/health-status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByHealthStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String status) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        String upperStatus = status.toUpperCase();

        if ("ALL".equals(upperStatus)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperStatus.equals(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorId}/health-status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByHealthStatus(
            @PathVariable Integer doctorId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);
        String upperStatus = status.toUpperCase();

        if ("ALL".equals(upperStatus)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperStatus.equals(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    // Combined Filter Endpoints
    @GetMapping("/filter")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsFiltered(
            @RequestAttribute("userId") Integer userId,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String appointmentStatus) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);

        if (gender != null && !gender.equalsIgnoreCase("ALL")) {
            patients = patients.stream()
                    .filter(p -> gender.equalsIgnoreCase(p.getGender()))
                    .toList();
        }

        if (healthStatus != null && !healthStatus.equalsIgnoreCase("ALL")) {
            patients = patients.stream()
                    .filter(p -> healthStatus.equalsIgnoreCase(p.getHealthStatus()))
                    .toList();
        }

        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorId}/filter")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsFiltered(
            @PathVariable Integer doctorId,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String appointmentStatus) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorId);

        if (gender != null && !gender.equalsIgnoreCase("ALL")) {
            patients = patients.stream()
                    .filter(p -> gender.equalsIgnoreCase(p.getGender()))
                    .toList();
        }

        if (healthStatus != null && !healthStatus.equalsIgnoreCase("ALL")) {
            patients = patients.stream()
                    .filter(p -> healthStatus.equalsIgnoreCase(p.getHealthStatus()))
                    .toList();
        }

        return ResponseEntity.ok(patients);
    }
}
