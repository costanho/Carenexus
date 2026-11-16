package com.carenexus.api.core.controller;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.core.service.AiServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/**
 * Proxies all AI image-recognition operations to the internal AI service.
 * Frontend never talks to the AI service directly — all calls go through here.
 * JWT authentication is enforced by Spring Security on every endpoint.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiServiceClient aiClient;
    private final UserContext userContext;

    // ── Cases ─────────────────────────────────────────────────────────────────

    @PostMapping("/cases")
    public ResponseEntity<Map> createCase(@RequestBody Map<String, Object> body) {
        return aiClient.createCase(body);
    }

    @GetMapping("/cases")
    public ResponseEntity<Object[]> listCases(
            @RequestParam(required = false) String patientSystemId) {
        return aiClient.listCases(patientSystemId);
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<Map> getCase(@PathVariable int caseId) {
        return aiClient.getCase(caseId);
    }

    @PatchMapping("/cases/{caseId}")
    public ResponseEntity<Map> updateCase(
            @PathVariable int caseId,
            @RequestBody Map<String, Object> body) {
        return aiClient.updateCase(caseId, body);
    }

    // ── X-ray upload ─────────────────────────────────────────────────────────

    @PostMapping(value = "/cases/{caseId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map> uploadXray(
            @PathVariable int caseId,
            @RequestParam("file") MultipartFile file) throws Exception {
        return aiClient.uploadXray(caseId, file);
    }

    @GetMapping("/cases/{caseId}/xrays")
    public ResponseEntity<Object[]> getCaseXrays(@PathVariable int caseId) {
        return aiClient.getCaseXrays(caseId);
    }

    // ── Analysis (streaming SSE) ──────────────────────────────────────────────

    @PostMapping(
            value = "/cases/{caseId}/analyse",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public StreamingResponseBody analyseCase(
            @PathVariable int caseId,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        // All data must be read in the request thread:
        // - SecurityContext won't propagate into StreamingResponseBody's async thread
        // - MultipartFile backing store may be cleaned up after the request thread returns
        String callerSystemId = userContext.getCurrentUserSystemId();
        String callerRole     = userContext.getCurrentUserRole();
        byte[] fileBytes      = file.getBytes();
        String filename       = file.getOriginalFilename();
        String mimeType       = file.getContentType();
        return outputStream -> aiClient.streamAnalysis(
                caseId, fileBytes, filename, mimeType, outputStream, callerSystemId, callerRole);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @PostMapping("/cases/{caseId}/report")
    public ResponseEntity<Map> generateReport(
            @PathVariable int caseId,
            @RequestBody Map<String, Object> body) {
        return aiClient.generateReport(caseId, body);
    }
}
