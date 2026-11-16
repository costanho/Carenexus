package com.carenexus.api.billing.notify;

import com.carenexus.api.billing.model.Invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Text formats shared by the billing notifications and the printable receipt (contract be-billing-notify-receipt,
 * section 3). Every method is null-safe and English-only.
 * <ul>
 *   <li>money: {@code <currency> <amount>} with thousands separators and two decimals, {@code KES 2,900.00}; a null
 *       currency prints {@code KES}; a negative amount gets a leading minus, {@code -KES 300.00};</li>
 *   <li>date: {@code d MMM yyyy}, {@code 24 Nov 2026};</li>
 *   <li>date and time: {@code d MMM yyyy 'at' HH:mm}, {@code 10 Nov 2026 at 09:31}.</li>
 * </ul>
 */
public final class BillingText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm",
            Locale.ENGLISH);

    private BillingText() {
    }

    /** Amount at scale 2 (HALF_UP); null reads as zero. */
    public static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code KES 2,900.00}; {@code -KES 300.00} for a negative amount. */
    public static String money(String currency, BigDecimal amount) {
        String code = currency == null || currency.isBlank() ? Invoice.DEFAULT_CURRENCY : currency.trim();
        BigDecimal value = money(amount);
        String text = String.format(Locale.ENGLISH, "%s %,.2f", code, value.abs());
        return value.signum() < 0 ? "-" + text : text;
    }

    /** {@code 24 Nov 2026}; empty for null. */
    public static String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    /** {@code 10 Nov 2026} (the date part of a clinic wall-clock time); empty for null. */
    public static String date(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATE.format(dateTime);
    }

    /** {@code 10 Nov 2026 at 09:31}; empty for null. */
    public static String dateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATE_TIME.format(dateTime);
    }

    /** Plain decimal with two places and no grouping, for dedupe keys: {@code 1000.00}. */
    public static String plain(BigDecimal amount) {
        return money(amount).toPlainString();
    }
}
