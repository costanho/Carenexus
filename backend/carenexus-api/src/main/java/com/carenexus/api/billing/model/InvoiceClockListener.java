package com.carenexus.api.billing.model;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Stamps {@link Invoice#getCreatedAt()} (when the service left it empty) and {@link Invoice#getUpdatedAt()} with the
 * clinic wall-clock time of the application's {@link Clock} bean ({@code carenexus.scheduling.time-zone}), the same
 * clock InvoiceService uses for {@code createdAt}, {@code issuedAt} and {@code paidAt}. The JVM default zone is not
 * used, so the stamps stay clinic time even on a JVM that runs in another zone.
 * <p>
 * Hibernate obtains this listener from the Spring container (Spring Boot's SpringBeanContainer), which injects the
 * clock through the constructor.
 */
public class InvoiceClockListener {

    private final Clock clock;

    public InvoiceClockListener(Clock clock) {
        this.clock = clock;
    }

    @PrePersist
    void onCreate(Invoice invoice) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (invoice.getCreatedAt() == null) {
            invoice.setCreatedAt(now);
        }
        if (invoice.getUpdatedAt() == null) {
            invoice.setUpdatedAt(invoice.getCreatedAt());
        }
    }

    @PreUpdate
    void onUpdate(Invoice invoice) {
        invoice.setUpdatedAt(LocalDateTime.now(clock));
    }
}
