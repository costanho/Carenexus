-- V13: database safety net against double booking, plus sane appointment durations.
--
-- The application already serializes bookings per doctor and per patient (pg_advisory_xact_lock) and rejects
-- overlapping SCHEDULED/RESCHEDULED appointments in AppointmentService. These indexes guarantee that, even if
-- some other code path or a manual insert skips that check, two ACTIVE appointments can never start at the
-- same moment for the same doctor (or for the same patient). Cancelled / completed / no-show rows are not
-- constrained, so a cancelled slot can be booked again.
--
-- Full interval-overlap protection would need an exclusion constraint with btree_gist, which requires
-- extension privileges that managed Postgres instances do not always grant; the service-level overlap check
-- covers partial overlaps.
--
-- Existing data: the indexes / CHECK are only created when the current rows satisfy them (the dev seed does).
-- If a database already contains conflicting active rows, the migration does not fail; it skips that object
-- and raises a NOTICE so the rows can be cleaned up and the object created by hand.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM appointments
        WHERE status IN ('SCHEDULED', 'RESCHEDULED')
        GROUP BY doctor_system_id, scheduled_at
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V13: skipped ux_appointments_doctor_active_slot: a doctor has several active appointments at the same time';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_doctor_active_slot
            ON appointments (doctor_system_id, scheduled_at)
            WHERE status IN ('SCHEDULED', 'RESCHEDULED');
    END IF;

    IF EXISTS (
        SELECT 1 FROM appointments
        WHERE status IN ('SCHEDULED', 'RESCHEDULED')
        GROUP BY patient_system_id, scheduled_at
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V13: skipped ux_appointments_patient_active_slot: a patient has several active appointments at the same time';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_patient_active_slot
            ON appointments (patient_system_id, scheduled_at)
            WHERE status IN ('SCHEDULED', 'RESCHEDULED');
    END IF;

    -- Durations: the API accepts 5..240 minutes; the DB rejects nonsense (0, negative, more than a day).
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'appointments_duration_minutes_check'
    ) THEN
        ALTER TABLE appointments
            ADD CONSTRAINT appointments_duration_minutes_check
            CHECK (duration_minutes > 0 AND duration_minutes <= 1440) NOT VALID;
        IF NOT EXISTS (
            SELECT 1 FROM appointments WHERE duration_minutes <= 0 OR duration_minutes > 1440
        ) THEN
            ALTER TABLE appointments VALIDATE CONSTRAINT appointments_duration_minutes_check;
        ELSE
            RAISE NOTICE 'V13: appointments_duration_minutes_check left NOT VALID: existing rows have invalid durations';
        END IF;
    END IF;
END
$$;
