package com.carenexus.api.lifecycle.repository;

import com.carenexus.api.core.model.Appointment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Future active appointments of a deactivated or closed account, read in keyset pages ordered by
 * {@code scheduledAt, appointmentId}. The cursor is the last row of the previous page; the first page uses
 * {@code afterAt} = clinic now and {@code afterId} = {@link Integer#MAX_VALUE}, which selects every appointment that
 * starts after now. Keyset paging keeps working while the rows already read are being cancelled, and it never skips
 * an appointment that was left as it is (one with a completed consultation).
 */
public interface LifecycleAppointmentRepository extends Repository<Appointment, Integer> {

    @Query("SELECT a FROM Appointment a WHERE a.doctorSystemId = :systemId AND a.status IN :statuses "
            + "AND (a.scheduledAt > :afterAt OR (a.scheduledAt = :afterAt AND a.appointmentId > :afterId)) "
            + "ORDER BY a.scheduledAt ASC, a.appointmentId ASC")
    List<Appointment> findActiveAsDoctorAfter(@Param("systemId") String systemId,
                                              @Param("statuses") Collection<String> statuses,
                                              @Param("afterAt") LocalDateTime afterAt,
                                              @Param("afterId") Integer afterId,
                                              Pageable pageable);

    @Query("SELECT a FROM Appointment a WHERE a.patientSystemId = :systemId AND a.status IN :statuses "
            + "AND (a.scheduledAt > :afterAt OR (a.scheduledAt = :afterAt AND a.appointmentId > :afterId)) "
            + "ORDER BY a.scheduledAt ASC, a.appointmentId ASC")
    List<Appointment> findActiveAsPatientAfter(@Param("systemId") String systemId,
                                               @Param("statuses") Collection<String> statuses,
                                               @Param("afterAt") LocalDateTime afterAt,
                                               @Param("afterId") Integer afterId,
                                               Pageable pageable);
}
