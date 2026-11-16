-- be-billing-invoices: index for GET /api/doctors/billing/uninvoiced.
-- The list reads one doctor's COMPLETED consultations newest completion first (nulls last, then id) and checks
-- each against invoices (already indexed by consultation_id and by the partial unique index on appointment_id).
-- This partial index lets the page be read in order without sorting every completed consultation of the doctor.
-- Non-destructive and idempotent.
CREATE INDEX IF NOT EXISTS idx_consultations_doctor_completed
    ON consultations (doctor_system_id, completed_at DESC NULLS LAST, consultation_id DESC)
    WHERE status = 'COMPLETED';
