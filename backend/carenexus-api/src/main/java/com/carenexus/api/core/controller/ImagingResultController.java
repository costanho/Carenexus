package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.CreateImagingResultRequest;
import com.carenexus.api.core.dto.response.ImagingResultResponse;
import com.carenexus.api.core.service.ImagingResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/imaging-results")
@RequiredArgsConstructor
public class ImagingResultController {

    private final ImagingResultService imagingResultService;

    @PostMapping
    public ResponseEntity<ImagingResultResponse> createImagingResult(@RequestBody CreateImagingResultRequest request) {
        ImagingResultResponse response = imagingResultService.createImagingResult(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImagingResultResponse> getImagingResultById(@PathVariable Integer id) {
        ImagingResultResponse response = imagingResultService.getImagingResultById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientSystemId}")
    public ResponseEntity<List<ImagingResultResponse>> getPatientImagingResults(@PathVariable String patientSystemId) {
        List<ImagingResultResponse> results = imagingResultService.getPatientImagingResults(patientSystemId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/doctor/{doctorSystemId}")
    public ResponseEntity<List<ImagingResultResponse>> getDoctorImagingResults(@PathVariable String doctorSystemId) {
        List<ImagingResultResponse> results = imagingResultService.getDoctorImagingResults(doctorSystemId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/consultation/{consultationId}")
    public ResponseEntity<List<ImagingResultResponse>> getConsultationImagingResults(@PathVariable Integer consultationId) {
        List<ImagingResultResponse> results = imagingResultService.getConsultationImagingResults(consultationId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/xray")
    public ResponseEntity<List<ImagingResultResponse>> getXrayResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("XRAY");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/mri")
    public ResponseEntity<List<ImagingResultResponse>> getMriResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("MRI");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/ct-scan")
    public ResponseEntity<List<ImagingResultResponse>> getCtScanResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("CT_SCAN");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/ultrasound")
    public ResponseEntity<List<ImagingResultResponse>> getUltrasoundResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("ULTRASOUND");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/pet")
    public ResponseEntity<List<ImagingResultResponse>> getPetResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("PET");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/mammography")
    public ResponseEntity<List<ImagingResultResponse>> getMammographyResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("MAMMOGRAPHY");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/dexa")
    public ResponseEntity<List<ImagingResultResponse>> getDexaResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("DEXA");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/echo")
    public ResponseEntity<List<ImagingResultResponse>> getEchoResults() {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType("ECHO");
        return ResponseEntity.ok(results);
    }

    @GetMapping("/type/{imagingType}")
    public ResponseEntity<List<ImagingResultResponse>> getImagingResultsByType(@PathVariable String imagingType) {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByType(imagingType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientSystemId}/type/{imagingType}")
    public ResponseEntity<List<ImagingResultResponse>> getPatientImagingResultsByType(
            @PathVariable String patientSystemId,
            @PathVariable String imagingType) {
        List<ImagingResultResponse> results = imagingResultService.getPatientImagingResultsByType(patientSystemId, imagingType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/doctor/{doctorSystemId}/type/{imagingType}")
    public ResponseEntity<List<ImagingResultResponse>> getDoctorImagingResultsByType(
            @PathVariable String doctorSystemId,
            @PathVariable String imagingType) {
        List<ImagingResultResponse> results = imagingResultService.getDoctorImagingResultsByType(doctorSystemId, imagingType.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ImagingResultResponse>> getImagingResultsByStatus(@PathVariable String status) {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsByStatus(status.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientSystemId}/status/{status}")
    public ResponseEntity<List<ImagingResultResponse>> getPatientImagingResultsByStatus(
            @PathVariable String patientSystemId,
            @PathVariable String status) {
        List<ImagingResultResponse> results = imagingResultService.getPatientImagingResultsByStatus(patientSystemId, status.toUpperCase());
        return ResponseEntity.ok(results);
    }

    @GetMapping("/critical")
    public ResponseEntity<List<ImagingResultResponse>> getCriticalImagingResults() {
        List<ImagingResultResponse> results = imagingResultService.getCriticalImagingResults();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/patient/{patientSystemId}/critical")
    public ResponseEntity<List<ImagingResultResponse>> getPatientCriticalImagingResults(@PathVariable String patientSystemId) {
        List<ImagingResultResponse> results = imagingResultService.getPatientCriticalImagingResults(patientSystemId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/review/pending")
    public ResponseEntity<List<ImagingResultResponse>> getPendingReviewImagingResults() {
        List<ImagingResultResponse> results = imagingResultService.getPendingReviewImagingResults();
        return ResponseEntity.ok(results);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ImagingResultResponse> updateImagingResult(
            @PathVariable Integer id,
            @RequestBody CreateImagingResultRequest request) {
        ImagingResultResponse response = imagingResultService.updateImagingResult(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ImagingResultResponse> updateImagingResultStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        ImagingResultResponse response = imagingResultService.updateImagingResultStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<ImagingResultResponse> reviewImagingResult(
            @PathVariable Integer id,
            @RequestParam String reviewedBy,
            @RequestParam String status) {
        ImagingResultResponse response = imagingResultService.reviewImagingResult(id, reviewedBy, status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImagingResult(@PathVariable Integer id) {
        imagingResultService.deleteImagingResult(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<List<ImagingResultResponse>> getMyImagingResults(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsForUser(systemId, userRole);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/critical")
    public ResponseEntity<List<ImagingResultResponse>> getMyCriticalImagingResults(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ImagingResultResponse> results = imagingResultService.getCriticalImagingResultsForUser(systemId, userRole);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/type/{imagingType}")
    public ResponseEntity<List<ImagingResultResponse>> getMyImagingResultsByType(
            @PathVariable String imagingType,
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsForUser(systemId, userRole)
                .stream()
                .filter(result -> result.getImagingType().equalsIgnoreCase(imagingType))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/me/status/{status}")
    public ResponseEntity<List<ImagingResultResponse>> getMyImagingResultsByStatus(
            @PathVariable String status,
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        List<ImagingResultResponse> results = imagingResultService.getImagingResultsForUser(systemId, userRole)
                .stream()
                .filter(result -> result.getStatus().equalsIgnoreCase(status))
                .toList();
        return ResponseEntity.ok(results);
    }
}
