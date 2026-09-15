package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Caregiver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CaregiverRepository extends JpaRepository<Caregiver, Integer> {
    Optional<Caregiver> findByUserId(Integer userId);
}
