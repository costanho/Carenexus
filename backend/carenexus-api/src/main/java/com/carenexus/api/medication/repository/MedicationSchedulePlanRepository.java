package com.carenexus.api.medication.repository;

import com.carenexus.api.medication.model.MedicationSchedulePlan;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Plan reads through JPA. Plans are written with the single-statement upsert in {@link MedicationScheduleJdbc}.
 */
public interface MedicationSchedulePlanRepository extends Repository<MedicationSchedulePlan, Integer> {

    Optional<MedicationSchedulePlan> findById(Integer prescriptionId);
}
