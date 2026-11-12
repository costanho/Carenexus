-- ============================================================
-- V46 (be-care-plans): structured goals and interventions for care plans, plus review tracking.
--
-- care_plans (V1, re-keyed to system ids in V3) gains:
--   last_reviewed_at  clinic wall-clock time of the doctor's last review (NULL = never reviewed)
--   last_review_note  note of the last review (each review overwrites it)
-- New child tables, one row per goal / intervention, ordered by position (0-based):
--   care_plan_goals          description, target date, progress status and the latest progress note
--   care_plan_interventions  description, frequency and who is responsible
-- The legacy free-text columns care_plans.goals and care_plans.interventions stay in place but are
-- intentionally unmapped and never exposed by the API.
--
-- updated_at on care_plans: the application stamps it in the clinic zone on every plan change (PUT,
-- status, review). The V1 trigger overwrote that value with CURRENT_TIMESTAMP on every UPDATE; it is
-- replaced by one that only stamps updated_at when the statement did not set it itself, so rows changed
-- by other SQL (dev scripts, data fixes) still get a fresh value.
-- ============================================================

ALTER TABLE care_plans ADD COLUMN IF NOT EXISTS last_reviewed_at TIMESTAMP NULL;
ALTER TABLE care_plans ADD COLUMN IF NOT EXISTS last_review_note VARCHAR(1000) NULL;

-- List filters: a patient's plans and a doctor's own plans, newest first.
CREATE INDEX IF NOT EXISTS idx_care_plans_patient_created
    ON care_plans (patient_system_id, created_at DESC, plan_id DESC);
CREATE INDEX IF NOT EXISTS idx_care_plans_doctor_created
    ON care_plans (doctor_system_id, created_at DESC, plan_id DESC);

CREATE TABLE IF NOT EXISTS care_plan_goals (
    goal_id        SERIAL PRIMARY KEY,
    plan_id        INTEGER       NOT NULL REFERENCES care_plans (plan_id) ON DELETE CASCADE,
    description    VARCHAR(500)  NOT NULL,
    target_date    DATE          NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    progress_note  VARCHAR(1000) NULL,
    position       INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT chk_care_plan_goals_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ACHIEVED', 'DROPPED')),
    CONSTRAINT chk_care_plan_goals_position CHECK (position >= 0)
);
COMMENT ON TABLE care_plan_goals IS 'Goals of a care plan; status and progress note are updated by the patient, a permitted caregiver or the author doctor';
CREATE INDEX IF NOT EXISTS idx_care_plan_goals_plan ON care_plan_goals (plan_id, position, goal_id);

CREATE TABLE IF NOT EXISTS care_plan_interventions (
    intervention_id SERIAL PRIMARY KEY,
    plan_id         INTEGER      NOT NULL REFERENCES care_plans (plan_id) ON DELETE CASCADE,
    description     VARCHAR(500) NOT NULL,
    frequency       VARCHAR(100) NULL,
    responsible     VARCHAR(100) NULL,
    position        INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_care_plan_interventions_position CHECK (position >= 0)
);
COMMENT ON TABLE care_plan_interventions IS 'Interventions of a care plan; replaced as a whole when the author doctor edits the plan';
CREATE INDEX IF NOT EXISTS idx_care_plan_interventions_plan ON care_plan_interventions (plan_id, position, intervention_id);

-- updated_at: keep a value the application set, stamp one otherwise.
CREATE OR REPLACE FUNCTION care_plans_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
        NEW.updated_at = LOCALTIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS update_care_plans_updated_at ON care_plans;
CREATE TRIGGER update_care_plans_updated_at BEFORE UPDATE ON care_plans
    FOR EACH ROW EXECUTE FUNCTION care_plans_touch_updated_at();
