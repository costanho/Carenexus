package com.carenexus.api.calls.repository;

import com.carenexus.api.calls.model.ProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ProviderEventRepository extends JpaRepository<ProviderEvent, Long> {

    /**
     * Record an event once. Returns 1 when inserted, 0 when (provider, event_id) was already recorded
     * (duplicate delivery). ON CONFLICT keeps the surrounding transaction usable.
     */
    @Modifying
    @Query(value = "INSERT INTO provider_events (provider, event_id, event_type, room_name, provider_identity, " +
            "event_created_at, received_at) VALUES (:provider, :eventId, :eventType, CAST(:roomName AS VARCHAR), CAST(:identity AS VARCHAR), " +
            "CAST(:eventCreatedAt AS TIMESTAMPTZ), now()) ON CONFLICT (provider, event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("provider") String provider, @Param("eventId") String eventId,
                       @Param("eventType") String eventType, @Param("roomName") String roomName,
                       @Param("identity") String identity, @Param("eventCreatedAt") Instant eventCreatedAt);

    Optional<ProviderEvent> findByProviderAndEventId(String provider, String eventId);
}
