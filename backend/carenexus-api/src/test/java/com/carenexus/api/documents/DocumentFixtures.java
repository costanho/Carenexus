package com.carenexus.api.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Test files whose type the local scanner detects from the bytes, and rows of the tables a document can link to.
 */
public final class DocumentFixtures {

    private DocumentFixtures() {
    }

    public static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] pngBytes() {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    public static byte[] jpegBytes() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    public static byte[] dicomBytes() {
        byte[] bytes = new byte[200];
        byte[] magic = "DICM".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, bytes, 128, magic.length);
        return bytes;
    }

    /** Plain text: detected as application/octet-stream, so not allowed whatever its name says. */
    public static byte[] textBytes() {
        return "just some notes, not a medical file".getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code size} bytes that start like a PDF. */
    public static byte[] pdfOfSize(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'a');
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    public static MockMultipartFile file(String filename, String declaredType, byte[] content) {
        return new MockMultipartFile("file", filename, declaredType, content);
    }

    public static MockMultipartFile pdf(String filename) {
        return file(filename, "application/pdf", pdfBytes());
    }

    /** A lab result of the patient (only the NOT NULL columns); returns its result_id. */
    public static int insertLabResult(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId) {
        Integer id = jdbc.queryForObject(
                "INSERT INTO lab_results (patient_system_id, doctor_system_id, test_name, test_type, test_date) "
                        + "VALUES (?, ?, 'Full blood count', 'BLOOD', ?) RETURNING result_id",
                Integer.class, patientSystemId, doctorSystemId, java.sql.Date.valueOf(LocalDate.now()));
        return id == null ? 0 : id;
    }

    /** A medical record of the patient; returns its record_id. */
    public static int insertMedicalRecord(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                          boolean deleted) {
        Integer id = jdbc.queryForObject(
                "INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, title, is_deleted) "
                        + "VALUES (?, ?, 'NOTE', 'Follow-up note', ?) RETURNING record_id",
                Integer.class, patientSystemId, doctorSystemId, deleted);
        return id == null ? 0 : id;
    }

    /** An imaging result of the patient (only the NOT NULL columns); returns its imaging_id. */
    public static int insertImagingResult(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId) {
        Integer id = jdbc.queryForObject(
                "INSERT INTO imaging_results (patient_system_id, doctor_system_id, imaging_type, image_date) "
                        + "VALUES (?, ?, 'XRAY', ?) RETURNING imaging_id",
                Integer.class, patientSystemId, doctorSystemId, java.sql.Date.valueOf(LocalDate.now()));
        return id == null ? 0 : id;
    }

    /**
     * A completed consultation of the patient, with the completed appointment it belongs to (a consultation needs
     * one); returns its consultation_id. {@code minutesAgo} keeps the appointment times of one doctor distinct.
     */
    public static int insertConsultation(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                         int minutesAgo) {
        Integer appointmentId = jdbc.queryForObject(
                "INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, status) "
                        + "VALUES (?, ?, ?, 'COMPLETED') RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1).minusMinutes(minutesAgo)));
        Integer id = jdbc.queryForObject(
                "INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status) "
                        + "VALUES (?, ?, ?, 'COMPLETED') RETURNING consultation_id",
                Integer.class, appointmentId, patientSystemId, doctorSystemId);
        return id == null ? 0 : id;
    }

    /** A submitted insurance claim of the patient (only the NOT NULL columns); returns its claim_id. */
    public static int insertInsuranceClaim(JdbcTemplate jdbc, String patientSystemId) {
        Integer id = jdbc.queryForObject(
                "INSERT INTO insurance_claims (patient_system_id, insurance_provider, policy_number, claim_amount) "
                        + "VALUES (?, 'Test Medical Aid', 'POL-123456', 150.00) RETURNING claim_id",
                Integer.class, patientSystemId);
        return id == null ? 0 : id;
    }

    /** A document row written before V41: a free-text file_url and no storage key. Returns its document_id. */
    public static int insertLegacyDocument(JdbcTemplate jdbc, String patientSystemId, String uploaderSystemId) {
        Integer id = jdbc.queryForObject(
                "INSERT INTO documents (patient_system_id, uploaded_by_system_id, document_type, title, file_url) "
                        + "VALUES (?, ?, 'OTHER', 'Legacy scan', 'https://example.org/legacy.pdf') RETURNING document_id",
                Integer.class, patientSystemId, uploaderSystemId);
        return id == null ? 0 : id;
    }
}
