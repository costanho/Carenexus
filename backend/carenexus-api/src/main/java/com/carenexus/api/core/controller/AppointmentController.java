package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    /**
     * POST /api/appointments
     * Create new appointment
     */
    @PostMapping
    public ResponseEntity<AppointmentResponse> createAppointment(@RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/appointments/{id}
     * Get appointment by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointmentById(@PathVariable Integer id) {
        AppointmentResponse response = appointmentService.getAppointmentById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/appointments/patient/{patientId}
     * Get all appointments for a patient
     */
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<AppointmentResponse>> getPatientAppointments(@PathVariable Integer patientId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByPatientId(patientId);
        return ResponseEntity.ok(appointments);
    }

    /**
     * GET /api/appointments/doctor/{doctorId}
     * Get all appointments for a doctor
     */
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable Integer doctorId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        return ResponseEntity.ok(appointments);
    }

    /**
     * GET /api/appointments/patient/{patientId}/upcoming
     * Get upcoming appointments for a patient
     */
    @GetMapping("/patient/{patientId}/upcoming")
    public ResponseEntity<List<AppointmentResponse>> getUpcomingAppointments(@PathVariable Integer patientId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingAppointments(patientId);
        return ResponseEntity.ok(appointments);
    }

    /**
     * GET /api/appointments/doctor/{doctorId}/schedule
     * Get doctor's schedule for a date range
     * Query params: start and end (ISO 8601 format)
     */
    @GetMapping("/doctor/{doctorId}/schedule")
    public ResponseEntity<List<AppointmentResponse>> getDoctorSchedule(
            @PathVariable Integer doctorId,
            @RequestParam String start,
            @RequestParam String end) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        List<AppointmentResponse> appointments = appointmentService.getDoctorSchedule(doctorId, startTime, endTime);
        return ResponseEntity.ok(appointments);
    }

    /**
     * PUT /api/appointments/{id}
     * Update appointment details (only for SCHEDULED appointments)
     */
    @PutMapping("/{id}")
    public ResponseEntity<AppointmentResponse> updateAppointment(
            @PathVariable Integer id,
            @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.updateAppointment(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/appointments/{id}/status
     * Update appointment status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(id, status);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/appointments/{id}
     * Cancel appointment
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelAppointment(@PathVariable Integer id) {
        appointmentService.cancelAppointment(id);
        return ResponseEntity.noContent().build();
    }
}
