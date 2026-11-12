package com.carenexus.api.account.legal.repository;

import com.carenexus.api.account.legal.model.LegalAcceptance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Acceptance rows of the terms and the privacy policy.
 */
@Repository
public interface LegalAcceptanceRepository extends JpaRepository<LegalAcceptance, Integer> {

    /**
     * Records an acceptance once. Returns 1 when the row was written and 0 when this account had already accepted
     * this version. ON CONFLICT keeps the surrounding transaction usable on a repeat.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO legal_acceptances (system_id, doc_type, version, accepted_at, ip_address, user_agent) "
            + "VALUES (:systemId, :docType, :version, :acceptedAt, :ipAddress, :userAgent) "
            + "ON CONFLICT (system_id, doc_type, version) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("systemId") String systemId, @Param("docType") String docType,
                       @Param("version") String version, @Param("acceptedAt") Instant acceptedAt,
                       @Param("ipAddress") String ipAddress, @Param("userAgent") String userAgent);

    /** The newest 50 rows of one account, newest first (acceptedAt, then id). */
    List<LegalAcceptance> findTop50BySystemIdOrderByAcceptedAtDescAcceptanceIdDesc(String systemId);

    boolean existsBySystemIdAndDocTypeAndVersion(String systemId, String docType, String version);

    /** One page of an account's rows; the caller passes the sort. */
    Page<LegalAcceptance> findBySystemId(String systemId, Pageable pageable);

    /**
     * Number of current document versions the account has not accepted (0, 1 or 2). Drives
     * {@code legalActionRequired}.
     */
    @Query(value = "SELECT COUNT(*) FROM legal_documents d "
            + "WHERE NOT EXISTS (SELECT 1 FROM legal_documents n WHERE n.doc_type = d.doc_type "
            + "  AND (n.effective_at > d.effective_at OR (n.effective_at = d.effective_at AND n.document_id > d.document_id))) "
            + "AND NOT EXISTS (SELECT 1 FROM legal_acceptances a WHERE a.system_id = :systemId "
            + "  AND a.doc_type = d.doc_type AND a.version = d.version)", nativeQuery = true)
    long countUnacceptedCurrentVersions(@Param("systemId") String systemId);
}
