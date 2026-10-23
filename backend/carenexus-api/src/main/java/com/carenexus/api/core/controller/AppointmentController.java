package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    public ResponseEntity<AppointmentResponse> createAppointment(@RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointmentById(@PathVariable Integer id) {
        AppointmentResponse response = appointmentService.getAppointmentById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getPatientAppointments(@PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByPatientSystemId(patientSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable String doctorSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorSystemId(doctorSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/patient/{patientSystemId}/upcoming")
    public ResponseEntity<List<AppointmentResponse>> getUpcomingAppointments(@PathVariable String patientSystemId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingAppointments(patientSystemId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/doctor/{doctorSystemId}/schedule")
    public ResponseEntity<List<AppointmentResponse>> getDoctorSchedule(
            @PathVariable String doctorSystemId,
            @RequestParam String start,
            @RequestParam String end) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        List<AppointmentResponse> appointments = appointmentService.getDoctorSchedule(doctorSystemId, startTime, endTime);
        return ResponseEntity.ok(appointments);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppointmentResponse> updateAppointment(
            @PathVariable Integer id,
            @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.updateAppointment(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        AppointmentResponse response = appointmentService.updateAppointmentStatus(id, status);
        return ResponseEntity.ok(response);
    }

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
