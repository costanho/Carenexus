package com.carenexus.api.core.controller;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final AuthorizationService authorizationService;

    /**
     * Book an appointment. Patients book for themselves (patientSystemId may be omitted); caregivers need
     * appointment-management access to the patient; doctors book related patients into their own schedule.
     * For VIDEO appointments the server generates the video link.
     */
    @PostMapping
    public ResponseEntity<AppointmentResponse> createAppointment(@RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Clinic booking rules (hours, slot size, durations) the server enforces. */
    @GetMapping("/booking-rules")
    public ResponseEntity<Map<String, Object>> getBookingRules() {
        return ResponseEntity.ok(appointmentService.getBookingRules());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointmentById(@PathVariable Integer id) {
        AppointmentResponse response = appointmentService.getAppointmentById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getPatientAppointments(@PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsForPatientAsCaller(patientSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/patient/{patientSystemId}/upcoming")
    public ResponseEntity<List<AppointmentResponse>> getUpcomingAppointments(@PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingAppointmentsForPatientAsCaller(patientSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/doctor/{doctorSystemId}/schedule")
    public ResponseEntity<List<AppointmentResponse>> getDoctorSchedule(
            @PathVariable String doctorSystemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<AppointmentResponse> appointments = appointmentService.getDoctorSchedule(doctorSystemId, start, end);
        return ResponseEntity.ok(appointments);
    }

    /**
     * Busy times of a doctor on one day: [{start, end, durationMinutes}] for SCHEDULED/RESCHEDULED appointments.
     * No patient details. For slot pickers.
     */
    @GetMapping("/doctor/{doctorSystemId}/busy")
    public ResponseEntity<List<Map<String, Object>>> getDoctorBusySlots(
            @PathVariable String doctorSystemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(appointmentService.getDoctorBusySlots(doctorSystemId, date));
    }

    /**
     * Edit an active appointment. Sending a new scheduledAt reschedules it (status becomes RESCHEDULED).
     */
    @PutMapping("/{id}")
    public ResponseEntity<AppointmentResponse> updateAppointment(
            @PathVariable Integer id,
            @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.updateAppointment(id, request);
        return ResponseEntity.ok(response);
    }

    /** Move an active appointment to a new time: body {scheduledAt, durationMinutes?}. */
    @PatchMapping("/{id}/reschedule")
    public ResponseEntity<AppointmentResponse> rescheduleAppointment(
            @PathVariable Integer id,
            @RequestBody RescheduleAppointmentRequest request) {
        AppointmentResponse response = appointmentService.rescheduleAppointment(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(id, status);
        return ResponseEntity.ok(response);
    }

    /** Soft cancel: the appointment is kept with status CANCELLED. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelAppointment(@PathVariable Integer id) {
        appointmentService.cancelAppointment(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/upcoming")
    public ResponseEntity<List<AppointmentResponse>> getMyUpcomingAppointments(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(systemId, userRole);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/me/completed")
    public ResponseEntity<List<AppointmentResponse>> getMyCompletedAppointments(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(systemId, userRole);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/me/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getMyCancelledAppointments(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(systemId, userRole);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/me/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyRescheduledAppointments(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(systemId, userRole);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/me/no-show")
    public ResponseEntity<List<AppointmentResponse>> getMyNoShowAppointments(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(systemId, userRole);
        return ResponseEntity.ok(appointments);
    }
}
