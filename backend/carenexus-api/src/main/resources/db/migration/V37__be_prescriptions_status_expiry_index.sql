-- ============================================================
-- V37: prescriptions (be-prescriptions)
--   Index for the hourly expiry sweeper (PrescriptionExpirySweeper, job prescription-expiry), which looks for
--   rows with status IN ('ACTIVE', 'ON_HOLD') AND expiry_date < clinic today, and for the effective-status
--   filter of GET /api/prescriptions.
-- References only prescriptions (V1, re-keyed in V3). Written defensively (IF NOT EXISTS). Nothing is dropped.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_prescriptions_status_expiry ON prescriptions (status, expiry_date);

COMMENT ON COLUMN prescriptions.expiry_date IS 'Last valid day (inclusive, clinic calendar); NULL = no expiry. ACTIVE or ON_HOLD rows past it are set to EXPIRED by the prescription-expiry job';
