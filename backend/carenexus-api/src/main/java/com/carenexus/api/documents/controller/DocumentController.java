package com.carenexus.api.documents.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.documents.dto.response.DocumentResponse;
import com.carenexus.api.documents.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Documents in a patient's record. Every rule is enforced in {@link DocumentService}.
 *
 * - POST   /api/documents                            multipart upload, 201 (200 on idempotent replay)
 * - GET    /api/documents/patient/{patientSystemId}  paged list, newest first
 * - GET    /api/documents/{id}                       metadata
 * - GET    /api/documents/{id}/download              the file, streamed through the API as an attachment
 * - DELETE /api/documents/{id}                       soft delete, 204
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Files in a patient's record: upload, list, metadata, download and soft delete")
public class DocumentController {

    static final String UPLOAD_SCOPE = "POST /api/documents";
    static final String CACHE_CONTROL = "private, no-store";

    private final DocumentService documentService;
    private final IdempotencyService idempotencyService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document into a patient's record",
            description = "The patient (own record), a related doctor, a caregiver whose active grant allows managing "
                    + "documents (can_manage_care_plan, level not VIEW_ONLY) or an administrator. One file of 1 byte "
                    + "to 10 MB; its real type is detected from the bytes and must be PDF, JPEG, PNG or DICOM, and the "
                    + "file must pass the security scan (otherwise 400 FILE_REJECTED). The file is stored under a "
                    + "generated key, never the client file name; the sanitised name is kept for the download. "
                    + "linkType may be sent without linkId (item not created yet); a linkId must name an item of "
                    + "that type for the same patient. Rate limit document-upload (60 per hour per caller). "
                    + "Accepts Idempotency-Key: a repeat returns the stored body with 200 and stores nothing. "
                    + "Side effects: audit CREATE DOCUMENT, DocumentUploadedEvent.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The document was stored"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the body of the first call"),
            @ApiResponse(responseCode = "413", description = "File larger than 10 MB or request larger than 12 MB",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "The request is not multipart/form-data",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many uploads (code RATE_LIMITED, Retry-After header)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "File storage or scanner unavailable (code PROVIDER_UNAVAILABLE)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DocumentResponse> upload(
            @Parameter(description = "Patient the document belongs to (PAT-...)")
            @RequestParam(required = false) @NotBlank String patientSystemId,
            @Parameter(description = "LAB_REPORT, IMAGING, PRESCRIPTION, REFERRAL, CONSENT, DISCHARGE, INSURANCE or OTHER; "
                    + "LAB_REPORT when linkType is LAB_RESULT, IMAGING when linkType is IMAGING_RESULT")
            @RequestParam(required = false) @NotBlank String documentType,
            @Parameter(description = "Title, at most 300 characters")
            @RequestParam(required = false) @NotBlank @Size(max = 300) String title,
            @Parameter(description = "Optional note, at most 2000 characters")
            @RequestParam(required = false) @Size(max = 2000) String description,
            @Parameter(description = "Optional: LAB_RESULT, IMAGING_RESULT, MEDICAL_RECORD, CONSULTATION or INSURANCE_CLAIM")
            @RequestParam(required = false) String linkType,
            @Parameter(description = "Optional row id of the linked item; only with linkType")
            @RequestParam(required = false) @Positive Integer linkId,
            @Parameter(description = "The file (PDF, JPEG, PNG or DICOM, at most 10 MB)")
            @RequestPart(value = "file", required = false) @NotNull(message = "is required") MultipartFile file,
            @Parameter(description = "Optional duplicate-request key: 8 to 64 characters of letters, digits, '_' or '-'")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        if (file != null && file.getSize() > DocumentService.MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(DocumentService.MAX_FILE_BYTES);
        }
        DocumentService.UploadCommand command = documentService.prepareUpload(
                patientSystemId, documentType, title, description, linkType, linkId, file);
        // Two phases: the scan and the storage write run outside the idempotency transaction (no pooled connection
        // held), the row and the stored response commit together; a failed commit deletes the stored file.
        IdempotentResult<DocumentResponse> result = idempotencyService.execute(
                UPLOAD_SCOPE, idempotencyKey, DocumentResponse.class, () -> documentService.storeUpload(command),
                documentService::commitUpload, documentService::discardUpload);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's documents, newest first",
            description = "The patient (own record), a related doctor or an administrator see every type. A caregiver "
                    + "sees LAB_REPORT only with lab-result access, IMAGING only with imaging access and the other "
                    + "types only with record access; 403 when the grant gives none of them, or lacks the access of an "
                    + "explicit type filter. Deleted documents are never listed. Filters combine with AND; linkId needs "
                    + "linkType. Sorted by upload time, then id, descending. Side effect: audit READ DOCUMENT.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of document metadata rows")
    public PageResponse<DocumentResponse> listForPatient(
            @Parameter(description = "Patient system id (PAT-...)") @PathVariable String patientSystemId,
            @Parameter(description = "Optional document type filter") @RequestParam(required = false) String type,
            @Parameter(description = "Optional link type filter") @RequestParam(required = false) String linkType,
            @Parameter(description = "Optional linked item id (needs linkType)") @RequestParam(required = false) Integer linkId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return documentService.listForPatient(patientSystemId, type, linkType, linkId, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get the metadata of one document",
            description = "Anyone who may read the document's type for its patient (same rules as the list). An "
                    + "unknown or deleted document is 404 for every caller. Side effect: audit READ DOCUMENT.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The document metadata")
    public DocumentResponse getDocument(@Parameter(description = "Document id") @PathVariable Integer id) {
        return documentService.getDocument(id);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download the file of one document",
            description = "Same access rule as the metadata, checked on every call. The whole file is streamed "
                    + "through the API (no Range support) as an attachment with the sanitised file name, the detected "
                    + "content type, nosniff and Cache-Control private, no-store. Errors are JSON. Side effect: audit "
                    + "READ DOCUMENT once the file was opened.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The file",
                    content = {
                            @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "application/dicom", schema = @Schema(type = "string", format = "binary"))
                    }),
            @ApiResponse(responseCode = "503", description = "File storage unavailable (code PROVIDER_UNAVAILABLE)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<InputStreamResource> download(@Parameter(description = "Document id") @PathVariable Integer id) {
        DocumentService.DocumentDownload download = documentService.openDownload(id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.originalFilename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff");
        if (download.sizeBytes() >= 0) {
            response.contentLength(download.sizeBytes());
        }
        return response.body(new InputStreamResource(download.content()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document (soft delete)",
            description = "Needs document management access to the patient, and the caller must be the patient, the "
                    + "uploader or an administrator (a related doctor or a managing caregiver deletes only their own "
                    + "uploads). The file is kept; the document then answers 404 everywhere, including a second "
                    + "delete. Side effect: audit DELETE DOCUMENT.")
    @StandardErrors
    @ApiResponses(@ApiResponse(responseCode = "204", description = "The document was deleted"))
    public ResponseEntity<Void> delete(@Parameter(description = "Document id") @PathVariable Integer id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
