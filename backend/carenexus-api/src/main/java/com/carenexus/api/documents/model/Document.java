package com.carenexus.api.documents.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A file in a patient's record (table {@code documents}, V1, re-keyed by V3, extended by V41).
 *
 * The bytes live in {@link com.carenexus.api.storage.FileStorage} under {@link #storageKey}; the row never holds a
 * client-supplied location. A row is visible through the API only while it is not deleted and has a storage key
 * (rows written before V41 have none). Deleting is soft ({@link #isDeleted} + {@link #deletedAt}); the stored file
 * is kept. The legacy columns {@code file_url}, {@code mime_type} and {@code file_size_kb} are deliberately not
 * mapped and stay null on new rows.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    // ---- documentType (existing CHECK on documents.document_type) ----
    public static final String TYPE_LAB_REPORT = "LAB_REPORT";
    public static final String TYPE_IMAGING = "IMAGING";
    public static final String TYPE_PRESCRIPTION = "PRESCRIPTION";
    public static final String TYPE_REFERRAL = "REFERRAL";
    public static final String TYPE_CONSENT = "CONSENT";
    public static final String TYPE_DISCHARGE = "DISCHARGE";
    public static final String TYPE_INSURANCE = "INSURANCE";
    public static final String TYPE_OTHER = "OTHER";

    // ---- linkType (V41 CHECK chk_documents_link_type) ----
    public static final String LINK_LAB_RESULT = "LAB_RESULT";
    public static final String LINK_IMAGING_RESULT = "IMAGING_RESULT";
    public static final String LINK_MEDICAL_RECORD = "MEDICAL_RECORD";
    public static final String LINK_CONSULTATION = "CONSULTATION";
    public static final String LINK_INSURANCE_CLAIM = "INSURANCE_CLAIM";

    public static final int TITLE_MAX = 300;
    public static final int DESCRIPTION_MAX = 2000;
    public static final int ORIGINAL_FILENAME_MAX = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Integer documentId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "uploaded_by_system_id", nullable = false, length = 20)
    private String uploadedBySystemId;

    @Column(name = "document_type", nullable = false, length = 20)
    private String documentType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "storage_key", length = 200)
    private String storageKey;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "link_type", length = 30)
    private String linkType;

    @Column(name = "link_id")
    private Integer linkId;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    /** Clinic wall-clock time of the upload, set by the service. */
    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /** Clinic wall-clock time of the soft delete. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Deleted rows and rows without a stored file do not exist for the API. */
    public boolean isVisible() {
        return !Boolean.TRUE.equals(isDeleted) && storageKey != null;
    }
}
