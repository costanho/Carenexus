package com.carenexus.api.facility.repository;

import com.carenexus.api.facility.model.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Facilities. List queries go through {@link JpaSpecificationExecutor} with the filters built by
 * {@code FacilityService} (active flag, name or address search) and a deterministic sort (name, case-insensitive,
 * then id).
 */
public interface FacilityRepository extends JpaRepository<Facility, Integer>, JpaSpecificationExecutor<Facility> {
}
