package com.carenexus.api.medication.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * The daily dose times of one prescription (table {@code medication_schedule_plans}, V38). The prescription id is
 * the primary key. An empty array means the prescription has no scheduled doses.
 *
 * Rows are written with a single-statement upsert ({@code MedicationScheduleJdbc.upsertPlan}); the entity is used
 * for reads and for Hibernate's schema validation.
 */
@Entity
@Table(name = "medication_schedule_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationSchedulePlan {

    @Id
    @Column(name = "prescription_id")
    private Integer prescriptionId;

    /** JSON array of "HH:mm" clinic wall-clock strings, distinct and ascending. */
    @Column(name = "times", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String times;

    /** Doctor who last set the times (the prescriber at issue). */
    @Column(name = "updated_by_system_id", length = 20)
    private String updatedBySystemId;

    /** Clinic wall-clock time the times were last set. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
