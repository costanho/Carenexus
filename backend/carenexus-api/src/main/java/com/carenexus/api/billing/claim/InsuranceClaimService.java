package com.carenexus.api.billing.claim;

import com.carenexus.api.billing.claim.dto.AdjudicateClaimRequest;
import com.carenexus.api.billing.claim.dto.ClaimDocument;
import com.carenexus.api.billing.claim.dto.InsuranceClaimResponse;
import com.carenexus.api.billing.claim.dto.SubmitClaimRequest;
import com.carenexus.api.billing.claim.repository.ClaimLookupQueries;
import com.carenexus.api.billing.claim.repository.ClaimLookupQueries.DocumentRow;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.InvoiceTotals;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.provider.InsuranceClaimProvider;
import com.carenexus.api.billing.provider.claims.InsuranceClaimProviderException;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InsuranceClaimRepository.ClaimKey;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.InsuranceClaimStatusChangedEvent;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Insurance claims: submission by the patient's side, the reads, the appeal, and the administrator's adjudication
 * that settles approved amounts onto the invoice (api/be-billing-claims.md).
 *
 * <h3>State machine</h3>
 * <pre>
 * (none)    -> SUBMITTED                        submit (patient, caregiver with MANAGE_BILLING, admin)
 * SUBMITTED -> PENDING                          adjudicate (admin: "start review")
 * PENDING   -> APPROVED | PARTIAL | REJECTED    adjudicate (admin: decision; REJECTED only without an approved amount)
 * REJECTED  -> APPEALED, PARTIAL -> APPEALED    appeal (patient, caregiver with MANAGE_BILLING, admin)
 * APPEALED  -> PENDING                          adjudicate (admin: "reopen review")
 * </pre>
 * Every other transition is a 409; a request whose target equals the current status is a no-op that changes and
 * publishes nothing. An invoice has at most one open claim (SUBMITTED, PENDING, APPEALED).
 *
 * <h3>Settlement</h3>
 * {@code approvedAmount} is cumulative. A decision (APPROVED or PARTIAL) settles the difference to the previous
 * approved amount: a COMPLETED payment with method INSURANCE is created in the same transaction, applied to the
 * invoice through {@link InvoiceService#applyPayment} and linked on the claim. The settlement must not exceed
 * {@link InvoiceService#outstanding}.
 *
 * <h3>Locks</h3>
 * Every write locks the invoice row first and then, for appeal and adjudication, the claim row, and re-reads the
 * status under the lock. Two concurrent submissions for one invoice give one claim and one 409; two concurrent
 * approvals give one payment and one no-op.
 *
 * <h3>Roles</h3>
 * Doctors never see claims: every method refuses role DOCTOR before a row is loaded. Patients and caregivers go
 * through {@link AuthorizationService#requirePatientAccess} with BILLING (reads) or MANAGE_BILLING (submit, appeal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsuranceClaimService {

    public static final String RESOURCE = "INSURANCE_CLAIM";
    static final String PAYMENT_RESOURCE = "PAYMENT";

    /** Gateway name stored on the payments created by an approval. */
    public static final String INSURANCE_GATEWAY = "INSURANCE";

    static final String CLAIM_NOT_FOUND = "Claim not found";
    static final String INVOICE_NOT_FOUND = "Invoice not found";
    static final String OPEN_CLAIM_EXISTS = "This invoice already has an open insurance claim";
    static final String CLAIM_EXCEEDS_BALANCE = "Claim amount exceeds the outstanding balance";
    static final String DOCUMENTS_NOT_OF_PATIENT = "One or more documents do not belong to this patient";
    static final String PROVIDER_UNAVAILABLE = "The insurance claims service is not available. Try again later.";
    static final String PATIENT_ID_REQUIRED = "patientSystemId is required";
    static final String PARTIAL_NEEDS_AMOUNT = "approvedAmount is required for a partial approval";
    static final String REJECT_NEEDS_REASON = "rejectionReason is required when rejecting a claim";
    static final String PARTIAL_NOT_BELOW_CLAIM = "A partial approval must be below the claimed amount";
    static final String APPROVED_LOWERED = "The approved amount cannot be lower than the amount already approved";
    static final String REJECT_WITH_APPROVED = "A claim with an approved amount cannot be rejected; decide PARTIAL "
            + "with the same amount instead";
    static final String SETTLEMENT_EXCEEDS_BALANCE = "The approved amount exceeds the invoice's outstanding balance";

    static final String TITLE_SUBMITTED = "New insurance claim";
    static final String TITLE_APPEALED = "Insurance claim appealed";
    static final String ADMIN_TAB = "claims";

    /** Sort of every claim list: newest submission first, ties by id. */
    static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "submittedAt").and(Sort.by(Sort.Direction.DESC, "claimId"));

    private static final DateTimeFormatter DEDUPE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final TypeReference<List<Integer>> ID_LIST = new TypeReference<>() {
    };

    /** Allowed adjudication moves: target -> statuses it may come from. */
    private static final Map<String, Set<String>> ADJUDICATION_FROM = Map.of(
            InsuranceClaim.STATUS_PENDING, Set.of(InsuranceClaim.STATUS_SUBMITTED, InsuranceClaim.STATUS_APPEALED),
            InsuranceClaim.STATUS_APPROVED, Set.of(InsuranceClaim.STATUS_PENDING),
            InsuranceClaim.STATUS_PARTIAL, Set.of(InsuranceClaim.STATUS_PENDING),
            InsuranceClaim.STATUS_REJECTED, Set.of(InsuranceClaim.STATUS_PENDING));

    private final InsuranceClaimRepository claimRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;
    private final InsuranceClaimProvider claimProvider;
    private final ClaimLookupQueries lookups;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // =====================================================================================================
    // SUBMIT
    // =====================================================================================================

    /**
     * Submits a claim for an issued invoice (POST /api/billing/invoices/{invoiceId}/claims). The invoice's PATIENT,
     * a CAREGIVER with MANAGE_BILLING, or an ADMIN on the patient's behalf. The invoice must be SENT, PARTIALLY_PAID
     * or OVERDUE, without an open claim; the amount defaults to (and must not exceed) the outstanding balance; every
     * document must be a usable document of the invoice's patient. Publishes InsuranceClaimStatusChangedEvent
     * (previousStatus null) and notifies the administrators. Audited as CREATE.
     */
    @Transactional
    public InsuranceClaimResponse submit(Integer invoiceId, SubmitClaimRequest request) {
        requireClaimRole();
        String caller = authorizationService.currentSystemId();
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND));
        String patient = invoice.getPatientSystemId();
        authorizationService.requirePatientAccess(patient, PatientPermission.MANAGE_BILLING, invoiceId);
        if (!authorizationService.isAdmin() && !issued(invoice)) {
            throw new ResourceNotFoundException(INVOICE_NOT_FOUND);
        }
        if (!Invoice.PAYABLE_STATUSES.contains(invoice.getStatus())) {
            throw new ConflictException("An insurance claim cannot be submitted for an invoice in status "
                    + invoice.getStatus());
        }
        if (claimRepository.existsByInvoiceIdAndStatusIn(invoiceId, InsuranceClaim.OPEN_STATUSES)) {
            throw new ConflictException(OPEN_CLAIM_EXISTS);
        }
        BigDecimal outstanding = invoiceService.outstanding(invoiceId);
        BigDecimal amount = request.getClaimAmount() == null ? outstanding : InvoiceTotals.money(request.getClaimAmount());
        if (amount.signum() <= 0 || amount.compareTo(outstanding) > 0) {
            throw new ConflictException(CLAIM_EXCEEDS_BALANCE);
        }
        List<Integer> documentIds = distinct(request.getDocumentIds());
        if (!documentIds.isEmpty()
                && lookups.countUsableDocuments(patient, new HashSet<>(documentIds)) != documentIds.size()) {
            throw new BadRequestException(DOCUMENTS_NOT_OF_PATIENT);
        }

        LocalDateTime now = now();
        InsuranceClaim claim = InsuranceClaim.builder()
                .patientSystemId(patient)
                .invoiceId(invoiceId)
                .claimNo(String.format(Locale.ROOT, "%s%04d-%06d", InsuranceClaim.CLAIM_NO_PREFIX, now.getYear(),
                        claimRepository.nextClaimNumber()))
                .insuranceProvider(request.getInsuranceProvider())
                .policyNumber(request.getPolicyNumber())
                .memberNumber(blankToNull(request.getMemberNumber()))
                .claimAmount(amount)
                .documentIds(writeIds(documentIds))
                .status(InsuranceClaim.STATUS_SUBMITTED)
                .submittedBySystemId(caller)
                .submittedAt(now)
                .updatedAt(now)
                .build();
        claim.setProviderReference(submitToProvider(claim));
        claim = claimRepository.saveAndFlush(claim);

        auditService.logClinicalAccess("CREATE", RESOURCE, claim.getClaimId(), patient);
        eventPublisher.publishEvent(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), invoiceId, patient,
                null, InsuranceClaim.STATUS_SUBMITTED));
        notifyAdministrators(claim, invoice.getInvoiceNo(), caller, TITLE_SUBMITTED,
                "Claim " + claim.getClaimNo() + " for invoice " + invoice.getInvoiceNo() + " is waiting for review.",
                "CLAIM_SUBMITTED:" + claim.getClaimId());
        return toResponse(claim);
    }

    // =====================================================================================================
    // READS
    // =====================================================================================================

    /**
     * One claim (GET /api/billing/claims/{claimId}): the claim's PATIENT, a CAREGIVER with BILLING, or an ADMIN.
     * Audited as READ.
     */
    @Transactional(readOnly = true)
    public InsuranceClaimResponse get(Integer claimId) {
        requireClaimRole();
        InsuranceClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException(CLAIM_NOT_FOUND));
        authorizationService.requirePatientAccess(claim.getPatientSystemId(), PatientPermission.BILLING, claimId);
        auditService.logClinicalAccess("READ", RESOURCE, claimId, claim.getPatientSystemId());
        return toResponse(claim);
    }

    /**
     * Claims of one patient (GET /api/billing/claims). PATIENT: own id (default); CAREGIVER: grant with BILLING;
     * ADMIN: any patient (id required). Newest first. One READ audit row for the patient.
     */
    @Transactional(readOnly = true)
    public PageResponse<InsuranceClaimResponse> listForPatient(String patientSystemId, String status,
                                                               Integer invoiceId, int page, int size) {
        requireClaimRole();
        String patient = blankToNull(patientSystemId);
        if (patient == null) {
            if (!AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
                throw new BadRequestException(PATIENT_ID_REQUIRED);
            }
            patient = authorizationService.currentSystemId();
        }
        authorizationService.requirePatientAccess(patient, PatientPermission.BILLING);
        List<String> statuses = parseStatuses(status);

        Specification<InsuranceClaim> spec = ClaimSpecifications.patient(patient)
                .and(ClaimSpecifications.statusIn(statuses))
                .and(ClaimSpecifications.invoice(invoiceId));
        Page<InsuranceClaim> rows = claimRepository.findAll(spec, PageParams.of(page, size, LIST_SORT));
        auditService.logClinicalAccess("READ", RESOURCE, null, patient);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * Every claim of every patient (GET /api/admin/billing/claims). ADMIN only. Newest first. One READ audit row
     * per distinct patient on the page.
     */
    @Transactional(readOnly = true)
    public PageResponse<InsuranceClaimResponse> listAll(String status, String patientSystemId, Integer invoiceId,
                                                        int page, int size) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        List<String> statuses = parseStatuses(status);
        Specification<InsuranceClaim> spec = ClaimSpecifications.patient(blankToNull(patientSystemId))
                .and(ClaimSpecifications.statusIn(statuses))
                .and(ClaimSpecifications.invoice(invoiceId));
        Page<InsuranceClaim> rows = claimRepository.findAll(spec, PageParams.of(page, size, LIST_SORT));
        auditService.logListRead(RESOURCE, rows.getContent(), InsuranceClaim::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    // =====================================================================================================
    // APPEAL
    // =====================================================================================================

    /**
     * Appeals a REJECTED or PARTIAL decision (POST /api/billing/claims/{claimId}/appeal): the claim's PATIENT, a
     * CAREGIVER with MANAGE_BILLING, or an ADMIN. The claim becomes APPEALED with the reason, resolvedAt is cleared.
     * Already APPEALED: no-op (the new reason is not stored). Refused while another claim of the invoice is open.
     * Publishes InsuranceClaimStatusChangedEvent and notifies the administrators. Audited as UPDATE.
     */
    @Transactional
    public InsuranceClaimResponse appeal(Integer claimId, String reason) {
        requireClaimRole();
        String caller = authorizationService.currentSystemId();
        ClaimKey key = claimRepository.findKeyByClaimId(claimId)
                .orElseThrow(() -> new ResourceNotFoundException(CLAIM_NOT_FOUND));
        authorizationService.requirePatientAccess(key.getPatientSystemId(), PatientPermission.MANAGE_BILLING, claimId);
        Invoice invoice = lockInvoice(key.getInvoiceId());
        InsuranceClaim claim = lockClaim(claimId);

        String previous = claim.getStatus();
        if (InsuranceClaim.STATUS_APPEALED.equals(previous)) {
            return toResponse(claim);
        }
        if (!InsuranceClaim.APPEALABLE_STATUSES.contains(previous)) {
            throw new ConflictException("A claim in status " + previous + " cannot be appealed");
        }
        if (claim.getInvoiceId() != null && claimRepository.existsByInvoiceIdAndStatusInAndClaimIdNot(
                claim.getInvoiceId(), InsuranceClaim.OPEN_STATUSES, claimId)) {
            throw new ConflictException(OPEN_CLAIM_EXISTS);
        }

        LocalDateTime decidedAt = claim.getResolvedAt();
        claim.setStatus(InsuranceClaim.STATUS_APPEALED);
        claim.setAppealReason(reason == null ? null : reason.trim());
        claim.setResolvedAt(null);
        claim.setUpdatedAt(now());
        claim = claimRepository.saveAndFlush(claim);

        auditService.logClinicalAccess("UPDATE", RESOURCE, claimId, claim.getPatientSystemId());
        publishStatusChange(claim, previous);
        String invoiceNo = invoice == null ? null : invoice.getInvoiceNo();
        notifyAdministrators(claim, invoiceNo, caller, TITLE_APPEALED,
                "Claim " + claim.getClaimNo() + " for invoice " + displayInvoiceNo(invoiceNo)
                        + " was appealed and is waiting for review.",
                "CLAIM_APPEALED:" + claimId + ":" + (decidedAt == null ? "0" : DEDUPE_TIME.format(decidedAt)));
        return toResponse(claim);
    }

    // =====================================================================================================
    // ADJUDICATION (administrator)
    // =====================================================================================================

    /**
     * Moves a claim along the state machine (PATCH /api/admin/billing/claims/{claimId}). ADMIN only. A decision
     * with a settlement above zero creates a COMPLETED INSURANCE payment, applies it to the invoice and links it on
     * the claim, all in this transaction, then publishes PaymentRecordedEvent and InsuranceClaimStatusChangedEvent.
     * A same-status request is a no-op. Audited as UPDATE (and CREATE / PAYMENT for a settlement).
     */
    @Transactional
    public InsuranceClaimResponse adjudicate(Integer claimId, AdjudicateClaimRequest request) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        String admin = authorizationService.currentSystemId();
        String target = request.getStatus() == null ? "" : request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!ADJUDICATION_FROM.containsKey(target)) {
            // Bean Validation refuses this first; kept so a direct caller cannot store another status.
            throw new BadRequestException("status: must be one of PENDING, APPROVED, PARTIAL, REJECTED");
        }
        String reason = blankToNull(request.getRejectionReason());
        if (InsuranceClaim.STATUS_PARTIAL.equals(target) && request.getApprovedAmount() == null) {
            throw new BadRequestException(PARTIAL_NEEDS_AMOUNT);
        }
        if (InsuranceClaim.STATUS_REJECTED.equals(target) && reason == null) {
            throw new BadRequestException(REJECT_NEEDS_REASON);
        }

        ClaimKey key = claimRepository.findKeyByClaimId(claimId)
                .orElseThrow(() -> new ResourceNotFoundException(CLAIM_NOT_FOUND));
        Invoice invoice = lockInvoice(key.getInvoiceId());
        InsuranceClaim claim = lockClaim(claimId);

        String previous = claim.getStatus();
        if (target.equals(previous)) {
            return toResponse(claim);
        }
        if (!ADJUDICATION_FROM.get(target).contains(previous)) {
            throw new ConflictException("A claim cannot move from " + previous + " to " + target);
        }

        BigDecimal claimAmount = InvoiceTotals.money(claim.getClaimAmount());
        BigDecimal previouslyApproved = claim.getApprovedAmount() == null
                ? InvoiceTotals.ZERO : InvoiceTotals.money(claim.getApprovedAmount());
        BigDecimal newApproved = null;
        if (InsuranceClaim.STATUS_PARTIAL.equals(target)) {
            newApproved = InvoiceTotals.money(request.getApprovedAmount());
            if (newApproved.compareTo(claimAmount) >= 0) {
                throw new BadRequestException(PARTIAL_NOT_BELOW_CLAIM);
            }
            if (newApproved.compareTo(previouslyApproved) < 0) {
                throw new BadRequestException(APPROVED_LOWERED);
            }
        } else if (InsuranceClaim.STATUS_APPROVED.equals(target)) {
            newApproved = claimAmount;
        } else if (InsuranceClaim.STATUS_REJECTED.equals(target) && claim.getApprovedAmount() != null) {
            throw new ConflictException(REJECT_WITH_APPROVED);
        }

        BigDecimal settlement = newApproved == null ? InvoiceTotals.ZERO : newApproved.subtract(previouslyApproved);
        if (settlement.signum() > 0) {
            BigDecimal outstanding = invoice == null ? InvoiceTotals.ZERO : invoiceService.outstanding(invoice.getInvoiceId());
            if (settlement.compareTo(outstanding) > 0) {
                throw new ConflictException(SETTLEMENT_EXCEEDS_BALANCE);
            }
        }

        LocalDateTime now = now();
        claim.setStatus(target);
        claim.setReviewedBySystemId(admin);
        claim.setUpdatedAt(now);
        switch (target) {
            case InsuranceClaim.STATUS_APPROVED -> {
                claim.setApprovedAmount(newApproved);
                claim.setRejectionReason(null);
                claim.setResolvedAt(now);
            }
            case InsuranceClaim.STATUS_PARTIAL -> {
                claim.setApprovedAmount(newApproved);
                claim.setRejectionReason(reason);
                claim.setResolvedAt(now);
            }
            case InsuranceClaim.STATUS_REJECTED -> {
                claim.setApprovedAmount(null);
                claim.setRejectionReason(reason);
                claim.setResolvedAt(now);
                claim.setPaymentId(null);
            }
            default -> {
                // PENDING: only the reviewer changes.
            }
        }

        Payment payment = null;
        if (settlement.signum() > 0) {
            payment = paymentRepository.saveAndFlush(Payment.builder()
                    .patientSystemId(claim.getPatientSystemId())
                    .invoiceId(invoice.getInvoiceId())
                    .amount(settlement)
                    .currency(invoice.getCurrency())
                    .status(Payment.STATUS_COMPLETED)
                    .method(PaymentMethod.TYPE_INSURANCE)
                    .gateway(INSURANCE_GATEWAY)
                    .providerReference(claim.getClaimNo())
                    .initiatedBySystemId(admin)
                    .refundedAmount(InvoiceTotals.ZERO)
                    .paidAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            auditService.logClinicalAccess("CREATE", PAYMENT_RESOURCE, payment.getPaymentId(),
                    claim.getPatientSystemId());
            invoiceService.applyPayment(invoice.getInvoiceId(), settlement);
            claim.setPaymentId(payment.getPaymentId());
        }
        claim = claimRepository.saveAndFlush(claim);
        auditService.logClinicalAccess("UPDATE", RESOURCE, claimId, claim.getPatientSystemId());

        if (payment != null) {
            eventPublisher.publishEvent(new PaymentRecordedEvent(payment.getPaymentId(), invoice.getInvoiceId(),
                    claim.getPatientSystemId(), admin, settlement, payment.getCurrency(), PaymentMethod.TYPE_INSURANCE,
                    PaymentRecordedEvent.COMPLETED));
        }
        publishStatusChange(claim, previous);
        return toResponse(claim);
    }

    // =====================================================================================================
    // INVOICE VOID (administrator)
    // =====================================================================================================

    /**
     * The administrator's void of an invoice that has received no payment (POST /api/admin/billing/invoices/{id}/void).
     * ADMIN only. Delegates to {@link InvoiceService#cancelBySystem}, which refuses an invoice with payments or a
     * REFUNDED one (409), publishes InvoiceStatusChangedEvent and audits the change. Already CANCELLED: no-op.
     * Claims of the invoice are not changed.
     */
    @Transactional
    public InvoiceResponse voidInvoice(Integer invoiceId, String reason) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        return invoiceService.cancelBySystem(invoiceId, reason == null ? null : reason.trim());
    }

    // =====================================================================================================
    // INTERNALS
    // =====================================================================================================

    /** PATIENT, CAREGIVER or ADMIN; a DOCTOR (or any other role) gets 403 before a row is loaded. */
    private void requireClaimRole() {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
    }

    private String submitToProvider(InsuranceClaim claim) {
        try {
            String reference = claimProvider.submit(claim);
            if (reference == null || reference.isBlank()) {
                throw new InsuranceClaimProviderException("The claims provider returned no reference", true);
            }
            return reference.length() > 255 ? reference.substring(0, 255) : reference;
        } catch (InsuranceClaimProviderException failure) {
            log.warn("Claims provider '{}' did not accept claim {} ({}, retryable={})", claimProvider.name(),
                    claim.getClaimNo(), failure.getClass().getSimpleName(), failure.isRetryable());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    PROVIDER_UNAVAILABLE);
        }
    }

    /** Locks the invoice row (first lock of every write); null when the claim has no invoice. */
    private Invoice lockInvoice(Integer invoiceId) {
        if (invoiceId == null) {
            return null;
        }
        return invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
    }

    private InsuranceClaim lockClaim(Integer claimId) {
        return claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new ResourceNotFoundException(CLAIM_NOT_FOUND));
    }

    private void publishStatusChange(InsuranceClaim claim, String previous) {
        eventPublisher.publishEvent(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), claim.getInvoiceId(),
                claim.getPatientSystemId(), previous, claim.getStatus()));
    }

    /**
     * One SYSTEM notification per active administrator except the caller (the publisher does not skip the actor
     * for category SYSTEM). Opens the claim in the admin billing section's claims tab.
     */
    private void notifyAdministrators(InsuranceClaim claim, String invoiceNo, String caller, String title,
                                      String message, String dedupeKey) {
        for (String adminSystemId : lookups.activeAdminsExcept(caller)) {
            notificationPublisher.publish(NotificationCommand.builder()
                    .recipientSystemId(adminSystemId)
                    .type(NotificationType.SYSTEM)
                    .category(NotificationCategory.SYSTEM)
                    .priority(NotificationPriority.NORMAL)
                    .title(title)
                    .message(message)
                    .target(NotificationTarget.ADMIN_BILLING)
                    .param("tab", ADMIN_TAB)
                    .resourceType(RESOURCE)
                    .resourceId(claim.getClaimId())
                    .actorSystemId(caller)
                    .dedupeKey(dedupeKey)
                    .build());
        }
    }

    /** Issued at least once: patients and caregivers never see a DRAFT, or an invoice cancelled before sending. */
    private static boolean issued(Invoice invoice) {
        String status = invoice.getStatus();
        if (Invoice.STATUS_DRAFT.equals(status)) {
            return false;
        }
        return !Invoice.STATUS_CANCELLED.equals(status) || invoice.getIssuedAt() != null;
    }

    /** One status or a comma-separated list, trimmed and upper-cased; empty = every status. */
    static List<String> parseStatuses(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<String> statuses = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            String normalized = value.toUpperCase(Locale.ROOT);
            if (!InsuranceClaim.STATUSES.contains(normalized)) {
                throw new BadRequestException("Unknown claim status '" + value + "'");
            }
            statuses.add(normalized);
        }
        return List.copyOf(statuses);
    }

    /** Ids in the order given, first occurrence kept, nulls dropped. */
    private static List<Integer> distinct(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
    }

    private static BigDecimal outstandingOf(Invoice invoice) {
        if (invoice == null || !Invoice.PAYABLE_STATUSES.contains(invoice.getStatus())) {
            return InvoiceTotals.ZERO;
        }
        BigDecimal balance = InvoiceTotals.money(invoice.getTotal()).subtract(InvoiceTotals.money(invoice.getAmountPaid()));
        return balance.signum() < 0 ? InvoiceTotals.ZERO : balance;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String displayInvoiceNo(String invoiceNo) {
        return invoiceNo == null ? "(unknown)" : invoiceNo;
    }

    // ---- JSON of document ids ----

    private String writeIds(List<Integer> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Document ids could not be serialized", e);
        }
    }

    /** Stored document ids; a legacy value that is not a JSON array of integers yields an empty list. */
    private List<Integer> readIds(InsuranceClaim claim) {
        String json = claim.getDocumentIds();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Integer> ids = objectMapper.readValue(json, ID_LIST);
            return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
        } catch (JsonProcessingException e) {
            log.warn("Insurance claim {} has document ids that are not a list of integers ({})", claim.getClaimId(),
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ---- mapping ----

    InsuranceClaimResponse toResponse(InsuranceClaim claim) {
        return toResponses(List.of(claim)).get(0);
    }

    /** Batch mapping: one query each for the invoices, the patient names and the documents of all rows. */
    List<InsuranceClaimResponse> toResponses(Collection<InsuranceClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<Integer> invoiceIds = new HashSet<>();
        Set<String> patients = new HashSet<>();
        Map<Integer, List<Integer>> idsByClaim = new LinkedHashMap<>();
        Set<Integer> allDocumentIds = new HashSet<>();
        for (InsuranceClaim claim : claims) {
            if (claim.getInvoiceId() != null) {
                invoiceIds.add(claim.getInvoiceId());
            }
            if (claim.getPatientSystemId() != null) {
                patients.add(claim.getPatientSystemId());
            }
            List<Integer> ids = readIds(claim);
            idsByClaim.put(claim.getClaimId(), ids);
            allDocumentIds.addAll(ids);
        }
        Map<Integer, Invoice> invoices = new HashMap<>();
        if (!invoiceIds.isEmpty()) {
            invoiceRepository.findByInvoiceIdIn(invoiceIds).forEach(i -> invoices.put(i.getInvoiceId(), i));
        }
        Map<String, String> names = lookups.namesOf(patients);
        Map<Integer, DocumentRow> documents = lookups.usableDocuments(allDocumentIds);

        List<InsuranceClaimResponse> responses = new ArrayList<>(claims.size());
        for (InsuranceClaim claim : claims) {
            Invoice invoice = claim.getInvoiceId() == null ? null : invoices.get(claim.getInvoiceId());
            List<Integer> ids = idsByClaim.getOrDefault(claim.getClaimId(), List.of());
            List<ClaimDocument> attached = ids.stream()
                    .map(documents::get)
                    .filter(Objects::nonNull)
                    .filter(doc -> Objects.equals(doc.patientSystemId(), claim.getPatientSystemId()))
                    .map(doc -> new ClaimDocument(doc.documentId(), doc.title()))
                    .toList();
            responses.add(new InsuranceClaimResponse(
                    claim.getClaimId(),
                    claim.getClaimNo(),
                    claim.getInvoiceId(),
                    invoice == null ? null : invoice.getInvoiceNo(),
                    claim.getPatientSystemId(),
                    names.get(claim.getPatientSystemId()),
                    claim.getInsuranceProvider(),
                    claim.getPolicyNumber(),
                    claim.getMemberNumber(),
                    InvoiceTotals.money(claim.getClaimAmount()),
                    claim.getApprovedAmount() == null ? null : InvoiceTotals.money(claim.getApprovedAmount()),
                    invoice == null || invoice.getCurrency() == null ? Invoice.DEFAULT_CURRENCY : invoice.getCurrency(),
                    outstandingOf(invoice),
                    claim.getStatus(),
                    claim.getRejectionReason(),
                    claim.getAppealReason(),
                    ids,
                    attached,
                    claim.getPaymentId(),
                    claim.getProviderReference(),
                    claim.getSubmittedBySystemId(),
                    claim.getReviewedBySystemId(),
                    claim.getSubmittedAt(),
                    claim.getResolvedAt(),
                    claim.getUpdatedAt()));
        }
        return responses;
    }
}
