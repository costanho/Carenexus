-- be-doctor-directory-patients (V52)
--
-- 1. The note, time and author of the latest health-status change made by a doctor or an administrator through
--    PATCH /api/doctors/patients/{patientSystemId}/health-status. These columns are deliberately NOT mapped on the
--    Patient entity: DoctorPatientQueryRepository reads and writes them with native SQL. health_status_note is free
--    text about the patient, so a data export of the patient must include it and an erasure must clear it.
-- 2. Indexes for the doctor patient list (grouped appointment statistics per doctor and patient) and for the
--    doctor directory (listed doctors: verification_status APPROVED and is_active).
--
-- Creates no table and drops nothing; written defensively because developer databases hold seed data.

ALTER TABLE patients ADD COLUMN IF NOT EXISTS health_status_note VARCHAR(500) NULL;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS health_status_updated_at TIMESTAMP NULL;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS health_status_updated_by VARCHAR(20) NULL;

COMMENT ON COLUMN patients.health_status_note IS
    'Care-team note given with the latest health-status change (free text about the patient; include in data exports, clear on erasure)';
COMMENT ON COLUMN patients.health_status_updated_at IS
    'When a doctor or administrator last set health_status or its note';
COMMENT ON COLUMN patients.health_status_updated_by IS
    'System id of the doctor or administrator who last set health_status or its note';

CREATE INDEX IF NOT EXISTS idx_doctors_listing
    ON doctors (verification_status, is_active);

CREATE INDEX IF NOT EXISTS idx_appointments_doctor_patient_status_time
    ON appointments (doctor_system_id, patient_system_id, status, scheduled_at);
