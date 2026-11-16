package com.carenexus.api.common.spi;

/**
 * Extension point of appointment booking. AppointmentService calls every {@code BookingConstraint} bean inside the
 * booking transaction (doctor and patient advisory locks already held), so other modules can add rules such as
 * doctor availability, time off or facility hours without editing the booking service.
 */
public interface BookingConstraint {

    /**
     * Called inside the booking transaction after the built-in checks, for create, reschedule and edit.
     * Throw BadRequestException / ConflictException.
     */
    void check(BookingRequestContext ctx);
}
