package com.carenexus.api.account.legal.repository;

import com.carenexus.api.account.legal.model.LegalDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Versions of the terms and the privacy policy. "Current" is the version with the greatest effective_at of its
 * type, ties broken by the greatest document_id: a row is current when no row of the same type is newer.
 */
@Repository
public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Integer> {

    /** JPQL condition: no row of the same type is newer than {@code d}. */
    String IS_CURRENT = "NOT EXISTS (SELECT n.documentId FROM LegalDocument n WHERE n.docType = d.docType "
            + "AND (n.effectiveAt > d.effectiveAt OR (n.effectiveAt = d.effectiveAt AND n.documentId > d.documentId)))";

    /** The current version of every type, without the text (at most one row per type). */
    @Query("SELECT new com.carenexus.api.account.legal.repository.LegalDocumentHeader("
            + "d.documentId, d.docType, d.version, d.title, d.effectiveAt, "
            + "CASE WHEN LOWER(d.contentMd) LIKE '%" + LegalDocument.PENDING_REVIEW_PHRASE + "%' THEN true ELSE false END) "
            + "FROM LegalDocument d WHERE " + IS_CURRENT + " ORDER BY d.docType")
    List<LegalDocumentHeader> findCurrentHeaders();

    /** The current version of one type, with its text. */
    @Query("SELECT d FROM LegalDocument d WHERE d.docType = :docType AND " + IS_CURRENT)
    Optional<LegalDocument> findCurrentByDocType(@Param("docType") String docType);

    boolean existsByDocTypeAndVersion(String docType, String version);

    /**
     * Locks every version of one type (SELECT ... FOR UPDATE) until the transaction ends, so two publishes of the
     * same type run one after the other and each sees the other's row in its duplicate and date checks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM LegalDocument d WHERE d.docType = :docType ORDER BY d.documentId")
    List<LegalDocument> lockVersionsOfType(@Param("docType") String docType);
}
