package com.carenexus.api.scheduling.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The clinic booking rules of {@code carenexus.scheduling} as the scheduling module reads them: the same properties
 * (and defaults) AppointmentService enforces and {@code GET /api/appointments/booking-rules} reports.
 *
 * {@code closeHour} is the hour by which a patient-side appointment must END; 24 means the end of the day.
 * The current clinic time comes from the application {@link Clock}, so tests can fix it.
 */
@Component
public class ClinicSchedule {

    /** Slot length used when a doctor has no template (working hours = clinic hours). */
    public static final int CLINIC_HOURS_SLOT_MINUTES = 30;

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final Clock clock;
    private final ZoneId zone;
    private final int openHour;
    private final int closeHour;
    private final boolean allowWeekends;
    private final int maxAdvanceDays;

    public ClinicSchedule(Clock clock,
                          @Value("${carenexus.scheduling.time-zone:Africa/Harare}") String timeZone,
                          @Value("${carenexus.scheduling.open-hour:8}") int openHour,
                          @Value("${carenexus.scheduling.close-hour:18}") int closeHour,
                          @Value("${carenexus.scheduling.allow-weekends:true}") boolean allowWeekends,
                          @Value("${carenexus.scheduling.max-advance-days:365}") int maxAdvanceDays) {
        this.clock = clock;
        this.zone = parseZone(timeZone);
        this.openHour = Math.max(0, Math.min(openHour, 24));
        this.closeHour = Math.max(0, Math.min(closeHour, 24));
        this.allowWeekends = allowWeekends;
        this.maxAdvanceDays = Math.max(0, maxAdvanceDays);
    }

    public ZoneId zone() {
        return zone;
    }

    public int openHour() {
        return openHour;
    }

    public int closeHour() {
        return closeHour;
    }

    public boolean allowWeekends() {
        return allowWeekends;
    }

    public int maxAdvanceDays() {
        return maxAdvanceDays;
    }

    /** Clinic opening as minutes after midnight. */
    public int openMinute() {
        return openHour * 60;
    }

    /** Clinic closing as minutes after midnight (1440 when {@code closeHour} is 24). */
    public int closeMinute() {
        return Math.min(closeHour * 60, MINUTES_PER_DAY);
    }

    /** True when the clinic takes patient bookings on this day of the week. */
    public boolean isOpenOn(DayOfWeek day) {
        return allowWeekends || (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY);
    }

    public boolean isOpenOn(LocalDate date) {
        return isOpenOn(date.getDayOfWeek());
    }

    /** Current clinic wall-clock time. */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), zone);
    }

    /** Same fallback as AppointmentService: an unusable zone id means the JVM zone (pinned to the clinic at start). */
    private static ZoneId parseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
