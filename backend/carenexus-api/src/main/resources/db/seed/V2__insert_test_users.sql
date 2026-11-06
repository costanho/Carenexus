-- ============================================================
-- CareNexus Test Users - Development/Testing Only
-- ============================================================

-- Insert Test Users
INSERT INTO users (first_name, last_name, email, phone, password_hash, role, is_active, created_at, updated_at) VALUES
  -- Patient test user
  ('Linda', 'Moyo', 'linda.moyo@carenexus.com', '+1-555-0101', '$2b$10$GC24Dy1u5cDmzMLY0QPVbOfcREu2NbJEelAbyUjfHDCklqm4pMXom', 'PATIENT', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Doctor test user
  ('James', 'Banda', 'james.banda@carenexus.com', '+1-555-0102', '$2b$10$KFGgid72AwXdTFlvU/.nje3.G0eRo7ZQ9rbW7g5Hl/JSqo/H8b2Pe', 'DOCTOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Caregiver/Proxy test user
  ('Sarah', 'Dube', 'sarah.dube@carenexus.com', '+1-555-0103', '$2b$10$XT8Ku.QbMgPyBSh.SDzeHuDFF.b83NLI6UPBg3oelQ/jFhINptiXu', 'CAREGIVER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

  -- Admin test user
  ('Admin', 'User', 'admin@carenexus.com', '+1-555-0104', '$2b$10$WpZdSmf53alPwAbmSqH8euxFQ0IZ4fiAvYopkUK0vH9iwM.KePlFe', 'ADMIN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (email) DO NOTHING;

-- Insert Patient Profile for Linda Moyo
INSERT INTO patients (user_id, date_of_birth, gender, blood_type, health_status, created_at)
SELECT user_id, '1985-03-15'::DATE, 'FEMALE', 'O+', 'STABLE', CURRENT_TIMESTAMP
FROM users WHERE email = 'linda.moyo@carenexus.com'
ON CONFLICT DO NOTHING;

-- Insert Doctor Profile for James Banda
INSERT INTO doctors (user_id, specialization, license_no, is_active, created_at)
SELECT user_id, 'General Practitioner', 'LICENSE-JB-2023-001', true, CURRENT_TIMESTAMP
FROM users WHERE email = 'james.banda@carenexus.com'
ON CONFLICT (user_id) DO NOTHING;

-- Insert Caregiver Profile for Sarah Dube
INSERT INTO caregivers (user_id, relationship, phone, created_at)
SELECT user_id, 'Daughter', '+1-555-0103', CURRENT_TIMESTAMP
FROM users WHERE email = 'sarah.dube@carenexus.com'
ON CONFLICT (user_id) DO NOTHING;

-- Insert Emergency Contact for Linda Moyo (Sarah Dube as primary caregiver)
INSERT INTO emergency_contacts (patient_id, name, relationship, phone, email, is_primary, is_caregiver, created_at)
SELECT p.patient_id, 'Sarah Dube', 'Daughter', '+1-555-0103', 'sarah.dube@carenexus.com', true, true, CURRENT_TIMESTAMP
FROM patients p
JOIN users u ON p.user_id = u.user_id
WHERE u.email = 'linda.moyo@carenexus.com'
ON CONFLICT DO NOTHING;

-- ============================================================
-- Test Credentials for Frontend Testing
-- ============================================================
--
-- Patient:
--   Email: linda.moyo@carenexus.com
--   Password: patient@123
--   Role: PATIENT
--
-- Doctor:
--   Email: james.banda@carenexus.com
--   Password: doctor@banda
--   Role: DOCTOR
--
-- Proxy/Caregiver:
--   Email: sarah.dube@carenexus.com
--   Password: proxy@sarah
--   Role: CAREGIVER
--
-- Admin:
--   Email: admin@carenexus.com
--   Password: admin@nexus
--   Role: ADMIN
-- ============================================================
