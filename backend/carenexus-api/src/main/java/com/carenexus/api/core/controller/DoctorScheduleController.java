package com.carenexus.api.core.controller;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.response.DoctorDashboardResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.DoctorScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/doctors/schedule")
@RequiredArgsConstructor
public class DoctorScheduleController {

    private final DoctorScheduleService doctorScheduleService;
    private final DoctorRepository doctorRepository;
    private final AuthorizationService authorizationService;

    /** Self routes: caller must be a DOCTOR (or ADMIN) with a doctor profile. */
    private String getDoctorSystemIdFromUserId(String systemId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        return doctor.getSystemId();
    }

    /** /{doctorSystemId}/... routes: only that doctor themselves, or an admin. */
    private void requireDoctorSelf(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
    }

    @GetMapping
    public ResponseEntity<List<ScheduleEntryResponse>> getMySchedule(
            @RequestAttribute("systemId") String systemId) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorSchedule(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/today")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyTodaySchedule(
            @RequestAttribute("systemId") String systemId) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorTodaySchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/today")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorTodaySchedule(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorTodaySchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyUpcomingSchedule(
            @RequestAttribute("systemId") String systemId) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorUpcomingSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/upcoming")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorUpcomingSchedule(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorUpcomingSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/past")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyPastSchedule(
            @RequestAttribute("systemId") String systemId) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorPastSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/past")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorPastSchedule(@PathVariable String doctorSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorPastSchedule(doctorSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/date-range")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleByDateRange(
            @RequestAttribute("systemId") String systemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByDateRange(doctorSystemId, startDate, endDate);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/date-range")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleByDateRange(
            @PathVariable String doctorSystemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByDateRange(doctorSystemId, startDate, endDate);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleByStatus(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String status) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByStatus(doctorSystemId, status.toUpperCase());
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/status/{status}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleByStatus(
            @PathVariable String doctorSystemId,
            @PathVariable String status) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByStatus(doctorSystemId, status.toUpperCase());
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleWithPatient(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        String doctorSystemId = getDoctorSystemIdFromUserId(systemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByPatient(doctorSystemId, patientSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        requireDoctorSelf(doctorSystemId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByPatient(doctorSystemId, patientSystemId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DoctorDashboardResponse> getMyDashboard(
            @RequestAttribute("systemId") String systemId) {
        getDoctorSystemIdFromUserId(systemId);
        DoctorDashboardResponse dashboard = doctorScheduleService.getDoctorDashboard(systemId);
        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/{systemId}/dashboard")
    public ResponseEntity<DoctorDashboardResponse> getDoctorDashboard(@PathVariable String systemId) {
        requireDoctorSelf(systemId);
        DoctorDashboardResponse dashboard = doctorScheduleService.getDoctorDashboard(systemId);
        return ResponseEntity.ok(dashboard);
    }
}
