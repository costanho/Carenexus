-- ============================================================
-- V50 (be-corrections): patient requests for correction of clinical records.
--   One row per request. A patient (or a caregiver with MANAGE_CARE_PLAN) asks for a correction of one clinical
--   record; the authoring doctor (or, when that doctor is not available, an administrator) accepts or rejects it.
--   The correction itself is made through the normal authoring endpoints of the record; this table only records
--   that it was asked for and how it was resolved.
--   Times are TIMESTAMPTZ (instants). resource_id is the id of the record in its own table
--   (medical_records.record_id, lab_results.result_id, imaging_results.imaging_id, prescriptions.prescription_id,
--   consultations.consultation_id); it is not a foreign key because it points into five tables.
-- ============================================================
CREATE TABLE IF NOT EXISTS record_correction_requests (
  request_id                 SERIAL PRIMARY KEY,
  patient_system_id          VARCHAR(20)   NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  requested_by_system_id     VARCHAR(20)   NOT NULL REFERENCES users(system_id) ON DELETE RESTRICT,
  resource_type              VARCHAR(30)   NOT NULL,
  resource_id                INTEGER       NOT NULL,
  requested_text             VARCHAR(2000) NOT NULL,
  status                     VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
  assigned_doctor_system_id  VARCHAR(20)   REFERENCES users(system_id) ON DELETE SET NULL,
  resolver_system_id         VARCHAR(20)   REFERENCES users(system_id) ON DELETE SET NULL,
  resolution_note            VARCHAR(1000),
  created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
  resolved_at                TIMESTAMPTZ,
  CONSTRAINT record_correction_requests_resource_type_check CHECK (resource_type IN
    ('MEDICAL_RECORD','LAB_RESULT','IMAGING_RESULT','PRESCRIPTION','CONSULTATION')),
  CONSTRAINT record_correction_requests_status_check CHECK (status IN ('OPEN','ACCEPTED','REJECTED')),
  CONSTRAINT record_correction_requests_resource_id_check CHECK (resource_id > 0),
  CONSTRAINT record_correction_requests_resolution_check CHECK (
    (status = 'OPEN' AND resolved_at IS NULL AND resolution_note IS NULL)
    OR (status <> 'OPEN' AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL))
);

COMMENT ON TABLE record_correction_requests IS 'Requests by a patient or permitted caregiver to correct a clinical record, and how they were resolved';
COMMENT ON COLUMN record_correction_requests.resource_id IS 'Id of the record in the table named by resource_type (no FK: five possible tables)';
COMMENT ON COLUMN record_correction_requests.assigned_doctor_system_id IS 'Doctor who must review the request; NULL = waiting for an administrator to assign it';
COMMENT ON COLUMN record_correction_requests.resolver_system_id IS 'Doctor or administrator who accepted or rejected the request';

-- The doctor review queue: requests assigned to a doctor, filtered by status.
CREATE INDEX IF NOT EXISTS idx_correction_requests_assignee_status
  ON record_correction_requests (assigned_doctor_system_id, status);
-- The per-patient list (newest first).
CREATE INDEX IF NOT EXISTS idx_correction_requests_patient_created
  ON record_correction_requests (patient_system_id, created_at);
-- The admin list (status filter, newest first).
CREATE INDEX IF NOT EXISTS idx_correction_requests_status_created
  ON record_correction_requests (status, created_at);
-- At most one OPEN request per record; resolved requests do not block a new one.
CREATE UNIQUE INDEX IF NOT EXISTS ux_correction_requests_open_resource
  ON record_correction_requests (resource_type, resource_id) WHERE status = 'OPEN';
