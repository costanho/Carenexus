-- ============================================================
-- Migration: Update scheduled appointment dates to future
-- Version: V7
-- Description: Moves all SCHEDULED appointments to future dates
--              so upcoming schedule endpoints return real data
-- ============================================================

UPDATE appointments SET
  scheduled_at = '2026-11-10T09:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 1;

UPDATE appointments SET
  scheduled_at = '2026-11-12T10:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 5;

UPDATE appointments SET
  scheduled_at = '2026-11-15T15:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 7;

UPDATE appointments SET
  scheduled_at = '2026-11-18T10:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 8;

UPDATE appointments SET
  scheduled_at = '2026-11-22T14:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 18;

UPDATE appointments SET
  scheduled_at = '2026-11-25T08:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 14;

UPDATE appointments SET
  scheduled_at = '2026-11-28T13:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 15;

UPDATE appointments SET
  scheduled_at = '2026-11-20T11:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 11;

UPDATE appointments SET
  scheduled_at = '2026-11-08T10:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 25;

UPDATE appointments SET
  scheduled_at = '2026-11-30T11:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 21;

UPDATE appointments SET
  scheduled_at = '2026-12-01T09:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 10;

UPDATE appointments SET
  scheduled_at = '2026-12-03T16:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 23;

UPDATE appointments SET
  scheduled_at = '2026-12-05T14:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 12;

UPDATE appointments SET
  scheduled_at = '2026-12-08T10:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 13;

UPDATE appointments SET
  scheduled_at = '2026-12-10T09:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 16;

UPDATE appointments SET
  scheduled_at = '2026-12-12T10:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 17;

UPDATE appointments SET
  scheduled_at = '2026-12-15T10:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 20;

UPDATE appointments SET
  scheduled_at = '2026-12-18T10:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 27;

UPDATE appointments SET
  scheduled_at = '2026-12-20T09:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 22;

UPDATE appointments SET
  scheduled_at = '2027-01-05T15:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 19;

UPDATE appointments SET
  scheduled_at = '2027-01-10T14:30:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 24;

UPDATE appointments SET
  scheduled_at = '2027-01-15T15:00:00', updated_at = CURRENT_TIMESTAMP
WHERE appointment_id = 26;

-- Update IN_PROGRESS consultations to match their appointment's new scheduled time
UPDATE consultations SET
  started_at = '2026-11-10T09:00:00', created_at = '2026-11-10T09:00:00'
WHERE consultation_id = 4;

UPDATE consultations SET
  started_at = '2026-11-12T10:30:00', created_at = '2026-11-12T10:30:00'
WHERE consultation_id = 5;

-- Update DRAFT consultations to match their appointment's new scheduled time
UPDATE consultations SET created_at = '2026-11-18T10:00:00' WHERE consultation_id = 6;
UPDATE consultations SET created_at = '2026-12-01T09:30:00' WHERE consultation_id = 7;
UPDATE consultations SET created_at = '2026-11-20T11:00:00' WHERE consultation_id = 8;
UPDATE consultations SET created_at = '2026-12-08T10:00:00' WHERE consultation_id = 9;
UPDATE consultations SET created_at = '2026-11-28T13:00:00' WHERE consultation_id = 10;
UPDATE consultations SET created_at = '2026-12-15T10:30:00' WHERE consultation_id = 11;
UPDATE consultations SET created_at = '2026-12-18T10:00:00' WHERE consultation_id = 12;
