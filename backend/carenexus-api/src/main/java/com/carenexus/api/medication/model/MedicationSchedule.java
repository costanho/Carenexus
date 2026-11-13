package com.carenexus.api.medication.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One dose (table {@code medication_schedules}, V1 + V38): one prescription, one clinic calendar day, one time of
 * day. Doses are created by the server from the prescription's plan ({@link MedicationSchedulePlan}) with explicit
 * column lists (so columns other slices add, such as {@code reminder_sent_at}, stay untouched); this entity reads
 * them and records the taken / missed status.
 *
 * Status is derived from the two flags: PENDING (neither), TAKEN ({@code is_taken}), MISSED ({@code missed}).
 * The legacy integer {@code patient_id} and the free-text {@code notes} columns are not mapped and not written.
 */
@Entity
@Table(name = "medication_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationSchedule {

    public static final String PENDING = "PENDING";
    public static final String TAKEN = "TAKEN";
    public static final String MISSED = "MISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Integer scheduleId;

    @Column(name = "prescription_id", nullable = false)
    private Integer prescriptionId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** Clinic wall-clock time of the dose. */
    @Column(name = "scheduled_time", nullable = false)
    private LocalTime scheduledTime;

    /** Copy of the prescription's medication name when the dose was created. */
    @Column(name = "medication_name", nullable = false, length = 200)
    private String medicationName;

    /** Copy of the prescription's dosage when the dose was created. */
    @Column(name = "dosage", nullable = false, length = 100)
    private String dosage;

    @Column(name = "is_taken", nullable = false)
    @Builder.Default
    private Boolean taken = Boolean.FALSE;

    /** Clinic wall-clock time the dose was marked taken; null unless taken. */
    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    @Column(name = "missed", nullable = false)
    @Builder.Default
    private Boolean missed = Boolean.FALSE;

    /** Clinic calendar day of the dose. */
    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Patient or caregiver who set the current status; null while pending and for an automatic MISSED. */
    @Column(name = "marked_by_system_id", length = 20)
    private String markedBySystemId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** PENDING, TAKEN or MISSED. */
    public String status() {
        if (Boolean.TRUE.equals(taken)) {
            return TAKEN;
        }
        if (Boolean.TRUE.equals(missed)) {
            return MISSED;
        }
        return PENDING;
    }
}
