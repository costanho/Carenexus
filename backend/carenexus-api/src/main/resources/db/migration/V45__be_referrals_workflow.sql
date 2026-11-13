-- ============================================================
-- V45 (be-referrals): referral workflow columns.
--
-- The referrals table exists since V1 (re-keyed to system ids in V3) but had no code. This adds what the
-- workflow needs:
--   updated_at                        last change (backfilled from created_at)
--   responded_at, completed_at        when the referral was accepted or declined, and completed
--   decline_reason                    reason given when DECLINED (max 500)
--   outcome_note                      note of the latest accept or complete (max 1000)
--   outcome_recorded_by_system_id     who recorded the latest decision on the specialist's behalf
--                                     (the referring doctor or an administrator); null when the named
--                                     platform specialist acted
--   consultation_id                   consultation the referral was issued from
--   external_specialist_name / _organisation / _contact (max 150 each)
--                                     a specialist outside the platform
--
-- A referral has at most one of a platform specialist (specialist_system_id) and an external specialist
-- name; both may be empty while the referral is unassigned. CANCELLED joins the status CHECK.
-- The legacy columns appointment_date and notes stay unmapped.
-- ============================================================

ALTER TABLE referrals ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
UPDATE referrals SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE referrals ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE referrals ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE referrals ADD COLUMN IF NOT EXISTS responded_at TIMESTAMP;
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS decline_reason VARCHAR(500);
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS outcome_note VARCHAR(1000);
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS outcome_recorded_by_system_id VARCHAR(20);
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS consultation_id INTEGER;
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS external_specialist_name VARCHAR(150);
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS external_specialist_organisation VARCHAR(150);
ALTER TABLE referrals ADD COLUMN IF NOT EXISTS external_specialist_contact VARCHAR(150);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'referrals_outcome_recorded_by_fkey') THEN
        ALTER TABLE referrals ADD CONSTRAINT referrals_outcome_recorded_by_fkey
            FOREIGN KEY (outcome_recorded_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'referrals_consultation_id_fkey') THEN
        ALTER TABLE referrals ADD CONSTRAINT referrals_consultation_id_fkey
            FOREIGN KEY (consultation_id) REFERENCES consultations(consultation_id) ON DELETE SET NULL;
    END IF;
END $$;

-- Status: V1 allowed PENDING, ACCEPTED, COMPLETED, DECLINED; the referring doctor can now withdraw (CANCELLED).
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_status_check;
ALTER TABLE referrals ADD CONSTRAINT referrals_status_check
    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'COMPLETED', 'CANCELLED'));

-- At most one kind of specialist (both may be empty while unassigned).
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_one_specialist_check;
ALTER TABLE referrals ADD CONSTRAINT referrals_one_specialist_check
    CHECK (specialist_system_id IS NULL OR external_specialist_name IS NULL);

-- Specialist inbox with a status filter, and the newest-first lists.
CREATE INDEX IF NOT EXISTS idx_referrals_specialist_status ON referrals (specialist_system_id, status);
CREATE INDEX IF NOT EXISTS idx_referrals_doctor_created ON referrals (referring_doctor_system_id, created_at DESC, referral_id DESC);
CREATE INDEX IF NOT EXISTS idx_referrals_patient_created ON referrals (patient_system_id, created_at DESC, referral_id DESC);
CREATE INDEX IF NOT EXISTS idx_referrals_consultation ON referrals (consultation_id) WHERE consultation_id IS NOT NULL;
