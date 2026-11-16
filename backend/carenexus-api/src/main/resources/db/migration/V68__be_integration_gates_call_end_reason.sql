-- V68 (be-integration-gates): a call session that never started is closed when its appointment gets an outcome.
--
-- Marking an appointment COMPLETED or NO_SHOW (directly, or through a completed consultation) publishes
-- AppointmentCallRelevantChangeEvent.Kind.ENDED; CallSessionService ends a session that has not gone LIVE with
-- status ENDED and end_reason APPOINTMENT_ENDED and enqueues DELETE_ROOM. This widens the end_reason check of V18
-- (the inline column check is named call_sessions_end_reason_check by PostgreSQL). No existing row changes.

ALTER TABLE call_sessions DROP CONSTRAINT call_sessions_end_reason_check;

ALTER TABLE call_sessions ADD CONSTRAINT call_sessions_end_reason_check
    CHECK (end_reason IN ('DOCTOR_ENDED', 'APPOINTMENT_CANCELLED', 'TYPE_CHANGED', 'EXPIRED', 'APPOINTMENT_ENDED'));
