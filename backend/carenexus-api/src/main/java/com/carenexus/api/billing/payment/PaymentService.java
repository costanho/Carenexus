package com.carenexus.api.billing.payment;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.payment.dto.BillingConfigResponse;
import com.carenexus.api.billing.payment.dto.InitiatePaymentRequest;
import com.carenexus.api.billing.payment.dto.PaymentResponse;
import com.carenexus.api.billing.payment.dto.SimulatePaymentRequest;
import com.carenexus.api.billing.provider.LocalPaymentGateway;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.provider.PaymentGatewayException;
import com.carenexus.api.billing.provider.gateway.LocalWebhookSigner;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentMethodRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway payments of invoices (api/be-billing-payments.md): initiation through the active {@link PaymentGateway},
 * payment reads, the billing configuration and the dev-only simulation. Refunds are in
 * {@link PaymentRefundService}, callbacks in {@link PaymentWebhookService}, timeouts in the reconciliation sweeper.
 * <p>
 * Authorization: reads need the caregiver BILLING permission, starting and simulating a payment needs
 * MANAGE_BILLING; the two are not merged. A doctor only reads payments of invoices they issued. Every successful
 * operation writes its PAYMENT audit row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String RESOURCE = "PAYMENT";

    static final String INVOICE_RESOURCE = InvoiceService.RESOURCE;

    static final String PAYMENT_NOT_FOUND = "Payment not found";
    static final String INVOICE_NOT_FOUND = "Invoice not found";
    static final String METHOD_NOT_FOUND = "Payment method not found";
    static final String NOT_FOUND = "Not found";
    static final String ROLE_NOT_AUTHORIZED = "Your role is not authorized to perform this action";
    static final String DOCTOR_ONLY_ISSUED = "You can only view payments of invoices you issued";
    static final String PATIENT_ID_REQUIRED = "patientSystemId is required";
    static final String PHONE_REQUIRED = "A phone number is required for an M-PESA payment";
    static final String PAYMENT_EXCEEDS = "Payment exceeds the outstanding balance";
    static final String PENDING_RESERVED = "Another payment for this invoice is still being processed";
    static final String ONLY_PENDING_SIMULATED = "Only a pending payment can be simulated";
    static final String PROVIDER_UNAVAILABLE = "The payment service is temporarily unavailable. Please try again.";
    static final String SIMULATED_FAILURE = "Simulated failure";

    /** Methods a patient or caregiver may pay with, in the fixed order of {@link PaymentMethod#TYPES}. */
    static final Set<String> ONLINE_METHODS = Set.of(PaymentMethod.TYPE_CARD, PaymentMethod.TYPE_MPESA);

    /** Sort of every payment list: newest first, ties by id. */
    static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "paymentId"));

    private static final String TRANSACTION_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final InvoiceService invoiceService;
    private final PaymentGateway paymentGateway;
    private final PaymentSettlement settlement;
    private final PaymentWebhookService webhookService;
    private final PaymentResponseMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final SystemSettingsService systemSettingsService;
    private final BillingProperties billingProperties;
    private final EnvironmentInfo environmentInfo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Outcome of {@link #initiate}: the response and whether this call created the payment. */
    public record InitiationResult(PaymentResponse payment, boolean created) {
    }

    // =====================================================================================================
    // INITIATION
    // =====================================================================================================

    /**
     * Starts a payment of an invoice (POST /api/billing/invoices/{invoiceId}/payments). The invoice row is locked
     * for the whole transaction, so two concurrent payments are serialized and the reservation rule (the amount may
     * not exceed what the invoice's PENDING payments leave free) holds.
     *
     * @param idempotencyKey the request's Idempotency-Key, handed to the gateway (may be null)
     */
    @Transactional
    public PaymentResponse initiate(Integer invoiceId, InitiatePaymentRequest request, String idempotencyKey) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        String caller = authorizationService.currentSystemId();
        boolean admin = authorizationService.isAdmin();

        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND));
        if (!admin) {
            authorizationService.requirePatientAccess(invoice.getPatientSystemId(), PatientPermission.MANAGE_BILLING,
                    invoice.getInvoiceId());
            if (!visibleToPatient(invoice)) {
                throw new ResourceNotFoundException(INVOICE_NOT_FOUND);
            }
        }

        String method = request.getMethod().trim().toUpperCase(Locale.ROOT);
        if (!paymentGateway.supportedMethods().contains(method)) {
            throw new BadRequestException("Payment method " + method + " is not available");
        }
        if (!admin && !ONLINE_METHODS.contains(method)) {
            throw new AuthorizationException("Only an administrator can record a " + method + " payment",
                    INVOICE_RESOURCE, invoice.getInvoiceId());
        }

        PaymentMethod saved = null;
        if (request.getPaymentMethodId() != null) {
            saved = paymentMethodRepository
                    .findUsableByMethodIdAndSystemId(request.getPaymentMethodId(), invoice.getPatientSystemId())
                    .orElseThrow(() -> new ResourceNotFoundException(METHOD_NOT_FOUND));
            if (!method.equals(saved.getType())) {
                throw new BadRequestException("The saved payment method is not a " + method + " method");
            }
        }

        String payerPhone = null;
        if (PaymentMethod.TYPE_MPESA.equals(method)) {
            payerPhone = blankToNull(request.getPayerPhone());
            if (payerPhone == null && saved != null) {
                payerPhone = blankToNull(saved.getMpesaPhone());
            }
            if (payerPhone == null) {
                throw new BadRequestException(PHONE_REQUIRED);
            }
        }

        if (!Invoice.PAYABLE_STATUSES.contains(invoice.getStatus())) {
            throw new ConflictException("An invoice in status " + invoice.getStatus() + " cannot be paid");
        }
        BigDecimal outstanding = PaymentResponseMapper.money(invoiceService.outstanding(invoice.getInvoiceId()));
        BigDecimal amount = request.getAmount() == null ? outstanding : PaymentResponseMapper.money(request.getAmount());
        if (amount.signum() <= 0 || amount.compareTo(outstanding) > 0) {
            throw new ConflictException(PAYMENT_EXCEEDS);
        }
        BigDecimal reserved = PaymentResponseMapper.money(
                paymentRepository.sumPendingAmountByInvoiceId(invoice.getInvoiceId()));
        if (amount.compareTo(outstanding.subtract(reserved)) > 0) {
            throw new ConflictException(PENDING_RESERVED);
        }

        String paymentRef = "pay_" + UUID.randomUUID().toString().replace("-", "");
        PaymentGateway.InitiateResult result;
        try {
            result = paymentGateway.initiate(new PaymentGateway.InitiateRequest(paymentRef,
                    idempotencyKey == null || idempotencyKey.isBlank() ? paymentRef : idempotencyKey.trim(),
                    amount, invoice.getCurrency(), method, payerPhone,
                    saved == null ? null : saved.getProviderToken(),
                    "Invoice " + invoice.getInvoiceNo()));
        } catch (PaymentGatewayException e) {
            log.warn("Payment gateway {} refused to start a payment for invoice {} (retryable: {})",
                    paymentGateway.name(), invoice.getInvoiceId(), e.isRetryable());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    PROVIDER_UNAVAILABLE);
        }
        if (result == null || result.status() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    PROVIDER_UNAVAILABLE);
        }

        LocalDateTime now = now();
        Payment payment = Payment.builder()
                .patientSystemId(invoice.getPatientSystemId())
                .appointmentId(invoice.getAppointmentId())
                .invoiceId(invoice.getInvoiceId())
                .paymentMethodId(saved == null ? null : saved.getMethodId())
                .amount(amount)
                .currency(invoice.getCurrency())
                .method(method)
                .gateway(paymentGateway.name())
                .providerReference(truncate(result.providerReference(), 255))
                .initiatedBySystemId(caller)
                .payerPhone(payerPhone)
                .createdAt(now)
                .updatedAt(now)
                .build();

        switch (result.status()) {
            case PaymentGateway.STATUS_COMPLETED -> {
                invoiceService.applyPayment(invoice.getInvoiceId(), amount);
                payment.setStatus(Payment.STATUS_COMPLETED);
                payment.setNextAction(Payment.NEXT_ACTION_NONE);
                payment.setPaidAt(now);
                paymentRepository.saveAndFlush(payment);
                settlement.publish(payment, PaymentRecordedEvent.COMPLETED, amount, caller);
            }
            case PaymentGateway.STATUS_FAILED -> {
                payment.setStatus(Payment.STATUS_FAILED);
                payment.setNextAction(Payment.NEXT_ACTION_NONE);
                payment.setFailureReason(truncate(result.failureReason() == null || result.failureReason().isBlank()
                        ? PaymentWebhookService.DEFAULT_FAILURE_REASON : result.failureReason().trim(), 500));
                paymentRepository.saveAndFlush(payment);
                settlement.publish(payment, PaymentRecordedEvent.FAILED, amount, caller);
            }
            case PaymentGateway.STATUS_PENDING -> {
                String nextAction = result.nextAction() != null && Payment.NEXT_ACTIONS.contains(result.nextAction())
                        ? result.nextAction() : Payment.NEXT_ACTION_AWAIT_CONFIRMATION;
                payment.setStatus(Payment.STATUS_PENDING);
                payment.setNextAction(nextAction);
                payment.setNextActionUrl(Payment.NEXT_ACTION_REDIRECT.equals(nextAction)
                        ? truncate(result.redirectUrl(), 500) : null);
                paymentRepository.saveAndFlush(payment);
            }
            default -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    PROVIDER_UNAVAILABLE);
        }
        auditService.logClinicalAccess("CREATE", RESOURCE, payment.getPaymentId(), payment.getPatientSystemId());
        return mapper.toResponse(payment);
    }

    // =====================================================================================================
    // READS
    // =====================================================================================================

    /**
     * One payment (GET /api/billing/payments/{id}): the patient, a caregiver with BILLING, the doctor who issued the
     * payment's invoice, an administrator. Audited as READ.
     */
    @Transactional(readOnly = true)
    public PaymentResponse get(Integer paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        String role = authorizationService.currentRole();
        String caller = authorizationService.currentSystemId();
        switch (role) {
            case AuthorizationService.ADMIN -> {
                // every payment
            }
            case AuthorizationService.DOCTOR -> {
                Optional<Invoice> invoice = payment.getInvoiceId() == null
                        ? Optional.empty() : invoiceRepository.findById(payment.getInvoiceId());
                if (invoice.isEmpty() || !caller.equals(invoice.get().getDoctorSystemId())) {
                    auditDenial(caller, payment, "Doctor reading a payment of an invoice issued by someone else");
                    throw new AuthorizationException(DOCTOR_ONLY_ISSUED, RESOURCE, payment.getPaymentId());
                }
            }
            case AuthorizationService.PATIENT, AuthorizationService.CAREGIVER ->
                    requirePaymentAccess(payment, PatientPermission.BILLING);
            default -> throw new AuthorizationException(ROLE_NOT_AUTHORIZED, RESOURCE, payment.getPaymentId());
        }
        auditService.logClinicalAccess("READ", RESOURCE, payment.getPaymentId(), payment.getPatientSystemId());
        return mapper.toResponse(payment);
    }

    /**
     * Payments of one patient, every invoice and status (GET /api/billing/payments). PATIENT: own id (default);
     * CAREGIVER: grant with BILLING; ADMIN: required id. DOCTOR: 403.
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listForPatient(String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        String target = blankToNull(patientSystemId);
        if (target == null) {
            if (!AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
                throw new BadRequestException(PATIENT_ID_REQUIRED);
            }
            target = authorizationService.currentSystemId();
        }
        authorizationService.requirePatientAccess(target, PatientPermission.BILLING);
        Page<Payment> rows = paymentRepository.findByPatientSystemId(target, PageParams.of(page, size, LIST_SORT));
        return page(rows);
    }

    /**
     * Payment history of one invoice (GET /api/billing/invoices/{id}/payments), every status. Authorized and
     * audited exactly like the invoice read ({@code invoiceService.getForCaller}), so the issuing doctor sees it.
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listForInvoice(Integer invoiceId, int page, int size) {
        invoiceService.getForCaller(invoiceId);
        Page<Payment> rows = paymentRepository.findByInvoiceId(invoiceId, PageParams.of(page, size, LIST_SORT));
        return page(rows);
    }

    /** What the Pay dialog needs to know (GET /api/billing/config). Any signed-in user; not audited. */
    @Transactional(readOnly = true)
    public BillingConfigResponse config() {
        authorizationService.currentSystemId();
        Set<String> supported = paymentGateway.supportedMethods();
        List<String> supportedMethods = new ArrayList<>();
        List<String> onlineMethods = new ArrayList<>();
        for (String type : PaymentMethod.TYPES) {
            if (supported.contains(type)) {
                supportedMethods.add(type);
                if (ONLINE_METHODS.contains(type)) {
                    onlineMethods.add(type);
                }
            }
        }
        return new BillingConfigResponse(paymentGateway.name(), !isLocalGateway(), List.copyOf(supportedMethods),
                List.copyOf(onlineMethods), systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY),
                simulationAvailable());
    }

    // =====================================================================================================
    // DEV-ONLY SIMULATION
    // =====================================================================================================

    /** True when POST /api/billing/payments/{id}/simulate is available: local gateway and a developer profile. */
    public boolean simulationAvailable() {
        return billingProperties.isLocalProvider() && isLocalGateway() && paymentGateway.isConfigured()
                && environmentInfo.isDevProfile();
    }

    /** 404 {@code Not found} unless {@link #simulationAvailable()}. */
    public void requireSimulationAvailable() {
        if (!simulationAvailable()) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
    }

    /**
     * Settles a PENDING local payment the way the gateway would: builds the local callback body, signs it with the
     * local secret and feeds it through {@link PaymentWebhookService#receive}, so the signature check, the event row
     * and the invoice update are all exercised. The patient, a caregiver with MANAGE_BILLING or an administrator.
     */
    @Transactional
    public PaymentResponse simulate(Integer paymentId, SimulatePaymentRequest request) {
        requireSimulationAvailable();
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        if (!authorizationService.isAdmin()) {
            requirePaymentAccess(payment, PatientPermission.MANAGE_BILLING);
        }
        if (!Payment.STATUS_PENDING.equals(payment.getStatus())
                || !paymentGateway.name().equals(payment.getGateway()) || payment.getProviderReference() == null) {
            throw new ConflictException(ONLY_PENDING_SIMULATED);
        }

        boolean completed = SimulatePaymentRequest.OUTCOME_COMPLETED
                .equals(request.getOutcome().trim().toUpperCase(Locale.ROOT));
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventId", "sim_" + UUID.randomUUID().toString().replace("-", ""));
        event.put("eventType", completed ? PaymentGateway.EVENT_PAYMENT_COMPLETED : PaymentGateway.EVENT_PAYMENT_FAILED);
        event.put("providerReference", payment.getProviderReference());
        if (completed) {
            event.put("transactionId", "LOC-" + randomCode(12));
            event.putNull("failureReason");
        } else {
            event.putNull("transactionId");
            event.put("failureReason", SIMULATED_FAILURE);
        }
        event.put("occurredAt", clock.instant().toString());
        String body;
        try {
            body = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Simulated callback could not be written", e);
        }
        String signature = LocalWebhookSigner.sign(billingProperties.getLocal().getWebhookSecret(), body);
        webhookService.receive(body, Map.of(LocalWebhookSigner.HEADER_LOWER, signature));

        Payment settled = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        auditService.logClinicalAccess("UPDATE", RESOURCE, settled.getPaymentId(), settled.getPatientSystemId());
        return mapper.toResponse(settled);
    }

    // =====================================================================================================
    // HELPERS
    // =====================================================================================================

    /**
     * Patient access for one payment. A denial is audited and reported with resource type PAYMENT and the payment id
     * (the permission's own resource type is INVOICE, which would pair the payment id with the wrong table).
     */
    private void requirePaymentAccess(Payment payment, PatientPermission permission) {
        authorizationService.requirePatientAccess(payment.getPatientSystemId(), permission, RESOURCE,
                payment.getPaymentId());
    }

    private PageResponse<PaymentResponse> page(Page<Payment> rows) {
        auditService.logListRead(RESOURCE, rows.getContent(), Payment::getPatientSystemId);
        return PageResponse.of(rows, mapper.toResponses(rows.getContent()));
    }

    private void auditDenial(String caller, Payment payment, String reason) {
        try {
            auditService.logAccessDenied(caller, RESOURCE, payment.getPaymentId(), payment.getPatientSystemId(),
                    null, reason);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
    }

    private boolean isLocalGateway() {
        return paymentGateway instanceof LocalPaymentGateway;
    }

    /** Never-issued invoices (DRAFT, or CANCELLED before it was sent) are invisible to patients and caregivers. */
    static boolean visibleToPatient(Invoice invoice) {
        String status = invoice.getStatus();
        if (Invoice.STATUS_DRAFT.equals(status)) {
            return false;
        }
        return !Invoice.STATUS_CANCELLED.equals(status) || invoice.getIssuedAt() != null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String randomCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(TRANSACTION_ALPHABET.charAt(RANDOM.nextInt(TRANSACTION_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
