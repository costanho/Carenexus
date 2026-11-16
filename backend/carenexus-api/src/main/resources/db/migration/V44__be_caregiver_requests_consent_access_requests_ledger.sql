-- ============================================================
-- V44 (be-caregiver-requests-consent): caregiver access requests and the consent ledger.
--
-- 1. caregiver_access_requests: a caregiver asks an existing patient (by email) for access; the patient approves
--    (a dependent_access grant is created or a revoked one reused) or declines. Status PENDING, APPROVED, DECLINED,
--    CANCELLED, EXPIRED. patient_system_id is NULL when the email matched no patient (the request is stored anyway
--    so the answer never reveals whether an account exists; nobody can approve it and it expires after 30 days).
--    Two partial unique indexes keep at most one PENDING request per caregiver and patient, and per caregiver and
--    email. No constraint ties responded_at (or any other column) to status: be-account-lifecycle cancels pending
--    rows with a status-only UPDATE.
-- 2. consent_records re-keyed to system ids and turned into the immutable consent ledger: one row per lifecycle
--    step of a caregiver grant (event_kind), with a JSONB scope snapshot. The legacy integer columns become
--    nullable; consent_type keeps its CHECK and every ledger row writes 'DATA_ACCESS'. access_id and
--    granted_by_system_id carry no foreign key on purpose: a ledger row outlives what it describes.
-- 3. permissions, caregiver_alerts and caregiver_activity_log are documented as unused (dependent_access flags,
--    notifications and audit_log replace them).
-- ============================================================

-- 1. Access requests ------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS caregiver_access_requests (
    request_id                SERIAL PRIMARY KEY,
    caregiver_system_id       VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
    patient_system_id         VARCHAR(20)  NULL REFERENCES users(system_id) ON DELETE CASCADE,
    patient_email             VARCHAR(150) NOT NULL,
    relationship              VARCHAR(80)  NOT NULL,
    access_level              VARCHAR(20)  NOT NULL DEFAULT 'VIEW_ONLY',
    can_view_records          BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_appointments     BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_medications      BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_lab_results      BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_imaging          BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_consultations    BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_billing          BOOLEAN NOT NULL DEFAULT FALSE,
    can_schedule_appointments BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_care_plan      BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_billing        BOOLEAN NOT NULL DEFAULT FALSE,
    can_message_care_team     BOOLEAN NOT NULL DEFAULT FALSE,
    can_join_calls            BOOLEAN NOT NULL DEFAULT FALSE,
    message                   VARCHAR(500),
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decline_reason            VARCHAR(500),
    access_id                 INTEGER NULL REFERENCES dependent_access(access_id) ON DELETE SET NULL,
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at              TIMESTAMP    NULL,
    CONSTRAINT caregiver_access_requests_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT caregiver_access_requests_level_check
        CHECK (access_level IN ('FULL_ACCESS', 'VIEW_ONLY', 'EDIT_ONLY', 'CUSTOM'))
);

COMMENT ON TABLE caregiver_access_requests IS
    'Caregiver requests for access to an existing patient (US-14). patient_system_id is NULL when the email matched '
    'no patient. Status PENDING until approved, declined, cancelled or expired (30 days); only PENDING changes.';

-- At most one pending request per caregiver and patient, and per caregiver and email.
CREATE UNIQUE INDEX IF NOT EXISTS ux_caregiver_access_requests_pending_pair
    ON caregiver_access_requests (caregiver_system_id, patient_system_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS ux_caregiver_access_requests_pending_email
    ON caregiver_access_requests (caregiver_system_id, patient_email) WHERE status = 'PENDING';

-- Caregiver list, patient list (both newest first) and the expiry job.
CREATE INDEX IF NOT EXISTS idx_caregiver_access_requests_caregiver
    ON caregiver_access_requests (caregiver_system_id, created_at DESC, request_id DESC);
CREATE INDEX IF NOT EXISTS idx_caregiver_access_requests_patient
    ON caregiver_access_requests (patient_system_id, created_at DESC, request_id DESC)
    WHERE patient_system_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_caregiver_access_requests_pending_created
    ON caregiver_access_requests (created_at, request_id) WHERE status = 'PENDING';

-- 2. Consent ledger -------------------------------------------------------------------------------------------

ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS patient_system_id    VARCHAR(20);
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS caregiver_system_id  VARCHAR(20);
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS doctor_system_id     VARCHAR(20);
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS granted_by_system_id VARCHAR(20);
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS access_id            INTEGER;
ALTER TABLE consent_records ADD COLUMN IF NOT EXISTS event_kind           VARCHAR(30);

-- Backfill the system ids of rows that existed before (the table had no writer, so normally there are none).
UPDATE consent_records c SET patient_system_id = p.system_id
  FROM patients p WHERE c.patient_system_id IS NULL AND c.patient_id = p.patient_id;
UPDATE consent_records c SET caregiver_system_id = g.system_id
  FROM caregivers g WHERE c.caregiver_system_id IS NULL AND c.caregiver_id = g.caregiver_id;
UPDATE consent_records c SET doctor_system_id = d.system_id
  FROM doctors d WHERE c.doctor_system_id IS NULL AND c.doctor_id = d.doctor_id;

ALTER TABLE consent_records ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE consent_records ALTER COLUMN patient_id DROP NOT NULL;
ALTER TABLE consent_records ALTER COLUMN caregiver_id DROP NOT NULL;
ALTER TABLE consent_records ALTER COLUMN doctor_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'consent_records_patient_system_id_fkey') THEN
        ALTER TABLE consent_records ADD CONSTRAINT consent_records_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'consent_records_caregiver_system_id_fkey') THEN
        ALTER TABLE consent_records ADD CONSTRAINT consent_records_caregiver_system_id_fkey
            FOREIGN KEY (caregiver_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'consent_records_doctor_system_id_fkey') THEN
        ALTER TABLE consent_records ADD CONSTRAINT consent_records_doctor_system_id_fkey
            FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END $$;

ALTER TABLE consent_records DROP CONSTRAINT IF EXISTS consent_records_event_kind_check;
ALTER TABLE consent_records ADD CONSTRAINT consent_records_event_kind_check
    CHECK (event_kind IS NULL OR event_kind IN ('GRANTED', 'PERMISSIONS_CHANGED', 'COMMUNICATION_APPROVED',
                                                'EXTENDED', 'NARROWED', 'REVOKED', 'EXPIRED'));

-- The patient's ledger (newest first, optionally one grant) and the rows of one grant.
CREATE INDEX IF NOT EXISTS idx_consent_records_patient_signed
    ON consent_records (patient_system_id, signed_date DESC, consent_id DESC) WHERE event_kind IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_consent_records_access
    ON consent_records (access_id, signed_date DESC, consent_id DESC) WHERE access_id IS NOT NULL;

COMMENT ON TABLE consent_records IS
    'Consent ledger: one immutable row per lifecycle step of a caregiver grant (event_kind, scope snapshot of the '
    'grant after the step, granted_by_system_id = who performed it, NULL = system). Written by the caregiveraccess '
    'module, never updated or deleted by the application. Rows with event_kind NULL predate V44.';
COMMENT ON COLUMN consent_records.scope IS
    'Ledger rows: JSON snapshot {accessLevel, ten data flags, canMessageCareTeam, canJoinCalls, messagingApproved, '
    'callsApproved, expiresAt, guardianAttested} of the grant right after the step.';

-- 3. Unused legacy tables -------------------------------------------------------------------------------------

COMMENT ON TABLE permissions IS
    'UNUSED since V44: caregiver permissions are the flags on dependent_access. No code reads or writes this table.';
COMMENT ON TABLE caregiver_alerts IS
    'UNUSED since V44: caregiver alerts are in-app notifications (notifications table). No code reads or writes this table.';
COMMENT ON TABLE caregiver_activity_log IS
    'UNUSED since V44: caregiver activity is read from audit_log (actor = caregiver, patient_system_id = patient). '
    'No code reads or writes this table.';
