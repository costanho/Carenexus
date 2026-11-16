package com.carenexus.api.careplans.repository;

import com.carenexus.api.careplans.model.CarePlanIntervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Interventions of care plans, always read in display order (position, then id).
 */
public interface CarePlanInterventionRepository extends JpaRepository<CarePlanIntervention, Integer> {

    List<CarePlanIntervention> findByPlanIdOrderByPositionAscInterventionIdAsc(Integer planId);

    /** Batch load for a page of plans (one query, no per-plan lookups). */
    List<CarePlanIntervention> findByPlanIdInOrderByPlanIdAscPositionAscInterventionIdAsc(Collection<Integer> planIds);

    /** Removes every intervention of a plan (they are replaced as a whole on PUT). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM CarePlanIntervention i WHERE i.planId = :planId")
    int deleteByPlanId(@Param("planId") Integer planId);
}
