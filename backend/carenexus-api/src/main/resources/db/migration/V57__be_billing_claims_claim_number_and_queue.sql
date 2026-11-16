-- ============================================================
-- V57 (be-billing-claims): insurance claim numbering and list indexes.
--
--   1. claim_no_seq: numeric part of CLM-<year>-<nnnnnn> claim numbers (starts at 1, never reused).
--   2. insurance_claims.rejection_reason / appeal_reason hold at least 1000 characters. V49 left both as TEXT,
--      so nothing is widened; the guarded block only widens a shorter VARCHAR should an environment differ.
--   3. Indexes for the administrator review queue (status, submitted_at) and for a patient's claim list in its
--      fixed order (submitted_at DESC, claim_id DESC). V49 already created idx_claim_patient_system_status,
--      idx_claim_invoice and the one-open-claim-per-invoice unique index ux_insurance_claims_open.
--
-- Non-destructive and idempotent: nothing is dropped, every object is created IF NOT EXISTS.
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS claim_no_seq START WITH 1 INCREMENT BY 1 MINVALUE 1 NO CYCLE;

COMMENT ON SEQUENCE claim_no_seq IS 'Numeric part of insurance claim numbers CLM-<year>-<nnnnnn> (be-billing-claims)';

DO $$
DECLARE
    col RECORD;
BEGIN
    FOR col IN
        SELECT column_name, data_type, character_maximum_length
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'insurance_claims'
           AND column_name IN ('rejection_reason', 'appeal_reason')
    LOOP
        IF col.data_type = 'character varying' AND col.character_maximum_length IS NOT NULL
           AND col.character_maximum_length < 1000 THEN
            EXECUTE format('ALTER TABLE insurance_claims ALTER COLUMN %I TYPE TEXT', col.column_name);
        END IF;
    END LOOP;
END
$$;

-- Administrator queue: GET /api/admin/billing/claims?status=SUBMITTED,PENDING,APPEALED, newest first.
CREATE INDEX IF NOT EXISTS idx_claim_status_submitted
    ON insurance_claims (status, submitted_at DESC, claim_id DESC);

-- A patient's claims, newest first: GET /api/billing/claims?patientSystemId=.
CREATE INDEX IF NOT EXISTS idx_claim_patient_submitted
    ON insurance_claims (patient_system_id, submitted_at DESC, claim_id DESC);
