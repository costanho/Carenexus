package com.carenexus.api.billing.fee;

import com.carenexus.api.billing.model.FeeSchedule;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * Single-statement writes of {@code billing_fee_schedule}. Each upsert relies on the unique index
 * {@code ux_billing_fee_schedule_owner_type (COALESCE(doctor_system_id, ''), appointment_type)}, so two concurrent
 * saves of the same fee never collide on an insert (the later one simply wins).
 */
public interface FeeWriteRepository extends Repository<FeeSchedule, Integer> {

    /** Sets (and re-activates) the override of one doctor for one appointment type. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount, currency, is_active) "
            + "VALUES (:doctorSystemId, :appointmentType, :amount, :currency, TRUE) "
            + "ON CONFLICT ((COALESCE(doctor_system_id, '')), appointment_type) "
            + "DO UPDATE SET amount = EXCLUDED.amount, currency = EXCLUDED.currency, is_active = TRUE",
            nativeQuery = true)
    int upsertDoctorFee(@Param("doctorSystemId") String doctorSystemId,
                        @Param("appointmentType") String appointmentType,
                        @Param("amount") BigDecimal amount,
                        @Param("currency") String currency);

    /** Sets (and re-activates) the clinic default of one appointment type. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount, currency, is_active) "
            + "VALUES (NULL, :appointmentType, :amount, :currency, TRUE) "
            + "ON CONFLICT ((COALESCE(doctor_system_id, '')), appointment_type) "
            + "DO UPDATE SET amount = EXCLUDED.amount, currency = EXCLUDED.currency, is_active = TRUE",
            nativeQuery = true)
    int upsertDefaultFee(@Param("appointmentType") String appointmentType,
                         @Param("amount") BigDecimal amount,
                         @Param("currency") String currency);

    /** Deactivates the doctor's override of one appointment type, so the clinic default applies again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE billing_fee_schedule SET is_active = FALSE "
            + "WHERE doctor_system_id = :doctorSystemId AND appointment_type = :appointmentType AND is_active",
            nativeQuery = true)
    int deactivateDoctorFee(@Param("doctorSystemId") String doctorSystemId,
                            @Param("appointmentType") String appointmentType);
}
