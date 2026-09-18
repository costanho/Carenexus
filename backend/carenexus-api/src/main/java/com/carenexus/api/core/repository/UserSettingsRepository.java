package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Integer> {
    Optional<UserSettings> findByUserId(Integer userId);
}
