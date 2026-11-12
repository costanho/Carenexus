-- ============================================================
-- V15: separate, patient-approved communication permissions on dependent_access
--   can_message_care_team (exists since V1) and the new can_join_calls are REQUESTED permissions.
--   They only take effect with a patient approval stamp: *_approved_at set AND
--   *_approved_by_system_id = patient_system_id. FULL_ACCESS never implies them.
--   Reapproval rule: every existing grant starts without an approval stamp, so existing
--   messaging grants (possibly self-edited by caregivers before) need the patient to approve again.
-- ============================================================

ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS can_join_calls                  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS messaging_approved_at           TIMESTAMPTZ;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS messaging_approved_by_system_id VARCHAR(20);
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS calls_approved_at               TIMESTAMPTZ;
ALTER TABLE dependent_access ADD COLUMN IF NOT EXISTS calls_approved_by_system_id     VARCHAR(20);

ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_messaging_approved_by_fkey
  FOREIGN KEY (messaging_approved_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_calls_approved_by_fkey
  FOREIGN KEY (calls_approved_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;

-- Reapproval: explicit reset of any approval (no-op on a fresh database, documents the rule).
UPDATE dependent_access
   SET messaging_approved_at = NULL, messaging_approved_by_system_id = NULL,
       calls_approved_at = NULL, calls_approved_by_system_id = NULL;

-- An approval can only exist for a requested permission and only from the patient themselves.
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_messaging_approval_check CHECK (
  (messaging_approved_at IS NULL AND messaging_approved_by_system_id IS NULL)
  OR (messaging_approved_at IS NOT NULL AND can_message_care_team
      AND messaging_approved_by_system_id = patient_system_id));
ALTER TABLE dependent_access ADD CONSTRAINT dependent_access_calls_approval_check CHECK (
  (calls_approved_at IS NULL AND calls_approved_by_system_id IS NULL)
  OR (calls_approved_at IS NOT NULL AND can_join_calls
      AND calls_approved_by_system_id = patient_system_id));

COMMENT ON COLUMN dependent_access.can_join_calls IS 'Requested permission to join video calls; effective only with calls_approved_at';
COMMENT ON COLUMN dependent_access.messaging_approved_at IS 'When the patient approved can_message_care_team';
COMMENT ON COLUMN dependent_access.calls_approved_at IS 'When the patient approved can_join_calls';
