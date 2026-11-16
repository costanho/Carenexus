package com.carenexus.api.careplans.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One intervention of a care plan ({@code care_plan_interventions}, V46). Ordered by {@code position} (0-based).
 * Interventions are replaced as a whole when the author doctor edits the plan, so their ids are not stable.
 */
@Entity
@Table(name = "care_plan_interventions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarePlanIntervention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "intervention_id")
    private Integer interventionId;

    @Column(name = "plan_id", nullable = false)
    private Integer planId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "frequency", length = 100)
    private String frequency;

    @Column(name = "responsible", length = 100)
    private String responsible;

    @Column(name = "position", nullable = false)
    private Integer position;
}
