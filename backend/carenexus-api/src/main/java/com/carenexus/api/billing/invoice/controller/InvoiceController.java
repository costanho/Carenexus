package com.carenexus.api.billing.invoice.controller;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.CancelInvoiceRequest;
import com.carenexus.api.billing.invoice.dto.CreateInvoiceRequest;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.invoice.dto.UpdateInvoiceRequest;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Invoices for patients, caregivers, the issuing doctor and administrators.
 *
 * - POST /api/billing/invoices                 generate the invoice of a visit (doctor of the visit, admin; Idempotency-Key)
 * - GET  /api/billing/invoices/{id}            one invoice (patient, caregiver with BILLING, issuing doctor, admin)
 * - GET  /api/billing/invoices?patientSystemId=&status=&consultationId=&appointmentId=&page=&size=
 * - GET  /api/billing/invoices/me?status=&consultationId=&appointmentId=&page=&size=   (patient)
 * - PUT  /api/billing/invoices/{id}            edit a DRAFT (issuing doctor, admin)
 * - POST /api/billing/invoices/{id}/send       DRAFT to SENT (issuing doctor, admin)
 * - POST /api/billing/invoices/{id}/cancel     cancel an unpaid invoice (issuing doctor, admin)
 */
@RestController
@RequestMapping("/api/billing/invoices")
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: invoices", description = "Invoice generation, lifecycle and reads")
public class InvoiceController {

    static final String CREATE_SCOPE = "POST /api/billing/invoices";

    private final InvoiceService invoiceService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @StandardErrors
    @Operation(summary = "Generate the invoice of a consultation or an appointment",
            description = "Doctor of the visit (active clinician) or ADMIN. Always creates a DRAFT; without lines one "
                    + "line is generated from the fee schedule. Idempotent per visit: an existing non-cancelled invoice "
                    + "of the consultation or appointment is returned with 200 and the body is not applied. Optional "
                    + "Idempotency-Key header (replay answers 200 with the stored body). Audited as CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The invoice was created"),
            @ApiResponse(responseCode = "200", description = "The visit already had an invoice, or the key was replayed")
    })
    public ResponseEntity<InvoiceResponse> createInvoice(
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateInvoiceRequest request) {
        AtomicBoolean created = new AtomicBoolean(false);
        IdempotentResult<InvoiceResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                InvoiceResponse.class, () -> {
                    InvoiceService.GenerationResult generation = invoiceService.generate(request);
                    created.set(generation.created());
                    return generation.invoice();
                });
        HttpStatus status = !result.replayed() && created.get() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.body());
    }

    @GetMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Get one invoice",
            description = "PATIENT (own, issued only), CAREGIVER with an active grant allowing billing (issued only), "
                    + "the issuing DOCTOR (any status), ADMIN. A never-issued invoice answers 404 to patients and "
                    + "caregivers. Audited as READ.")
    @ApiResponse(responseCode = "200", description = "The invoice")
    public ResponseEntity<InvoiceResponse> getInvoice(@PathVariable("id") Integer id) {
        return ResponseEntity.ok(invoiceService.getForCaller(id));
    }

    @GetMapping
    @StandardErrors
    @Operation(summary = "List the invoices of one patient",
            description = "PATIENT (own; patientSystemId optional), CAREGIVER with billing access, DOCTOR with a care "
                    + "relationship (only invoices the caller issued), ADMIN (all of that patient). Patients and "
                    + "caregivers never see never-issued invoices. Newest first. One READ audit row per patient.")
    @ApiResponse(responseCode = "200", description = "One page of invoices")
    public ResponseEntity<PageResponse<InvoiceResponse>> listInvoices(
            @Parameter(description = "Patient; defaults to the caller for a PATIENT, required otherwise")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer consultationId,
            @RequestParam(required = false) Integer appointmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listForPatient(patientSystemId, status, consultationId, appointmentId,
                page, size));
    }

    @GetMapping("/me")
    @StandardErrors
    @Operation(summary = "List the calling patient's own invoices",
            description = "PATIENT only. Never-issued invoices are excluded. Newest first. Audited as READ.")
    @ApiResponse(responseCode = "200", description = "One page of invoices")
    public ResponseEntity<PageResponse<InvoiceResponse>> listMyInvoices(
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer consultationId,
            @RequestParam(required = false) Integer appointmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listMine(status, consultationId, appointmentId, page, size));
    }

    @PutMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Edit a draft invoice",
            description = "Issuing DOCTOR or ADMIN. Replaces lines, discount and notes of a DRAFT invoice and "
                    + "recomputes the totals with the current tax rate. 409 once the invoice left DRAFT. Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The updated draft")
    public ResponseEntity<InvoiceResponse> updateInvoice(@PathVariable("id") Integer id,
                                                         @Valid @RequestBody UpdateInvoiceRequest request) {
        return ResponseEntity.ok(invoiceService.update(id, request));
    }

    @PostMapping("/{id}/send")
    @StandardErrors
    @Operation(summary = "Send a draft invoice to the patient",
            description = "Issuing DOCTOR or ADMIN. DRAFT becomes SENT, issuedAt = now, dueDate = today + payment "
                    + "terms; publishes InvoiceStatusChangedEvent and InvoiceIssuedEvent. Already SENT: no-op. "
                    + "Any request body is ignored. Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The sent invoice")
    public ResponseEntity<InvoiceResponse> sendInvoice(@PathVariable("id") Integer id) {
        return ResponseEntity.ok(invoiceService.send(id));
    }

    @PostMapping("/{id}/cancel")
    @StandardErrors
    @Operation(summary = "Cancel an unpaid invoice",
            description = "Issuing DOCTOR or ADMIN. DRAFT, SENT or OVERDUE with nothing paid becomes CANCELLED; the "
                    + "visit can then be invoiced again. Already CANCELLED: no-op. Publishes "
                    + "InvoiceStatusChangedEvent. Audited as UPDATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The cancelled invoice"),
            @ApiResponse(responseCode = "409", description = "The invoice has payments or is REFUNDED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<InvoiceResponse> cancelInvoice(@PathVariable("id") Integer id,
                                                         @Valid @RequestBody(required = false) CancelInvoiceRequest request) {
        return ResponseEntity.ok(invoiceService.cancel(id, request == null ? null : request.getReason()));
    }
}
