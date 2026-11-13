-- ============================================================
-- V42 (be-care-team): care team management
--   1. External providers: provider_system_id becomes nullable, with provider_name / provider_phone /
--      provider_organisation for members who have no CareNexus account (a pharmacy, a lab, a nurse...).
--      A CHECK keeps every row pointing at an account or carrying a name.
--   2. One active row per (patient, provider account): partial unique index WHERE is_active.
--   3. At most one active primary member per patient: partial unique index WHERE is_active AND is_primary.
--   4. Legacy PRIMARY_DOCTOR rows: the oldest active one of each patient that has no primary yet gets
--      is_primary = TRUE (at most one per patient, so index 3 can be created). Roles are NOT rewritten and the V10
--      role CHECK is NOT narrowed: PRIMARY_DOCTOR, CAREGIVER and ASSIGNED_CAREGIVER stay valid stored values
--      (test fixtures and CareTeamRepository.DOCTOR_ROLES use PRIMARY_DOCTOR). The API reads a PRIMARY_DOCTOR row
--      as role DOCTOR with isPrimary true and never writes that value.
--   5. Index for a provider's own assignment list, newest first.
-- unassigned_at already exists since V10 (kept with IF NOT EXISTS for databases that drifted).
-- Written defensively like V13: dev databases may hold hand-made rows. When existing active rows already break
-- one of the unique rules, that index is skipped with a NOTICE instead of failing the migration (the service
-- enforces the same rules under a per-patient lock). Nothing is dropped.
-- ============================================================

-- ------------------------------------------------------------
-- 1. External providers
-- ------------------------------------------------------------
ALTER TABLE care_team ALTER COLUMN provider_system_id DROP NOT NULL;

ALTER TABLE care_team ADD COLUMN IF NOT EXISTS provider_name         VARCHAR(150);
ALTER TABLE care_team ADD COLUMN IF NOT EXISTS provider_phone        VARCHAR(20);
ALTER TABLE care_team ADD COLUMN IF NOT EXISTS provider_organisation VARCHAR(200);
ALTER TABLE care_team ADD COLUMN IF NOT EXISTS unassigned_at         TIMESTAMP;

COMMENT ON COLUMN care_team.provider_system_id    IS 'CareNexus account of the provider (a doctor); NULL for an external provider entered by name';
COMMENT ON COLUMN care_team.provider_name         IS 'External provider name (no account); NULL when provider_system_id is set';
COMMENT ON COLUMN care_team.provider_phone        IS 'External provider phone; NULL when provider_system_id is set';
COMMENT ON COLUMN care_team.provider_organisation IS 'External provider organisation; NULL when provider_system_id is set';
COMMENT ON COLUMN care_team.unassigned_at         IS 'Clinic wall-clock time the member was removed (is_active = FALSE)';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'care_team_provider_present_check') THEN
        ALTER TABLE care_team
            ADD CONSTRAINT care_team_provider_present_check
            CHECK (provider_system_id IS NOT NULL OR (provider_name IS NOT NULL AND btrim(provider_name) <> ''));
    END IF;
END
$$;

-- ------------------------------------------------------------
-- 4. Legacy primary doctors (before the index that depends on it)
--    Only patients with no active primary row are touched, and only one row per patient (DISTINCT ON).
-- ------------------------------------------------------------
UPDATE care_team ct
   SET is_primary = TRUE
 WHERE ct.care_team_id IN (
        SELECT DISTINCT ON (c.patient_system_id) c.care_team_id
          FROM care_team c
         WHERE c.is_active
           AND c.role = 'PRIMARY_DOCTOR'
           AND NOT EXISTS (SELECT 1
                             FROM care_team p
                            WHERE p.patient_system_id = c.patient_system_id
                              AND p.is_active
                              AND p.is_primary)
         ORDER BY c.patient_system_id, c.assigned_at, c.care_team_id);

-- ------------------------------------------------------------
-- 2. and 3. Unique rules
-- ------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM care_team
         WHERE is_active AND provider_system_id IS NOT NULL
         GROUP BY patient_system_id, provider_system_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V42: skipped ux_care_team_active_provider: a provider has several active rows for one patient';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_care_team_active_provider
            ON care_team (patient_system_id, provider_system_id)
            WHERE is_active;
    END IF;

    IF EXISTS (
        SELECT 1 FROM care_team
         WHERE is_active AND is_primary
         GROUP BY patient_system_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V42: skipped ux_care_team_active_primary: a patient has several active primary members';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_care_team_active_primary
            ON care_team (patient_system_id)
            WHERE is_active AND is_primary;
    END IF;
END
$$;

-- ------------------------------------------------------------
-- 5. A provider's assignments, newest first (GET /api/doctors/care-team)
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_care_team_provider_assigned
    ON care_team (provider_system_id, assigned_at DESC, care_team_id DESC)
    WHERE is_active;
