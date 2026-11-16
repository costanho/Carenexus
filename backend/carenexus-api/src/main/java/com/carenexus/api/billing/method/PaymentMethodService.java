package com.carenexus.api.billing.method;

import com.carenexus.api.billing.method.dto.CreatePaymentMethodRequest;
import com.carenexus.api.billing.method.dto.PaymentMethodResponse;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.repository.PaymentMethodRepository;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved payment methods (CARD, MPESA, INSURANCE) of patients.
 *
 * <h3>Who</h3>
 * Only a PATIENT owns saved methods: listing, adding, choosing the default and removing are for the owner only
 * (the owner always comes from the token). A caregiver with MANAGE_BILLING and an administrator can read a patient's
 * methods (read-only) to choose one when paying; doctors never see payment methods.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>Among a patient's active methods exactly one is the default whenever there is one: the first method is
 *       the default whatever the request says, and removing the default promotes the newest remaining method.</li>
 *   <li>Every write of one owner runs under a per-owner advisory lock, so default swaps never collide on the
 *       one-default unique index.</li>
 *   <li>Responses are masked for everyone: {@code lastFour} and {@code displayLabel} only. The phone, policy and
 *       member numbers and the provider token are stored, never returned and never logged.</li>
 *   <li>At most {@link #MAX_METHODS} active methods per patient; saving the same instrument twice is a 409.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentMethodService {

    public static final String RESOURCE = "PAYMENT_METHOD";
    public static final int MAX_METHODS = 10;

    /** Savable types; CASH and BANK_TRANSFER carry no instrument detail. */
    public static final List<String> SAVABLE_TYPES = List.of(PaymentMethod.TYPE_CARD, PaymentMethod.TYPE_MPESA,
            PaymentMethod.TYPE_INSURANCE);

    static final String MPESA_PROVIDER = "M-PESA";
    static final String CARD_LABEL = "Card";
    static final String LABEL_DOTS = " •••• ";
    static final String TOKEN_PREFIX = "local_pm_";

    static final String NOT_FOUND = "Payment method not found";
    static final String DUPLICATE = "This payment method is already saved";
    static final String TOO_MANY = "You can save at most " + MAX_METHODS + " payment methods";
    static final String CARD_LAST_FOUR_REQUIRED = "lastFour is required for a card";
    static final String MPESA_PHONE_REQUIRED = "mpesaPhone is required for M-PESA";
    static final String INSURER_REQUIRED = "insurerName is required for insurance";
    static final String POLICY_REQUIRED = "policyNumber is required for insurance";

    private final PaymentMethodRepository paymentMethodRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // =====================================================================================================
    // READS
    // =====================================================================================================

    /** The calling patient's active methods (GET /api/billing/payment-methods). PATIENT only. */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> listMine() {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return listOf(authorizationService.currentSystemId());
    }

    /**
     * One patient's active methods, read-only (GET /api/billing/payment-methods/patient/{id}): the patient
     * themself, a caregiver with MANAGE_BILLING, or an administrator. Doctors are refused before the patient-access
     * check, because a care relationship must not open billing instruments. For an administrator the patient's
     * existence is not checked: an unknown id has no methods.
     */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> listForPatient(String patientSystemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_BILLING);
        return listOf(patientSystemId);
    }

    private List<PaymentMethodResponse> listOf(String systemId) {
        List<PaymentMethod> methods = paymentMethodRepository.findUsableBySystemId(systemId);
        auditService.logListRead(RESOURCE, methods, PaymentMethod::getSystemId);
        return methods.stream().map(PaymentMethodService::toResponse).toList();
    }

    // =====================================================================================================
    // WRITES (owner only)
    // =====================================================================================================

    /**
     * Saves a method for the calling patient. Check order: role (403), required-for-type (400), duplicate (409),
     * cap (409). The new method becomes the default when asked or when it is the patient's first.
     */
    @Transactional
    public PaymentMethodResponse create(CreatePaymentMethodRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String owner = authorizationService.currentSystemId();
        PaymentMethod candidate = buildCandidate(owner, request);

        paymentMethodRepository.lockOwner(owner);
        List<PaymentMethod> existing = paymentMethodRepository.findUsableBySystemId(owner);
        if (existing.stream().anyMatch(method -> sameInstrument(method, candidate))) {
            throw new ConflictException(DUPLICATE);
        }
        if (existing.size() >= MAX_METHODS) {
            throw new ConflictException(TOO_MANY);
        }

        boolean makeDefault = request.wantsDefault() || existing.isEmpty();
        if (makeDefault && existing.stream().anyMatch(method -> Boolean.TRUE.equals(method.getIsDefault()))) {
            paymentMethodRepository.clearDefault(owner);
        }
        candidate.setIsDefault(makeDefault);
        candidate.setCreatedAt(LocalDateTime.now(clock));
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(candidate);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getMethodId(), owner);
        log.info("Payment method {} ({}) saved for {}", saved.getMethodId(), saved.getType(), owner);
        return toResponse(saved);
    }

    /**
     * Makes one of the calling patient's methods the default; the previous default loses the flag in the same
     * transaction. Already the default: no-op. Unknown, removed or another patient's method: 404.
     */
    @Transactional
    public PaymentMethodResponse makeDefault(Integer methodId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String owner = authorizationService.currentSystemId();
        paymentMethodRepository.lockOwner(owner);
        PaymentMethod method = requireOwnUsable(methodId, owner);
        if (Boolean.TRUE.equals(method.getIsDefault())) {
            return toResponse(method);
        }
        // clearDefault flushes and clears the persistence context: reload the method afterwards.
        paymentMethodRepository.clearDefault(owner);
        PaymentMethod reloaded = requireOwnUsable(methodId, owner);
        reloaded.setIsDefault(true);
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(reloaded);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getMethodId(), owner);
        return toResponse(saved);
    }

    /**
     * Soft-deletes one of the calling patient's methods (inactive, deleted, not default). When it was the default,
     * the newest remaining method becomes the default. Old payments keep referencing the row.
     */
    @Transactional
    public void delete(Integer methodId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String owner = authorizationService.currentSystemId();
        paymentMethodRepository.lockOwner(owner);
        PaymentMethod method = requireOwnUsable(methodId, owner);
        boolean wasDefault = Boolean.TRUE.equals(method.getIsDefault());
        method.setIsDeleted(true);
        method.setIsActive(false);
        method.setIsDefault(false);
        paymentMethodRepository.saveAndFlush(method);

        if (wasDefault) {
            paymentMethodRepository.findUsableBySystemId(owner).stream()
                    .max(Comparator.comparing(PaymentMethod::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(PaymentMethod::getMethodId))
                    .ifPresent(next -> {
                        next.setIsDefault(true);
                        paymentMethodRepository.saveAndFlush(next);
                    });
        }
        auditService.logClinicalAccess("DELETE", RESOURCE, method.getMethodId(), owner);
    }

    // =====================================================================================================
    // HELPERS
    // =====================================================================================================

    private PaymentMethod requireOwnUsable(Integer methodId, String owner) {
        return paymentMethodRepository.findUsableByMethodIdAndSystemId(methodId, owner)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /** The row to save: only the fields of the type, the server-built label and an opaque local token. */
    private static PaymentMethod buildCandidate(String owner, CreatePaymentMethodRequest request) {
        String type = request.getType().toUpperCase(Locale.ROOT);
        PaymentMethod method = PaymentMethod.builder()
                .systemId(owner)
                .type(type)
                .isActive(true)
                .isDeleted(false)
                .providerToken(TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", ""))
                .build();
        switch (type) {
            case PaymentMethod.TYPE_CARD -> {
                if (request.getLastFour() == null) {
                    throw new BadRequestException(CARD_LAST_FOUR_REQUIRED);
                }
                method.setProvider(request.getProvider());
                method.setLastFour(request.getLastFour());
            }
            case PaymentMethod.TYPE_MPESA -> {
                if (request.getMpesaPhone() == null) {
                    throw new BadRequestException(MPESA_PHONE_REQUIRED);
                }
                String digits = digitsOf(request.getMpesaPhone());
                method.setProvider(MPESA_PROVIDER);
                method.setMpesaPhone(request.getMpesaPhone());
                method.setLastFour(digits.substring(digits.length() - 4));
            }
            case PaymentMethod.TYPE_INSURANCE -> {
                if (request.getInsurerName() == null) {
                    throw new BadRequestException(INSURER_REQUIRED);
                }
                if (request.getPolicyNumber() == null) {
                    throw new BadRequestException(POLICY_REQUIRED);
                }
                String policy = request.getPolicyNumber();
                method.setProvider(request.getInsurerName());
                method.setInsurerName(request.getInsurerName());
                method.setPolicyNumber(policy);
                method.setMemberNumber(request.getMemberNumber());
                method.setLastFour(policy.substring(policy.length() - 4));
            }
            // Bean Validation already restricts the type; this only guards a direct call.
            default -> throw new BadRequestException(CreatePaymentMethodRequest.TYPE_MESSAGE);
        }
        method.setDisplayLabel(labelOf(method.getProvider(), method.getLastFour()));
        return method;
    }

    /** Same instrument as an active method of the owner (the duplicate rule). */
    static boolean sameInstrument(PaymentMethod existing, PaymentMethod candidate) {
        if (!candidate.getType().equals(existing.getType())) {
            return false;
        }
        return switch (candidate.getType()) {
            case PaymentMethod.TYPE_CARD -> equalsIgnoreCase(existing.getProvider(), candidate.getProvider())
                    && Objects.equals(existing.getLastFour(), candidate.getLastFour());
            case PaymentMethod.TYPE_MPESA -> existing.getMpesaPhone() != null
                    && digitsOf(existing.getMpesaPhone()).equals(digitsOf(candidate.getMpesaPhone()));
            case PaymentMethod.TYPE_INSURANCE -> equalsIgnoreCase(existing.getInsurerName(), candidate.getInsurerName())
                    && equalsIgnoreCase(existing.getPolicyNumber(), candidate.getPolicyNumber());
            default -> false;
        };
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    static String digitsOf(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    /** {@code <provider, or "Card" when there is none> •••• <lastFour>}. */
    static String labelOf(String provider, String lastFour) {
        String name = provider == null || provider.isBlank() ? CARD_LABEL : provider.trim();
        return name + LABEL_DOTS + (lastFour == null ? "" : lastFour);
    }

    /** Masked view. A legacy row without a stored label gets one built from its provider and last four. */
    static PaymentMethodResponse toResponse(PaymentMethod method) {
        boolean insurance = PaymentMethod.TYPE_INSURANCE.equals(method.getType());
        String label = Optional.ofNullable(method.getDisplayLabel())
                .filter(value -> !value.isBlank())
                .orElseGet(() -> labelOf(method.getProvider(), method.getLastFour()));
        return PaymentMethodResponse.builder()
                .methodId(method.getMethodId())
                .systemId(method.getSystemId())
                .type(method.getType())
                .provider(method.getProvider())
                .lastFour(method.getLastFour())
                .insurerName(insurance ? method.getInsurerName() : null)
                .displayLabel(label)
                .isDefault(Boolean.TRUE.equals(method.getIsDefault()))
                .createdAt(method.getCreatedAt())
                .build();
    }
}
