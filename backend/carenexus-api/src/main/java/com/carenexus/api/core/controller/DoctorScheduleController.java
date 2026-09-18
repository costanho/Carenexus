package com.carenexus.api.core.controller;

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

    private Integer getDoctorIdFromUserId(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
        return doctor.getDoctorId();
    }

    @GetMapping
    public ResponseEntity<List<ScheduleEntryResponse>> getMySchedule(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorSchedule(@PathVariable Integer doctorId) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/today")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyTodaySchedule(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorTodaySchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/today")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorTodaySchedule(@PathVariable Integer doctorId) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorTodaySchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyUpcomingSchedule(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorUpcomingSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/upcoming")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorUpcomingSchedule(@PathVariable Integer doctorId) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorUpcomingSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/past")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyPastSchedule(
            @RequestAttribute("userId") Integer userId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorPastSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/past")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorPastSchedule(@PathVariable Integer doctorId) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorPastSchedule(doctorId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/date-range")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleByDateRange(
            @RequestAttribute("userId") Integer userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByDateRange(doctorId, startDate, endDate);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/date-range")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleByDateRange(
            @PathVariable Integer doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByDateRange(doctorId, startDate, endDate);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleByStatus(
            @RequestAttribute("userId") Integer userId,
            @PathVariable String status) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByStatus(doctorId, status.toUpperCase());
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/status/{status}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleByStatus(
            @PathVariable Integer doctorId,
            @PathVariable String status) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByStatus(doctorId, status.toUpperCase());
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getMyScheduleWithPatient(
            @RequestAttribute("userId") Integer userId,
            @PathVariable Integer patientId) {
        Integer doctorId = getDoctorIdFromUserId(userId);
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByPatient(doctorId, patientId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{doctorId}/patient/{patientId}")
    public ResponseEntity<List<ScheduleEntryResponse>> getDoctorScheduleWithPatient(
            @PathVariable Integer doctorId,
            @PathVariable Integer patientId) {
        List<ScheduleEntryResponse> schedule = doctorScheduleService.getDoctorScheduleByPatient(doctorId, patientId);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DoctorDashboardResponse> getMyDashboard(
            @RequestAttribute("userId") Integer userId) {
        DoctorDashboardResponse dashboard = doctorScheduleService.getDoctorDashboard(userId);
        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/{userId}/dashboard")
    public ResponseEntity<DoctorDashboardResponse> getDoctorDashboard(@PathVariable Integer userId) {
        DoctorDashboardResponse dashboard = doctorScheduleService.getDoctorDashboard(userId);
        return ResponseEntity.ok(dashboard);
    }
}
