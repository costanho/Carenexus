-- ============================================================
-- Migration: Add system_id as universal unique identifier
-- Version: V3
-- Description: Adds system_id to all tables to replace user_id
--              as the primary reference across the system
-- ============================================================

-- STEP 0: Drop dependent views
DROP VIEW IF EXISTS v_doctor_workload CASCADE;
DROP VIEW IF EXISTS v_patient_dashboard CASCADE;

-- STEP 1: Add system_id column to users table
ALTER TABLE users ADD COLUMN system_id VARCHAR(20) UNIQUE;

-- STEP 2: Populate system_id based on role and user_id
UPDATE users SET system_id = 'PAT-' || LPAD(user_id::text, 5, '0')
WHERE role = 'PATIENT';

UPDATE users SET system_id = 'DOC-' || LPAD(user_id::text, 5, '0')
WHERE role = 'DOCTOR';

UPDATE users SET system_id = 'CGV-' || LPAD(user_id::text, 5, '0')
WHERE role = 'CAREGIVER';

UPDATE users SET system_id = 'ADM-' || LPAD(user_id::text, 5, '0')
WHERE role = 'ADMIN';

-- STEP 3: Make system_id NOT NULL
ALTER TABLE users ALTER COLUMN system_id SET NOT NULL;

-- ============================================================
-- SECTION 1: Update DOCTORS table
-- ============================================================
-- Drop foreign key and unique constraint on user_id
ALTER TABLE doctors DROP CONSTRAINT doctors_user_id_key;
ALTER TABLE doctors DROP CONSTRAINT doctors_user_id_fkey;

-- Add system_id column
ALTER TABLE doctors ADD COLUMN system_id VARCHAR(20) UNIQUE;

-- Populate system_id from users table
UPDATE doctors d SET system_id = u.system_id
FROM users u WHERE u.user_id = d.user_id;

-- Drop old user_id column
ALTER TABLE doctors DROP COLUMN user_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE doctors ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE doctors ADD CONSTRAINT doctors_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- ============================================================
-- SECTION 2: Update PATIENTS table
-- ============================================================
-- Drop foreign key and unique constraint on user_id
ALTER TABLE patients DROP CONSTRAINT patients_user_id_key;
ALTER TABLE patients DROP CONSTRAINT patients_user_id_fkey;

-- Add system_id column
ALTER TABLE patients ADD COLUMN system_id VARCHAR(20) UNIQUE;

-- Populate system_id from users table
UPDATE patients p SET system_id = u.system_id
FROM users u WHERE u.user_id = p.user_id;

-- Drop old user_id column
ALTER TABLE patients DROP COLUMN user_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE patients ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE patients ADD CONSTRAINT patients_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- ============================================================
-- SECTION 3: Update CAREGIVERS table
-- ============================================================
-- Drop foreign key and unique constraint on user_id
ALTER TABLE caregivers DROP CONSTRAINT caregivers_user_id_key;
ALTER TABLE caregivers DROP CONSTRAINT caregivers_user_id_fkey;

-- Add system_id column
ALTER TABLE caregivers ADD COLUMN system_id VARCHAR(20) UNIQUE;

-- Populate system_id from users table
UPDATE caregivers c SET system_id = u.system_id
FROM users u WHERE u.user_id = c.user_id;

-- Drop old user_id column
ALTER TABLE caregivers DROP COLUMN user_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE caregivers ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE caregivers ADD CONSTRAINT caregivers_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- ============================================================
-- SECTION 4: Update REFRESH_TOKENS table
-- ============================================================
-- Drop foreign key and add system_id
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_user_id_fkey;

-- Add system_id column
ALTER TABLE refresh_tokens ADD COLUMN system_id VARCHAR(20);

-- Populate system_id from users table
UPDATE refresh_tokens rt SET system_id = u.system_id
FROM users u WHERE u.user_id = rt.user_id;

-- Drop old user_id column
ALTER TABLE refresh_tokens DROP COLUMN user_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE refresh_tokens ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- ============================================================
-- SECTION 5: Update APPOINTMENTS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE appointments DROP CONSTRAINT appointments_patient_id_fkey;
ALTER TABLE appointments DROP CONSTRAINT appointments_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE appointments ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE appointments ADD COLUMN doctor_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE appointments a SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =a.patient_id;

UPDATE appointments a SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =a.doctor_id;

-- Drop old columns
ALTER TABLE appointments DROP COLUMN patient_id;
ALTER TABLE appointments DROP COLUMN doctor_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE appointments ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE appointments ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE appointments ADD CONSTRAINT appointments_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE appointments ADD CONSTRAINT appointments_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_appt_patient;
DROP INDEX IF EXISTS idx_appt_doctor;
CREATE INDEX idx_appointments_patient ON appointments(patient_system_id);
CREATE INDEX idx_appointments_doctor ON appointments(doctor_system_id);

-- ============================================================
-- SECTION 6: Update CONSULTATIONS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE consultations DROP CONSTRAINT consultations_patient_id_fkey;
ALTER TABLE consultations DROP CONSTRAINT consultations_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE consultations ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE consultations ADD COLUMN doctor_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE consultations c SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =c.patient_id;

UPDATE consultations c SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =c.doctor_id;

-- Drop old columns
ALTER TABLE consultations DROP COLUMN patient_id;
ALTER TABLE consultations DROP COLUMN doctor_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE consultations ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE consultations ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE consultations ADD CONSTRAINT consultations_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE consultations ADD CONSTRAINT consultations_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_consult_patient;
DROP INDEX IF EXISTS idx_consult_doctor;
CREATE INDEX idx_consultations_patient ON consultations(patient_system_id);
CREATE INDEX idx_consultations_doctor ON consultations(doctor_system_id);

-- ============================================================
-- SECTION 7: Update CARE_TEAM table
-- ============================================================
-- Drop foreign keys
ALTER TABLE care_team DROP CONSTRAINT care_team_patient_id_fkey;
ALTER TABLE care_team DROP CONSTRAINT care_team_provider_id_fkey;

-- Add system_id columns
ALTER TABLE care_team ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE care_team ADD COLUMN provider_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE care_team ct SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =ct.patient_id;

UPDATE care_team ct SET provider_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =ct.provider_id;

-- Drop old columns
ALTER TABLE care_team DROP COLUMN patient_id;
ALTER TABLE care_team DROP COLUMN provider_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE care_team ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE care_team ALTER COLUMN provider_system_id SET NOT NULL;
ALTER TABLE care_team ADD CONSTRAINT care_team_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE care_team ADD CONSTRAINT care_team_provider_system_id_fkey
  FOREIGN KEY (provider_system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- Update indices
DROP INDEX IF EXISTS idx_ct_patient;
DROP INDEX IF EXISTS idx_ct_provider;
CREATE INDEX idx_care_team_patient ON care_team(patient_system_id);
CREATE INDEX idx_care_team_provider ON care_team(provider_system_id);

-- ============================================================
-- SECTION 8: Update MEDICAL_RECORDS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE medical_records DROP CONSTRAINT medical_records_patient_id_fkey;
ALTER TABLE medical_records DROP CONSTRAINT medical_records_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE medical_records ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE medical_records ADD COLUMN doctor_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE medical_records mr SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =mr.patient_id;

UPDATE medical_records mr SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =mr.doctor_id;

-- Drop old columns
ALTER TABLE medical_records DROP COLUMN patient_id;
ALTER TABLE medical_records DROP COLUMN doctor_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE medical_records ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE medical_records ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE medical_records ADD CONSTRAINT medical_records_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE medical_records ADD CONSTRAINT medical_records_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_mr_patient;
DROP INDEX IF EXISTS idx_mr_doctor;
CREATE INDEX idx_medical_records_patient ON medical_records(patient_system_id);
CREATE INDEX idx_medical_records_doctor ON medical_records(doctor_system_id);

-- ============================================================
-- SECTION 9: Update PRESCRIPTIONS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE prescriptions DROP CONSTRAINT prescriptions_patient_id_fkey;
ALTER TABLE prescriptions DROP CONSTRAINT prescriptions_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE prescriptions ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE prescriptions ADD COLUMN doctor_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE prescriptions rx SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =rx.patient_id;

UPDATE prescriptions rx SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =rx.doctor_id;

-- Drop old columns
ALTER TABLE prescriptions DROP COLUMN patient_id;
ALTER TABLE prescriptions DROP COLUMN doctor_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE prescriptions ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE prescriptions ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_rx_patient;
DROP INDEX IF EXISTS idx_rx_doctor;
CREATE INDEX idx_prescriptions_patient ON prescriptions(patient_system_id);
CREATE INDEX idx_prescriptions_doctor ON prescriptions(doctor_system_id);

-- ============================================================
-- SECTION 10: Update LAB_RESULTS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE lab_results DROP CONSTRAINT lab_results_patient_id_fkey;
ALTER TABLE lab_results DROP CONSTRAINT lab_results_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE lab_results ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE lab_results ADD COLUMN doctor_system_id VARCHAR(20);
ALTER TABLE lab_results ADD COLUMN reviewed_by_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE lab_results lr SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =lr.patient_id;

UPDATE lab_results lr SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =lr.doctor_id;

UPDATE lab_results lr SET reviewed_by_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =lr.reviewed_by;

-- Drop old columns
ALTER TABLE lab_results DROP COLUMN patient_id;
ALTER TABLE lab_results DROP COLUMN doctor_id;
ALTER TABLE lab_results DROP COLUMN reviewed_by;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE lab_results ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE lab_results ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE lab_results ADD CONSTRAINT lab_results_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE lab_results ADD CONSTRAINT lab_results_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE lab_results ADD CONSTRAINT lab_results_reviewed_by_system_id_fkey
  FOREIGN KEY (reviewed_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update indices
DROP INDEX IF EXISTS idx_lab_patient;
DROP INDEX IF EXISTS idx_lab_doctor;
CREATE INDEX idx_lab_results_patient ON lab_results(patient_system_id);
CREATE INDEX idx_lab_results_doctor ON lab_results(doctor_system_id);

-- ============================================================
-- SECTION 11: Update IMAGING_RESULTS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE imaging_results DROP CONSTRAINT imaging_results_patient_id_fkey;
ALTER TABLE imaging_results DROP CONSTRAINT imaging_results_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE imaging_results ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE imaging_results ADD COLUMN doctor_system_id VARCHAR(20);
ALTER TABLE imaging_results ADD COLUMN reviewed_by_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE imaging_results ir SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =ir.patient_id;

UPDATE imaging_results ir SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =ir.doctor_id;

UPDATE imaging_results ir SET reviewed_by_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =ir.reviewed_by;

-- Drop old columns
ALTER TABLE imaging_results DROP COLUMN patient_id;
ALTER TABLE imaging_results DROP COLUMN doctor_id;
ALTER TABLE imaging_results DROP COLUMN reviewed_by;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE imaging_results ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE imaging_results ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE imaging_results ADD CONSTRAINT imaging_results_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE imaging_results ADD CONSTRAINT imaging_results_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE imaging_results ADD CONSTRAINT imaging_results_reviewed_by_system_id_fkey
  FOREIGN KEY (reviewed_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update indices
DROP INDEX IF EXISTS idx_img_patient;
DROP INDEX IF EXISTS idx_img_doctor;
CREATE INDEX idx_imaging_results_patient ON imaging_results(patient_system_id);
CREATE INDEX idx_imaging_results_doctor ON imaging_results(doctor_system_id);

-- ============================================================
-- SECTION 12: Update REFERRALS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE referrals DROP CONSTRAINT referrals_patient_id_fkey;
ALTER TABLE referrals DROP CONSTRAINT referrals_referring_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE referrals ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE referrals ADD COLUMN referring_doctor_system_id VARCHAR(20);
ALTER TABLE referrals ADD COLUMN specialist_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE referrals r SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =r.patient_id;

UPDATE referrals r SET referring_doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =r.referring_doctor_id;

UPDATE referrals r SET specialist_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =r.specialist_id;

-- Drop old columns
ALTER TABLE referrals DROP COLUMN patient_id;
ALTER TABLE referrals DROP COLUMN referring_doctor_id;
ALTER TABLE referrals DROP COLUMN specialist_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE referrals ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE referrals ALTER COLUMN referring_doctor_system_id SET NOT NULL;
ALTER TABLE referrals ADD CONSTRAINT referrals_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE referrals ADD CONSTRAINT referrals_referring_doctor_system_id_fkey
  FOREIGN KEY (referring_doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE referrals ADD CONSTRAINT referrals_specialist_system_id_fkey
  FOREIGN KEY (specialist_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update indices
DROP INDEX IF EXISTS idx_ref_patient;
DROP INDEX IF EXISTS idx_ref_dr;
DROP INDEX IF EXISTS idx_ref_sp;
CREATE INDEX idx_referrals_patient ON referrals(patient_system_id);
CREATE INDEX idx_referrals_doctor ON referrals(referring_doctor_system_id);
CREATE INDEX idx_referrals_specialist ON referrals(specialist_system_id);

-- ============================================================
-- SECTION 13: Update DEPENDENT_ACCESS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE dependent_access DROP CONSTRAINT dependent_access_caregiver_id_fkey;
ALTER TABLE dependent_access DROP CONSTRAINT dependent_access_patient_id_fkey;

-- Add system_id columns
ALTER TABLE dependent_access ADD COLUMN caregiver_system_id VARCHAR(20);
ALTER TABLE dependent_access ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE dependent_access ADD COLUMN authorized_by_system_id VARCHAR(20);

-- Populate from caregivers, patients and users tables
UPDATE dependent_access da SET caregiver_system_id = c.system_id
FROM caregivers c WHERE c.caregiver_id =da.caregiver_id;

UPDATE dependent_access da SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =da.patient_id;

UPDATE dependent_access da SET authorized_by_system_id = u.system_id
FROM users u WHERE u.user_id = da.authorized_by;

-- Drop old columns
ALTER TABLE dependent_access DROP COLUMN caregiver_id;
ALTER TABLE dependent_access DROP COLUMN patient_id;
ALTER TABLE dependent_access DROP COLUMN authorized_by;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE dependent_access ALTER COLUMN caregiver_system_id SET NOT NULL;
ALTER TABLE dependent_access ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_caregiver_system_id_fkey
  FOREIGN KEY (caregiver_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_authorized_by_system_id_fkey
  FOREIGN KEY (authorized_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update unique constraint
ALTER TABLE dependent_access DROP CONSTRAINT IF EXISTS dependent_access_caregiver_id_patient_id_key;
ALTER TABLE dependent_access ADD UNIQUE(caregiver_system_id, patient_system_id);

-- Update indices
DROP INDEX IF EXISTS idx_da_patient;
DROP INDEX IF EXISTS idx_da_caregiver;
CREATE INDEX idx_dependent_access_patient ON dependent_access(patient_system_id);
CREATE INDEX idx_dependent_access_caregiver ON dependent_access(caregiver_system_id);

-- ============================================================
-- SECTION 14: Update EMERGENCY_CONTACTS table
-- ============================================================
-- Drop foreign key
ALTER TABLE emergency_contacts DROP CONSTRAINT emergency_contacts_patient_id_fkey;

-- Add system_id column
ALTER TABLE emergency_contacts ADD COLUMN patient_system_id VARCHAR(20);

-- Populate from patients table
UPDATE emergency_contacts ec SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =ec.patient_id;

-- Drop old column
ALTER TABLE emergency_contacts DROP COLUMN patient_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE emergency_contacts ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE emergency_contacts ADD CONSTRAINT emergency_contacts_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- Update indices
DROP INDEX IF EXISTS idx_emg_patient;
CREATE INDEX idx_emergency_contacts_patient ON emergency_contacts(patient_system_id);

-- ============================================================
-- SECTION 15: Update CARE_PLANS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE care_plans DROP CONSTRAINT care_plans_patient_id_fkey;
ALTER TABLE care_plans DROP CONSTRAINT care_plans_doctor_id_fkey;

-- Add system_id columns
ALTER TABLE care_plans ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE care_plans ADD COLUMN doctor_system_id VARCHAR(20);

-- Populate from patients and doctors tables
UPDATE care_plans cp SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =cp.patient_id;

UPDATE care_plans cp SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =cp.doctor_id;

-- Drop old columns
ALTER TABLE care_plans DROP COLUMN patient_id;
ALTER TABLE care_plans DROP COLUMN doctor_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE care_plans ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE care_plans ALTER COLUMN doctor_system_id SET NOT NULL;
ALTER TABLE care_plans ADD CONSTRAINT care_plans_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE care_plans ADD CONSTRAINT care_plans_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_cp_patient;
DROP INDEX IF EXISTS idx_cp_doctor;
CREATE INDEX idx_care_plans_patient ON care_plans(patient_system_id);
CREATE INDEX idx_care_plans_doctor ON care_plans(doctor_system_id);

-- ============================================================
-- SECTION 16: Update DOCUMENTS table
-- ============================================================
-- Drop foreign keys
ALTER TABLE documents DROP CONSTRAINT documents_patient_id_fkey;
ALTER TABLE documents DROP CONSTRAINT documents_uploaded_by_fkey;

-- Add system_id columns
ALTER TABLE documents ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE documents ADD COLUMN uploaded_by_system_id VARCHAR(20);

-- Populate from patients and users tables
UPDATE documents doc SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =doc.patient_id;

UPDATE documents doc SET uploaded_by_system_id = u.system_id
FROM users u WHERE u.user_id = doc.uploaded_by;

-- Drop old columns
ALTER TABLE documents DROP COLUMN patient_id;
ALTER TABLE documents DROP COLUMN uploaded_by;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE documents ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE documents ALTER COLUMN uploaded_by_system_id SET NOT NULL;
ALTER TABLE documents ADD CONSTRAINT documents_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE documents ADD CONSTRAINT documents_uploaded_by_system_id_fkey
  FOREIGN KEY (uploaded_by_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_doc_patient;
DROP INDEX IF EXISTS idx_doc_uploader;
CREATE INDEX idx_documents_patient ON documents(patient_system_id);
CREATE INDEX idx_documents_uploader ON documents(uploaded_by_system_id);

-- ============================================================
-- SECTION 17: Update AUDIT_LOG table
-- ============================================================
-- Drop foreign key if exists
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_user_id_fkey;

-- Add system_id column
ALTER TABLE audit_log ADD COLUMN system_id VARCHAR(20);

-- Populate from users table
UPDATE audit_log al SET system_id = u.system_id
FROM users u WHERE u.user_id = al.user_id;

-- Drop old column
ALTER TABLE audit_log DROP COLUMN user_id;

-- Add foreign key (allow NULL for system actions)
ALTER TABLE audit_log ADD CONSTRAINT audit_log_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update indices
DROP INDEX IF EXISTS idx_audit_user;
CREATE INDEX idx_audit_log_system_id ON audit_log(system_id);

-- ============================================================
-- SECTION 18: Update MESSAGES and MESSAGE_THREADS tables
-- ============================================================
-- Drop foreign keys on messages
ALTER TABLE messages DROP CONSTRAINT messages_sender_id_fkey;
ALTER TABLE messages DROP CONSTRAINT messages_recipient_id_fkey;
ALTER TABLE messages DROP CONSTRAINT messages_patient_id_fkey;

-- Add system_id columns to messages
ALTER TABLE messages ADD COLUMN sender_system_id VARCHAR(20);
ALTER TABLE messages ADD COLUMN recipient_system_id VARCHAR(20);
ALTER TABLE messages ADD COLUMN patient_system_id VARCHAR(20);

-- Populate from users and patients tables
UPDATE messages m SET sender_system_id = u.system_id
FROM users u WHERE u.user_id = m.sender_id;

UPDATE messages m SET recipient_system_id = u.system_id
FROM users u WHERE u.user_id = m.recipient_id;

UPDATE messages m SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =m.patient_id;

-- Drop old columns
ALTER TABLE messages DROP COLUMN sender_id;
ALTER TABLE messages DROP COLUMN recipient_id;
ALTER TABLE messages DROP COLUMN patient_id;

-- Make system_id columns NOT NULL and add foreign keys
ALTER TABLE messages ALTER COLUMN sender_system_id SET NOT NULL;
ALTER TABLE messages ALTER COLUMN recipient_system_id SET NOT NULL;
ALTER TABLE messages ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE messages ADD CONSTRAINT messages_sender_system_id_fkey
  FOREIGN KEY (sender_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE messages ADD CONSTRAINT messages_recipient_system_id_fkey
  FOREIGN KEY (recipient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
ALTER TABLE messages ADD CONSTRAINT messages_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

-- Update indices
DROP INDEX IF EXISTS idx_msg_sender;
DROP INDEX IF EXISTS idx_msg_recipient;
DROP INDEX IF EXISTS idx_msg_patient;
CREATE INDEX idx_messages_sender ON messages(sender_system_id);
CREATE INDEX idx_messages_recipient ON messages(recipient_system_id);
CREATE INDEX idx_messages_patient ON messages(patient_system_id);

-- Update message_threads patient foreign key
ALTER TABLE message_threads DROP CONSTRAINT message_threads_patient_id_fkey;
ALTER TABLE message_threads ADD COLUMN patient_system_id VARCHAR(20);
UPDATE message_threads mt SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =mt.patient_id;
ALTER TABLE message_threads DROP COLUMN patient_id;
ALTER TABLE message_threads ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE message_threads ADD CONSTRAINT message_threads_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS idx_thread_patient;
CREATE INDEX idx_message_threads_patient ON message_threads(patient_system_id);

-- ============================================================
-- SECTION 19: Update NOTIFICATIONS table
-- ============================================================
-- Drop foreign key
ALTER TABLE notifications DROP CONSTRAINT notifications_user_id_fkey;

-- Add system_id column
ALTER TABLE notifications ADD COLUMN system_id VARCHAR(20);

-- Populate from users table
UPDATE notifications n SET system_id = u.system_id
FROM users u WHERE u.user_id = n.user_id;

-- Drop old column
ALTER TABLE notifications DROP COLUMN user_id;

-- Make system_id NOT NULL and add foreign key
ALTER TABLE notifications ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT notifications_system_id_fkey
  FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;

-- Update indices
DROP INDEX IF EXISTS idx_notif_user;
CREATE INDEX idx_notifications_system_id ON notifications(system_id);

-- ============================================================
-- SECTION 20: Update remaining AI and DISEASE tables
-- ============================================================
-- Update DISEASE_DETECTION table
ALTER TABLE disease_detection DROP CONSTRAINT IF EXISTS disease_detection_patient_id_fkey;
ALTER TABLE disease_detection DROP CONSTRAINT IF EXISTS disease_detection_doctor_id_fkey;
ALTER TABLE disease_detection DROP CONSTRAINT IF EXISTS disease_detection_reviewed_by_fkey;

ALTER TABLE disease_detection ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN doctor_system_id VARCHAR(20);
ALTER TABLE disease_detection ADD COLUMN reviewed_by_system_id VARCHAR(20);

UPDATE disease_detection dd SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =dd.patient_id;

UPDATE disease_detection dd SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =dd.doctor_id;

UPDATE disease_detection dd SET reviewed_by_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =dd.reviewed_by;

ALTER TABLE disease_detection DROP COLUMN IF EXISTS patient_id;
ALTER TABLE disease_detection DROP COLUMN IF EXISTS doctor_id;
ALTER TABLE disease_detection DROP COLUMN IF EXISTS reviewed_by;

ALTER TABLE disease_detection ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE disease_detection ADD CONSTRAINT disease_detection_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE disease_detection ADD CONSTRAINT disease_detection_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
ALTER TABLE disease_detection ADD CONSTRAINT disease_detection_reviewed_by_system_id_fkey
  FOREIGN KEY (reviewed_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Update AI_ALERTS table
ALTER TABLE ai_alerts DROP CONSTRAINT IF EXISTS ai_alerts_patient_id_fkey;
ALTER TABLE ai_alerts DROP CONSTRAINT IF EXISTS ai_alerts_doctor_id_fkey;
ALTER TABLE ai_alerts DROP CONSTRAINT IF EXISTS ai_alerts_acknowledged_by_fkey;

ALTER TABLE ai_alerts ADD COLUMN patient_system_id VARCHAR(20);
ALTER TABLE ai_alerts ADD COLUMN doctor_system_id VARCHAR(20);
ALTER TABLE ai_alerts ADD COLUMN acknowledged_by_system_id VARCHAR(20);

UPDATE ai_alerts aa SET patient_system_id = p.system_id
FROM patients p WHERE p.patient_id =aa.patient_id;

UPDATE ai_alerts aa SET doctor_system_id = d.system_id
FROM doctors d WHERE d.doctor_id =aa.doctor_id;

UPDATE ai_alerts aa SET acknowledged_by_system_id = u.system_id
FROM users u WHERE u.user_id = aa.acknowledged_by;

ALTER TABLE ai_alerts DROP COLUMN IF EXISTS patient_id;
ALTER TABLE ai_alerts DROP COLUMN IF EXISTS doctor_id;
ALTER TABLE ai_alerts DROP COLUMN IF EXISTS acknowledged_by;

ALTER TABLE ai_alerts ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_patient_system_id_fkey
  FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE CASCADE;
ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_doctor_system_id_fkey
  FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
ALTER TABLE ai_alerts ADD CONSTRAINT ai_alerts_acknowledged_by_system_id_fkey
  FOREIGN KEY (acknowledged_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- ============================================================
-- FINAL: Create main indices for fast lookups
-- ============================================================
CREATE INDEX idx_users_system_id ON users(system_id);
CREATE INDEX idx_doctors_system_id ON doctors(system_id);
CREATE INDEX idx_patients_system_id ON patients(system_id);
CREATE INDEX idx_caregivers_system_id ON caregivers(system_id);

-- ============================================================
-- STEP: Recreate views with system_id
-- ============================================================
CREATE OR REPLACE VIEW v_patient_dashboard AS
  SELECT
    p.system_id,
    u.first_name,
    u.last_name,
    p.health_status,
    COUNT(DISTINCT a.appointment_id)  AS total_appointments,
    COUNT(DISTINCT rx.prescription_id) AS active_prescriptions,
    COUNT(DISTINCT lr.result_id)      AS lab_results,
    COUNT(DISTINCT ai.alert_id)       AS ai_alerts_unread
  FROM patients p
  JOIN users u              ON u.system_id    = p.system_id
  LEFT JOIN appointments  a  ON a.patient_system_id = p.system_id AND a.status  = 'SCHEDULED'
  LEFT JOIN prescriptions rx ON rx.patient_system_id = p.system_id AND rx.status = 'ACTIVE'
  LEFT JOIN lab_results   lr ON lr.patient_system_id = p.system_id
  LEFT JOIN ai_alerts     ai ON ai.patient_system_id = p.system_id AND ai.is_acknowledged = FALSE
  GROUP BY p.system_id, u.first_name, u.last_name, p.health_status;

CREATE OR REPLACE VIEW v_doctor_workload AS
  SELECT
    d.system_id,
    u.first_name,
    u.last_name,
    d.specialization,
    COUNT(DISTINCT a.appointment_id)  AS upcoming_appointments,
    COUNT(DISTINCT c.consultation_id) AS active_consultations,
    COUNT(DISTINCT ai.alert_id)       AS unread_ai_alerts
  FROM doctors d
  JOIN users u              ON u.system_id   = d.system_id
  LEFT JOIN appointments  a  ON a.doctor_system_id = d.system_id AND a.status = 'SCHEDULED'
  LEFT JOIN consultations c  ON c.doctor_system_id = d.system_id AND c.status = 'IN_PROGRESS'
  LEFT JOIN ai_alerts     ai ON ai.doctor_system_id = d.system_id AND ai.is_acknowledged = FALSE
  GROUP BY d.system_id, u.first_name, u.last_name, d.specialization;

-- ============================================================
-- Migration complete: system_id is now the universal identifier
-- ============================================================
