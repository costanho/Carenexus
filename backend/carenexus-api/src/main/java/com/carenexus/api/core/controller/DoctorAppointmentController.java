package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
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
    private final DoctorRepository doctorRepository;

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(getDoctorIdFromUserId(userId));
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable Integer doctorId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyScheduledAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(userId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}/scheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorScheduledAppointments(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<AppointmentResponse> appointments = appointmentService.getUpcomingForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/completed")
    public ResponseEntity<List<AppointmentResponse>> getMyCompletedAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(userId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}/completed")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCompletedAppointments(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<AppointmentResponse> appointments = appointmentService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getMyCancelledAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(userId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}/cancelled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorCancelledAppointments(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<AppointmentResponse> appointments = appointmentService.getCancelledForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/no-show")
    public ResponseEntity<List<AppointmentResponse>> getMyNoShowAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(userId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}/no-show")
    public ResponseEntity<List<AppointmentResponse>> getDoctorNoShowAppointments(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<AppointmentResponse> appointments = appointmentService.getNoShowForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getMyRescheduledAppointments(
            @RequestAttribute("userId") Integer userId) {
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(userId, "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/{doctorId}/rescheduled")
    public ResponseEntity<List<AppointmentResponse>> getDoctorRescheduledAppointments(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        List<AppointmentResponse> appointments = appointmentService.getRescheduledForUser(doctor.getUserId(), "DOCTOR");
        return ResponseEntity.ok(appointments);
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsWithPatient(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getPatientId().equals(patientId))
                .toList());
    }

    @GetMapping("/{doctorId}/patient/{patientId}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsWithPatient(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getPatientId().equals(patientId))
                .toList());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointmentsByStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String status) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        String upperStatus = status.toUpperCase();
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(upperStatus))
                .toList());
    }

    @GetMapping("/{doctorId}/status/{status}")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointmentsByStatus(
            @PathVariable Integer doctorId,
            @PathVariable String status) {
        List<AppointmentResponse> appointments = appointmentService.getAppointmentsByDoctorId(doctorId);
        String upperStatus = status.toUpperCase();
        return ResponseEntity.ok(appointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(upperStatus))
                .toList());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMyAppointmentStats(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<AppointmentResponse> scheduled = appointmentService.getUpcomingForUser(userId, "DOCTOR");
        List<AppointmentResponse> completed = appointmentService.getCompletedForUser(userId, "DOCTOR");
        List<AppointmentResponse> cancelled = appointmentService.getCancelledForUser(userId, "DOCTOR");
        List<AppointmentResponse> noShow = appointmentService.getNoShowForUser(userId, "DOCTOR");
        List<AppointmentResponse> rescheduled = appointmentService.getRescheduledForUser(userId, "DOCTOR");
        List<AppointmentResponse> all = appointmentService.getAppointmentsByDoctorId(doctorId);

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

    @GetMapping("/{doctorId}/stats")
    public ResponseEntity<Map<String, Object>> getDoctorAppointmentStats(@PathVariable Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<AppointmentResponse> scheduled = appointmentService.getUpcomingForUser(doctor.getUserId(), "DOCTOR");
        List<AppointmentResponse> completed = appointmentService.getCompletedForUser(doctor.getUserId(), "DOCTOR");
        List<AppointmentResponse> cancelled = appointmentService.getCancelledForUser(doctor.getUserId(), "DOCTOR");
        List<AppointmentResponse> noShow = appointmentService.getNoShowForUser(doctor.getUserId(), "DOCTOR");
        List<AppointmentResponse> rescheduled = appointmentService.getRescheduledForUser(doctor.getUserId(), "DOCTOR");
        List<AppointmentResponse> all = appointmentService.getAppointmentsByDoctorId(doctorId);

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
