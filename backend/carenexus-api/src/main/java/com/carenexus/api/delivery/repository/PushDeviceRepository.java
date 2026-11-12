package com.carenexus.api.delivery.repository;

import com.carenexus.api.delivery.model.PushDevice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PushDeviceRepository extends JpaRepository<PushDevice, Integer> {

    /** A user's devices, most recently seen first (pass a page of at most PushDevice.MAX_DEVICES_PER_USER). */
    @Query("SELECT d FROM PushDevice d WHERE d.systemId = :systemId ORDER BY d.lastSeenAt DESC, d.deviceId DESC")
    List<PushDevice> findBySystemIdNewestFirst(@Param("systemId") String systemId, Pageable pageable);

    boolean existsBySystemId(String systemId);

    /** Removes the caller's own registration of a token; 0 when the token is not registered to that user. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushDevice d WHERE d.systemId = :systemId AND d.token = :token")
    int deleteOwn(@Param("systemId") String systemId, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushDevice d WHERE d.token IN :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);

    /** Keeps the {@code keep} most recently seen devices of a user and deletes the rest. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM push_devices WHERE system_id = :systemId AND device_id NOT IN ("
            + "SELECT device_id FROM push_devices WHERE system_id = :systemId "
            + "ORDER BY last_seen_at DESC, device_id DESC LIMIT :keep)", nativeQuery = true)
    int deleteBeyondNewest(@Param("systemId") String systemId, @Param("keep") int keep);
}
