package com.carenexus.api.delivery.fanout;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The recipient's quiet window, evaluated in the recipient's own time zone.
 *
 * <ul>
 *   <li>No window when quiet hours are off, a bound is missing, or start equals end.</li>
 *   <li>Start is inclusive, end is exclusive; a window whose start is after its end crosses midnight.</li>
 *   <li>The zone is {@code user_settings.timezone} when it is a valid zone id, otherwise the clinic zone.</li>
 *   <li>Inside the window a message is held until the end of the window, returned as clinic wall-clock time
 *       (the form {@code OutboundRequest.notBefore} takes).</li>
 * </ul>
 *
 * Priority is not decided here: the caller never asks for CRITICAL messages.
 *
 * @param enabled  {@code user_settings.quiet_hours_enabled}
 * @param start    {@code user_settings.quiet_hours_start}, recipient wall-clock
 * @param end      {@code user_settings.quiet_hours_end}, recipient wall-clock
 * @param timezone {@code user_settings.timezone} as stored (may be null, blank or invalid)
 */
public record QuietHours(boolean enabled, LocalTime start, LocalTime end, String timezone) {

    /** No quiet window (the defaults when the recipient has no settings row). */
    public static final QuietHours NONE = new QuietHours(false, null, null, null);

    /** True when the settings describe an actual window. */
    public boolean hasWindow() {
        return enabled && start != null && end != null && !start.equals(end);
    }

    /** The zone the window is read in: the recipient's valid zone, else {@code clinicZone}. */
    public ZoneId zone(ZoneId clinicZone) {
        if (timezone == null || timezone.isBlank()) {
            return clinicZone;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            return clinicZone;
        }
    }

    /**
     * When a non-critical message raised at {@code now} may be sent.
     *
     * @return the end of the quiet window as clinic wall-clock time, or null when {@code now} is outside the window
     *         (or there is no window): the message is due at once
     */
    public LocalDateTime deferUntil(Instant now, ZoneId clinicZone) {
        if (!hasWindow()) {
            return null;
        }
        ZoneId zone = zone(clinicZone);
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        LocalDate date = local.toLocalDate();

        boolean inside = start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
        if (!inside) {
            return null;
        }
        LocalDate endDate = time.isBefore(end) ? date : date.plusDays(1);
        // ZonedDateTime.of moves a time inside a daylight-saving gap forward.
        ZonedDateTime windowEnd = ZonedDateTime.of(endDate, end, zone);
        return windowEnd.withZoneSameInstant(clinicZone).toLocalDateTime();
    }
}
