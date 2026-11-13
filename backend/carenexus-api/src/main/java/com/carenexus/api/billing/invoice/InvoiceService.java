package com.carenexus.api.billing.invoice;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.fee.FeeService;
import com.carenexus.api.billing.invoice.dto.CreateInvoiceRequest;
import com.carenexus.api.billing.invoice.dto.InvoiceLineItem;
import com.carenexus.api.billing.invoice.dto.InvoiceLineItemRequest;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.invoice.dto.UninvoicedConsultationResponse;
import com.carenexus.api.billing.invoice.dto.UpdateInvoiceRequest;
import com.carenexus.api.billing.invoice.repository.InvoiceVisitQueries;
import com.carenexus.api.billing.invoice.repository.InvoiceVisitQueries.AppointmentVisit;
import com.carenexus.api.billing.invoice.repository.InvoiceVisitQueries.ConsultationVisit;
import com.carenexus.api.billing.invoice.repository.InvoiceVisitQueries.UninvoicedRow;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.InvoiceSpecifications;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.event.InvoiceIssuedEvent;
import com.carenexus.api.common.event.InvoiceStatusChangedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Invoices: generation from a consultation or an appointment, the status machine, the reads of every role, and
 * the facade that the wave 3 billing slices call (CONTRACTS.v2 6.1: {@link #getForCaller}, {@link #outstanding},
 * {@link #applyPayment}, {@link #reversePayment}, {@link #markOverdue}, {@link #cancelBySystem}).
 *
 * <h3>Status machine</h3>
 * <pre>
 * DRAFT          -> SENT (send), CANCELLED (cancel)
 * SENT           -> PARTIALLY_PAID | PAID (applyPayment), OVERDUE (markOverdue), CANCELLED (cancel, nothing paid)
 * PARTIALLY_PAID -> PAID (applyPayment), OVERDUE (markOverdue), REFUNDED (reversePayment to zero)
 * OVERDUE        -> PARTIALLY_PAID | PAID (applyPayment), CANCELLED (cancel, nothing paid), REFUNDED (reversePayment to zero)
 * PAID           -> PARTIALLY_PAID | REFUNDED (reversePayment)
 * CANCELLED, REFUNDED: terminal
 * </pre>
 * Every other transition is a 409; a same-status request is a no-op that returns the invoice and publishes nothing.
 * Every status change publishes {@link InvoiceStatusChangedEvent} in the changing transaction; DRAFT to SENT also
 * publishes {@link InvoiceIssuedEvent}.
 *
 * <h3>Visibility</h3>
 * Patients and caregivers (with BILLING) never see an invoice that was never issued (DRAFT, or CANCELLED with no
 * issue time): it is absent from their lists and answers 404 by id. A doctor sees only the invoices they issued (a
 * care relationship is not enough for billing). An administrator sees everything.
 *
 * <h3>Settings</h3>
 * Currency, payment terms and tax rate are read through {@link SystemSettingsService} on every use, never from
 * {@link BillingProperties}; only the auto-invoice mode comes from the properties.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    public static final String RESOURCE = "INVOICE";

    static final String NOT_FOUND = "Invoice not found";
    static final String ROLE_NOT_AUTHORIZED = "Your role is not authorized to perform this action";
    static final String CLINICIAN_INACTIVE = "Your clinician account is not active";
    static final String MISSING_VISIT = "Provide a consultationId or an appointmentId";
    static final String CONSULTATION_NOT_OF_APPOINTMENT = "The consultation does not belong to this appointment";
    static final String NOT_OWN_VISIT = "You can only invoice your own consultations and appointments";
    static final String CONSULTATION_NOT_FOUND = "Consultation not found";
    static final String APPOINTMENT_NOT_FOUND = "Appointment not found";
    static final String CONSULTATION_NOT_COMPLETED = "Only a completed consultation can be invoiced";
    static final String APPOINTMENT_CANCELLED = "A cancelled appointment cannot be invoiced";
    static final String VIEW_ONLY_ISSUED = "You can only view invoices you issued";
    static final String MANAGE_ONLY_ISSUED = "You can only manage invoices you issued";
    static final String ONLY_DRAFT_EDITABLE = "Only a draft invoice can be edited";
    static final String ZERO_TOTAL_SEND = "An invoice with a zero total cannot be sent";
    static final String CANCEL_WITH_PAYMENTS = "An invoice with payments cannot be cancelled";
    static final String PAYMENT_EXCEEDS = "Payment exceeds the outstanding balance";
    static final String REFUND_EXCEEDS = "Refund exceeds the amount paid";
    static final String AMOUNT_NOT_POSITIVE = "Amount must be greater than zero";
    static final String PATIENT_ID_REQUIRED = "patientSystemId is required";

    static final int CANCEL_REASON_MAX = 500;

    /** Sort of every invoice list: newest first, ties by id. */
    static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "invoiceId"));

    private static final TypeReference<List<InvoiceLineItem>> LINE_ITEMS = new TypeReference<>() {
    };

    private final InvoiceRepository invoiceRepository;
    private final InvoiceVisitQueries visitQueries;
    private final FeeService feeService;
    private final SystemSettingsService systemSettingsService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Result of {@link #generate}: the invoice and whether this call created it (201) or found it (200). */
    public record GenerationResult(InvoiceResponse invoice, boolean created) {
    }

    // =====================================================================================================
    // GENERATION
    // =====================================================================================================

    /**
     * Generates the invoice of a consultation or an appointment, always as DRAFT. Doctor of the visit (active
     * clinician) or administrator. Idempotent per visit: an existing invoice whose status is not CANCELLED, linked
     * to the consultation or to the appointment, is returned unchanged (created = false) and the request body is
     * not applied. Concurrent requests for one visit are serialized, so they produce one invoice.
     */
    @Transactional
    public GenerationResult generate(CreateInvoiceRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        String caller = authorizationService.currentSystemId();
        boolean callerIsDoctor = AuthorizationService.DOCTOR.equals(authorizationService.currentRole());
        if (callerIsDoctor && !visitQueries.isDoctorActive(caller)) {
            throw new AuthorizationException(CLINICIAN_INACTIVE);
        }
        if (request.getConsultationId() == null && request.getAppointmentId() == null) {
            throw new BadRequestException(MISSING_VISIT);
        }

        // Load the visit (404), check it belongs to the caller (403).
        ConsultationVisit consultation = null;
        AppointmentVisit appointment;
        if (request.getConsultationId() != null) {
            consultation = visitQueries.findConsultation(request.getConsultationId())
                    .orElseThrow(() -> new ResourceNotFoundException(CONSULTATION_NOT_FOUND));
            if (request.getAppointmentId() != null
                    && !request.getAppointmentId().equals(consultation.appointmentId())) {
                throw new BadRequestException(CONSULTATION_NOT_OF_APPOINTMENT);
            }
            appointment = consultation.appointmentId() == null ? null
                    : visitQueries.findAppointment(consultation.appointmentId()).orElse(null);
        } else {
            appointment = visitQueries.findAppointment(request.getAppointmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND));
        }
        String visitDoctor = consultation != null ? consultation.doctorSystemId() : appointment.doctorSystemId();
        if (callerIsDoctor && !caller.equals(visitDoctor)) {
            String patient = consultation != null ? consultation.patientSystemId() : appointment.patientSystemId();
            auditDenial(caller, null, patient, "Doctor invoicing a visit of another doctor");
            throw new AuthorizationException(NOT_OWN_VISIT, RESOURCE, null);
        }

        // One invoice per visit: serialize, then return the live invoice when there is one (200).
        Integer appointmentId = consultation != null ? consultation.appointmentId() : appointment.appointmentId();
        if (appointmentId != null) {
            visitQueries.lockVisit(appointmentId);
        }
        if (consultation == null) {
            consultation = visitQueries.findNewestCompletedConsultation(appointment.appointmentId()).orElse(null);
        }
        Optional<Invoice> existing = findLiveInvoiceOfVisit(consultation == null ? null : consultation.consultationId(),
                appointmentId);
        if (existing.isPresent()) {
            Invoice invoice = existing.get();
            auditService.logClinicalAccess("READ", RESOURCE, invoice.getInvoiceId(), invoice.getPatientSystemId());
            return new GenerationResult(toResponse(invoice), false);
        }

        // Visit state (409).
        if (request.getConsultationId() != null) {
            if (!"COMPLETED".equals(consultation.status())) {
                throw new ConflictException(CONSULTATION_NOT_COMPLETED);
            }
        } else if ("CANCELLED".equals(appointment.status())) {
            throw new ConflictException(APPOINTMENT_CANCELLED);
        }

        String patient = consultation != null ? consultation.patientSystemId() : appointment.patientSystemId();
        String appointmentType = appointment == null ? null : appointment.type();
        Invoice invoice = createDraft(patient, visitDoctor, caller, appointmentId,
                consultation == null ? null : consultation.consultationId(), appointmentType,
                request.getLineItems(), request.getDiscount(), request.getNotes());
        return new GenerationResult(toResponse(invoice), true);
    }

    /**
     * Automatic invoice of a completed consultation (the listener's work, inside the completing transaction).
     * Mode DRAFT creates a draft from the fee schedule; mode SENT creates and sends it (a zero total stays DRAFT);
     * mode OFF does nothing. Does nothing when a non-cancelled invoice already exists for the consultation or its
     * appointment, or when the visit cannot be found. The creator is the consultation's doctor.
     *
     * @return the invoice id when one was created
     */
    @Transactional(noRollbackFor = {ConflictException.class, BadRequestException.class,
            ResourceNotFoundException.class})
    public Optional<Integer> generateForCompletedConsultation(ConsultationCompletedEvent event, String mode) {
        if (event == null || event.consultationId() == null || BillingProperties.AUTO_INVOICE_OFF.equals(mode)) {
            return Optional.empty();
        }
        // The consultation row must exist (invoices.consultation_id is a foreign key): an event about an unknown
        // consultation is skipped instead of failing the publisher's transaction with a constraint violation.
        Optional<ConsultationVisit> consultation = visitQueries.findConsultation(event.consultationId());
        if (consultation.isEmpty()) {
            log.warn("Auto-invoice skipped: consultation {} not found", event.consultationId());
            return Optional.empty();
        }
        Integer appointmentId = event.appointmentId() != null ? event.appointmentId()
                : consultation.get().appointmentId();
        String patient = event.patientSystemId() != null ? event.patientSystemId()
                : consultation.get().patientSystemId();
        String doctor = event.doctorSystemId() != null ? event.doctorSystemId() : consultation.get().doctorSystemId();
        String appointmentType = null;
        if (appointmentId != null) {
            visitQueries.lockVisit(appointmentId);
            Optional<AppointmentVisit> appointment = visitQueries.findAppointment(appointmentId);
            if (appointment.isEmpty()) {
                log.warn("Auto-invoice skipped: appointment {} of consultation {} not found", appointmentId,
                        event.consultationId());
                return Optional.empty();
            }
            appointmentType = appointment.get().type();
        }
        if (findLiveInvoiceOfVisit(event.consultationId(), appointmentId).isPresent()) {
            return Optional.empty();
        }

        Invoice invoice = createDraft(patient, doctor, doctor, appointmentId, event.consultationId(), appointmentType,
                null, null, null);
        if (BillingProperties.AUTO_INVOICE_SENT.equals(mode) && invoice.getTotal().signum() > 0) {
            issue(invoice);
        }
        return Optional.of(invoice.getInvoiceId());
    }

    // =====================================================================================================
    // READS
    // =====================================================================================================

    /**
     * One invoice for the caller (endpoint GET /api/billing/invoices/{id}): patient access with BILLING for
     * patients and caregivers (never-issued invoices answer 404), the issuing doctor, or an administrator.
     * Audited as READ.
     */
    @Transactional(readOnly = true)
    public InvoiceResponse getForCaller(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        String role = authorizationService.currentRole();
        String caller = authorizationService.currentSystemId();
        switch (role) {
            case AuthorizationService.ADMIN -> {
                // every invoice
            }
            case AuthorizationService.DOCTOR -> {
                if (!caller.equals(invoice.getDoctorSystemId())) {
                    deny(caller, invoice, "Doctor reading an invoice issued by someone else", VIEW_ONLY_ISSUED);
                }
            }
            case AuthorizationService.PATIENT, AuthorizationService.CAREGIVER -> {
                authorizationService.requirePatientAccess(invoice.getPatientSystemId(), PatientPermission.BILLING,
                        invoice.getInvoiceId());
                if (!visibleToPatient(invoice)) {
                    throw new ResourceNotFoundException(NOT_FOUND);
                }
            }
            default -> throw new AuthorizationException(ROLE_NOT_AUTHORIZED, RESOURCE, invoice.getInvoiceId());
        }
        auditService.logClinicalAccess("READ", RESOURCE, invoice.getInvoiceId(), invoice.getPatientSystemId());
        return toResponse(invoice);
    }

    /**
     * Invoices of one patient (GET /api/billing/invoices). PATIENT: own id (default); CAREGIVER: grant with BILLING;
     * DOCTOR: care relationship, and only invoices the caller issued; ADMIN: all of that patient. Patients and
     * caregivers never see never-issued invoices.
     */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> listForPatient(String patientSystemId, String status, Integer consultationId,
                                                        Integer appointmentId, int page, int size) {
        String role = authorizationService.currentRole();
        String caller = authorizationService.currentSystemId();
        List<String> statuses = parseStatuses(status);
        String patient = blankToNull(patientSystemId);
        if (patient == null) {
            if (!AuthorizationService.PATIENT.equals(role)) {
                throw new BadRequestException(PATIENT_ID_REQUIRED);
            }
            patient = caller;
        }
        authorizationService.requirePatientAccess(patient, PatientPermission.BILLING);

        Specification<Invoice> spec = InvoiceSpecifications.patient(patient)
                .and(InvoiceSpecifications.statusIn(statuses))
                .and(InvoiceSpecifications.consultation(consultationId))
                .and(InvoiceSpecifications.appointment(appointmentId));
        if (AuthorizationService.DOCTOR.equals(role)) {
            spec = spec.and(InvoiceSpecifications.doctor(caller));
        } else if (AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role)) {
            spec = spec.and(visibleToPatientSpec());
        }
        return page(spec, page, size);
    }

    /** The calling patient's own invoices (GET /api/billing/invoices/me). PATIENT only. */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> listMine(String status, Integer consultationId, Integer appointmentId,
                                                  int page, int size) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return listForPatient(authorizationService.currentSystemId(), status, consultationId, appointmentId, page, size);
    }

    /**
     * Invoices issued by the calling doctor, every status including DRAFT (GET /api/doctors/billing/invoices).
     * DOCTOR only; {@code patientSystemId} is a plain filter.
     */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> listIssuedByCaller(String status, String patientSystemId,
                                                            Integer consultationId, Integer appointmentId,
                                                            int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        List<String> statuses = parseStatuses(status);
        Specification<Invoice> spec = InvoiceSpecifications.doctor(caller)
                .and(InvoiceSpecifications.patient(blankToNull(patientSystemId)))
                .and(InvoiceSpecifications.statusIn(statuses))
                .and(InvoiceSpecifications.consultation(consultationId))
                .and(InvoiceSpecifications.appointment(appointmentId));
        return page(spec, page, size);
    }

    /** Every invoice, every status (GET /api/admin/billing/invoices). ADMIN only. */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> listAll(String status, String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        List<String> statuses = parseStatuses(status);
        Specification<Invoice> spec = InvoiceSpecifications.patient(blankToNull(patientSystemId))
                .and(InvoiceSpecifications.statusIn(statuses));
        return page(spec, page, size);
    }

    /**
     * The calling doctor's COMPLETED consultations without an invoice in a status other than CANCELLED, with the
     * fee the invoice would be generated with. DOCTOR only. Not audited (it shows only names the doctor already sees
     * in their consultation list).
     */
    @Transactional(readOnly = true)
    public PageResponse<UninvoicedConsultationResponse> listUninvoiced(int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        Pageable pageable = PageParams.of(page, size, Sort.unsorted());
        long total = visitQueries.countUninvoiced(caller);
        List<UninvoicedRow> rows = total == 0 ? List.of()
                : visitQueries.findUninvoiced(caller, pageable.getPageSize(), pageable.getOffset());
        Map<String, BigDecimal> fees = feeService.feesFor(caller);
        String currency = feeService.currentCurrency();
        List<UninvoicedConsultationResponse> content = rows.stream()
                .map(row -> new UninvoicedConsultationResponse(row.consultationId(), row.appointmentId(),
                        row.appointmentType(), row.patientSystemId(), row.patientName(), row.completedAt(),
                        row.scheduledAt(),
                        InvoiceTotals.money(fees.getOrDefault(row.appointmentType(), InvoiceTotals.ZERO)), currency))
                .toList();
        return PageResponse.of(new PageImpl<>(content, pageable, total), content);
    }

    /**
     * What can still be paid now: total - amountPaid for SENT, PARTIALLY_PAID and OVERDUE, 0.00 otherwise.
     * No caller authorization (facade for the payment slices); 404 for an unknown id.
     */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        return outstandingOf(invoice);
    }

    // =====================================================================================================
    // USER ACTIONS: EDIT, SEND, CANCEL
    // =====================================================================================================

    /** Replaces lines, discount and notes of a DRAFT invoice. Issuing doctor or administrator. */
    @Transactional
    public InvoiceResponse update(Integer invoiceId, UpdateInvoiceRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Invoice invoice = lock(invoiceId);
        requireManager(invoice);
        if (!Invoice.STATUS_DRAFT.equals(invoice.getStatus())) {
            throw new ConflictException(ONLY_DRAFT_EDITABLE);
        }
        InvoiceTotals totals = InvoiceTotals.compute(request.getLineItems(), request.getDiscount(), currentTaxRate());
        applyTotals(invoice, totals);
        invoice.setNotes(blankToNull(request.getNotes()));
        invoiceRepository.saveAndFlush(invoice);
        auditService.logClinicalAccess("UPDATE", RESOURCE, invoice.getInvoiceId(), invoice.getPatientSystemId());
        return toResponse(invoice);
    }

    /**
     * Issues a DRAFT invoice: SENT, issuedAt = now, dueDate = today + payment terms. Publishes
     * InvoiceStatusChangedEvent and InvoiceIssuedEvent. Already SENT: no-op.
     */
    @Transactional
    public InvoiceResponse send(Integer invoiceId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Invoice invoice = lock(invoiceId);
        requireManager(invoice);
        if (Invoice.STATUS_SENT.equals(invoice.getStatus())) {
            return toResponse(invoice);
        }
        if (!Invoice.STATUS_DRAFT.equals(invoice.getStatus())) {
            throw new ConflictException("An invoice in status " + invoice.getStatus() + " cannot be sent");
        }
        if (InvoiceTotals.money(invoice.getTotal()).signum() == 0) {
            throw new ConflictException(ZERO_TOTAL_SEND);
        }
        issue(invoice);
        return toResponse(invoice);
    }

    /**
     * Cancels an invoice that has received no payment (DRAFT, SENT or OVERDUE with amountPaid 0.00). Issuing
     * doctor or administrator. Already CANCELLED: no-op.
     */
    @Transactional
    public InvoiceResponse cancel(Integer invoiceId, String reason) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Invoice invoice = lock(invoiceId);
        requireManager(invoice);
        cancelInternal(invoice, reason);
        return toResponse(invoice);
    }

    // =====================================================================================================
    // FACADE FOR THE PAYMENT, CLAIM AND SWEEPER SLICES (no caller authorization)
    // =====================================================================================================

    /**
     * Applies a payment under a row lock. Allowed in SENT, PARTIALLY_PAID and OVERDUE; the amount must be above
     * zero and not above the outstanding balance. The invoice becomes PAID (paidAt = now) when nothing is left,
     * otherwise PARTIALLY_PAID.
     */
    @Transactional
    public InvoiceResponse applyPayment(Integer invoiceId, BigDecimal amount) {
        BigDecimal value = requirePositive(amount);
        Invoice invoice = lock(invoiceId);
        if (!Invoice.PAYABLE_STATUSES.contains(invoice.getStatus())) {
            throw new ConflictException("An invoice in status " + invoice.getStatus() + " cannot be paid");
        }
        if (value.compareTo(outstandingOf(invoice)) > 0) {
            throw new ConflictException(PAYMENT_EXCEEDS);
        }
        String previous = invoice.getStatus();
        invoice.setAmountPaid(InvoiceTotals.money(invoice.getAmountPaid()).add(value));
        if (invoice.getAmountPaid().compareTo(InvoiceTotals.money(invoice.getTotal())) >= 0) {
            invoice.setStatus(Invoice.STATUS_PAID);
            invoice.setPaidAt(now());
        } else {
            invoice.setStatus(Invoice.STATUS_PARTIALLY_PAID);
        }
        return saveChange(invoice, previous);
    }

    /**
     * Reverses (part of) a payment under a row lock. Allowed in PAID, PARTIALLY_PAID and OVERDUE; the amount must be
     * above zero and not above amountPaid. REFUNDED when nothing is left paid; otherwise PAID becomes PARTIALLY_PAID
     * and the other statuses stay. paidAt is cleared when the status leaves PAID.
     */
    @Transactional
    public InvoiceResponse reversePayment(Integer invoiceId, BigDecimal amount) {
        BigDecimal value = requirePositive(amount);
        Invoice invoice = lock(invoiceId);
        String previous = invoice.getStatus();
        if (!Invoice.STATUS_PAID.equals(previous) && !Invoice.STATUS_PARTIALLY_PAID.equals(previous)
                && !Invoice.STATUS_OVERDUE.equals(previous)) {
            throw new ConflictException("An invoice in status " + previous + " cannot be refunded");
        }
        BigDecimal paid = InvoiceTotals.money(invoice.getAmountPaid());
        if (value.compareTo(paid) > 0) {
            throw new ConflictException(REFUND_EXCEEDS);
        }
        invoice.setAmountPaid(paid.subtract(value));
        if (invoice.getAmountPaid().signum() == 0) {
            invoice.setStatus(Invoice.STATUS_REFUNDED);
        } else if (Invoice.STATUS_PAID.equals(previous)) {
            invoice.setStatus(Invoice.STATUS_PARTIALLY_PAID);
        }
        if (Invoice.STATUS_PAID.equals(previous) && !Invoice.STATUS_PAID.equals(invoice.getStatus())) {
            invoice.setPaidAt(null);
        }
        return saveChange(invoice, previous);
    }

    /**
     * SENT or PARTIALLY_PAID with a due date before today (clinic zone) becomes OVERDUE; anything else is a no-op
     * returning the current invoice. Needs no signed-in caller (the overdue sweeper calls it from a job).
     */
    @Transactional
    public InvoiceResponse markOverdue(Integer invoiceId) {
        Invoice invoice = lock(invoiceId);
        String previous = invoice.getStatus();
        boolean open = Invoice.STATUS_SENT.equals(previous) || Invoice.STATUS_PARTIALLY_PAID.equals(previous);
        if (!open || invoice.getDueDate() == null || !invoice.getDueDate().isBefore(today())) {
            return toResponse(invoice);
        }
        invoice.setStatus(Invoice.STATUS_OVERDUE);
        return saveChange(invoice, previous);
    }

    /**
     * Administrator void: DRAFT, SENT or OVERDUE with amountPaid 0.00 become CANCELLED with the reason. Already
     * CANCELLED is a no-op. No caller authorization (the calling endpoint checks the administrator).
     */
    @Transactional
    public InvoiceResponse cancelBySystem(Integer invoiceId, String reason) {
        Invoice invoice = lock(invoiceId);
        cancelInternal(invoice, reason);
        return toResponse(invoice);
    }

    // =====================================================================================================
    // INTERNALS
    // =====================================================================================================

    private Invoice createDraft(String patientSystemId, String doctorSystemId, String createdBySystemId,
                                Integer appointmentId, Integer consultationId, String appointmentType,
                                List<InvoiceLineItemRequest> requestedLines, BigDecimal discount, String notes) {
        List<InvoiceLineItemRequest> lines = requestedLines == null || requestedLines.isEmpty()
                ? List.of(feeLine(doctorSystemId, appointmentType))
                : requestedLines;
        InvoiceTotals totals = InvoiceTotals.compute(lines, discount, currentTaxRate());
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        Invoice invoice = Invoice.builder()
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .createdBySystemId(createdBySystemId)
                .appointmentId(appointmentId)
                .consultationId(consultationId)
                .invoiceNo(String.format(Locale.ROOT, "INV-%04d-%06d", today.getYear(),
                        invoiceRepository.nextInvoiceNumber()))
                .currency(systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY))
                .status(Invoice.STATUS_DRAFT)
                .amountPaid(InvoiceTotals.ZERO)
                .dueDate(today.plusDays(systemSettingsService.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS)))
                .notes(blankToNull(notes))
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyTotals(invoice, totals);
        invoice = invoiceRepository.save(invoice);
        auditService.logClinicalAccess("CREATE", RESOURCE, invoice.getInvoiceId(), patientSystemId);
        return invoice;
    }

    /** The line generated from the fee schedule when a create request has no lines. */
    private InvoiceLineItemRequest feeLine(String doctorSystemId, String appointmentType) {
        String description = switch (appointmentType == null ? "" : appointmentType) {
            case "VIDEO" -> "Video consultation";
            case "IN_PERSON" -> "In-person consultation";
            case "PHONE" -> "Phone consultation";
            default -> "Consultation";
        };
        BigDecimal fee = appointmentType == null ? InvoiceTotals.ZERO : feeService.feeFor(doctorSystemId, appointmentType);
        return new InvoiceLineItemRequest(description, 1, fee);
    }

    /** DRAFT to SENT: issue time, due date from the current terms, both events. */
    private void issue(Invoice invoice) {
        String previous = invoice.getStatus();
        LocalDateTime now = now();
        invoice.setStatus(Invoice.STATUS_SENT);
        invoice.setIssuedAt(now);
        invoice.setDueDate(now.toLocalDate().plusDays(systemSettingsService.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS)));
        saveChange(invoice, previous);
        eventPublisher.publishEvent(new InvoiceIssuedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(),
                invoice.getPatientSystemId(), invoice.getDoctorSystemId(), InvoiceTotals.money(invoice.getTotal()),
                invoice.getCurrency(), invoice.getDueDate()));
    }

    private void cancelInternal(Invoice invoice, String reason) {
        String previous = invoice.getStatus();
        if (Invoice.STATUS_CANCELLED.equals(previous)) {
            return;
        }
        if (InvoiceTotals.money(invoice.getAmountPaid()).signum() > 0) {
            throw new ConflictException(CANCEL_WITH_PAYMENTS);
        }
        if (!Invoice.STATUS_DRAFT.equals(previous) && !Invoice.STATUS_SENT.equals(previous)
                && !Invoice.STATUS_OVERDUE.equals(previous)) {
            throw new ConflictException("An invoice in status " + previous + " cannot be cancelled");
        }
        String cleanReason = blankToNull(reason);
        if (cleanReason != null && cleanReason.length() > CANCEL_REASON_MAX) {
            cleanReason = cleanReason.substring(0, CANCEL_REASON_MAX);
        }
        invoice.setStatus(Invoice.STATUS_CANCELLED);
        invoice.setCancelReason(cleanReason);
        saveChange(invoice, previous);
    }

    /** Saves, audits UPDATE and publishes InvoiceStatusChangedEvent when the status changed. */
    private InvoiceResponse saveChange(Invoice invoice, String previousStatus) {
        invoiceRepository.saveAndFlush(invoice);
        auditService.logClinicalAccess("UPDATE", RESOURCE, invoice.getInvoiceId(), invoice.getPatientSystemId());
        if (!Objects.equals(previousStatus, invoice.getStatus())) {
            eventPublisher.publishEvent(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(),
                    invoice.getPatientSystemId(), previousStatus, invoice.getStatus()));
        }
        return toResponse(invoice);
    }

    private Optional<Invoice> findLiveInvoiceOfVisit(Integer consultationId, Integer appointmentId) {
        if (consultationId != null) {
            Optional<Invoice> byConsultation = invoiceRepository.findActiveByConsultationId(consultationId);
            if (byConsultation.isPresent()) {
                return byConsultation;
            }
        }
        return appointmentId == null ? Optional.empty() : invoiceRepository.findActiveByAppointmentId(appointmentId);
    }

    private void applyTotals(Invoice invoice, InvoiceTotals totals) {
        invoice.setLineItems(writeLines(totals.lines()));
        invoice.setSubtotal(totals.subtotal());
        invoice.setDiscount(totals.discount());
        invoice.setTax(totals.tax());
        invoice.setTotal(totals.total());
    }

    private Invoice lock(Integer invoiceId) {
        return invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /** The issuing doctor or an administrator (role already checked). */
    private void requireManager(Invoice invoice) {
        if (authorizationService.isAdmin()) {
            return;
        }
        String caller = authorizationService.currentSystemId();
        if (!caller.equals(invoice.getDoctorSystemId())) {
            deny(caller, invoice, "Doctor managing an invoice issued by someone else", MANAGE_ONLY_ISSUED);
        }
    }

    private void deny(String caller, Invoice invoice, String auditReason, String message) {
        auditDenial(caller, invoice.getInvoiceId(), invoice.getPatientSystemId(), auditReason);
        throw new AuthorizationException(message, RESOURCE, invoice.getInvoiceId());
    }

    /** ACCESS_DENIED audit row in its own transaction; a failing audit write never masks the denial. */
    private void auditDenial(String caller, Integer invoiceId, String patientSystemId, String auditReason) {
        try {
            auditService.logAccessDenied(caller, RESOURCE, invoiceId, patientSystemId, null, auditReason);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
    }

    private PageResponse<InvoiceResponse> page(Specification<Invoice> spec, int page, int size) {
        Page<Invoice> rows = invoiceRepository.findAll(spec, PageParams.of(page, size, LIST_SORT));
        auditService.logListRead(RESOURCE, rows.getContent(), Invoice::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /** Never-issued invoices (DRAFT, or CANCELLED before it was ever sent) are invisible to patients and caregivers. */
    static boolean visibleToPatient(Invoice invoice) {
        String status = invoice.getStatus();
        if (Invoice.STATUS_DRAFT.equals(status)) {
            return false;
        }
        return !Invoice.STATUS_CANCELLED.equals(status) || invoice.getIssuedAt() != null;
    }

    private static Specification<Invoice> visibleToPatientSpec() {
        return (root, query, cb) -> cb.and(
                cb.notEqual(root.get("status"), Invoice.STATUS_DRAFT),
                cb.or(cb.notEqual(root.get("status"), Invoice.STATUS_CANCELLED), cb.isNotNull(root.get("issuedAt"))));
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
            if (!Invoice.STATUSES.contains(normalized)) {
                throw new BadRequestException("Unknown invoice status '" + value + "'");
            }
            statuses.add(normalized);
        }
        return List.copyOf(statuses);
    }

    private static BigDecimal outstandingOf(Invoice invoice) {
        if (!Invoice.PAYABLE_STATUSES.contains(invoice.getStatus())) {
            return InvoiceTotals.ZERO;
        }
        BigDecimal balance = InvoiceTotals.money(invoice.getTotal()).subtract(InvoiceTotals.money(invoice.getAmountPaid()));
        return balance.signum() < 0 ? InvoiceTotals.ZERO : balance;
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        BigDecimal value = amount == null ? null : InvoiceTotals.money(amount);
        if (value == null || value.signum() <= 0) {
            throw new BadRequestException(AMOUNT_NOT_POSITIVE);
        }
        return value;
    }

    private BigDecimal currentTaxRate() {
        return systemSettingsService.getDecimal(SettingKey.BILLING_TAX_RATE);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- mapping ----

    InvoiceResponse toResponse(Invoice invoice) {
        return toResponses(List.of(invoice)).get(0);
    }

    List<InvoiceResponse> toResponses(Collection<Invoice> invoices) {
        Set<String> people = new HashSet<>();
        for (Invoice invoice : invoices) {
            people.add(invoice.getPatientSystemId());
            if (invoice.getDoctorSystemId() != null) {
                people.add(invoice.getDoctorSystemId());
            }
        }
        people.remove(null);
        Map<String, String> names = visitQueries.namesOf(people);
        List<InvoiceResponse> responses = new ArrayList<>(invoices.size());
        for (Invoice invoice : invoices) {
            responses.add(new InvoiceResponse(
                    invoice.getInvoiceId(),
                    invoice.getInvoiceNo(),
                    invoice.getPatientSystemId(),
                    names.get(invoice.getPatientSystemId()),
                    invoice.getDoctorSystemId(),
                    invoice.getDoctorSystemId() == null ? null : names.get(invoice.getDoctorSystemId()),
                    invoice.getCreatedBySystemId(),
                    invoice.getAppointmentId(),
                    invoice.getConsultationId(),
                    readLines(invoice),
                    InvoiceTotals.money(invoice.getSubtotal()),
                    InvoiceTotals.money(invoice.getTax()),
                    InvoiceTotals.money(invoice.getDiscount()),
                    InvoiceTotals.money(invoice.getTotal()),
                    InvoiceTotals.money(invoice.getAmountPaid()),
                    outstandingOf(invoice),
                    invoice.getCurrency(),
                    invoice.getStatus(),
                    invoice.getDueDate(),
                    invoice.getIssuedAt(),
                    invoice.getPaidAt(),
                    invoice.getCancelReason(),
                    invoice.getNotes(),
                    invoice.getCreatedAt(),
                    invoice.getUpdatedAt()));
        }
        return responses;
    }

    private String writeLines(List<InvoiceLineItem> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invoice lines could not be serialized", e);
        }
    }

    /** Stored lines; a legacy row whose JSON is not an array of lines yields an empty list. */
    private List<InvoiceLineItem> readLines(Invoice invoice) {
        String json = invoice.getLineItems();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<InvoiceLineItem> stored = objectMapper.readValue(json, LINE_ITEMS);
            if (stored == null) {
                return List.of();
            }
            return stored.stream()
                    .filter(Objects::nonNull)
                    .map(line -> new InvoiceLineItem(line.description(), line.quantity(),
                            line.unitPrice() == null ? null : InvoiceTotals.money(line.unitPrice()),
                            line.amount() == null ? null : InvoiceTotals.money(line.amount())))
                    .toList();
        } catch (JsonProcessingException e) {
            log.warn("Invoice {} has line items that are not a list of lines ({})", invoice.getInvoiceId(),
                    e.getClass().getSimpleName());
            return List.of();
        }
    }
}
