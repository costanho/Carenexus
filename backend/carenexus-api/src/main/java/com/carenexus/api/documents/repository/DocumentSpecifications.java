package com.carenexus.api.documents.repository;

import com.carenexus.api.documents.model.Document;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Filters for {@link DocumentRepository#findAll(Specification, org.springframework.data.domain.Pageable)}. The
 * optional filters match every row when their argument is null, so callers chain them without null checks.
 */
public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    /** Rows of this patient that exist for the API: not deleted and with a stored file. */
    public static Specification<Document> visibleOfPatient(String patientSystemId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("patientSystemId"), patientSystemId),
                cb.isFalse(root.get("isDeleted")),
                cb.isNotNull(root.get("storageKey")));
    }

    /** Document types the caller may see. An empty collection matches nothing. */
    public static Specification<Document> typeIn(Collection<String> documentTypes) {
        return (root, query, cb) -> documentTypes.isEmpty()
                ? cb.disjunction() : root.get("documentType").in(documentTypes);
    }

    public static Specification<Document> linkType(String linkType) {
        return (root, query, cb) -> linkType == null ? cb.conjunction() : cb.equal(root.get("linkType"), linkType);
    }

    public static Specification<Document> linkId(Integer linkId) {
        return (root, query, cb) -> linkId == null ? cb.conjunction() : cb.equal(root.get("linkId"), linkId);
    }
}
