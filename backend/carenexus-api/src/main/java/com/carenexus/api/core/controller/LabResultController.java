package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateLabResultRequest;
import com.carenexus.api.core.dto.response.LabResultResponse;
import com.carenexus.api.core.service.LabResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lab-results")
@RequiredArgsConstructor
public class LabResultController {

    private final LabResultService labResultService;

    @PostMapping
    public ResponseEntity<LabResultResponse> createLabResult(@RequestBody CreateLabResultRequest request) {
        LabResultResponse response = labResultService.createLabResult(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LabResultResponse> getLabResultById(@PathVariable Integer id) {
        LabResultResponse response = labResultService.getLabResultById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<LabResultResponse>> getPatientLabResults(@PathVariable Integer patientId) {
        List<LabResultResponse> results = labResultService.getPatientLabResults(patientId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<List<LabResultResponse>> getDoctorLabResults(@PathVariable Integer doctorId) {
        List<LabResultResponse> results = labResultService.getDoctorLabResults(doctorId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/consultation/{consultationId}")
    public ResponseEntity<List<LabResultResponse>> getConsultationLabResults(@PathVariable Integer consultationId) {
        List<LabResultResponse> results = labResultService.getConsultationLabResults(consultationId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/blood")
    public ResponseEntity<List<LabResultResponse>> getBloodTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("BLOOD");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/urine")
    public ResponseEntity<List<LabResultResponse>> getUrineTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("URINE");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/stool")
    public ResponseEntity<List<LabResultResponse>> getStoolTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("STOOL");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/culture")
    public ResponseEntity<List<LabResultResponse>> getCultureTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("CULTURE");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/biopsy")
    public ResponseEntity<List<LabResultResponse>> getBiopsyTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("BIOPSY");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/genetic")
    public ResponseEntity<List<LabResultResponse>> getGeneticTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("GENETIC");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/swab")
    public ResponseEntity<List<LabResultResponse>> getSwabTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("SWAB");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/other")
    public ResponseEntity<List<LabResultResponse>> getOtherTestResults() {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType("OTHER");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/{testType}")
    public ResponseEntity<List<LabResultResponse>> getLabResultsByTestType(@PathVariable String testType) {
        List<LabResultResponse> results = labResultService.getLabResultsByTestType(testType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientId}/type/{testType}")
    public ResponseEntity<List<LabResultResponse>> getPatientLabResultsByTestType(
            @PathVariable Integer patientId,
            @PathVariable String testType) {
        List<LabResultResponse> results = labResultService.getPatientLabResultsByTestType(patientId, testType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/doctor/{doctorId}/type/{testType}")
    public ResponseEntity<List<LabResultResponse>> getDoctorLabResultsByTestType(
            @PathVariable Integer doctorId,
            @PathVariable String testType) {
        List<LabResultResponse> results = labResultService.getDoctorLabResultsByTestType(doctorId, testType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<LabResultResponse>> getLabResultsByStatus(@PathVariable String status) {
        List<LabResultResponse> results = labResultService.getLabResultsByStatus(status.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientId}/status/{status}")
    public ResponseEntity<List<LabResultResponse>> getPatientLabResultsByStatus(
            @PathVariable Integer patientId,
            @PathVariable String status) {
        List<LabResultResponse> results = labResultService.getPatientLabResultsByStatus(patientId, status.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/critical")
    public ResponseEntity<List<LabResultResponse>> getCriticalLabResults() {
        List<LabResultResponse> results = labResultService.getCriticalLabResults();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientId}/critical")
    public ResponseEntity<List<LabResultResponse>> getPatientCriticalLabResults(@PathVariable Integer patientId) {
        List<LabResultResponse> results = labResultService.getPatientCriticalLabResults(patientId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/review/pending")
    public ResponseEntity<List<LabResultResponse>> getPendingReviewLabResults() {
        List<LabResultResponse> results = labResultService.getPendingReviewLabResults();
        return ResponseEntity.ok(results);
    }

    @PutMapping("/{id}")
    public ResponseEntity<LabResultResponse> updateLabResult(
            @PathVariable Integer id,
            @RequestBody CreateLabResultRequest request) {
        LabResultResponse response = labResultService.updateLabResult(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<LabResultResponse> updateLabResultStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        LabResultResponse response = labResultService.updateLabResultStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<LabResultResponse> reviewLabResult(
            @PathVariable Integer id,
            @RequestParam Integer reviewedBy,
            @RequestParam String status) {
        LabResultResponse response = labResultService.reviewLabResult(id, reviewedBy, status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLabResult(@PathVariable Integer id) {
        labResultService.deleteLabResult(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<List<LabResultResponse>> getMyLabResults(
            @RequestAttribute("userId") Integer userId,
            @RequestAttribute("userRole") String userRole) {
        List<LabResultResponse> results = labResultService.getLabResultsForUser(userId, userRole);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/critical")
    public ResponseEntity<List<LabResultResponse>> getMyCriticalLabResults(
            @RequestAttribute("userId") Integer userId,
            @RequestAttribute("userRole") String userRole) {
        List<LabResultResponse> results = labResultService.getCriticalLabResultsForUser(userId, userRole);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/type/{testType}")
    public ResponseEntity<List<LabResultResponse>> getMyLabResultsByTestType(
            @PathVariable String testType,
            @RequestAttribute("userId") Integer userId,
            @RequestAttribute("userRole") String userRole) {
        List<LabResultResponse> results = labResultService.getLabResultsForUser(userId, userRole)
                .stream()
                .filter(result -> result.getTestType().equalsIgnoreCase(testType))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/status/{status}")
    public ResponseEntity<List<LabResultResponse>> getMyLabResultsByStatus(
            @PathVariable String status,
            @RequestAttribute("userId") Integer userId,
            @RequestAttribute("userRole") String userRole) {
        List<LabResultResponse> results = labResultService.getLabResultsForUser(userId, userRole)
                .stream()
                .filter(result -> result.getStatus().equalsIgnoreCase(status))
                .toList();
        return ResponseEntity.ok(results);
    }
}
