package com.carenexus.api.medication.service;

import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.PrescriptionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Creates and removes PENDING doses of one prescription. Callers pass the clinic's "now" (from the injected
 * {@code Clock}) and run inside a transaction. Every operation is idempotent.
 */
@Component
@RequiredArgsConstructor
public class DoseMaterializer {

    /** Doses are created for today (times strictly after now) and tomorrow, never further ahead. */
    static final int DAYS_AHEAD = 1;

    private final MedicationScheduleJdbc jdbc;

    /**
     * Creates the missing doses of {@code times} for today (only times strictly after {@code now}) and tomorrow,
     * within the prescription's dates ({@code prescribedDate <= day}, and {@code day <= expiryDate} when set).
     * The prescription's status is not checked here: callers decide whether it is ACTIVE.
     *
     * @return the number of doses created
     */
    public int materialise(PrescriptionSnapshot rx, List<LocalTime> times, LocalDateTime now) {
        if (times == null || times.isEmpty()) {
            return 0;
        }
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();
        LocalDateTime createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        int created = 0;
        for (int offset = 0; offset <= DAYS_AHEAD; offset++) {
            LocalDate day = today.plusDays(offset);
            if (!withinDates(rx, day)) {
                continue;
            }
            for (LocalTime time : times) {
                if (offset == 0 && !time.isAfter(nowTime)) {
                    continue;
                }
                created += jdbc.insertDose(rx, day, time, createdAt);
            }
        }
        return created;
    }

    /** Deletes the prescription's PENDING doses scheduled strictly after {@code now}; marked doses stay. */
    public int removeFuturePending(int prescriptionId, LocalDateTime now) {
        return jdbc.deleteFuturePending(prescriptionId, now);
    }

    static boolean withinDates(PrescriptionSnapshot rx, LocalDate day) {
        if (rx.prescribedDate() != null && day.isBefore(rx.prescribedDate())) {
            return false;
        }
        return rx.expiryDate() == null || !day.isAfter(rx.expiryDate());
    }
}
