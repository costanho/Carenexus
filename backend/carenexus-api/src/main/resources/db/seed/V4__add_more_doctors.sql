-- ============================================================
-- Migration: Add more doctor test data
-- Version: V4
-- Description: Inserts additional doctor users and profiles
--              with various specializations
-- ============================================================

-- Temporarily allow NULL so we can insert and then populate system_id
ALTER TABLE users ALTER COLUMN system_id DROP NOT NULL;

-- Insert Additional Doctor Users
INSERT INTO users (first_name, last_name, email, phone, password_hash, role, is_active, created_at, updated_at) VALUES
  -- Cardiologist
  ('Dr. Michael', 'Kariuki', 'michael.kariuki@carenexus.com', '+1-555-0201', '$2b$10$LjqRp8fVnZhGZ4K8qL2O3Op9QZ0pWx7hK9mN2qRsT0uVwxYzAb1wO', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Pediatrician
  ('Dr. Grace', 'Kipchoge', 'grace.kipchoge@carenexus.com', '+1-555-0202', '$2b$10$OmP4vWxYzAqRsT1uVwxYzAbCdEfGhIjKlMnOpQrStUvWxYzAb2dEf', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Orthopedic Surgeon
  ('Dr. Samuel', 'Omondi', 'samuel.omondi@carenexus.com', '+1-555-0203', '$2b$10$FgHiJkLmNoPqRsT2uVwxYzAbCdEfGhIjKlMnOpQrStUvWxYzAb3cFg', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Ophthalmologist
  ('Dr. Elizabeth', 'Njeri', 'elizabeth.njeri@carenexus.com', '+1-555-0204', '$2b$10$WxYzAbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOpQrStUv', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Dermatologist
  ('Dr. David', 'Mutua', 'david.mutua@carenexus.com', '+1-555-0205', '$2b$10$QrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOp', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (email) DO NOTHING;

-- Populate system_id for newly inserted doctors
UPDATE users SET system_id = 'DOC-' || LPAD(user_id::text, 5, '0')
WHERE role = 'DOCTOR' AND system_id IS NULL;

-- Restore NOT NULL constraint
ALTER TABLE users ALTER COLUMN system_id SET NOT NULL;

-- Insert Doctor Profiles using system_id (user_id column was dropped in V3)
INSERT INTO doctors (system_id, specialization, license_no, bio, is_active, created_at) VALUES
  -- Michael Kariuki - Cardiologist
  ((SELECT system_id FROM users WHERE email = 'michael.kariuki@carenexus.com'), 'Cardiology', 'LICENSE-MK-2021-001', 'Board-certified cardiologist with 10+ years of experience in cardiac care', true, CURRENT_TIMESTAMP),

  -- Grace Kipchoge - Pediatrician
  ((SELECT system_id FROM users WHERE email = 'grace.kipchoge@carenexus.com'), 'Pediatrics', 'LICENSE-GK-2020-002', 'Specialized in child health and development', true, CURRENT_TIMESTAMP),

  -- Samuel Omondi - Orthopedic Surgeon
  ((SELECT system_id FROM users WHERE email = 'samuel.omondi@carenexus.com'), 'Orthopedic Surgery', 'LICENSE-SO-2019-003', 'Expert in bone, joint and muscle disorders', true, CURRENT_TIMESTAMP),

  -- Elizabeth Njeri - Ophthalmologist
  ((SELECT system_id FROM users WHERE email = 'elizabeth.njeri@carenexus.com'), 'Ophthalmology', 'LICENSE-EN-2022-004', 'Eye care specialist and vision correction expert', true, CURRENT_TIMESTAMP),

  -- David Mutua - Dermatologist
  ((SELECT system_id FROM users WHERE email = 'david.mutua@carenexus.com'), 'Dermatology', 'LICENSE-DM-2020-005', 'Skin disease and cosmetic specialist', true, CURRENT_TIMESTAMP)
ON CONFLICT (system_id) DO NOTHING;

-- ============================================================
-- New Test Credentials for Additional Doctors
-- ============================================================
--
-- Cardiologist:
--   Email: michael.kariuki@carenexus.com
--   Password: doctor@michael
--   Role: DOCTOR
--   Specialization: Cardiology
--
-- Pediatrician:
--   Email: grace.kipchoge@carenexus.com
--   Password: doctor@grace
--   Role: DOCTOR
--   Specialization: Pediatrics
--
-- Orthopedic Surgeon:
--   Email: samuel.omondi@carenexus.com
--   Password: doctor@samuel
--   Role: DOCTOR
--   Specialization: Orthopedic Surgery
--
-- Ophthalmologist:
--   Email: elizabeth.njeri@carenexus.com
--   Password: doctor@elizabeth
--   Role: DOCTOR
--   Specialization: Ophthalmology
--
-- Dermatologist:
--   Email: david.mutua@carenexus.com
--   Password: doctor@david
--   Role: DOCTOR
--   Specialization: Dermatology
--
-- ============================================================
