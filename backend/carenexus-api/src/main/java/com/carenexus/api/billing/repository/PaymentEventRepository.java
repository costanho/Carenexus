package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    /**
     * Record a gateway event once. Returns 1 when inserted, 0 when (provider, event_id) was already recorded
     * (duplicate delivery). ON CONFLICT keeps the surrounding transaction usable. The row starts without a
     * payment and without an outcome; load it with {@link #findByProviderAndEventId} to complete it.
     */
    @Modifying
    @Query(value = "INSERT INTO payment_events (provider, event_id, event_type, received_at) "
            + "VALUES (:provider, :eventId, :eventType, now()) "
            + "ON CONFLICT (provider, event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("provider") String provider, @Param("eventId") String eventId,
                       @Param("eventType") String eventType);

    Optional<PaymentEvent> findByProviderAndEventId(String provider, String eventId);
}
