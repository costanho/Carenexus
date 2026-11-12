package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.DocumentUploadedEvent;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.documents.DocumentFixtures;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.provider.LocalFileScanner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for documents (contract api/be-documents.md): the authorisation matrix of upload, list, metadata,
 * download and delete for every role (caregiver flags, VIEW_ONLY, FULL_ACCESS, expired grant), the field and file
 * checks with their exact envelopes (type allow-list, EICAR, empty file, size limit), idempotent replay, link
 * filters and link targets, soft delete, legacy rows, the download headers, audit rows and the upload event.
 * Runs with committed transactions (CommittedIntegrationTest).
 */
@RecordApplicationEvents
class DocumentReleaseGateTest extends CommittedIntegrationTest {

    private static final String DOCS = "/api/documents";

    private static final String NOT_OWN = "Patients can only access their own data";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";
    private static final String NO_MANAGE = "Your proxy access for this patient does not allow managing documents";
    private static final String NO_VIEW_DOCS = "Your proxy access for this patient does not allow viewing documents";
    private static final String NO_VIEW_LABS = "Your proxy access for this patient does not allow viewing lab results";
    private static final String NOT_FOUND = "Document not found";
    private static final String TYPE_NOT_ALLOWED = "This file type is not allowed. Upload a PDF, JPEG, PNG or DICOM file.";

    private static final List<String> RESPONSE_FIELDS = List.of("documentId", "patientSystemId", "uploadedBySystemId",
            "uploadedByName", "uploadedByRole", "documentType", "title", "description", "originalFilename",
            "contentType", "sizeBytes", "linkType", "linkId", "uploadedAt", "canDelete");

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    @LocalServerPort
    private int port;

    // ==================== HELPERS ====================

    /** POST /api/documents as {@code as} with the given file (may be null) and text parts (name, value, ...). */
    private MvcResult upload(User as, MockMultipartFile file, Map<String, String> headers, String... parts) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(DOCS);
        if (file != null) {
            builder.file(file);
        }
        for (int i = 0; i + 1 < parts.length; i += 2) {
            if (parts[i + 1] != null) {
                builder.param(parts[i], parts[i + 1]);
            }
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }
        builder.header("Authorization", tokenFor(as));
        return mockMvc.perform(builder).andReturn();
    }

    private MvcResult upload(User as, MockMultipartFile file, String... parts) throws Exception {
        return upload(as, file, null, parts);
    }

    private MvcResult uploadPdf(User as, User forPatient, String documentType) throws Exception {
        return upload(as, DocumentFixtures.pdf("report.pdf"),
                "patientSystemId", forPatient.getSystemId(), "documentType", documentType, "title", "Report");
    }

    /** Uploads a PDF and asserts 201; returns the documentId. */
    private int stored(User as, User forPatient, String documentType) throws Exception {
        MvcResult result = uploadPdf(as, forPatient, documentType);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("documentId").asInt();
    }

    private void assertStatus(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        if (message != null) {
            assertEquals(message, message(result));
        }
    }

    private void assertValidation(MvcResult result, String message) throws Exception {
        assertStatus(result, 400, message);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private void assertFileRejected(MvcResult result, String message) throws Exception {
        assertStatus(result, 400, message);
        assertEquals("FILE_REJECTED", json(result).path("code").asText());
    }

    private int auditRows(String action, Integer resourceId, String patientSystemId) {
        Integer count = resourceId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'DOCUMENT' "
                + "AND resource_id IS NULL AND patient_system_id = ? AND status = 'SUCCESS'", Integer.class, action, patientSystemId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'DOCUMENT' "
                + "AND resource_id = ? AND patient_system_id = ? AND status = 'SUCCESS'", Integer.class, action, resourceId, patientSystemId);
        return count == null ? 0 : count;
    }

    private List<Integer> ids(MvcResult result) throws Exception {
        List<Integer> ids = new ArrayList<>();
        json(result).path("content").forEach(row -> ids.add(row.path("documentId").asInt()));
        return ids;
    }

    private String storageKeyOf(int documentId) {
        return jdbc.queryForObject("SELECT storage_key FROM documents WHERE document_id = ?", String.class, documentId);
    }

    private static Map<String, String> idempotencyKey(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", key);
        return headers;
    }

    // ==================== UPLOAD ====================

    @Test
    void patientUploadsIntoOwnRecordWithStoredFileAuditAndEvent() throws Exception {
        byte[] bytes = DocumentFixtures.pdfBytes();
        MvcResult result = upload(patient, DocumentFixtures.file("../../etc/scan.exe", "application/x-msdownload", bytes),
                "patientSystemId", " pat-00001 ", "documentType", "insurance", "title", "  Medical aid card  ",
                "description", "   ");
        assertStatus(result, 201, null);
        JsonNode body = json(result);
        for (String field : RESPONSE_FIELDS) {
            assertTrue(body.has(field), "missing field " + field);
        }
        assertEquals(RESPONSE_FIELDS.size(), body.size(), "no extra fields (storage key, checksum, fileUrl)");
        int id = body.path("documentId").asInt();
        assertEquals("PAT-00001", body.path("patientSystemId").asText());
        assertEquals("PAT-00001", body.path("uploadedBySystemId").asText());
        assertEquals("PATIENT User1", body.path("uploadedByName").asText());
        assertEquals("PATIENT", body.path("uploadedByRole").asText());
        assertEquals("INSURANCE", body.path("documentType").asText());
        assertEquals("Medical aid card", body.path("title").asText());
        assertTrue(body.path("description").isNull());
        assertEquals("scan.exe.pdf", body.path("originalFilename").asText());
        assertEquals("application/pdf", body.path("contentType").asText());
        assertEquals(bytes.length, body.path("sizeBytes").asLong());
        assertTrue(body.path("linkType").isNull());
        assertTrue(body.path("linkId").isNull());
        assertTrue(body.path("canDelete").asBoolean());
        LocalDateTime uploadedAt = LocalDateTime.parse(body.path("uploadedAt").asText());
        assertTrue(uploadedAt.isAfter(LocalDateTime.now().minusMinutes(5)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM documents WHERE document_id = ?", id);
        String key = (String) row.get("storage_key");
        LocalDate today = LocalDate.now();
        assertTrue(key.matches(String.format("documents/%04d/%02d/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                today.getYear(), today.getMonthValue())), key);
        assertNull(row.get("file_url"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), row.get("sha256"));
        assertEquals(Boolean.FALSE, row.get("is_deleted"));
        try (InputStream in = fileStorage.open(key)) {
            assertArrayEquals(bytes, in.readAllBytes());
        }

        assertEquals(1, auditRows("CREATE", id, "PAT-00001"));
        List<DocumentUploadedEvent> published = events.stream(DocumentUploadedEvent.class).toList();
        assertEquals(1, published.size());
        assertEquals(new DocumentUploadedEvent(id, "PAT-00001", "PAT-00001", "INSURANCE"), published.get(0));
    }

    @Test
    void uploadAuthorisationMatrix() throws Exception {
        // Related doctor: allowed, named with the title.
        MvcResult byDoctor = uploadPdf(doctor, patient, "LAB_REPORT");
        assertStatus(byDoctor, 201, null);
        assertEquals("Dr. DOCTOR User2", json(byDoctor).path("uploadedByName").asText());
        assertEquals("DOCTOR", json(byDoctor).path("uploadedByRole").asText());
        assertTrue(json(byDoctor).path("canDelete").asBoolean());

        // Unrelated users of every role.
        assertStatus(uploadPdf(otherPatient, patient, "OTHER"), 403, NOT_OWN);
        assertStatus(uploadPdf(otherDoctor, patient, "OTHER"), 403, NOT_CARE_TEAM);
        assertStatus(uploadPdf(otherCaregiver, patient, "OTHER"), 403, NO_PROXY);
        MvcResult denied = uploadPdf(caregiver, patient, "OTHER");
        assertStatus(denied, 403, NO_PROXY);
        assertEquals("DOCUMENT", json(denied).path("resourceType").asText());

        // Caregiver: read flags do not allow uploads; can_manage_care_plan does, unless VIEW_ONLY or expired.
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setCanViewLabResults(true);
        });
        assertStatus(uploadPdf(caregiver, patient, "OTHER"), 403, NO_MANAGE);
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        assertStatus(uploadPdf(caregiver, patient, "OTHER"), 403, NO_MANAGE);
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertStatus(uploadPdf(caregiver, patient, "OTHER"), 403, NO_PROXY);
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        MvcResult byCaregiver = uploadPdf(caregiver, patient, "OTHER");
        assertStatus(byCaregiver, 201, null);
        assertEquals("CAREGIVER", json(byCaregiver).path("uploadedByRole").asText());
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertStatus(uploadPdf(caregiver, patient, "OTHER"), 201, null);

        // Administrator: any patient; an unknown (well-formed) patient is 404 only for them.
        assertStatus(uploadPdf(admin, patient, "OTHER"), 201, null);
        assertStatus(upload(admin, DocumentFixtures.pdf("a.pdf"),
                "patientSystemId", "PAT-99999", "documentType", "OTHER", "title", "x"), 404, "Patient not found");
        assertStatus(upload(admin, DocumentFixtures.pdf("a.pdf"),
                "patientSystemId", "PAT-00002", "documentType", "OTHER", "title", "x"), 201, null);

        // No token.
        MvcResult anonymous = mockMvc.perform(multipart(DOCS).file(DocumentFixtures.pdf("a.pdf"))
                .param("patientSystemId", "PAT-00001").param("documentType", "OTHER").param("title", "x")).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());

        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));
    }

    @Test
    void uploadFieldValidationEnvelopes() throws Exception {
        MockMultipartFile pdf = DocumentFixtures.pdf("a.pdf");
        MvcResult missingTitle = upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER");
        assertValidation(missingTitle, "title: must not be blank");
        assertEquals("title", json(missingTitle).path("errors").get(0).path("field").asText());

        assertValidation(upload(patient, pdf, "documentType", "OTHER", "title", "x"),
                "patientSystemId: must not be blank");
        assertValidation(upload(patient, pdf, "patientSystemId", "DOC-00001", "documentType", "OTHER", "title", "x"),
                "patientSystemId: must be a patient id such as PAT-00001");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "title", "x"),
                "documentType: must not be blank");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "PHOTO", "title", "x"),
                "documentType: must be one of LAB_REPORT, IMAGING, PRESCRIPTION, REFERRAL, CONSENT, DISCHARGE, INSURANCE, OTHER");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title",
                "t".repeat(301)), "title: size must be between 0 and 300");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x",
                "description", "d".repeat(2001)), "description: size must be between 0 and 2000");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x",
                "linkType", "LAB_RESULT"), "documentType: must be LAB_REPORT when linkType is LAB_RESULT");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "LAB_REPORT", "title", "x",
                "linkType", "IMAGING_RESULT"), "documentType: must be IMAGING when linkType is IMAGING_RESULT");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x",
                "linkType", "INVOICE"),
                "linkType: must be one of LAB_RESULT, IMAGING_RESULT, MEDICAL_RECORD, CONSULTATION, INSURANCE_CLAIM");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x",
                "linkId", "5"), "linkId: requires linkType");
        assertValidation(upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x",
                "linkType", "MEDICAL_RECORD", "linkId", "0"), "linkId: must be greater than 0");
        MvcResult notInteger = upload(patient, pdf, "patientSystemId", "PAT-00001", "documentType", "OTHER",
                "title", "x", "linkType", "MEDICAL_RECORD", "linkId", "abc");
        assertStatus(notInteger, 400, "Invalid value for parameter 'linkId'");
        assertValidation(upload(patient, null, "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x"),
                "file: is required");

        // Several invalid fields: the alphabetically first one is reported, every one is listed.
        MvcResult several = upload(patient, null, "documentType", "OTHER");
        assertValidation(several, "file: is required");
        assertEquals(3, json(several).path("errors").size());

        // Not multipart at all.
        MvcResult json = mockMvc.perform(post(DOCS).header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertStatus(json, 415, "Unsupported content type");

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));
    }

    @Test
    void fileChecksDecideFromTheBytes() throws Exception {
        String[] parts = {"patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x"};
        assertFileRejected(upload(patient, DocumentFixtures.file("notes.pdf", "application/pdf",
                DocumentFixtures.textBytes()), parts), TYPE_NOT_ALLOWED);
        assertFileRejected(upload(patient, DocumentFixtures.file("empty.pdf", "application/pdf", new byte[0]), parts),
                "The file is empty");
        assertFileRejected(upload(patient, DocumentFixtures.file("eicar.pdf", "application/pdf",
                LocalFileScanner.eicarTestBytes()), parts), "The file was rejected by the security scan");
        byte[] pdfWithEicar = (new String(DocumentFixtures.pdfBytes()) + new String(LocalFileScanner.eicarTestBytes()))
                .getBytes();
        assertFileRejected(upload(patient, DocumentFixtures.file("doc.pdf", "application/pdf", pdfWithEicar), parts),
                "The file was rejected by the security scan");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));

        // Over 10 MB: the size envelope (413, no code).
        MvcResult tooLarge = upload(patient, DocumentFixtures.file("big.pdf", "application/pdf",
                DocumentFixtures.pdfOfSize(10 * 1024 * 1024 + 1)), parts);
        assertStatus(tooLarge, 413, "Uploaded file is too large");
        assertFalse(json(tooLarge).has("code"));

        // Each allowed type is detected whatever the declared type and name say.
        Map<String, byte[]> allowed = new LinkedHashMap<>();
        allowed.put("image/png", DocumentFixtures.pngBytes());
        allowed.put("image/jpeg", DocumentFixtures.jpegBytes());
        allowed.put("application/dicom", DocumentFixtures.dicomBytes());
        allowed.put("application/pdf", DocumentFixtures.pdfOfSize(10 * 1024 * 1024));
        Map<String, String> names = Map.of("image/png", "picture.png", "image/jpeg", "picture.jpg",
                "application/dicom", "picture.dcm", "application/pdf", "picture.pdf");
        for (Map.Entry<String, byte[]> entry : allowed.entrySet()) {
            MvcResult ok = upload(patient, DocumentFixtures.file("picture.txt", "text/plain", entry.getValue()), parts);
            assertStatus(ok, 201, null);
            assertEquals(entry.getKey(), json(ok).path("contentType").asText());
            assertEquals(names.get(entry.getKey()).replace("picture", "picture.txt"),
                    json(ok).path("originalFilename").asText());
        }
    }

    @Test
    void oversizedUploadThroughTheServletContainerGets413Envelope() throws Exception {
        // MockMvc does not apply spring.servlet.multipart limits, so this one goes through the real server.
        String boundary = "----carenexus" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String[] field : new String[][]{{"patientSystemId", "PAT-00001"}, {"documentType", "OTHER"}, {"title", "Big"}}) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n"
                    + field[1] + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(DocumentFixtures.pdfOfSize(10 * 1024 * 1024 + 4096));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + DOCS))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", tokenFor(patient))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode(), response.body());
        JsonNode envelope = objectMapper.readTree(response.body());
        assertEquals("Uploaded file is too large", envelope.path("message").asText());
        assertFalse(envelope.has("code"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));
    }

    @Test
    void idempotentReplayReturnsTheStoredBodyWithoutSideEffects() throws Exception {
        String key = "doc-" + UUID.randomUUID().toString().substring(0, 12);
        MvcResult first = upload(patient, DocumentFixtures.pdf("a.pdf"), idempotencyKey(key),
                "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "Once");
        assertStatus(first, 201, null);
        // The repeat's content is not compared: it still answers with the first document.
        MvcResult replay = upload(patient, DocumentFixtures.pdf("b.pdf"), idempotencyKey(key),
                "patientSystemId", "PAT-00001", "documentType", "CONSENT", "title", "Twice");
        assertStatus(replay, 200, null);
        assertEquals(json(first), json(replay));
        int id = json(first).path("documentId").asInt();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));
        assertEquals(1, auditRows("CREATE", id, "PAT-00001"));
        assertEquals(1, events.stream(DocumentUploadedEvent.class).count());

        // A failed first call frees its key.
        String failing = "doc-" + UUID.randomUUID().toString().substring(0, 12);
        assertFileRejected(upload(patient, DocumentFixtures.file("a.pdf", "application/pdf", DocumentFixtures.textBytes()),
                idempotencyKey(failing), "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x"),
                TYPE_NOT_ALLOWED);
        assertStatus(upload(patient, DocumentFixtures.pdf("a.pdf"), idempotencyKey(failing),
                "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x"), 201, null);

        assertStatus(upload(patient, DocumentFixtures.pdf("a.pdf"), idempotencyKey("bad key!"),
                "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "x"), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    @Test
    void linkTargetsMustExistForThePatient() throws Exception {
        int ownLab = DocumentFixtures.insertLabResult(jdbc, "PAT-00001", "DOC-00001");
        int otherLab = DocumentFixtures.insertLabResult(jdbc, "PAT-00002", "DOC-00002");
        int deletedRecord = DocumentFixtures.insertMedicalRecord(jdbc, "PAT-00001", "DOC-00001", true);
        int liveRecord = DocumentFixtures.insertMedicalRecord(jdbc, "PAT-00001", "DOC-00001", false);

        MvcResult linked = upload(doctor, DocumentFixtures.pdf("fbc.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "LAB_REPORT", "title", "FBC", "linkType", "lab_result", "linkId", String.valueOf(ownLab));
        assertStatus(linked, 201, null);
        assertEquals("LAB_RESULT", json(linked).path("linkType").asText());
        assertEquals(ownLab, json(linked).path("linkId").asInt());

        assertValidation(upload(doctor, DocumentFixtures.pdf("fbc.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "LAB_REPORT", "title", "FBC", "linkType", "LAB_RESULT", "linkId", String.valueOf(otherLab)),
                "linkId: no matching item for this patient");
        assertValidation(upload(doctor, DocumentFixtures.pdf("n.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "OTHER", "title", "Note", "linkType", "MEDICAL_RECORD", "linkId", String.valueOf(deletedRecord)),
                "linkId: no matching item for this patient");
        assertValidation(upload(doctor, DocumentFixtures.pdf("n.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "OTHER", "title", "Note", "linkType", "CONSULTATION", "linkId", "999999"),
                "linkId: no matching item for this patient");
        assertStatus(upload(doctor, DocumentFixtures.pdf("n.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "OTHER", "title", "Note", "linkType", "MEDICAL_RECORD", "linkId", String.valueOf(liveRecord)),
                201, null);

        // A link type without an id (item not created yet) is accepted.
        MvcResult pending = upload(patient, DocumentFixtures.pdf("claim.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "INSURANCE", "title", "Claim form", "linkType", "INSURANCE_CLAIM");
        assertStatus(pending, 201, null);
        assertTrue(json(pending).path("linkId").isNull());
    }

    @Test
    void imagingConsultationAndClaimLinkTargetsMustBelongToThePatient() throws Exception {
        int ownImaging = DocumentFixtures.insertImagingResult(jdbc, "PAT-00001", "DOC-00001");
        int otherImaging = DocumentFixtures.insertImagingResult(jdbc, "PAT-00002", "DOC-00002");
        int ownConsultation = DocumentFixtures.insertConsultation(jdbc, "PAT-00001", "DOC-00001", 10);
        int otherConsultation = DocumentFixtures.insertConsultation(jdbc, "PAT-00002", "DOC-00002", 20);
        int ownClaim = DocumentFixtures.insertInsuranceClaim(jdbc, "PAT-00001");
        int otherClaim = DocumentFixtures.insertInsuranceClaim(jdbc, "PAT-00002");

        MvcResult imaging = upload(doctor, DocumentFixtures.file("chest.dcm", "application/dicom",
                        DocumentFixtures.dicomBytes()), "patientSystemId", "PAT-00001", "documentType", "IMAGING",
                "title", "Chest X-ray", "linkType", "imaging_result", "linkId", String.valueOf(ownImaging));
        assertStatus(imaging, 201, null);
        assertEquals("IMAGING_RESULT", json(imaging).path("linkType").asText());
        assertEquals(ownImaging, json(imaging).path("linkId").asInt());
        assertValidation(upload(doctor, DocumentFixtures.pdf("x.pdf"), "patientSystemId", "PAT-00001",
                        "documentType", "IMAGING", "title", "X", "linkType", "IMAGING_RESULT", "linkId",
                        String.valueOf(otherImaging)), "linkId: no matching item for this patient");

        MvcResult consultation = upload(doctor, DocumentFixtures.pdf("referral.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "REFERRAL", "title", "Referral letter", "linkType", "CONSULTATION", "linkId",
                String.valueOf(ownConsultation));
        assertStatus(consultation, 201, null);
        assertEquals("CONSULTATION", json(consultation).path("linkType").asText());
        assertEquals(ownConsultation, json(consultation).path("linkId").asInt());
        assertValidation(upload(doctor, DocumentFixtures.pdf("r.pdf"), "patientSystemId", "PAT-00001",
                        "documentType", "REFERRAL", "title", "R", "linkType", "CONSULTATION", "linkId",
                        String.valueOf(otherConsultation)), "linkId: no matching item for this patient");

        MvcResult claim = upload(patient, DocumentFixtures.pdf("invoice.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "INSURANCE", "title", "Claim invoice", "linkType", "INSURANCE_CLAIM", "linkId",
                String.valueOf(ownClaim));
        assertStatus(claim, 201, null);
        assertEquals("INSURANCE_CLAIM", json(claim).path("linkType").asText());
        assertEquals(ownClaim, json(claim).path("linkId").asInt());
        assertValidation(upload(patient, DocumentFixtures.pdf("c.pdf"), "patientSystemId", "PAT-00001",
                        "documentType", "INSURANCE", "title", "C", "linkType", "INSURANCE_CLAIM", "linkId",
                        String.valueOf(otherClaim)), "linkId: no matching item for this patient");

        // Each linked document is found by its own link filter.
        String url = DOCS + "/patient/PAT-00001";
        assertEquals(List.of(json(imaging).path("documentId").asInt()),
                ids(doGet(url + "?linkType=IMAGING_RESULT&linkId=" + ownImaging, patient)));
        assertEquals(List.of(json(consultation).path("documentId").asInt()),
                ids(doGet(url + "?linkType=CONSULTATION&linkId=" + ownConsultation, patient)));
        assertEquals(List.of(json(claim).path("documentId").asInt()),
                ids(doGet(url + "?linkType=INSURANCE_CLAIM&linkId=" + ownClaim, patient)));
    }

    @Test
    void uploadRateLimitCountsPerCaller() throws Exception {
        rateLimitGuard.reset("document-upload", "PAT-00001");
        rateLimitProperties.setEnabled(true);
        try {
            for (int i = 0; i < 60; i++) {
                assertStatus(upload(patient, DocumentFixtures.file("p.png", "image/png", DocumentFixtures.pngBytes()),
                        "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "n" + i), 201, null);
            }
            MvcResult limited = upload(patient, DocumentFixtures.file("p.png", "image/png", DocumentFixtures.pngBytes()),
                    "patientSystemId", "PAT-00001", "documentType", "OTHER", "title", "one too many");
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));
            assertTrue(message(limited).startsWith("Too many requests. Please try again in "));
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("document-upload", "PAT-00001");
        }
    }

    // ==================== LIST ====================

    @Test
    void listIsPagedNewestFirstAndFilteredPerCaregiverPermission() throws Exception {
        int lab = stored(doctor, patient, "LAB_REPORT");
        int imaging = stored(doctor, patient, "IMAGING");
        int consent = stored(patient, patient, "CONSENT");
        int insurance = stored(patient, patient, "INSURANCE");
        stored(otherPatient, otherPatient, "OTHER");
        String url = DOCS + "/patient/PAT-00001";

        MvcResult own = doGet(url, patient);
        assertStatus(own, 200, null);
        JsonNode page = json(own);
        assertEquals(List.of(insurance, consent, imaging, lab), ids(own));
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt());
        assertEquals(4, page.path("totalElements").asInt());
        assertEquals(1, page.path("totalPages").asInt());
        assertTrue(page.path("content").get(0).path("canDelete").asBoolean());
        assertEquals(1, auditRows("READ", null, "PAT-00001"));

        MvcResult clamped = doGet(url + "?size=500&page=-3", patient);
        assertEquals(100, json(clamped).path("size").asInt());
        assertEquals(0, json(clamped).path("page").asInt());
        MvcResult second = doGet(url + "?size=3&page=1", patient);
        assertEquals(List.of(lab), ids(second));
        assertEquals(2, json(second).path("totalPages").asInt());

        assertEquals(List.of(insurance, consent, imaging, lab), ids(doGet(url, doctor)));
        assertEquals(List.of(insurance, consent, imaging, lab), ids(doGet(url, admin)));
        assertEquals(List.of(lab), ids(doGet(url + "?type=lab_report", doctor)));
        MvcResult doctorView = doGet(url, doctor);
        // The doctor uploaded the lab and imaging files, the patient the other two.
        assertEquals(List.of(false, false, true, true), List.of(
                json(doctorView).path("content").get(0).path("canDelete").asBoolean(),
                json(doctorView).path("content").get(1).path("canDelete").asBoolean(),
                json(doctorView).path("content").get(2).path("canDelete").asBoolean(),
                json(doctorView).path("content").get(3).path("canDelete").asBoolean()));

        assertStatus(doGet(url, otherPatient), 403, NOT_OWN);
        assertStatus(doGet(url, otherDoctor), 403, NOT_CARE_TEAM);
        assertStatus(doGet(url, otherCaregiver), 403, NO_PROXY);
        MvcResult adminUnknown = doGet(DOCS + "/patient/PAT-99999", admin);
        assertStatus(adminUnknown, 200, null);
        assertEquals(0, json(adminUnknown).path("totalElements").asInt());

        // Caregiver: no read flag at all -> 403; each flag shows exactly its types.
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertStatus(doGet(url, caregiver), 403, NO_VIEW_DOCS);
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        MvcResult records = doGet(url, caregiver);
        assertEquals(List.of(insurance, consent), ids(records));
        assertEquals(2, json(records).path("totalElements").asInt());
        MvcResult labDenied = doGet(url + "?type=LAB_REPORT", caregiver);
        assertStatus(labDenied, 403, NO_VIEW_LABS);
        assertEquals("LAB_RESULT", json(labDenied).path("resourceType").asText());
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        assertEquals(List.of(lab), ids(doGet(url, caregiver)));
        assertStatus(doGet(url + "?type=CONSENT", caregiver), 403, NO_VIEW_DOCS);
        grantAccess(caregiver, patient, a -> {
            a.setCanViewImaging(true);
            a.setCanViewLabResults(true);
        });
        assertEquals(List.of(imaging, lab), ids(doGet(url, caregiver)));
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(List.of(insurance, consent, imaging, lab), ids(doGet(url, caregiver)));
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertStatus(doGet(url, caregiver), 403, NO_PROXY);
    }

    @Test
    void listFiltersByLinkAndRejectsInvalidFilters() throws Exception {
        int labId = DocumentFixtures.insertLabResult(jdbc, "PAT-00001", "DOC-00001");
        int otherLabId = DocumentFixtures.insertLabResult(jdbc, "PAT-00001", "DOC-00001");
        int attached = json(upload(doctor, DocumentFixtures.pdf("a.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "LAB_REPORT", "title", "A", "linkType", "LAB_RESULT", "linkId", String.valueOf(labId)))
                .path("documentId").asInt();
        int attachedElsewhere = json(upload(doctor, DocumentFixtures.pdf("b.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "LAB_REPORT", "title", "B", "linkType", "LAB_RESULT", "linkId", String.valueOf(otherLabId)))
                .path("documentId").asInt();
        int pending = json(upload(doctor, DocumentFixtures.pdf("c.pdf"), "patientSystemId", "PAT-00001",
                "documentType", "LAB_REPORT", "title", "C", "linkType", "LAB_RESULT")).path("documentId").asInt();
        int free = stored(doctor, patient, "LAB_REPORT");
        String url = DOCS + "/patient/PAT-00001";

        assertEquals(List.of(attached), ids(doGet(url + "?linkType=LAB_RESULT&linkId=" + labId, patient)));
        assertEquals(List.of(pending, attachedElsewhere, attached), ids(doGet(url + "?linkType=lab_result", patient)));
        assertEquals(List.of(free, pending, attachedElsewhere, attached), ids(doGet(url + "?linkType=&type=", patient)));
        assertEquals(List.of(), ids(doGet(url + "?linkType=LAB_RESULT&linkId=999999", patient)));
        assertEquals(List.of(), ids(doGet(url + "?type=CONSENT&linkType=LAB_RESULT", patient)));

        assertValidation(doGet(url + "?linkId=" + labId, patient), "linkId: requires linkType");
        assertValidation(doGet(url + "?linkType=LAB_RESULT&linkId=0", patient), "linkId: must be greater than 0");
        assertValidation(doGet(url + "?linkType=BILL", patient),
                "linkType: must be one of LAB_RESULT, IMAGING_RESULT, MEDICAL_RECORD, CONSULTATION, INSURANCE_CLAIM");
        assertValidation(doGet(url + "?type=PHOTO", patient),
                "type: must be one of LAB_REPORT, IMAGING, PRESCRIPTION, REFERRAL, CONSENT, DISCHARGE, INSURANCE, OTHER");
        assertStatus(doGet(url + "?linkType=LAB_RESULT&linkId=x", patient), 400, "Invalid value for parameter 'linkId'");
        assertStatus(doGet(url + "?size=big", patient), 400, "Invalid value for parameter 'size'");
    }

    // ==================== METADATA AND DOWNLOAD ====================

    @Test
    void metadataAndDownloadFollowTheReadPermissionOfTheType() throws Exception {
        byte[] bytes = DocumentFixtures.pdfBytes();
        MvcResult created = upload(doctor, DocumentFixtures.file("Résultat sanguin.pdf", "application/pdf", bytes),
                "patientSystemId", "PAT-00001", "documentType", "LAB_REPORT", "title", "FBC");
        int lab = json(created).path("documentId").asInt();
        int consent = stored(patient, patient, "CONSENT");

        MvcResult meta = doGet(DOCS + "/" + lab, patient);
        assertStatus(meta, 200, null);
        assertEquals(json(created).path("title"), json(meta).path("title"));
        assertTrue(json(meta).path("canDelete").asBoolean());
        assertEquals(1, auditRows("READ", lab, "PAT-00001"));

        MvcResult download = doGet(DOCS + "/" + lab + "/download", patient);
        assertStatus(download, 200, null);
        assertArrayEquals(bytes, download.getResponse().getContentAsByteArray());
        assertEquals("application/pdf", download.getResponse().getContentType());
        assertEquals(bytes.length, download.getResponse().getContentLengthLong());
        String disposition = download.getResponse().getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment; "), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''R%C3%A9sultat%20sanguin.pdf"), disposition);
        assertEquals("private, no-store", download.getResponse().getHeader("Cache-Control"));
        assertEquals("nosniff", download.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals(2, auditRows("READ", lab, "PAT-00001"));

        // Everyone who may read the type; unknown id 404 before any permission check.
        assertStatus(doGet(DOCS + "/" + lab, doctor), 200, null);
        assertStatus(doGet(DOCS + "/" + lab + "/download", admin), 200, null);
        assertStatus(doGet(DOCS + "/999999", otherPatient), 404, NOT_FOUND);
        assertStatus(doGet(DOCS + "/999999/download", patient), 404, NOT_FOUND);
        assertStatus(doGet(DOCS + "/abc", patient), 400, "Invalid value for parameter 'id'");
        assertStatus(doGet(DOCS + "/" + lab, otherPatient), 403, NOT_OWN);
        assertStatus(doGet(DOCS + "/" + lab + "/download", otherDoctor), 403, NOT_CARE_TEAM);
        assertStatus(doGet(DOCS + "/" + lab + "/download", otherCaregiver), 403, NO_PROXY);

        // Caregiver: records access opens CONSENT but not LAB_REPORT.
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(doGet(DOCS + "/" + consent, caregiver), 200, null);
        assertStatus(doGet(DOCS + "/" + consent + "/download", caregiver), 200, null);
        MvcResult labDenied = doGet(DOCS + "/" + lab + "/download", caregiver);
        assertStatus(labDenied, 403, NO_VIEW_LABS);
        assertEquals("LAB_RESULT", json(labDenied).path("resourceType").asText());
        assertEquals(lab, json(labDenied).path("resourceId").asInt());
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        assertStatus(doGet(DOCS + "/" + lab + "/download", caregiver), 200, null);
        assertStatus(doGet(DOCS + "/" + consent, caregiver), 403, NO_VIEW_DOCS);

        // The stored file is gone: 404 with its own text, and no READ row for the failed download.
        int before = auditRows("READ", consent, "PAT-00001");
        fileStorage.delete(storageKeyOf(consent));
        assertStatus(doGet(DOCS + "/" + consent + "/download", patient), 404, "The file for this document is not available");
        assertEquals(before, auditRows("READ", consent, "PAT-00001"));
    }

    @Test
    void caregiverLosesAccessToOwnUploadWhenTheGrantExpires() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setCanViewRecords(true);
        });
        int own = stored(caregiver, patient, "OTHER");
        assertStatus(doGet(DOCS + "/" + own + "/download", caregiver), 200, null);
        assertTrue(json(doGet(DOCS + "/" + own, caregiver)).path("canDelete").asBoolean());

        // Read access without the read flag: may upload, but cannot see the upload afterwards.
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertStatus(doGet(DOCS + "/" + own, caregiver), 403, NO_VIEW_DOCS);

        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusSeconds(30));
        });
        assertStatus(doGet(DOCS + "/" + own, caregiver), 403, NO_PROXY);
        assertStatus(doGet(DOCS + "/" + own + "/download", caregiver), 403, NO_PROXY);
        assertStatus(doDelete(DOCS + "/" + own, caregiver), 403, NO_PROXY);
        // The document stays in the patient's record.
        assertStatus(doGet(DOCS + "/" + own, patient), 200, null);
    }

    @Test
    void legacyRowsWithoutAStoredFileDoNotExist() throws Exception {
        int legacy = DocumentFixtures.insertLegacyDocument(jdbc, "PAT-00001", "DOC-00001");
        assertStatus(doGet(DOCS + "/" + legacy, admin), 404, NOT_FOUND);
        assertStatus(doGet(DOCS + "/" + legacy + "/download", patient), 404, NOT_FOUND);
        assertStatus(doDelete(DOCS + "/" + legacy, admin), 404, NOT_FOUND);
        assertEquals(0, json(doGet(DOCS + "/patient/PAT-00001", patient)).path("totalElements").asInt());
    }

    // ==================== DELETE ====================

    @Test
    void softDeleteRulesAndEffects() throws Exception {
        int byDoctor = stored(doctor, patient, "LAB_REPORT");
        int byPatient = stored(patient, patient, "OTHER");
        int byPatientToo = stored(patient, patient, "CONSENT");
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        int byCaregiver = stored(caregiver, patient, "OTHER");

        // Related doctor and managing caregiver delete only their own uploads.
        assertStatus(doDelete(DOCS + "/" + byPatient, doctor), 403,
                "Only the uploader, the patient or an administrator can delete this document");
        assertStatus(doDelete(DOCS + "/" + byPatient, caregiver), 403,
                "Only the uploader, the patient or an administrator can delete this document");
        assertStatus(doDelete(DOCS + "/" + byPatient, otherPatient), 403, NOT_OWN);
        assertStatus(doDelete(DOCS + "/" + byPatient, otherDoctor), 403, NOT_CARE_TEAM);
        assertStatus(doDelete(DOCS + "/" + byPatient, otherCaregiver), 403, NO_PROXY);

        // A caregiver uploader whose grant was narrowed fails the MANAGE_DOCUMENTS check.
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        assertStatus(doDelete(DOCS + "/" + byCaregiver, caregiver), 403, NO_MANAGE);
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertStatus(doDelete(DOCS + "/" + byCaregiver, caregiver), 204, null);

        assertStatus(doDelete(DOCS + "/" + byDoctor, doctor), 204, null);
        assertStatus(doDelete(DOCS + "/" + byPatient, patient), 204, null);
        assertStatus(doDelete(DOCS + "/" + byPatientToo, admin), 204, null);
        assertStatus(doDelete(DOCS + "/" + byPatient, patient), 404, NOT_FOUND);

        for (int id : List.of(byDoctor, byPatient, byPatientToo, byCaregiver)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT is_deleted, deleted_at, storage_key FROM documents "
                    + "WHERE document_id = ?", id);
            assertEquals(Boolean.TRUE, row.get("is_deleted"));
            assertNotNull(row.get("deleted_at"));
            // The file is kept.
            try (InputStream in = fileStorage.open((String) row.get("storage_key"))) {
                assertTrue(in.readAllBytes().length > 0);
            }
            assertStatus(doGet(DOCS + "/" + id, admin), 404, NOT_FOUND);
            assertStatus(doGet(DOCS + "/" + id + "/download", patient), 404, NOT_FOUND);
            assertEquals(1, auditRows("DELETE", id, "PAT-00001"));
        }
        assertEquals(0, json(doGet(DOCS + "/patient/PAT-00001", admin)).path("totalElements").asInt());
    }
}
