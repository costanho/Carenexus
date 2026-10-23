package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.DoctorPatientResponse;
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

    @GetMapping
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/scheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyScheduledPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorScheduledPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/scheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorScheduledPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorScheduledPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCompletedPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCompletedPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/completed")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCompletedPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCompletedPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCancelledPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCancelledPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/cancelled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCancelledPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorCancelledPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/no-show")
    public ResponseEntity<List<DoctorPatientResponse>> getMyNoShowPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorNoShowPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/no-show")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorNoShowPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorNoShowPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/rescheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getMyRescheduledPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorRescheduledPatients(systemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/rescheduled")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorRescheduledPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorRescheduledPatients(doctorSystemId);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatientsByStatus(systemId, status.toUpperCase());
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatientsByStatus(doctorSystemId, status.toUpperCase());
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{patientSystemId}/detail")
    public ResponseEntity<DoctorPatientResponse> getMyPatientDetail(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        DoctorPatientResponse patient = doctorPatientService.getDoctorPatientDetail(systemId, patientSystemId);
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/{doctorSystemId}/{patientSystemId}/detail")
    public ResponseEntity<DoctorPatientResponse> getDoctorPatientDetail(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        DoctorPatientResponse patient = doctorPatientService.getDoctorPatientDetail(doctorSystemId, patientSystemId);
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/search")
    public ResponseEntity<List<DoctorPatientResponse>> searchMyPatients(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String query) {
        List<DoctorPatientResponse> patients = doctorPatientService.searchDoctorPatients(systemId, query);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/{doctorSystemId}/search")
    public ResponseEntity<List<DoctorPatientResponse>> searchDoctorPatients(
            @PathVariable String doctorSystemId,
            @RequestParam String query) {
        List<DoctorPatientResponse> patients = doctorPatientService.searchDoctorPatients(doctorSystemId, query);
        return ResponseEntity.ok(patients);
    }

    @GetMapping("/high-risk")
    public ResponseEntity<List<DoctorPatientResponse>> getMyHighRiskPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(systemId);

        List<DoctorPatientResponse> highRiskPatients = allPatients.stream()
                .filter(p -> "CRITICAL".equals(p.getHealthStatus()) || p.getNoShowCount() > 2)
                .toList();

        return ResponseEntity.ok(highRiskPatients);
    }

    @GetMapping("/{doctorSystemId}/high-risk")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorHighRiskPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorSystemId);

        List<DoctorPatientResponse> highRiskPatients = allPatients.stream()
                .filter(p -> "CRITICAL".equals(p.getHealthStatus()) || p.getNoShowCount() > 2)
                .toList();

        return ResponseEntity.ok(highRiskPatients);
    }

    @GetMapping("/frequent-visitors")
    public ResponseEntity<List<DoctorPatientResponse>> getMyFrequentVisitors(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(systemId);

        List<DoctorPatientResponse> frequentVisitors = allPatients.stream()
                .filter(p -> p.getTotalAppointments() >= 5)
                .sorted((p1, p2) -> Integer.compare(p2.getTotalAppointments(), p1.getTotalAppointments()))
                .toList();

        return ResponseEntity.ok(frequentVisitors);
    }

    @GetMapping("/{doctorSystemId}/frequent-visitors")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorFrequentVisitors(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> allPatients = doctorPatientService.getDoctorPatients(doctorSystemId);

        List<DoctorPatientResponse> frequentVisitors = allPatients.stream()
                .filter(p -> p.getTotalAppointments() >= 5)
                .sorted((p1, p2) -> Integer.compare(p2.getTotalAppointments(), p1.getTotalAppointments()))
                .toList();

        return ResponseEntity.ok(frequentVisitors);
    }

    @GetMapping("/gender/male")
    public ResponseEntity<List<DoctorPatientResponse>> getMyMalePatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> malePatients = patients.stream()
                .filter(p -> "MALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(malePatients);
    }

    @GetMapping("/{doctorSystemId}/gender/male")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorMalePatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> malePatients = patients.stream()
                .filter(p -> "MALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(malePatients);
    }

    @GetMapping("/gender/female")
    public ResponseEntity<List<DoctorPatientResponse>> getMyFemalePatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> femalePatients = patients.stream()
                .filter(p -> "FEMALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(femalePatients);
    }

    @GetMapping("/{doctorSystemId}/gender/female")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorFemalePatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> femalePatients = patients.stream()
                .filter(p -> "FEMALE".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(femalePatients);
    }

    @GetMapping("/gender/other")
    public ResponseEntity<List<DoctorPatientResponse>> getMyOtherGenderPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> otherPatients = patients.stream()
                .filter(p -> "OTHER".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(otherPatients);
    }

    @GetMapping("/{doctorSystemId}/gender/other")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorOtherGenderPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> otherPatients = patients.stream()
                .filter(p -> "OTHER".equalsIgnoreCase(p.getGender()))
                .toList();
        return ResponseEntity.ok(otherPatients);
    }

    @GetMapping("/gender/all")
    public ResponseEntity<List<DoctorPatientResponse>> getMyAllGenderPatients(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(systemId));
    }

    @GetMapping("/{doctorSystemId}/gender/all")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorAllGenderPatients(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorSystemId));
    }

    @GetMapping("/gender/{gender}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByGender(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String gender) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        String upperGender = gender.toUpperCase();

        if ("ALL".equals(upperGender)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperGender.equals(p.getGender()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/gender/{gender}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByGender(
            @PathVariable String doctorSystemId,
            @PathVariable String gender) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        String upperGender = gender.toUpperCase();

        if ("ALL".equals(upperGender)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperGender.equals(p.getGender()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/health-status/stable")
    public ResponseEntity<List<DoctorPatientResponse>> getMyStablePatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> stablePatients = patients.stream()
                .filter(p -> "STABLE".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(stablePatients);
    }

    @GetMapping("/{doctorSystemId}/health-status/stable")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorStablePatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> stablePatients = patients.stream()
                .filter(p -> "STABLE".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(stablePatients);
    }

    @GetMapping("/health-status/monitor")
    public ResponseEntity<List<DoctorPatientResponse>> getMyMonitorPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> monitorPatients = patients.stream()
                .filter(p -> "MONITOR".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(monitorPatients);
    }

    @GetMapping("/{doctorSystemId}/health-status/monitor")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorMonitorPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> monitorPatients = patients.stream()
                .filter(p -> "MONITOR".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(monitorPatients);
    }

    @GetMapping("/health-status/critical")
    public ResponseEntity<List<DoctorPatientResponse>> getMyCriticalPatients(
            @RequestAttribute("systemId") String systemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        List<DoctorPatientResponse> criticalPatients = patients.stream()
                .filter(p -> "CRITICAL".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(criticalPatients);
    }

    @GetMapping("/{doctorSystemId}/health-status/critical")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorCriticalPatients(@PathVariable String doctorSystemId) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        List<DoctorPatientResponse> criticalPatients = patients.stream()
                .filter(p -> "CRITICAL".equalsIgnoreCase(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(criticalPatients);
    }

    @GetMapping("/health-status/all")
    public ResponseEntity<List<DoctorPatientResponse>> getMyAllHealthStatusPatients(
            @RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(systemId));
    }

    @GetMapping("/{doctorSystemId}/health-status/all")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorAllHealthStatusPatients(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(doctorPatientService.getDoctorPatients(doctorSystemId));
    }

    @GetMapping("/health-status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsByHealthStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);
        String upperStatus = status.toUpperCase();

        if ("ALL".equals(upperStatus)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperStatus.equals(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/{doctorSystemId}/health-status/{status}")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsByHealthStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);
        String upperStatus = status.toUpperCase();

        if ("ALL".equals(upperStatus)) {
            return ResponseEntity.ok(patients);
        }

        List<DoctorPatientResponse> filtered = patients.stream()
                .filter(p -> upperStatus.equals(p.getHealthStatus()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/filter")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatientsFiltered(
            @RequestAttribute("systemId") String systemId,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String appointmentStatus) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(systemId);

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

    @GetMapping("/{doctorSystemId}/filter")
    public ResponseEntity<List<DoctorPatientResponse>> getDoctorPatientsFiltered(
            @PathVariable String doctorSystemId,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String appointmentStatus) {
        List<DoctorPatientResponse> patients = doctorPatientService.getDoctorPatients(doctorSystemId);

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
