-- ============================================================
-- V36: doctor availability (be-availability)
--   doctor_availability: a doctor's weekly working-hours template, one row per block (ISO weekday 1 = Monday ...
--     7 = Sunday, clinic wall-clock start and exclusive end). PUT /api/doctors/availability replaces all rows of a
--     doctor at once and writes the same slot_minutes to every row. A doctor without rows books against clinic hours.
--   doctor_time_off: ranges in which a doctor does not see patients ([starts_at, ends_at), clinic wall-clock).
-- References only users (V1/V3). Written defensively (IF NOT EXISTS). Nothing is dropped. Ships no rows.
-- ============================================================

CREATE TABLE IF NOT EXISTS doctor_availability (
  availability_id  SERIAL PRIMARY KEY,
  doctor_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  day_of_week      INTEGER     NOT NULL,
  start_time       TIME        NOT NULL,
  end_time         TIME        NOT NULL,
  slot_minutes     INTEGER     NOT NULL,
  is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT doctor_availability_day_of_week_check CHECK (day_of_week BETWEEN 1 AND 7),
  CONSTRAINT doctor_availability_slot_minutes_check CHECK (slot_minutes BETWEEN 5 AND 240 AND slot_minutes % 5 = 0),
  CONSTRAINT doctor_availability_times_check CHECK (end_time > start_time),
  CONSTRAINT ux_doctor_availability_doctor_day_start UNIQUE (doctor_system_id, day_of_week, start_time)
);

COMMENT ON TABLE doctor_availability IS 'Weekly working-hours template of a doctor (DoctorAvailabilityService); no rows = clinic hours apply';
COMMENT ON COLUMN doctor_availability.day_of_week  IS 'ISO weekday: 1 = Monday ... 7 = Sunday';
COMMENT ON COLUMN doctor_availability.start_time   IS 'First bookable minute of the block (clinic wall-clock)';
COMMENT ON COLUMN doctor_availability.end_time     IS 'Exclusive end of the block: an appointment must end by it (clinic wall-clock)';
COMMENT ON COLUMN doctor_availability.slot_minutes IS 'Distance between offered start times; the same value on every row of one doctor';
COMMENT ON COLUMN doctor_availability.is_active    IS 'FALSE keeps the block without offering it to patients';

CREATE TABLE IF NOT EXISTS doctor_time_off (
  time_off_id      SERIAL PRIMARY KEY,
  doctor_system_id VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  starts_at        TIMESTAMP    NOT NULL,
  ends_at          TIMESTAMP    NOT NULL,
  reason           VARCHAR(200),
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT doctor_time_off_range_check CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS idx_doctor_time_off_doctor_ends ON doctor_time_off (doctor_system_id, ends_at);

COMMENT ON TABLE doctor_time_off IS 'Ranges in which a doctor takes no patient bookings; existing appointments are not changed';
COMMENT ON COLUMN doctor_time_off.starts_at IS 'Inclusive start (clinic wall-clock, whole minutes)';
COMMENT ON COLUMN doctor_time_off.ends_at   IS 'Exclusive end (clinic wall-clock, whole minutes)';
COMMENT ON COLUMN doctor_time_off.reason    IS 'Private note of the doctor; never shown to patients or caregivers';
