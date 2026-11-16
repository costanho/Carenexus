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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One goal of a care plan ({@code care_plan_goals}, V46). Ordered by {@code position} (0-based) inside its plan.
 * The author doctor edits description and target date; status and progress note are goal progress, changed by the
 * patient, a caregiver with MANAGE_CARE_PLAN or the author doctor.
 */
@Entity
@Table(name = "care_plan_goals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarePlanGoal {

    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String ACHIEVED = "ACHIEVED";
    public static final String DROPPED = "DROPPED";

    /** Every goal status, in display order (matches the V46 CHECK constraint). */
    public static final List<String> STATUSES = List.of(OPEN, IN_PROGRESS, ACHIEVED, DROPPED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "goal_id")
    private Integer goalId;

    @Column(name = "plan_id", nullable = false)
    private Integer planId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = OPEN;

    @Column(name = "progress_note", length = 1000)
    private String progressNote;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
