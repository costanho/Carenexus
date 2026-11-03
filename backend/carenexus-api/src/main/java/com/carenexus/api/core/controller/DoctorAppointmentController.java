package com.carenexus.api.core.controller;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doctor-facing appointment endpoints. Every endpoint requires the DOCTOR (or ADMIN) role; the
 * /{doctorSystemId}/... variants are only usable by that doctor themselves or an admin, and the status
 * changes are only allowed on the calling doctor's own appointments (checked in AppointmentService).
 */
@RestController
@RequestMapping("/api/doctors/appointments")
@RequiredArgsConstructor
public class DoctorAppointmentController {

    private final AppointmentService appointmentService;
    private final AuthorizationService authorizationService;

    private void requireDoctor() {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
    }

    private void requireDoctorSelf(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
    }

    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyScheduledAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorScheduledAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<AppointmentResponse>> getMyCompletedAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/completed")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCompletedAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getMyCancelledAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCancelledAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/no-show")
    public ResponseEntity<List<AppointmentResponse>> getMyNoShowAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/no-show")
    public ResponseEntity<List<AppointmentResponse>> getDoctorNoShowAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyRescheduledAppointments(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(systemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorSystemId}/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorRescheduledAppointments(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(doctorSystemId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsWithPatient(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> patientSystemId.equals(a.getPatientSystemId()))
                .toList());
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> patientSystemId.equals(a.getPatientSystemId()))
                .toList());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        requireDoctor();
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(systemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> status.equalsIgnoreCase(a.getStatus()))
                .toList());
    }

    @GetMapping("/{doctorSystemId}/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> status.equalsIgnoreCase(a.getStatus()))
                .toList());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyAppointmentStats(
            @RequestAttribute("systemId") String systemId) {
        requireDoctor();
        return ResponseEntity.ok(buildStats(systemId));
    }

    @GetMapping("/{doctorSystemId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorAppointmentStats(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        return ResponseEntity.ok(buildStats(doctorSystemId));
    }

    private Map<String, Object> buildStats(String doctorSystemId) {
        List<AppointmentResponse> scheduled = appointmentService.getUpcomingForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> completed = appointmentService.getCompletedForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> cancelled = appointmentService.getCancelledForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> noShow = appointmentService.getNoShowForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> rescheduled = appointmentService.getRescheduledForUser(doctorSystemId, "DOCTOR");
        List<AppointmentResponse> all = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);

        int closed = completed.size() + cancelled.size() + noShow.size();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAppointments", all.size());
        stats.put("scheduledCount", scheduled.size());
        stats.put("completedCount", completed.size());
        stats.put("cancelledCount", cancelled.size());
        stats.put("noShowCount", noShow.size());
        stats.put("rescheduledCount", rescheduled.size());
        stats.put("completionRate", closed == 0 ? 0 : (completed.size() * 100.0) / closed);
        stats.put("noShowRate", all.isEmpty() ? 0 : (noShow.size() * 100.0) / all.size());
        return stats;
    }

    @PatchMapping("/{appointmentId}/mark-completed")
    public ResponseEntity<AppointmentResponse> markAppointmentCompleted(
            @PathVariable Integer appointmentId) {
        requireDoctor();
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "COMPLETED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/mark-cancelled")
    public ResponseEntity<AppointmentResponse> markAppointmentCancelled(
            @PathVariable Integer appointmentId) {
        requireDoctor();
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "CANCELLED");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/mark-no-show")
    public ResponseEntity<AppointmentResponse> markAppointmentNoShow(
            @PathVariable Integer appointmentId) {
        requireDoctor();
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, "NO_SHOW");
        return ResponseEntity.ok(response);
    }

    /**
     * Reschedule needs a new time: body {scheduledAt, durationMinutes?}. Without one the request is rejected,
     * because marking an appointment "rescheduled" without moving it would leave it at the old time.
     */
    @PatchMapping("/{appointmentId}/mark-rescheduled")
    public ResponseEntity<AppointmentResponse> markAppointmentRescheduled(
            @PathVariable Integer appointmentId,
            @RequestBody(required = false) RescheduleAppointmentRequest request) {
        requireDoctor();
        AppointmentResponse response = appointmentService.rescheduleAppointment(appointmentId, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{appointmentId}/status")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer appointmentId,
            @RequestParam String status) {
        requireDoctor();
        AppointmentResponse response = appointmentService.updateAppointmentStatus(appointmentId, status);
        return ResponseEntity.ok(response);
    }

    /**
     * Reminders are not implemented yet (no SMS/email/push sender exists). Answer honestly instead of
     * pretending a reminder went out.
     */
    @PostMapping("/{appointmentId}/send-reminder")
    public ResponseEntity<Map<String, String>> sendAppointmentReminder(
            @PathVariable Integer appointmentId) {
        requireDoctor();
        appointmentService.getAppointmentById(appointmentId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("message", "Sending appointment reminders is not available yet"));
    }
}
