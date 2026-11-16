package com.carenexus.api.careplans.repository;

import com.carenexus.api.careplans.model.CarePlanGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Goals of care plans, always read in display order (position, then id).
 */
public interface CarePlanGoalRepository extends JpaRepository<CarePlanGoal, Integer> {

    List<CarePlanGoal> findByPlanIdOrderByPositionAscGoalIdAsc(Integer planId);

    /** Batch load for a page of plans (one query, no per-plan lookups). */
    List<CarePlanGoal> findByPlanIdInOrderByPlanIdAscPositionAscGoalIdAsc(Collection<Integer> planIds);

    Optional<CarePlanGoal> findByGoalIdAndPlanId(Integer goalId, Integer planId);
}
