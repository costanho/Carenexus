package com.carenexus.api.billing.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The invoice timestamps come from the clinic clock, not from the JVM default zone: with the JVM in UTC and the
 * clinic in Africa/Harare (UTC+2), the stamps are Harare wall-clock time.
 */
class InvoiceClockListenerTest {

    private static final Instant AT = Instant.parse("2026-11-10T22:30:00Z");
    private static final LocalDateTime HARARE = LocalDateTime.of(2026, 11, 11, 0, 30);

    private final InvoiceClockListener listener =
            new InvoiceClockListener(Clock.fixed(AT, ZoneId.of("Africa/Harare")));

    private static <T> T inUtc(java.util.function.Supplier<T> body) {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            return body.get();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void anUpdateIsStampedWithClinicTime_whateverTheJvmZone() {
        Invoice invoice = new Invoice();
        invoice.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        inUtc(() -> {
            listener.onUpdate(invoice);
            return null;
        });

        assertEquals(HARARE, invoice.getUpdatedAt());
    }

    @Test
    void aNewInvoiceKeepsTheServicesCreatedAt_andFillsOnlyWhatIsMissing() {
        LocalDateTime createdByService = LocalDateTime.of(2026, 11, 10, 9, 15);
        Invoice stamped = new Invoice();
        stamped.setCreatedAt(createdByService);
        listener.onCreate(stamped);
        assertEquals(createdByService, stamped.getCreatedAt());
        assertEquals(createdByService, stamped.getUpdatedAt());

        Invoice bare = new Invoice();
        inUtc(() -> {
            listener.onCreate(bare);
            return null;
        });
        assertEquals(HARARE, bare.getCreatedAt());
        assertEquals(HARARE, bare.getUpdatedAt());
    }
}
