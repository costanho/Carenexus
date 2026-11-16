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
 * A patient's care plan, written by a doctor ({@code care_plans}, V1 / V3 / V46).
 *
 * Goals and interventions live in their own tables ({@link CarePlanGoal}, {@link CarePlanIntervention}). The legacy
 * free-text columns {@code goals} and {@code interventions} are intentionally not mapped.
 * {@code createdAt}, {@code updatedAt} and {@code lastReviewedAt} are clinic wall-clock times set by the service.
 */
@Entity
@Table(name = "care_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarePlan {

    public static final String ACTIVE = "ACTIVE";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String COMPLETED = "COMPLETED";
    public static final String DISCONTINUED = "DISCONTINUED";

    /** Every plan status, in display order (matches the V1 CHECK constraint). */
    public static final List<String> STATUSES = List.of(ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Integer planId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "doctor_system_id", nullable = false, length = 20)
    private String doctorSystemId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    /** The NEXT review date; null = no review scheduled. */
    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "last_review_note", length = 1000)
    private String lastReviewNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** COMPLETED and DISCONTINUED plans are read-only. */
    public boolean isTerminal() {
        return COMPLETED.equals(status) || DISCONTINUED.equals(status);
    }
}
