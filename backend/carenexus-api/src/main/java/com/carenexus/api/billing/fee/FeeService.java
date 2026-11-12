package com.carenexus.api.billing.fee;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.billing.fee.dto.FeeResponse;
import com.carenexus.api.billing.fee.dto.UpdateDefaultFeesRequest;
import com.carenexus.api.billing.fee.dto.UpdateFeesRequest;
import com.carenexus.api.billing.model.FeeSchedule;
import com.carenexus.api.billing.repository.FeeScheduleRepository;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consultation fee schedule: the price source of invoices.
 * <p>
 * Resolution for a doctor and an appointment type: the doctor's active override, else the active clinic default
 * (row without a doctor), else {@code 0.00}. The currency reported with every fee is the current
 * {@link SettingKey#BILLING_DEFAULT_CURRENCY} (the currency a new invoice is created in); amounts are used as plain
 * numbers, there is no conversion.
 * <p>
 * Writes: a doctor sets or removes their own overrides; an administrator sets the clinic defaults. Both are audited
 * as {@code FEE_UPDATE} on resource type {@code INVOICE}.
 */
@Service
@RequiredArgsConstructor
public class FeeService {

    public static final String SOURCE_DOCTOR = "DOCTOR";
    public static final String SOURCE_DEFAULT = "DEFAULT";
    public static final String SOURCE_NONE = "NONE";

    static final String RESOURCE = "INVOICE";
    static final String AUDIT_ACTION = "FEE_UPDATE";
    static final String DUPLICATE_TYPE = "Each appointment type may appear only once";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final FeeScheduleRepository feeScheduleRepository;
    private final FeeWriteRepository feeWriteRepository;
    private final SystemSettingsService systemSettingsService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    // ==================== READS ====================

    /**
     * The three fees (VIDEO, IN_PERSON, PHONE) for a doctor, or the clinic defaults when {@code doctorSystemId}
     * is null or blank. Any signed-in user may read fees; the doctor's existence is not checked.
     */
    @Transactional(readOnly = true)
    public List<FeeResponse> listFees(String doctorSystemId) {
        authorizationService.currentSystemId(); // signed-in callers only
        return resolveAll(blankToNull(doctorSystemId));
    }

    /**
     * Fee of one visit: doctor override, else clinic default, else 0.00 (scale 2). An unknown appointment type
     * resolves to 0.00. Joins the caller's transaction (no authorization: used by the invoice service).
     */
    public BigDecimal feeFor(String doctorSystemId, String appointmentType) {
        return feesFor(doctorSystemId).getOrDefault(normalizeType(appointmentType), ZERO);
    }

    /**
     * Fee per appointment type (VIDEO, IN_PERSON, PHONE) for one doctor, with the precedence above. Joins the
     * caller's transaction (no authorization: used by the invoice service).
     */
    public Map<String, BigDecimal> feesFor(String doctorSystemId) {
        Map<String, BigDecimal> fees = new LinkedHashMap<>();
        for (FeeResponse fee : resolveAll(blankToNull(doctorSystemId))) {
            fees.put(fee.appointmentType(), fee.amount());
        }
        return fees;
    }

    /** Current clinic currency (the currency fees are reported in and new invoices use). */
    public String currentCurrency() {
        return systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY);
    }

    // ==================== WRITES ====================

    /**
     * Sets or removes the calling doctor's overrides. DOCTOR only (an administrator is refused here and uses the
     * defaults endpoint). Returns the caller's three resolved fees.
     */
    @Transactional
    public List<FeeResponse> updateOwnFees(UpdateFeesRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();

        List<String> types = new ArrayList<>();
        for (UpdateFeesRequest.Item item : request.getFees()) {
            types.add(normalizeType(item.getAppointmentType()));
        }
        requireDistinct(types);

        String currency = currentCurrency();
        for (int i = 0; i < types.size(); i++) {
            BigDecimal amount = request.getFees().get(i).getAmount();
            if (amount == null) {
                feeWriteRepository.deactivateDoctorFee(doctorSystemId, types.get(i));
            } else {
                feeWriteRepository.upsertDoctorFee(doctorSystemId, types.get(i), money(amount), currency);
            }
        }
        auditService.logAccountEvent(doctorSystemId, AUDIT_ACTION, RESOURCE, doctorSystemId, "SUCCESS");
        return resolveAll(doctorSystemId);
    }

    /**
     * Sets the clinic default fees. ADMIN only. Returns the three defaults.
     */
    @Transactional
    public List<FeeResponse> updateDefaultFees(UpdateDefaultFeesRequest request) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        String adminSystemId = authorizationService.currentSystemId();

        List<String> types = new ArrayList<>();
        for (UpdateDefaultFeesRequest.Item item : request.getFees()) {
            types.add(normalizeType(item.getAppointmentType()));
        }
        requireDistinct(types);

        String currency = currentCurrency();
        for (int i = 0; i < types.size(); i++) {
            feeWriteRepository.upsertDefaultFee(types.get(i), money(request.getFees().get(i).getAmount()), currency);
        }
        auditService.logAccountEvent(adminSystemId, AUDIT_ACTION, RESOURCE, null, "SUCCESS");
        return resolveAll(null);
    }

    // ==================== HELPERS ====================

    private List<FeeResponse> resolveAll(String doctorSystemId) {
        List<FeeSchedule> rows = doctorSystemId == null
                ? feeScheduleRepository.findByDoctorSystemIdIsNull().stream()
                        .filter(row -> Boolean.TRUE.equals(row.getIsActive()))
                        .toList()
                : feeScheduleRepository.findActiveForDoctor(doctorSystemId);
        String currency = currentCurrency();

        List<FeeResponse> result = new ArrayList<>(FeeSchedule.APPOINTMENT_TYPES.size());
        for (String type : FeeSchedule.APPOINTMENT_TYPES) {
            FeeSchedule clinicDefault = null;
            FeeSchedule override = null;
            for (FeeSchedule row : rows) {
                if (!type.equals(row.getAppointmentType())) {
                    continue;
                }
                if (row.getDoctorSystemId() == null) {
                    clinicDefault = row;
                } else if (Objects.equals(row.getDoctorSystemId(), doctorSystemId)) {
                    override = row;
                }
            }
            BigDecimal defaultAmount = clinicDefault == null ? ZERO : money(clinicDefault.getAmount());
            BigDecimal amount;
            String source;
            if (override != null) {
                amount = money(override.getAmount());
                source = SOURCE_DOCTOR;
            } else if (clinicDefault != null) {
                amount = defaultAmount;
                source = SOURCE_DEFAULT;
            } else {
                amount = ZERO;
                source = SOURCE_NONE;
            }
            result.add(new FeeResponse(type, amount, currency, source, defaultAmount, doctorSystemId));
        }
        return result;
    }

    private static void requireDistinct(List<String> types) {
        Set<String> seen = new HashSet<>();
        for (String type : types) {
            if (!seen.add(type)) {
                throw new BadRequestException(DUPLICATE_TYPE);
            }
        }
    }

    static String normalizeType(String type) {
        return type == null ? null : type.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
