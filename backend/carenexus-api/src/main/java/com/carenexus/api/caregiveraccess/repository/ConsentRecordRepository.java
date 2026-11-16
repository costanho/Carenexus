package com.carenexus.api.caregiveraccess.repository;

import com.carenexus.api.caregiveraccess.model.ConsentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The consent ledger. Insert and read only: the ledger is immutable, so no update or delete method is exposed.
 * Only rows with an event kind (written by this module) are listed.
 */
public interface ConsentRecordRepository extends Repository<ConsentRecord, Integer> {

    ConsentRecord save(ConsentRecord record);

    /** The patient's ledger, newest first; {@code accessId} null = every grant. */
    @Query(value = "SELECT c FROM ConsentRecord c WHERE c.patientSystemId = :patientSystemId "
            + "AND c.eventKind IS NOT NULL AND (:accessId IS NULL OR c.accessId = :accessId) "
            + "ORDER BY c.signedDate DESC, c.consentId DESC",
            countQuery = "SELECT COUNT(c) FROM ConsentRecord c WHERE c.patientSystemId = :patientSystemId "
                    + "AND c.eventKind IS NOT NULL AND (:accessId IS NULL OR c.accessId = :accessId)")
    Page<ConsentRecord> findLedger(@Param("patientSystemId") String patientSystemId,
                                   @Param("accessId") Integer accessId, Pageable pageable);
}
