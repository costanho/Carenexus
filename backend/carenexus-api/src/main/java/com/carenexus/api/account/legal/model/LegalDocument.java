package com.carenexus.api.account.legal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * One published version of the Terms of Service or the Privacy Policy (table {@code legal_documents}, V29).
 *
 * The current version of a type is the row with the greatest {@code effectiveAt} (tie: greatest
 * {@code documentId}). Versions are immutable: rows are inserted by V29 and by an administrator's publish, never
 * updated or deleted through the API.
 */
@Entity
@Table(name = "legal_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalDocument {

    public static final String TYPE_TERMS = "TERMS";
    public static final String TYPE_PRIVACY = "PRIVACY";
    /** Every document type, in the order responses list them. */
    public static final List<String> TYPES = List.of(TYPE_TERMS, TYPE_PRIVACY);

    /** Phrase that marks placeholder copy (D7); matched case-insensitively. */
    public static final String PENDING_REVIEW_PHRASE = "pending legal review";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Integer documentId;

    /** {@link #TYPE_TERMS} or {@link #TYPE_PRIVACY}. */
    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    /** Opaque version label, unique per type, compared exactly. */
    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Text in the restricted Markdown subset, stored as sent. */
    @Column(name = "content_md", nullable = false, columnDefinition = "TEXT")
    private String contentMd;

    @Column(name = "effective_at", nullable = false, columnDefinition = "timestamptz")
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    /** Administrator who published the version; null for the versions shipped by migrations. */
    @Column(name = "published_by_system_id", length = 20)
    private String publishedBySystemId;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** True while the text is placeholder copy (contains "pending legal review", any case). */
    public static boolean isPendingLegalReview(String contentMd) {
        return contentMd != null && contentMd.toLowerCase(Locale.ROOT).contains(PENDING_REVIEW_PHRASE);
    }

    /**
     * Normalises a document type sent by a client: trimmed and upper-cased. Returns null when the value is not a
     * known type.
     */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        return TYPES.contains(type) ? type : null;
    }
}
