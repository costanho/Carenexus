package com.carenexus.api.core.service;

import com.carenexus.api.common.config.AiServiceConfig;
import com.carenexus.api.common.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;


/**
 * Internal client — all calls to the AI service go through here.
 * Spring Boot is the only entry point; this class handles auth headers.
 */
@Service
@RequiredArgsConstructor
public class AiServiceClient {

    private final AiServiceConfig config;
    private final UserContext userContext;

    @Qualifier("aiRestTemplate")
    private final RestTemplate restTemplate;

    // ── Case operations ──────────────────────────────────────────────────────

    public ResponseEntity<Map> createCase(Map<String, Object> body) {
        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/",
                HttpMethod.POST,
                new HttpEntity<>(body, internalHeaders()),
                Map.class
        );
    }

    public ResponseEntity<Map> getCase(int caseId) {
        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/" + caseId,
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class
        );
    }

    public ResponseEntity<Object[]> listCases(String patientSystemId) {
        String url = config.aiServiceUrl + "/cases/" + (patientSystemId != null ? "?patient_system_id=" + patientSystemId : "");
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(internalHeaders()), Object[].class);
    }

    public ResponseEntity<Map> updateCase(int caseId, Map<String, Object> body) {
        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/" + caseId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, internalHeaders()),
                Map.class
        );
    }

    // ── X-ray upload ─────────────────────────────────────────────────────────

    public ResponseEntity<Map> uploadXray(int caseId, MultipartFile file) throws IOException {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(fileResource, buildFileHeaders(file)));

        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/" + caseId + "/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
    }

    public ResponseEntity<Object[]> getCaseXrays(int caseId) {
        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/" + caseId + "/xrays",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Object[].class
        );
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    /**
     * Streaming SSE analysis — uploads the X-ray and passes through
     * Server-Sent Events in real time using HttpURLConnection (not RestTemplate,
     * which buffers the full response before returning).
     */
    public void streamAnalysis(int caseId, byte[] fileBytes, String filename, String mimeType,
                               OutputStream responseStream, String callerSystemId, String callerRole) throws IOException {
        String boundary = "----CareNexusBoundary" + System.currentTimeMillis();
        if (filename == null) filename = "xray.png";
        if (mimeType == null) mimeType = "image/png";

        // Build multipart body eagerly — Java 11 HttpClient sends it in one shot (no buffering deadlock)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeBytes("--" + boundary + "\r\n");
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
            dos.writeBytes("Content-Type: " + mimeType + "\r\n\r\n");
            dos.write(fileBytes);
            dos.writeBytes("\r\n--" + boundary + "--\r\n");
        }
        byte[] body = bos.toByteArray();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1) // Uvicorn speaks HTTP/1.1 only
                .build();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.aiServiceUrl + "/cases/" + caseId + "/analyse"))
                .timeout(Duration.ofMinutes(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "text/event-stream")
                .header("X-Internal-Key", config.aiInternalKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (callerSystemId != null) reqBuilder.header("X-Caller-System-Id", callerSystemId);
        if (callerRole     != null) reqBuilder.header("X-Caller-Role",      callerRole);

        HttpResponse<InputStream> response;
        try {
            response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Analysis request interrupted", e);
        }

        if (response.statusCode() != 200) {
            String err = new String(response.body().readAllBytes());
            throw new IOException("AI service returned " + response.statusCode() + ": " + err);
        }

        // Stream SSE events byte-by-byte — no buffering
        try (InputStream in = response.body()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                responseStream.write(buf, 0, n);
                responseStream.flush();
            }
        }
    }

    // ── Report generation ─────────────────────────────────────────────────────

    public ResponseEntity<Map> generateReport(int caseId, Map<String, Object> body) {
        return restTemplate.exchange(
                config.aiServiceUrl + "/cases/" + caseId + "/report",
                HttpMethod.POST,
                new HttpEntity<>(body, internalHeaders()),
                Map.class
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", config.aiInternalKey);
        headers.set("X-Caller-System-Id", userContext.getCurrentUserSystemId());
        headers.set("X-Caller-Role", userContext.getCurrentUserRole());
        return headers;
    }

    private HttpHeaders buildFileHeaders(MultipartFile file) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream"));
        return h;
    }
}
