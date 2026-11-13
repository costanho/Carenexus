package com.carenexus.api.billing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Billing settings ({@code carenexus.billing.*}). Every property has a default in code, so billing works with
 * the local implementations when nothing is configured. A value that cannot be used (an unknown auto-invoice
 * mode, a tax rate outside 0..1, a currency that is not three letters) fails startup with the property name.
 * <p>
 * {@code defaultCurrency}, {@code taxRate} and {@code paymentTermsDays} are the fallbacks of the matching
 * administrator settings (billing.default-currency, billing.tax-rate, billing.payment-terms-days).
 * <p>
 * The webhook secret is never logged: {@link #toString()} leaves it out.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("carenexus.billing")
public class BillingProperties {

    /** Auto-invoice modes: what the consultation-completed listener creates. */
    public static final String AUTO_INVOICE_DRAFT = "DRAFT";
    public static final String AUTO_INVOICE_SENT = "SENT";
    public static final String AUTO_INVOICE_OFF = "OFF";

    /** Name of the built-in payment gateway and of the built-in claims provider. */
    public static final String PROVIDER_LOCAL = "local";
    public static final String CLAIMS_PROVIDER_MANUAL = "manual";

    /** ISO-4217 code stamped on a new invoice when no administrator setting overrides it. */
    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO-4217 currency code")
    private String defaultCurrency = "KES";

    /** Tax as a fraction of the taxable amount: 0 to 1 ({@code 0.16} = 16 %). */
    @NotNull
    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal taxRate = BigDecimal.ZERO;

    /** Due date = issue date + this many days. */
    @Min(0)
    private int paymentTermsDays = 14;

    /**
     * What is created when a consultation is completed: a {@code DRAFT} invoice (the doctor sends it), a
     * {@code SENT} invoice, or nothing ({@code OFF}).
     */
    @NotBlank
    @Pattern(regexp = "DRAFT|SENT|OFF", message = "must be DRAFT, SENT or OFF")
    private String autoInvoice = AUTO_INVOICE_DRAFT;

    /** Payment gateway selector. {@code local} = the built-in gateway that settles through a signed local callback. */
    @NotBlank
    private String provider = PROVIDER_LOCAL;

    /** Insurance claims provider selector. {@code manual} = the status is moved by an administrator. */
    @NotBlank
    private String claimsProvider = CLAIMS_PROVIDER_MANUAL;

    /** Days after the due date before an unpaid invoice is marked OVERDUE. */
    private int overdueGraceDays = 0;

    @Valid
    @NotNull
    private Local local = new Local();

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency == null ? null : defaultCurrency.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Case-insensitive. An unquoted {@code OFF} in a YAML file is read by the YAML parser as the boolean
     * {@code false}, so {@code false} is accepted as {@code OFF}.
     */
    public void setAutoInvoice(String autoInvoice) {
        String value = autoInvoice == null ? null : autoInvoice.trim().toUpperCase(Locale.ROOT);
        this.autoInvoice = "FALSE".equals(value) ? AUTO_INVOICE_OFF : value;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? null : provider.trim().toLowerCase(Locale.ROOT);
    }

    public void setClaimsProvider(String claimsProvider) {
        this.claimsProvider = claimsProvider == null ? null : claimsProvider.trim().toLowerCase(Locale.ROOT);
    }

    /** True when the built-in local gateway is selected. */
    public boolean isLocalProvider() {
        return PROVIDER_LOCAL.equals(provider);
    }

    /** True when the built-in manual claims provider is selected. */
    public boolean isManualClaimsProvider() {
        return CLAIMS_PROVIDER_MANUAL.equals(claimsProvider);
    }

    /** True unless automatic invoicing is switched off. */
    public boolean isAutoInvoiceEnabled() {
        return !AUTO_INVOICE_OFF.equals(autoInvoice);
    }

    /** {@link #overdueGraceDays}, with a negative value read as 0. */
    public int effectiveOverdueGraceDays() {
        return Math.max(0, overdueGraceDays);
    }

    @Override
    public String toString() {
        // Never print the webhook secret.
        return "BillingProperties{defaultCurrency=" + defaultCurrency + ", taxRate=" + taxRate
                + ", paymentTermsDays=" + paymentTermsDays + ", autoInvoice=" + autoInvoice
                + ", provider=" + provider + ", claimsProvider=" + claimsProvider
                + ", overdueGraceDays=" + overdueGraceDays + ", local=" + local + "}";
    }

    /** Settings of the built-in local gateway ({@code carenexus.billing.local.*}). */
    @Getter
    @Setter
    public static class Local {

        /**
         * DEV ONLY default of the callback secret. A real environment sets its own secret; the gateway's startup
         * check refuses a development secret outside the developer profiles.
         */
        public static final String DEV_WEBHOOK_SECRET = "dev-only-billing-webhook-secret-change-me";

        /** HMAC secret that signs and verifies the local gateway's callback. Never logged. */
        private String webhookSecret = DEV_WEBHOOK_SECRET;

        public boolean hasWebhookSecret() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }

        /**
         * True when the secret is one of the published development values: the code default, or a value marked
         * the way the developer and test profiles mark theirs ("dev-only", "test-only", "NOT-FOR-PRODUCTION").
         */
        public boolean isDevWebhookSecret() {
            if (!hasWebhookSecret()) {
                return false;
            }
            String value = webhookSecret.toLowerCase(Locale.ROOT);
            return DEV_WEBHOOK_SECRET.equals(webhookSecret) || value.contains("dev-only")
                    || value.contains("test-only") || value.contains("not-for-production");
        }

        @Override
        public String toString() {
            // Never print the secret.
            return "Local{webhookSecretSet=" + hasWebhookSecret() + "}";
        }
    }
}
