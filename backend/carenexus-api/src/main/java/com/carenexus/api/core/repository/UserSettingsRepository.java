package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Integer> {
    Optional<UserSettings> findBySystemId(String systemId);

    /**
     * Creates the account's settings row with the column defaults unless it already exists. A single statement, so
     * two first requests of the same user at the same time cannot both insert (UNIQUE system_id). {@code now} is
     * the clinic wall-clock time used for created_at and updated_at.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_settings (system_id, created_at, updated_at) VALUES (:systemId, :now, :now) "
            + "ON CONFLICT (system_id) DO NOTHING", nativeQuery = true)
    int insertDefaultsIfAbsent(@Param("systemId") String systemId, @Param("now") LocalDateTime now);
}
