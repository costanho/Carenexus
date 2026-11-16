package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Integer> {
    Optional<Patient> findBySystemId(String systemId);

    /** Batch lookup (one IN query) for lists that show several patients. */
    List<Patient> findBySystemIdIn(Collection<String> systemIds);
}
