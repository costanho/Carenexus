package com.carenexus.api.documents.repository;

import com.carenexus.api.documents.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Documents. The patient list is always paged; combine its filters with {@link DocumentSpecifications} and
 * {@code findAll(Specification, Pageable)}. Only "visible" rows (not deleted, with a stored file) exist for the API.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Integer>, JpaSpecificationExecutor<Document> {

    /** A visible document by id: not soft-deleted and stored through the API (rows from before V41 have no key). */
    @Query("SELECT d FROM Document d WHERE d.documentId = :documentId "
            + "AND d.isDeleted = false AND d.storageKey IS NOT NULL")
    Optional<Document> findVisibleById(@Param("documentId") Integer documentId);

    /** Same as {@link #findVisibleById}, row-locked, for the soft delete (two concurrent deletes: one wins). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.documentId = :documentId "
            + "AND d.isDeleted = false AND d.storageKey IS NOT NULL")
    Optional<Document> findVisibleByIdForUpdate(@Param("documentId") Integer documentId);
}
