-- ============================================================
-- CareNexus Database Schema (PostgreSQL)
-- Reflects schema after all Flyway migrations (V1–V8)
-- V3 replaced integer user_id/patient_id/doctor_id FK columns
-- with system_id (e.g. PAT-00001, DOC-00002) across most tables.
-- Tables not updated by V3 still use integer FK references.
-- ============================================================

-- Trigger function to auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ language 'plpgsql';

-- ============================================================
-- SECTION 1: IDENTITY & PROFILES
-- ============================================================

CREATE TABLE users (
  user_id        SERIAL PRIMARY KEY,
  first_name     VARCHAR(100) NOT NULL,
  last_name      VARCHAR(100) NOT NULL,
  phone          VARCHAR(20),
  email          VARCHAR(150),
  password_hash  VARCHAR(255),
  role           VARCHAR(20) NOT NULL DEFAULT 'PATIENT' CHECK (role IN ('PATIENT','DOCTOR','CAREGIVER','ADMIN')),
  is_active      BOOLEAN NOT NULL DEFAULT TRUE,
  system_id      VARCHAR(20) NOT NULL UNIQUE,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(email),
  UNIQUE(phone)
);
COMMENT ON TABLE users IS 'Core authentication and identity';
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE INDEX idx_users_system_id ON users(system_id);

-- system_id format: PAT-NNNNN | DOC-NNNNN | CGV-NNNNN | ADM-NNNNN
-- Auto-populated on insert via application logic or migration.

CREATE TABLE doctors (
  doctor_id      SERIAL PRIMARY KEY,
  system_id      VARCHAR(20) NOT NULL UNIQUE REFERENCES users(system_id) ON DELETE CASCADE,
  specialization VARCHAR(150),
  license_no     VARCHAR(50) UNIQUE,
  bio            TEXT,
  is_active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE doctors IS 'Doctor / Physician profiles';
CREATE INDEX idx_doctors_system_id ON doctors(system_id);

CREATE TABLE patients (
  patient_id         SERIAL PRIMARY KEY,
  system_id          VARCHAR(20) NOT NULL UNIQUE REFERENCES users(system_id) ON DELETE CASCADE,
  date_of_birth      DATE,
  gender             VARCHAR(10) CHECK (gender IN ('MALE','FEMALE','OTHER')),
  blood_type         VARCHAR(5),
  allergies          JSONB,
  chronic_conditions JSONB,
  health_status      VARCHAR(20) NOT NULL DEFAULT 'STABLE' CHECK (health_status IN ('STABLE','MONITOR','CRITICAL')),
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE patients IS 'Patient profiles and health metadata';
CREATE INDEX idx_patients_health_status ON patients(health_status);
CREATE INDEX idx_patients_system_id     ON patients(system_id);

CREATE TABLE caregivers (
  caregiver_id SERIAL PRIMARY KEY,
  system_id    VARCHAR(20) NOT NULL UNIQUE REFERENCES users(system_id) ON DELETE CASCADE,
  relationship VARCHAR(80),
  phone        VARCHAR(20),
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE caregivers IS 'Caregiver profiles';
CREATE INDEX idx_caregivers_system_id ON caregivers(system_id);

CREATE TABLE facilities (
  facility_id SERIAL PRIMARY KEY,
  name        VARCHAR(200) NOT NULL,
  type        VARCHAR(80),
  address     VARCHAR(300),
  phone       VARCHAR(20),
  email       VARCHAR(150),
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE facilities IS 'Healthcare facilities and clinics';

CREATE TABLE emergency_contacts (
  contact_id        SERIAL PRIMARY KEY,
  patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  name              VARCHAR(150) NOT NULL,
  relationship      VARCHAR(80),
  phone             VARCHAR(20) NOT NULL,
  email             VARCHAR(150),
  is_primary        BOOLEAN NOT NULL DEFAULT FALSE,
  is_caregiver      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE emergency_contacts IS 'Emergency contacts for patients';
CREATE INDEX idx_emergency_contacts_patient ON emergency_contacts(patient_system_id);

CREATE TABLE refresh_tokens (
  token_id    SERIAL PRIMARY KEY,
  system_id   VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  token_hash  VARCHAR(255) NOT NULL UNIQUE,
  expires_at  TIMESTAMP NOT NULL,
  is_revoked  BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at  TIMESTAMP,
  device_type VARCHAR(50),
  device_info VARCHAR(255),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE refresh_tokens IS 'JWT refresh token store';
CREATE INDEX idx_refresh_system_id ON refresh_tokens(system_id);

-- ============================================================
-- SECTION 2: APPOINTMENTS & SCHEDULING
-- ============================================================

CREATE TABLE appointments (
  appointment_id          SERIAL PRIMARY KEY,
  patient_system_id       VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id        VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  facility_id             INTEGER REFERENCES facilities(facility_id) ON DELETE SET NULL,
  scheduled_at            TIMESTAMP NOT NULL,
  duration_minutes        INTEGER NOT NULL DEFAULT 30,
  type                    VARCHAR(20) NOT NULL DEFAULT 'IN_PERSON' CHECK (type IN ('VIDEO','IN_PERSON','PHONE')),
  status                  VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','COMPLETED','CANCELLED','NO_SHOW','RESCHEDULED')),
  reason_for_visit        TEXT,
  video_consultation_link VARCHAR(500),
  reminder_sent           BOOLEAN NOT NULL DEFAULT FALSE,
  notes                   TEXT,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE appointments IS 'Appointment bookings between patients and doctors';
CREATE TRIGGER update_appointments_updated_at BEFORE UPDATE ON appointments
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE INDEX idx_appointments_patient ON appointments(patient_system_id);
CREATE INDEX idx_appointments_doctor  ON appointments(doctor_system_id);
CREATE INDEX idx_appt_facility        ON appointments(facility_id);
CREATE INDEX idx_appt_scheduled       ON appointments(scheduled_at);
CREATE INDEX idx_appt_status          ON appointments(status);

CREATE TABLE referrals (
  referral_id                SERIAL PRIMARY KEY,
  patient_system_id          VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  referring_doctor_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  specialist_system_id       VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  clinical_summary           TEXT,
  reason                     TEXT NOT NULL,
  status                     VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','COMPLETED','DECLINED')),
  priority                   VARCHAR(20) NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE','URGENT','EMERGENCY')),
  referral_date              DATE NOT NULL,
  appointment_date           DATE,
  notes                      TEXT,
  created_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE referrals IS 'Patient referrals between doctors and specialists';
CREATE INDEX idx_referrals_patient    ON referrals(patient_system_id);
CREATE INDEX idx_referrals_doctor     ON referrals(referring_doctor_system_id);
CREATE INDEX idx_referrals_specialist ON referrals(specialist_system_id);

CREATE TABLE care_team (
  team_id            SERIAL PRIMARY KEY,
  patient_system_id  VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  provider_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  facility_id        INTEGER REFERENCES facilities(facility_id) ON DELETE SET NULL,
  role               VARCHAR(20) NOT NULL CHECK (role IN ('DOCTOR','NURSE','LAB','PHARMACY','SPECIALIST','THERAPIST')),
  joined_date        DATE NOT NULL,
  is_primary         BOOLEAN NOT NULL DEFAULT FALSE,
  is_active          BOOLEAN NOT NULL DEFAULT TRUE,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE care_team IS 'Multi-disciplinary care team assignments';
CREATE INDEX idx_care_team_patient  ON care_team(patient_system_id);
CREATE INDEX idx_care_team_provider ON care_team(provider_system_id);

CREATE TABLE consultations (
  consultation_id            SERIAL PRIMARY KEY,
  appointment_id             INTEGER NOT NULL REFERENCES appointments(appointment_id) ON DELETE RESTRICT,
  patient_system_id          VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id           VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  chief_complaint            TEXT,
  history_of_present_illness TEXT,
  physical_examination       TEXT,
  clinical_notes             TEXT,
  diagnosis                  TEXT,
  treatment_plan             TEXT,
  follow_up_instructions     TEXT,
  follow_up_required         BOOLEAN NOT NULL DEFAULT FALSE,
  follow_up_date             DATE,
  duration_minutes           INT,
  status                     VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('IN_PROGRESS','COMPLETED','DRAFT','CANCELLED')),
  started_at                 TIMESTAMP,
  completed_at               TIMESTAMP,
  created_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE consultations IS 'Clinical consultation records';
CREATE INDEX idx_consult_appt          ON consultations(appointment_id);
CREATE INDEX idx_consultations_patient ON consultations(patient_system_id);
CREATE INDEX idx_consultations_doctor  ON consultations(doctor_system_id);
CREATE INDEX idx_consult_status        ON consultations(status);

CREATE TABLE care_plans (
  plan_id           SERIAL PRIMARY KEY,
  patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id  VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  consultation_id   INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  title             VARCHAR(255) NOT NULL,
  description       TEXT,
  goals             TEXT,
  interventions     TEXT,
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','ON_HOLD','DISCONTINUED')),
  start_date        DATE NOT NULL,
  target_date       DATE,
  review_date       DATE,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE care_plans IS 'Patient care plans created by doctors';
CREATE TRIGGER update_care_plans_updated_at BEFORE UPDATE ON care_plans
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE INDEX idx_care_plans_patient ON care_plans(patient_system_id);
CREATE INDEX idx_care_plans_doctor  ON care_plans(doctor_system_id);

-- ============================================================
-- SECTION 3: MEDICAL RECORDS
-- ============================================================

CREATE TABLE medical_records (
  record_id         SERIAL PRIMARY KEY,
  patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id  VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  consultation_id   INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  record_type       VARCHAR(20) NOT NULL CHECK (record_type IN ('CONSULT','LAB','IMAGING','PRESCRIPTION','REFERRAL','DISCHARGE','NOTE','OPERATION')),
  title             TEXT NOT NULL,
  description       TEXT,
  treatment_plan    TEXT,
  observations      TEXT,
  icd_codes         JSONB,
  is_deleted        BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at        TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE medical_records IS 'Master medical records registry';
CREATE TRIGGER update_medical_records_updated_at BEFORE UPDATE ON medical_records
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE INDEX idx_medical_records_patient ON medical_records(patient_system_id);
CREATE INDEX idx_medical_records_doctor  ON medical_records(doctor_system_id);
CREATE INDEX idx_mr_type                 ON medical_records(record_type);

CREATE TABLE prescriptions (
  prescription_id      SERIAL PRIMARY KEY,
  patient_system_id    VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  consultation_id      INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  medication_name      VARCHAR(200) NOT NULL,
  generic_name         VARCHAR(200),
  dosage               VARCHAR(100) NOT NULL,
  frequency            VARCHAR(100) NOT NULL,
  route                VARCHAR(20) NOT NULL DEFAULT 'ORAL' CHECK (route IN ('ORAL','TOPICAL','INJECTION','INHALED','SUBLINGUAL','RECTAL','IV')),
  quantity             VARCHAR(50),
  refills_allowed      INTEGER NOT NULL DEFAULT 0,
  special_instructions TEXT,
  status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED','EXPIRED','ON_HOLD')),
  prescribed_date      DATE NOT NULL,
  expiry_date          DATE,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE prescriptions IS 'Medication prescriptions';
CREATE INDEX idx_prescriptions_patient ON prescriptions(patient_system_id);
CREATE INDEX idx_prescriptions_doctor  ON prescriptions(doctor_system_id);
CREATE INDEX idx_rx_status             ON prescriptions(status);

-- medication_schedules retains integer FKs (not updated by V3)
CREATE TABLE medication_schedules (
  schedule_id     SERIAL PRIMARY KEY,
  prescription_id INTEGER NOT NULL REFERENCES prescriptions(prescription_id) ON DELETE CASCADE,
  patient_id      INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  scheduled_time  TIME NOT NULL,
  medication_name VARCHAR(200) NOT NULL,
  dosage          VARCHAR(100) NOT NULL,
  is_taken        BOOLEAN NOT NULL DEFAULT FALSE,
  taken_at        TIMESTAMP,
  missed          BOOLEAN NOT NULL DEFAULT FALSE,
  notes           TEXT,
  schedule_date   DATE NOT NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE medication_schedules IS 'Daily medication schedule tracker';
CREATE INDEX idx_ms_prescription ON medication_schedules(prescription_id);
CREATE INDEX idx_ms_patient       ON medication_schedules(patient_id);
CREATE INDEX idx_ms_date          ON medication_schedules(schedule_date);

CREATE TABLE lab_results (
  result_id             SERIAL PRIMARY KEY,
  patient_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id      VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  consultation_id       INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  test_name             VARCHAR(200) NOT NULL,
  test_type             VARCHAR(20) NOT NULL CHECK (test_type IN ('BLOOD','URINE','STOOL','CULTURE','BIOPSY','GENETIC','SWAB','OTHER')),
  result_value          VARCHAR(500),
  unit                  VARCHAR(50),
  reference_range       VARCHAR(100),
  result_data           JSONB,
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','CRITICAL','REQUIRES_REVIEW','CANCELLED')),
  is_critical           BOOLEAN NOT NULL DEFAULT FALSE,
  test_date             DATE NOT NULL,
  reviewed_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  reviewed_at           TIMESTAMP,
  file_url              VARCHAR(500),
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE lab_results IS 'Laboratory test results';
CREATE INDEX idx_lab_results_patient ON lab_results(patient_system_id);
CREATE INDEX idx_lab_results_doctor  ON lab_results(doctor_system_id);
CREATE INDEX idx_lab_status          ON lab_results(status);
CREATE INDEX idx_lab_date            ON lab_results(test_date);

CREATE TABLE imaging_results (
  imaging_id            SERIAL PRIMARY KEY,
  patient_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  doctor_system_id      VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  consultation_id       INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  imaging_type          VARCHAR(20) NOT NULL CHECK (imaging_type IN ('XRAY','MRI','CT_SCAN','ULTRASOUND','PET','MAMMOGRAPHY','DEXA','ECHO')),
  body_part             TEXT,
  radiologist_report    TEXT,
  findings              TEXT,
  impression            TEXT,
  file_url              VARCHAR(500),
  thumbnail_url         VARCHAR(500),
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','REQUIRES_REVIEW','CRITICAL')),
  image_date            DATE NOT NULL,
  reviewed_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  reviewed_at           TIMESTAMP,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE imaging_results IS 'Medical imaging results and reports';
CREATE INDEX idx_imaging_results_patient ON imaging_results(patient_system_id);
CREATE INDEX idx_imaging_results_doctor  ON imaging_results(doctor_system_id);
CREATE INDEX idx_img_type                ON imaging_results(imaging_type);

CREATE TABLE documents (
  document_id           SERIAL PRIMARY KEY,
  patient_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  uploaded_by_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  document_type         VARCHAR(20) NOT NULL CHECK (document_type IN ('LAB_REPORT','IMAGING','PRESCRIPTION','REFERRAL','CONSENT','DISCHARGE','INSURANCE','OTHER')),
  title                 VARCHAR(300) NOT NULL,
  description           TEXT,
  file_url              VARCHAR(500) NOT NULL,
  mime_type             VARCHAR(100),
  file_size_kb          INT,
  is_deleted            BOOLEAN NOT NULL DEFAULT FALSE,
  uploaded_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE documents IS 'Patient document storage';
CREATE INDEX idx_documents_patient  ON documents(patient_system_id);
CREATE INDEX idx_documents_uploader ON documents(uploaded_by_system_id);
CREATE INDEX idx_doc_type           ON documents(document_type);

-- ============================================================
-- SECTION 4: PROXY & ACCESS CONTROL
-- ============================================================

CREATE TABLE dependent_access (
  access_id               SERIAL PRIMARY KEY,
  caregiver_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  patient_system_id       VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  relationship            VARCHAR(80) NOT NULL,
  access_level            VARCHAR(20) NOT NULL DEFAULT 'VIEW_ONLY' CHECK (access_level IN ('FULL_ACCESS','VIEW_ONLY','EDIT_ONLY','CUSTOM')),
  can_view_records        BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_appointments   BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_medications    BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_lab_results    BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_imaging        BOOLEAN NOT NULL DEFAULT FALSE,
  can_message_care_team   BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_consultations  BOOLEAN NOT NULL DEFAULT FALSE,
  can_schedule_appointments BOOLEAN NOT NULL DEFAULT FALSE,
  can_manage_care_plan    BOOLEAN NOT NULL DEFAULT FALSE,
  authorized_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  authorized_date         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at              TIMESTAMP,
  is_active               BOOLEAN NOT NULL DEFAULT TRUE,
  revoked_at              TIMESTAMP,
  UNIQUE(caregiver_system_id, patient_system_id)
);
COMMENT ON TABLE dependent_access IS 'Caregiver access control';
CREATE INDEX idx_dependent_access_patient   ON dependent_access(patient_system_id);
CREATE INDEX idx_dependent_access_caregiver ON dependent_access(caregiver_system_id);

-- permissions retains integer FKs (not updated by V3)
CREATE TABLE permissions (
  permission_id SERIAL PRIMARY KEY,
  caregiver_id  INTEGER NOT NULL REFERENCES caregivers(caregiver_id) ON DELETE CASCADE,
  patient_id    INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  resource      VARCHAR(80) NOT NULL,
  action        VARCHAR(20) NOT NULL CHECK (action IN ('READ','WRITE','DELETE','APPROVE','DOWNLOAD')),
  granted_by    INTEGER NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
  granted_date  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at    TIMESTAMP,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE
);
COMMENT ON TABLE permissions IS 'Granular resource-level permissions';
CREATE INDEX idx_perm_caregiver ON permissions(caregiver_id);
CREATE INDEX idx_perm_patient   ON permissions(patient_id);

-- consent_records retains integer FKs (not updated by V3)
CREATE TABLE consent_records (
  consent_id   SERIAL PRIMARY KEY,
  patient_id   INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE RESTRICT,
  caregiver_id INTEGER REFERENCES caregivers(caregiver_id) ON DELETE SET NULL,
  doctor_id    INTEGER REFERENCES doctors(doctor_id) ON DELETE SET NULL,
  consent_type VARCHAR(20) NOT NULL CHECK (consent_type IN ('DATA_ACCESS','CONSULTATION','TREATMENT','FULL_PROXY','RESEARCH','MARKETING')),
  terms_agreed BOOLEAN NOT NULL DEFAULT FALSE,
  scope        JSONB,
  ip_address   VARCHAR(45),
  signed_date  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  received_at  TIMESTAMP,
  expires_at   TIMESTAMP,
  is_active    BOOLEAN NOT NULL DEFAULT TRUE,
  revoked_at   TIMESTAMP
);
COMMENT ON TABLE consent_records IS 'Patient consent records';
CREATE INDEX idx_consent_patient   ON consent_records(patient_id);
CREATE INDEX idx_consent_caregiver ON consent_records(caregiver_id);

-- caregiver_alerts retains integer FKs (not updated by V3)
CREATE TABLE caregiver_alerts (
  alert_id     SERIAL PRIMARY KEY,
  caregiver_id INTEGER NOT NULL REFERENCES caregivers(caregiver_id) ON DELETE CASCADE,
  patient_id   INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  alert_type   VARCHAR(20) NOT NULL CHECK (alert_type IN ('MEDICATION','APPOINTMENT','LAB_RESULT','EMERGENCY','VITAL_SIGN','CARE_PLAN')),
  message      TEXT NOT NULL,
  severity     VARCHAR(20) NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  action_url   VARCHAR(500),
  is_read      BOOLEAN NOT NULL DEFAULT FALSE,
  read_at      TIMESTAMP,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE caregiver_alerts IS 'Alerts for caregivers';
CREATE INDEX idx_cga_caregiver ON caregiver_alerts(caregiver_id);
CREATE INDEX idx_cga_patient   ON caregiver_alerts(patient_id);
CREATE INDEX idx_cga_severity  ON caregiver_alerts(severity);

-- caregiver_activity_log retains integer FKs (not updated by V3)
CREATE TABLE caregiver_activity_log (
  log_id        SERIAL PRIMARY KEY,
  caregiver_id  INTEGER NOT NULL REFERENCES caregivers(caregiver_id) ON DELETE CASCADE,
  patient_id    INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  action        VARCHAR(20) NOT NULL CHECK (action IN ('VIEW','SCHEDULE','MESSAGE','DOWNLOAD','JOIN_CONSULT','UPLOAD','EDIT')),
  resource_type VARCHAR(80),
  resource_id   INT,
  ip_address    VARCHAR(45),
  device_info   VARCHAR(255),
  timestamp     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE caregiver_activity_log IS 'Audit trail of caregiver actions';
CREATE INDEX idx_cgal_caregiver ON caregiver_activity_log(caregiver_id);
CREATE INDEX idx_cgal_patient   ON caregiver_activity_log(patient_id);
CREATE INDEX idx_cgal_time      ON caregiver_activity_log(timestamp);

-- ============================================================
-- SECTION 5: COMMUNICATION & MESSAGING
-- ============================================================

CREATE TABLE message_threads (
  thread_id         SERIAL PRIMARY KEY,
  patient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  subject           VARCHAR(300),
  thread_type       VARCHAR(30) NOT NULL CHECK (thread_type IN ('PATIENT_DOCTOR','CAREGIVER_DOCTOR','CARE_TEAM','PATIENT_SUPPORT')),
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_message_at   TIMESTAMP,
  is_archived       BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE message_threads IS 'Messaging threads';
CREATE INDEX idx_message_threads_patient ON message_threads(patient_system_id);
CREATE INDEX idx_thread_last             ON message_threads(last_message_at);

CREATE TABLE messages (
  message_id          SERIAL PRIMARY KEY,
  sender_system_id    VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  recipient_system_id VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  patient_system_id   VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  thread_id           INTEGER NOT NULL REFERENCES message_threads(thread_id) ON DELETE CASCADE,
  subject             VARCHAR(300),
  body                TEXT NOT NULL,
  is_read             BOOLEAN NOT NULL DEFAULT FALSE,
  read_at             TIMESTAMP,
  is_urgent           BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at          TIMESTAMP,
  sent_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE messages IS 'Secure messages';
CREATE INDEX idx_messages_sender    ON messages(sender_system_id);
CREATE INDEX idx_messages_recipient ON messages(recipient_system_id);
CREATE INDEX idx_messages_patient   ON messages(patient_system_id);
CREATE INDEX idx_msg_thread         ON messages(thread_id);

CREATE TABLE notifications (
  notification_id SERIAL PRIMARY KEY,
  system_id       VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  type            VARCHAR(20) NOT NULL CHECK (type IN ('APPOINTMENT','MEDICATION','LAB_RESULT','MESSAGE','AI_ALERT','SYSTEM','PAYMENT','REFERRAL')),
  title           VARCHAR(255) NOT NULL,
  message         TEXT NOT NULL,
  is_read         BOOLEAN NOT NULL DEFAULT FALSE,
  read_at         TIMESTAMP,
  action_url      VARCHAR(500),
  icon            VARCHAR(50),
  priority        VARCHAR(20) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','CRITICAL')),
  expires_at      TIMESTAMP,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE notifications IS 'In-app notifications';
CREATE INDEX idx_notifications_system_id ON notifications(system_id);
CREATE INDEX idx_notif_type              ON notifications(type);
CREATE INDEX idx_notif_unread            ON notifications(is_read);
CREATE INDEX idx_notif_created           ON notifications(created_at);

-- reminders retains integer FK (not updated by V3)
CREATE TABLE reminders (
  reminder_id   SERIAL PRIMARY KEY,
  patient_id    INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  type          VARCHAR(20) NOT NULL CHECK (type IN ('MEDICATION','APPOINTMENT','LAB_FOLLOWUP','CARE_PLAN','CHECK_IN','VITAL_SIGN')),
  title         VARCHAR(255) NOT NULL,
  message       TEXT NOT NULL,
  reminder_time TIME NOT NULL,
  frequency     VARCHAR(20) NOT NULL DEFAULT 'DAILY' CHECK (frequency IN ('ONCE','DAILY','WEEKLY','MONTHLY','CUSTOM')),
  start_date    DATE NOT NULL,
  end_date      DATE,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE,
  last_sent_at  TIMESTAMP,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE reminders IS 'Automated reminders';
CREATE INDEX idx_rem_patient ON reminders(patient_id);
CREATE INDEX idx_rem_active  ON reminders(is_active);

CREATE TABLE audit_log (
  audit_id      SERIAL PRIMARY KEY,
  system_id     VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  action        VARCHAR(50) NOT NULL,
  resource_type VARCHAR(50),
  resource_id   INT,
  status        VARCHAR(20) NOT NULL,
  ip_address    VARCHAR(45),
  user_agent    VARCHAR(500),
  error_message VARCHAR(500),
  timestamp     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE audit_log IS 'Audit trail of system actions';
CREATE INDEX idx_audit_log_system_id ON audit_log(system_id);
CREATE INDEX idx_audit_resource      ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_timestamp     ON audit_log(timestamp);

-- ============================================================
-- SECTION 6: PAYMENTS & BILLING
-- ============================================================

-- payment_methods retains integer FK to users.user_id (not updated by V3)
CREATE TABLE payment_methods (
  method_id  SERIAL PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  type       VARCHAR(20) NOT NULL CHECK (type IN ('CARD','INSURANCE','CASH','MPESA','BANK_TRANSFER','CRYPTO')),
  provider   VARCHAR(100),
  last_four  VARCHAR(4),
  token      VARCHAR(255),
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  is_active  BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE payment_methods IS 'Patient payment methods';
CREATE INDEX idx_pm_user ON payment_methods(user_id);

-- invoices retains integer FKs (not updated by V3)
CREATE TABLE invoices (
  invoice_id      SERIAL PRIMARY KEY,
  patient_id      INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE RESTRICT,
  appointment_id  INTEGER REFERENCES appointments(appointment_id) ON DELETE SET NULL,
  consultation_id INTEGER REFERENCES consultations(consultation_id) ON DELETE SET NULL,
  invoice_no      VARCHAR(50) NOT NULL UNIQUE,
  line_items      JSONB,
  subtotal        NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  tax             NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  discount        NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  total           NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  currency        VARCHAR(3) NOT NULL DEFAULT 'KES',
  status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','PAID','OVERDUE','CANCELLED','REFUNDED')),
  due_date        DATE,
  paid_at         TIMESTAMP,
  notes           TEXT,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE invoices IS 'Patient invoices';
CREATE INDEX idx_inv_patient     ON invoices(patient_id);
CREATE INDEX idx_inv_appointment ON invoices(appointment_id);
CREATE INDEX idx_inv_status      ON invoices(status);

-- payments retains integer FKs (not updated by V3)
CREATE TABLE payments (
  payment_id        SERIAL PRIMARY KEY,
  patient_id        INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE RESTRICT,
  appointment_id    INTEGER REFERENCES appointments(appointment_id) ON DELETE SET NULL,
  invoice_id        INTEGER REFERENCES invoices(invoice_id) ON DELETE SET NULL,
  payment_method_id INTEGER REFERENCES payment_methods(method_id) ON DELETE SET NULL,
  amount            NUMERIC(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'KES',
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','FAILED','REFUNDED','CANCELLED')),
  transaction_id    VARCHAR(255) UNIQUE,
  gateway           VARCHAR(50),
  gateway_response  JSONB,
  paid_at           TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE payments IS 'Payment transactions';
CREATE INDEX idx_pay_patient     ON payments(patient_id);
CREATE INDEX idx_pay_invoice     ON payments(invoice_id);
CREATE INDEX idx_pay_appointment ON payments(appointment_id);
CREATE INDEX idx_pay_status      ON payments(status);

-- insurance_claims retains integer FKs (not updated by V3)
CREATE TABLE insurance_claims (
  claim_id           SERIAL PRIMARY KEY,
  patient_id         INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE RESTRICT,
  payment_id         INTEGER REFERENCES payments(payment_id) ON DELETE SET NULL,
  invoice_id         INTEGER REFERENCES invoices(invoice_id) ON DELETE SET NULL,
  insurance_provider VARCHAR(150) NOT NULL,
  policy_number      VARCHAR(100) NOT NULL,
  member_number      VARCHAR(100),
  claim_amount       NUMERIC(12,2) NOT NULL,
  approved_amount    NUMERIC(12,2),
  rejection_reason   TEXT,
  status             VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','PENDING','APPROVED','REJECTED','PARTIAL','APPEALED')),
  submitted_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at        TIMESTAMP,
  documents          JSONB
);
COMMENT ON TABLE insurance_claims IS 'Insurance claims';
CREATE INDEX idx_claim_patient ON insurance_claims(patient_id);
CREATE INDEX idx_claim_payment ON insurance_claims(payment_id);
CREATE INDEX idx_claim_status  ON insurance_claims(status);

-- ============================================================
-- SECTION 7: DISEASE DETECTION & AI
-- ============================================================

-- symptom_logs retains integer FK (not updated by V3)
CREATE TABLE symptom_logs (
  symptom_id    SERIAL PRIMARY KEY,
  patient_id    INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  symptoms      JSONB NOT NULL,
  severity      VARCHAR(20) NOT NULL DEFAULT 'MILD' CHECK (severity IN ('MILD','MODERATE','SEVERE','CRITICAL')),
  duration_days INT,
  notes         TEXT,
  source        VARCHAR(30) NOT NULL DEFAULT 'PATIENT_REPORTED' CHECK (source IN ('PATIENT_REPORTED','DOCTOR_ENTERED','WEARABLE','INTEGRATION')),
  logged_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE symptom_logs IS 'Patient-reported symptoms';
CREATE INDEX idx_sl_patient  ON symptom_logs(patient_id);
CREATE INDEX idx_sl_severity ON symptom_logs(severity);
CREATE INDEX idx_sl_logged   ON symptom_logs(logged_at);

-- health_metrics retains integer FK (not updated by V3)
CREATE TABLE health_metrics (
  metric_id   SERIAL PRIMARY KEY,
  patient_id  INTEGER NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
  type        VARCHAR(30) NOT NULL CHECK (type IN ('BP_SYSTOLIC','BP_DIASTOLIC','HEART_RATE','GLUCOSE','WEIGHT','HEIGHT','BMI','SPO2','TEMPERATURE','RESPIRATORY_RATE','STEPS')),
  value       NUMERIC(10,3) NOT NULL,
  unit        VARCHAR(30) NOT NULL,
  source      VARCHAR(20) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','WEARABLE','DEVICE','INTEGRATION')),
  device_id   VARCHAR(100),
  is_abnormal BOOLEAN NOT NULL DEFAULT FALSE,
  notes       TEXT,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE health_metrics IS 'Patient vital signs and health metrics';
CREATE INDEX idx_hm_patient  ON health_metrics(patient_id);
CREATE INDEX idx_hm_type     ON health_metrics(type);
CREATE INDEX idx_hm_abnormal ON health_metrics(is_abnormal);
CREATE INDEX idx_hm_recorded ON health_metrics(recorded_at);

CREATE TABLE disease_detection (
  detection_id          SERIAL PRIMARY KEY,
  patient_system_id     VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  symptom_id            INTEGER REFERENCES symptom_logs(symptom_id) ON DELETE SET NULL,
  doctor_system_id      VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  model_version         VARCHAR(50) NOT NULL,
  detected_conditions   JSONB NOT NULL,
  confidence_score      NUMERIC(4,3),
  risk_level            VARCHAR(20) NOT NULL DEFAULT 'LOW' CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  input_data            JSONB,
  recommendations       JSONB,
  reviewed_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  is_confirmed          BOOLEAN NOT NULL DEFAULT FALSE,
  confirmed_at          TIMESTAMP,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE disease_detection IS 'AI disease detection results';
CREATE INDEX idx_dd_patient   ON disease_detection(patient_system_id);
CREATE INDEX idx_dd_risk      ON disease_detection(risk_level);
CREATE INDEX idx_dd_confirmed ON disease_detection(is_confirmed);

CREATE TABLE ai_alerts (
  alert_id                  SERIAL PRIMARY KEY,
  patient_system_id         VARCHAR(20) NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  detection_id              INTEGER REFERENCES disease_detection(detection_id) ON DELETE SET NULL,
  doctor_system_id          VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  alert_type                VARCHAR(30) NOT NULL CHECK (alert_type IN ('CRITICAL_VITALS','DISEASE_RISK','MEDICATION_INTERACTION','MISSED_MEDICATION','FOLLOW_UP_DUE','ANOMALY_DETECTED')),
  message                   TEXT NOT NULL,
  priority                  VARCHAR(20) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  action_required           BOOLEAN NOT NULL DEFAULT FALSE,
  suggested_action          TEXT,
  is_acknowledged           BOOLEAN NOT NULL DEFAULT FALSE,
  acknowledged_by_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE SET NULL,
  acknowledged_at           TIMESTAMP,
  created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ai_alerts IS 'AI clinical alerts';
CREATE INDEX idx_ai_patient  ON ai_alerts(patient_system_id);
CREATE INDEX idx_ai_doctor   ON ai_alerts(doctor_system_id);
CREATE INDEX idx_ai_priority ON ai_alerts(priority);
CREATE INDEX idx_ai_ack      ON ai_alerts(is_acknowledged);

-- ============================================================
-- VIEWS
-- ============================================================

CREATE OR REPLACE VIEW v_patient_dashboard AS
  SELECT
    p.system_id,
    u.first_name,
    u.last_name,
    p.health_status,
    COUNT(DISTINCT a.appointment_id)   AS total_appointments,
    COUNT(DISTINCT rx.prescription_id) AS active_prescriptions,
    COUNT(DISTINCT lr.result_id)       AS lab_results,
    COUNT(DISTINCT ai.alert_id)        AS ai_alerts_unread
  FROM patients p
  JOIN users u               ON u.system_id          = p.system_id
  LEFT JOIN appointments  a  ON a.patient_system_id  = p.system_id AND a.status   = 'SCHEDULED'
  LEFT JOIN prescriptions rx ON rx.patient_system_id = p.system_id AND rx.status  = 'ACTIVE'
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
  JOIN users u               ON u.system_id         = d.system_id
  LEFT JOIN appointments  a  ON a.doctor_system_id  = d.system_id AND a.status = 'SCHEDULED'
  LEFT JOIN consultations c  ON c.doctor_system_id  = d.system_id AND c.status = 'IN_PROGRESS'
  LEFT JOIN ai_alerts     ai ON ai.doctor_system_id = d.system_id AND ai.is_acknowledged = FALSE
  GROUP BY d.system_id, u.first_name, u.last_name, d.specialization;

-- ============================================================
-- END OF CARENEXUS SCHEMA (PostgreSQL)
-- ============================================================