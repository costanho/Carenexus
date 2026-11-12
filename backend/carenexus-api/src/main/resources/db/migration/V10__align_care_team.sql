-- ============================================================
-- V10: align care_team with the CareTeam entity
--   V1/V3 created team_id + joined_date with a narrow role CHECK; the entity (and every
--   CareTeamRepository query) uses care_team_id / assigned_at / unassigned_at and roles such as
--   PRIMARY_DOCTOR. Without this, every care_team query fails with "column does not exist".
--   The table is not populated by any earlier migration or code path, so no data is rewritten.
-- ============================================================

ALTER TABLE care_team RENAME COLUMN team_id TO care_team_id;

ALTER TABLE care_team ADD COLUMN assigned_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE care_team ADD COLUMN unassigned_at TIMESTAMP;
ALTER TABLE care_team ALTER COLUMN joined_date SET DEFAULT CURRENT_DATE;

ALTER TABLE care_team DROP CONSTRAINT IF EXISTS care_team_role_check;
ALTER TABLE care_team ALTER COLUMN role TYPE VARCHAR(50);
ALTER TABLE care_team ADD CONSTRAINT care_team_role_check CHECK (role IN (
  'PRIMARY_DOCTOR','DOCTOR','SPECIALIST','NURSE','LAB','PHARMACY','THERAPIST','CAREGIVER','ASSIGNED_CAREGIVER'
));

CREATE INDEX IF NOT EXISTS idx_care_team_active ON care_team(provider_system_id, patient_system_id) WHERE is_active;
