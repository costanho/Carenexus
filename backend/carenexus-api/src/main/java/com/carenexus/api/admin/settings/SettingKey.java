package com.carenexus.api.admin.settings;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.exception.BadRequestException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed list of system settings an administrator can change. Readers go through
 * {@link SystemSettingsService} ({@code getString}, {@code getInt}, {@code getDecimal}) and never read the matching
 * application property themselves.
 *
 * Each key carries its dotted name (used by the admin API and as the primary key of {@code system_settings}), its
 * value type and limits, a one-sentence description, the application properties it falls back to (in order) and
 * the default used when no property is set.
 *
 * Declaration order is the order of {@code GET /api/admin/settings}.
 */
public enum SettingKey {

    /** Reader: {@code GET /api/settings/public} (web Help &amp; Support). */
    CLINIC_NAME("clinic.name", SettingType.STRING,
            "Name of the clinic shown on Help & Support and on printed invoices and receipts.",
            "CareNexus", null, null, 120,
            "carenexus.clinic.name"),

    /** Reader: {@code GET /api/settings/public}. Empty when no support email is configured. */
    SUPPORT_CONTACT_EMAIL("support.contact-email", SettingType.EMAIL,
            "Support email address shown on Help & Support.",
            "", null, null, 254,
            "carenexus.support.contact-email", "carenexus.support.email"),

    /** Reader: {@code GET /api/settings/public}. Empty when no support phone is configured. */
    SUPPORT_CONTACT_PHONE("support.contact-phone", SettingType.PHONE,
            "Support phone number shown on Help & Support.",
            "", null, null, 30,
            "carenexus.support.contact-phone", "carenexus.support.phone"),

    /** Reader: the automatic appointment reminder job. */
    REMINDERS_APPOINTMENT_LEAD_HOURS("reminders.appointment-lead-hours", SettingType.INT,
            "Hours before an appointment at which the first reminder is sent.",
            "24", 1, 168, null,
            "carenexus.reminders.appointment-lead-hours"),

    /** Reader: the automatic appointment reminder job. */
    REMINDERS_SECOND_LEAD_MINUTES("reminders.second-lead-minutes", SettingType.INT,
            "Minutes before an appointment at which the second reminder is sent.",
            "60", 5, 720, null,
            "carenexus.reminders.second-lead-minutes"),

    /** Reader: the doctor's "needs an outcome" appointment list. */
    APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES("appointments.needs-outcome-after-minutes", SettingType.INT,
            "Minutes after the end of an appointment before it is listed as needing an outcome.",
            "30", 5, 1440, null,
            "carenexus.scheduling.needs-outcome-after-minutes"),

    /** Reader: invoice creation. */
    BILLING_DEFAULT_CURRENCY("billing.default-currency", SettingType.CURRENCY,
            "Currency of new invoices (ISO 4217 code).",
            "KES", null, null, 3,
            "carenexus.billing.default-currency"),

    /** Reader: invoice creation (due date). */
    BILLING_PAYMENT_TERMS_DAYS("billing.payment-terms-days", SettingType.INT,
            "Days from issue until a new invoice is due.",
            "14", 0, 365, null,
            "carenexus.billing.payment-terms-days"),

    /** Reader: invoice creation. A fraction: 0.16 means 16 %. */
    BILLING_TAX_RATE("billing.tax-rate", SettingType.DECIMAL,
            "Tax rate applied to new invoices, as a fraction (0.16 = 16%).",
            "0", 0, 1, null,
            "carenexus.billing.tax-rate");

    /** Largest number of decimal places accepted for a DECIMAL value, as typed. */
    public static final int MAX_DECIMAL_PLACES = 4;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile(ValidationPatterns.PHONE);
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern WHOLE_NUMBER = Pattern.compile("^-?\\d{1,9}$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d{1,9}(\\.\\d+)?$");

    private final String key;
    private final SettingType type;
    private final String description;
    private final String codeDefault;
    private final BigDecimal min;
    private final BigDecimal max;
    private final Integer maxLength;
    private final List<String> properties;

    SettingKey(String key, SettingType type, String description, String codeDefault,
               Integer min, Integer max, Integer maxLength, String... properties) {
        this.key = key;
        this.type = type;
        this.description = description;
        this.codeDefault = codeDefault;
        this.min = min == null ? null : BigDecimal.valueOf(min.longValue());
        this.max = max == null ? null : BigDecimal.valueOf(max.longValue());
        this.maxLength = maxLength;
        this.properties = List.of(properties);
    }

    /** Dotted name used by the admin API and stored in {@code system_settings.setting_key}, for example {@code billing.tax-rate}. */
    public String key() {
        return key;
    }

    public SettingType type() {
        return type;
    }

    /** One English sentence shown next to the setting. */
    public String description() {
        return description;
    }

    /** Value used when neither a stored override nor an application property exists (canonical form; may be empty for the support contacts). */
    public String codeDefault() {
        return codeDefault;
    }

    /** Inclusive lower bound for INT and DECIMAL keys, null otherwise. */
    public BigDecimal min() {
        return min;
    }

    /** Inclusive upper bound for INT and DECIMAL keys, null otherwise. */
    public BigDecimal max() {
        return max;
    }

    /** Maximum length for STRING, EMAIL, PHONE and CURRENCY keys, null otherwise. */
    public Integer maxLength() {
        return maxLength;
    }

    /** Application properties read as fallback, in order; the first one holding a valid value wins. */
    public List<String> properties() {
        return properties;
    }

    /**
     * The key with this dotted name. The name is trimmed and compared case-insensitively; the Java constant name
     * is not accepted.
     */
    public static Optional<SettingKey> fromKey(String dottedName) {
        if (dottedName == null) {
            return Optional.empty();
        }
        String wanted = dottedName.trim().toLowerCase(Locale.ROOT);
        for (SettingKey candidate : values()) {
            if (candidate.key.equals(wanted)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Validates a raw value for this key and returns its canonical text form (the form that is stored and returned):
     * trimmed text for STRING, EMAIL and PHONE; three upper-case letters for CURRENCY; {@code Integer.toString} for
     * INT; a plain decimal without trailing zeros for DECIMAL.
     *
     * @throws BadRequestException code {@code VALIDATION_FAILED}, message {@code "value: <reason>"}, when the value
     *                             is blank or not acceptable for this key
     */
    public String normalize(String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (value.isEmpty()) {
            throw invalid("must not be blank");
        }
        return switch (type) {
            case STRING -> {
                if (value.length() > maxLength) {
                    throw invalid("must be at most " + maxLength + " characters");
                }
                yield value;
            }
            case EMAIL -> {
                if (value.length() > maxLength || !EMAIL.matcher(value).matches()) {
                    throw invalid("must be a valid email address");
                }
                yield value;
            }
            case PHONE -> {
                if (value.length() > maxLength || !PHONE.matcher(value).matches()) {
                    throw invalid("must be a valid phone number");
                }
                yield value;
            }
            case CURRENCY -> {
                String code = value.toUpperCase(Locale.ROOT);
                if (!CURRENCY_CODE.matcher(code).matches() || !isKnownCurrency(code)) {
                    throw invalid("must be an ISO 4217 currency code");
                }
                yield code;
            }
            case INT -> {
                if (!WHOLE_NUMBER.matcher(value).matches()) {
                    throw invalid("must be a whole number");
                }
                int number = Integer.parseInt(value);
                requireInRange(BigDecimal.valueOf(number));
                yield Integer.toString(number);
            }
            case DECIMAL -> {
                if (!NUMBER.matcher(value).matches()) {
                    throw invalid("must be a number");
                }
                int point = value.indexOf('.');
                if (point >= 0 && value.length() - point - 1 > MAX_DECIMAL_PLACES) {
                    throw invalid("must have at most " + MAX_DECIMAL_PLACES + " decimal places");
                }
                BigDecimal number = new BigDecimal(value);
                requireInRange(number);
                yield number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
            }
        };
    }

    private void requireInRange(BigDecimal number) {
        if ((min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0)) {
            throw invalid("must be between " + min.toPlainString() + " and " + max.toPlainString());
        }
    }

    private static boolean isKnownCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static BadRequestException invalid(String reason) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, "value: " + reason);
    }
}
