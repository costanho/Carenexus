package com.carenexus.api.documents.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One document of a patient's record (metadata only). The file is downloaded from
 * {@code GET /api/documents/{documentId}/download}; the storage key and checksum are never exposed.
 */
@Schema(description = "A document in a patient's record (metadata; the file is at /api/documents/{documentId}/download)")
public record DocumentResponse(
        @Schema(description = "Row id", example = "57")
        Integer documentId,

        @Schema(description = "Patient the document belongs to", example = "PAT-00001")
        String patientSystemId,

        @Schema(description = "System id of the account that uploaded it", example = "DOC-00002")
        String uploadedBySystemId,

        @Schema(description = "Uploader's display name; doctors carry the Dr. title", example = "Dr. Sarah Moyo")
        String uploadedByName,

        @Schema(description = "Uploader's role", allowableValues = {"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"},
                example = "DOCTOR")
        String uploadedByRole,

        @Schema(description = "Document type", allowableValues = {"LAB_REPORT", "IMAGING", "PRESCRIPTION", "REFERRAL",
                "CONSENT", "DISCHARGE", "INSURANCE", "OTHER"}, example = "LAB_REPORT")
        String documentType,

        @Schema(description = "Title given at upload (1 to 300 characters)", example = "Full blood count, November 2026")
        String title,

        @Schema(description = "Optional note (up to 2000 characters)", nullable = true,
                example = "Scanned report from the laboratory")
        String description,

        @Schema(description = "Sanitised file name used for the download; always ends with an extension matching "
                + "contentType", example = "fbc-2026-11.pdf")
        String originalFilename,

        @Schema(description = "Content type detected from the bytes (never the declared one)",
                allowableValues = {"application/pdf", "image/jpeg", "image/png", "application/dicom"},
                example = "application/pdf")
        String contentType,

        @Schema(description = "Stored size in bytes (1 to 10485760)", example = "248113")
        Long sizeBytes,

        @Schema(description = "What the document is attached to; null for a free-standing document", nullable = true,
                allowableValues = {"LAB_RESULT", "IMAGING_RESULT", "MEDICAL_RECORD", "CONSULTATION", "INSURANCE_CLAIM"},
                example = "LAB_RESULT")
        String linkType,

        @Schema(description = "Row id of the linked item; null without linkType, or when the item did not exist yet "
                + "at upload", nullable = true, example = "88")
        Integer linkId,

        @Schema(description = "Upload time, clinic wall-clock without offset", type = "string",
                example = "2026-11-10T14:05:31")
        LocalDateTime uploadedAt,

        @Schema(description = "True when the caller is the patient, the uploader or an administrator (shows the "
                + "delete control; DELETE re-checks the caller's current access)", example = "true")
        boolean canDelete) {
}
