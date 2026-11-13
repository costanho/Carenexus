package com.carenexus.api.documents.model;

import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.storage.ScanResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The value lists of the documents API and the rules attached to them: document types and the caregiver read
 * permission of each (decision D1), link types and the document type they require (D2), and the content types the
 * upload allow-list accepts with their file extensions (D8).
 */
public final class DocumentTypes {

    /** Every document type, in the order of the contract (also the order of the error message). */
    public static final List<String> DOCUMENT_TYPES = List.of(
            Document.TYPE_LAB_REPORT, Document.TYPE_IMAGING, Document.TYPE_PRESCRIPTION, Document.TYPE_REFERRAL,
            Document.TYPE_CONSENT, Document.TYPE_DISCHARGE, Document.TYPE_INSURANCE, Document.TYPE_OTHER);

    /** Every link type, in the order of the contract. */
    public static final List<String> LINK_TYPES = List.of(
            Document.LINK_LAB_RESULT, Document.LINK_IMAGING_RESULT, Document.LINK_MEDICAL_RECORD,
            Document.LINK_CONSULTATION, Document.LINK_INSURANCE_CLAIM);

    /** The document type a link type requires; link types not listed accept any type. */
    public static final Map<String, String> REQUIRED_TYPE_FOR_LINK = Map.of(
            Document.LINK_LAB_RESULT, Document.TYPE_LAB_REPORT,
            Document.LINK_IMAGING_RESULT, Document.TYPE_IMAGING);

    /** Allowed detected content types and their accepted file extensions; the first extension is the canonical one. */
    public static final Map<String, List<String>> EXTENSIONS_BY_CONTENT_TYPE;

    static {
        Map<String, List<String>> extensions = new LinkedHashMap<>();
        extensions.put(ScanResult.PDF, List.of(".pdf"));
        extensions.put(ScanResult.JPEG, List.of(".jpg", ".jpeg"));
        extensions.put(ScanResult.PNG, List.of(".png"));
        extensions.put(ScanResult.DICOM, List.of(".dcm", ".dicom"));
        EXTENSIONS_BY_CONTENT_TYPE = Map.copyOf(extensions);
    }

    /** The read permissions a document can need; a caregiver list is filtered by the ones the grant gives. */
    public static final Set<PatientPermission> READ_PERMISSIONS =
            Set.of(PatientPermission.DOCUMENTS, PatientPermission.LAB_RESULTS, PatientPermission.IMAGING);

    private DocumentTypes() {
    }

    public static String documentTypeList() {
        return String.join(", ", DOCUMENT_TYPES);
    }

    public static String linkTypeList() {
        return String.join(", ", LINK_TYPES);
    }

    /** Trimmed and upper-cased, or null when blank. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * Permission needed to read a document of this type (D1): LAB_REPORT needs LAB_RESULTS, IMAGING needs IMAGING,
     * every other type needs DOCUMENTS. Only caregivers are ever restricted by it.
     */
    public static PatientPermission readPermission(String documentType) {
        if (Document.TYPE_LAB_REPORT.equals(documentType)) {
            return PatientPermission.LAB_RESULTS;
        }
        if (Document.TYPE_IMAGING.equals(documentType)) {
            return PatientPermission.IMAGING;
        }
        return PatientPermission.DOCUMENTS;
    }

    /** Document types a holder of {@code permission} may read. */
    public static List<String> typesReadableWith(PatientPermission permission) {
        return DOCUMENT_TYPES.stream().filter(type -> readPermission(type) == permission).toList();
    }

    /** The lower-case allowed spelling of a detected content type, or null when it is not allowed. */
    public static String allowedContentType(String detectedContentType) {
        if (detectedContentType == null) {
            return null;
        }
        String normalized = detectedContentType.trim().toLowerCase(Locale.ROOT);
        return EXTENSIONS_BY_CONTENT_TYPE.containsKey(normalized) ? normalized : null;
    }
}
