package com.carenexus.api.account.legal.repository;

import java.time.Instant;

/**
 * A legal document version without its text: what the version lists, the session flag and the acceptance checks
 * need. {@code pendingLegalReview} is computed by the database query (text contains "pending legal review").
 */
public record LegalDocumentHeader(Integer documentId, String docType, String version, String title,
                                  Instant effectiveAt, boolean pendingLegalReview) {
}
