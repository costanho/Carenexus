-- V55 (be-ai-generators): index for the follow-up-due job (api/be-ai-generators.md 5.5).
-- The job looks for completed consultations that need a follow-up whose follow_up_date lies in a short window
-- before the clinic's today. A partial index keeps that scan small: only completed consultations with a follow-up
-- are indexed. No table, column or CHECK changes.

CREATE INDEX IF NOT EXISTS idx_consult_follow_up_due
    ON consultations (follow_up_date)
    WHERE status = 'COMPLETED' AND follow_up_required;
