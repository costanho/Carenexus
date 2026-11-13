-- ============================================================
-- V64: facilities (be-facilities)
--   The facilities table exists since V1 but had no API. FacilityService now edits rows, so the table gets the
--   updated_at column every other editable table has, and the index behind the facility lists
--   (filter on is_active, ordered by name).
--   updated_at is added nullable, backfilled with created_at for existing rows (dev seed), then made NOT NULL
--   with a default, so a row is never without it.
-- References only facilities (V1). Nothing is renamed or dropped: other slices keep reading facility_id, name,
-- address and is_active with native SQL. Written defensively (IF NOT EXISTS) because dev databases hold seed rows.
-- ============================================================

ALTER TABLE facilities ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE facilities SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE facilities ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE facilities ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_facilities_active_name ON facilities (is_active, name);

COMMENT ON COLUMN facilities.updated_at IS 'When the facility was last changed by an administrator (clinic wall-clock); equals created_at until then';
