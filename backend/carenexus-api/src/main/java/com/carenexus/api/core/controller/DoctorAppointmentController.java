package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors/appointments")
@RequiredArgsConstructor
public class DoctorAppointmentController {

    private final AppointmentService appointmentService;

    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyScheduledAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorScheduledAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<AppointmentResponse>> getMyCompletedAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/completed")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCompletedAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getMyCancelledAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCancelledAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/no-show")
    public ResponseEntity<List<AppointmentResponse>> getMyNoShowAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/no-show")
    public ResponseEntity<List<AppointmentResponse>> getDoctorNoShowAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyRescheduledAppointments(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorRescheduledAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsWithPatient(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getPatientSystemId().equals(patientSystemId))
                .toList());
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getPatientSystemId().equals(patientSystemId))
                .toList());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        String upperStatus = status.toUpperCase();
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(upperStatus))
                .toList());
    }

    @GetMapping("/{doctorSystemId}/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        String upperStatus = status.toUpperCase();
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(upperStatus))
                .toList());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyAppointmentStats(
            @RequestAttribute("systemId") String systemId) {
        List<AppointmentResponse> scheduled = appointmentService.getUpcomingForUser(systemId, "DOCTOR");
        List<AppointmentResponse> completed = appointmentService.getCompletedForUser(systemId, "DOCTOR");
        List<AppointmentResponse> cancelled = appointmentService.getCancelledForUser(systemId, "DOCTOR");
        List<AppointmentResponse> noShow = appointmentService.getNoShowForUser(systemId, "DOCTOR");
        List<AppointmentResponse> rescheduled = appointmentService.getRescheduledForUser(systemId, "DOCTOR");
        List<AppointmentResponse> all = appointmentService.getAppointmentsByDoctorSystemId(systemId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAppointments", all.size());
        stats.put("scheduledCount", scheduled.size());
        stats.put("completedCount", completed.size());
        stats.put("cancelledCount", cancelled.size());
        stats.put("noShowCount", noShow.size());
        stats.put("rescheduledCount", rescheduled.size());
        stats.put("completionRate", completed.isEmpty() ? 0 :
            (completed.size() * 100.0) / (completed.size() + cancelled.size() + noShow.size()));
        stats.put("noShowRate", all.isEmpty() ? 0 :
            (noShow.size() * 100.0) / all.size());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorAppointmentStats(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> scheduled = appointmentService.getUpcomingForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> completed = appointmentService.getCompletedForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> cancelled = appointmentService.getCancelledForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> noShow = appointmentService.getNoShowForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> rescheduled = appointmentService.getRescheduledForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> all = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAppointments", all.size());
        stats.put("scheduledCount", scheduled.size());
        stats.put("completedCount", completed.size());
        stats.put("cancelledCount", cancelled.size());
        stats.put("noShowCount", noShow.size());
        stats.put("rescheduledCount", rescheduled.size());
        stats.put("completionRate", completed.isEmpty() ? 0 :
            (completed.size() * 100.0) / (completed.size() + cancelled.size() + noShow.size()));
        stats.put("noShowRate", all.isEmpty() ? 0 :
            (noShow.size() * 100.0) / all.size());

        return ResponseEntity.ok(stats);
    }

    @PatchMapping("/{appointmentId}/mark-completed")
    public ResponseEntity<AppointmentResponse> markAppointmentCompleted(
            @PathVariable Integer appointmentId) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "COMPLETED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/mark-cancelled")
    public ResponseEntity<AppointmentResponse> markAppointmentCancelled(
            @PathVariable Integer appointmentId) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "CANCELLED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/mark-no-show")
    public ResponseEntity<AppointmentResponse> markAppointmentNoShow(
            @PathVariable Integer appointmentId) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "NO_SHOW");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/mark-rescheduled")
    public ResponseEntity<AppointmentResponse> markAppointmentRescheduled(
            @PathVariable Integer appointmentId) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "RESCHEDULED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/status")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer appointmentId,
            @RequestParam String status) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, status);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{appointmentId}/send-reminder")
    public ResponseEntity<AppointmentResponse> sendAppointmentReminder(
            @PathVariable Integer appointmentId) {
        AppointmentResponse response = appointmentService.getAppointmentById(appointmentId);
        if (response == null) {
            throw new RuntimeException("Appointment not found");
        }
        return ResponseEntity.ok(response);
    }
}
