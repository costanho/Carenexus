-- ============================================================
-- Migration: Add medication schedules test data
-- Version: V5
-- Description: Inserts medication prescriptions and their
--              daily schedules for testing
-- ============================================================

-- First, insert some test prescriptions for Linda Moyo (patient)
INSERT INTO prescriptions (patient_system_id, doctor_system_id, consultation_id, medication_name, generic_name, dosage, frequency, route, quantity, refills_allowed, special_instructions, status, prescribed_date, expiry_date, created_at)
SELECT
  p.system_id,
  d.system_id,
  NULL,
  'Amoxicillin',
  'Amoxicillin Trihydrate',
  '500mg',
  'Three times daily',
  'ORAL',
  '30 tablets',
  2,
  'Take with or without food',
  'ACTIVE',
  CURRENT_DATE,
  (CURRENT_DATE + INTERVAL '30 days'),
  CURRENT_TIMESTAMP
FROM patients p
JOIN users u ON p.system_id = u.system_id
JOIN doctors d ON d.system_id = (SELECT system_id FROM users WHERE email = 'james.banda@carenexus.com')
WHERE u.email = 'linda.moyo@carenexus.com'
ON CONFLICT DO NOTHING;

INSERT INTO prescriptions (patient_system_id, doctor_system_id, consultation_id, medication_name, generic_name, dosage, frequency, route, quantity, refills_allowed, special_instructions, status, prescribed_date, expiry_date, created_at)
SELECT
  p.system_id,
  d.system_id,
  NULL,
  'Lisinopril',
  'Lisinopril Dihydrate',
  '10mg',
  'Once daily',
  'ORAL',
  '30 tablets',
  3,
  'Take in the morning',
  'ACTIVE',
  CURRENT_DATE,
  (CURRENT_DATE + INTERVAL '90 days'),
  CURRENT_TIMESTAMP
FROM patients p
JOIN users u ON p.system_id = u.system_id
JOIN doctors d ON d.system_id = (SELECT system_id FROM users WHERE email = 'james.banda@carenexus.com')
WHERE u.email = 'linda.moyo@carenexus.com'
ON CONFLICT DO NOTHING;

INSERT INTO prescriptions (patient_system_id, doctor_system_id, consultation_id, medication_name, generic_name, dosage, frequency, route, quantity, refills_allowed, special_instructions, status, prescribed_date, expiry_date, created_at)
SELECT
  p.system_id,
  d.system_id,
  NULL,
  'Metformin',
  'Metformin Hydrochloride',
  '850mg',
  'Twice daily',
  'ORAL',
  '60 tablets',
  2,
  'Take with meals',
  'ACTIVE',
  CURRENT_DATE,
  (CURRENT_DATE + INTERVAL '60 days'),
  CURRENT_TIMESTAMP
FROM patients p
JOIN users u ON p.system_id = u.system_id
JOIN doctors d ON d.system_id = (SELECT system_id FROM users WHERE email = 'james.banda@carenexus.com')
WHERE u.email = 'linda.moyo@carenexus.com'
ON CONFLICT DO NOTHING;

-- Now insert medication schedules for today and next 7 days
INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '08:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Morning dose',
  CURRENT_DATE,
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
ON CONFLICT DO NOTHING;

INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '14:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Afternoon dose',
  CURRENT_DATE,
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
  AND rx.frequency LIKE '%Three%'
ON CONFLICT DO NOTHING;

INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '20:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Evening dose',
  CURRENT_DATE,
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
  AND rx.frequency LIKE '%Three%'
ON CONFLICT DO NOTHING;

-- Add schedules for next 7 days (once daily medications)
INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '08:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Morning dose',
  (CURRENT_DATE + day_offset),
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id,
GENERATE_SERIES(1, 7) AS day_offset
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
  AND rx.frequency LIKE '%Once%'
ON CONFLICT DO NOTHING;

-- Add schedules for next 7 days (twice daily medications)
INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '08:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Morning dose',
  (CURRENT_DATE + day_offset),
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id,
GENERATE_SERIES(1, 7) AS day_offset
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
  AND rx.frequency LIKE '%Twice%'
ON CONFLICT DO NOTHING;

INSERT INTO medication_schedules (prescription_id, patient_id, scheduled_time, medication_name, dosage, is_taken, taken_at, missed, notes, schedule_date, created_at)
SELECT
  rx.prescription_id,
  pat.patient_id,
  '20:00'::TIME,
  rx.medication_name,
  rx.dosage,
  false,
  NULL,
  false,
  'Evening dose',
  (CURRENT_DATE + day_offset),
  CURRENT_TIMESTAMP
FROM prescriptions rx
JOIN patients pat ON pat.system_id = rx.patient_system_id,
GENERATE_SERIES(1, 7) AS day_offset
WHERE rx.patient_system_id = (SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')
  AND rx.status = 'ACTIVE'
  AND rx.frequency LIKE '%Twice%'
ON CONFLICT DO NOTHING;

-- ============================================================
-- Test Data Summary
-- ============================================================
-- Patient: Linda Moyo (linda.moyo@carenexus.com)
--
-- Prescriptions added:
-- 1. Amoxicillin 500mg - 3x daily (08:00, 14:00, 20:00)
-- 2. Lisinopril 10mg - Once daily (08:00)
-- 3. Metformin 850mg - Twice daily (08:00, 20:00)
--
-- Schedules: Created for today + next 7 days
-- ============================================================
