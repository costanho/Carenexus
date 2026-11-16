-- ============================================================
-- V41: documents stored through the FileStorage seam (be-documents)
--   documents gains the columns the documents API needs: the storage key of the stored file, the sanitised
--   original file name, the detected content type, the stored size and SHA-256, the soft-delete time and an
--   optional link to the item the document is attached to (lab result, imaging result, medical record,
--   consultation or insurance claim).
--   file_url (V1, NOT NULL) becomes nullable: rows written by the API have a storage_key instead and leave it null.
--   V3 already re-keyed the table to patient_system_id / uploaded_by_system_id (NOT NULL, FK users(system_id)) and
--   dropped the integer columns; the guarded blocks below only repeat that on a database where it did not happen.
-- References only documents (V1/V3) and users (V1). Written defensively. Nothing is dropped.
-- ============================================================

-- ---- Re-keying (no-op after V3) ----
ALTER TABLE documents ADD COLUMN IF NOT EXISTS patient_system_id     VARCHAR(20);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS uploaded_by_system_id VARCHAR(20);

DO $$
BEGIN
    -- Legacy integer columns: backfill the system ids from them and make them nullable, if they still exist.
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'documents' AND column_name = 'patient_id') THEN
        EXECUTE 'UPDATE documents d SET patient_system_id = p.system_id FROM patients p '
             || 'WHERE p.patient_id = d.patient_id AND d.patient_system_id IS NULL';
        EXECUTE 'ALTER TABLE documents ALTER COLUMN patient_id DROP NOT NULL';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'documents' AND column_name = 'uploaded_by') THEN
        EXECUTE 'UPDATE documents d SET uploaded_by_system_id = u.system_id FROM users u '
             || 'WHERE u.user_id = d.uploaded_by AND d.uploaded_by_system_id IS NULL';
        EXECUTE 'ALTER TABLE documents ALTER COLUMN uploaded_by DROP NOT NULL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM documents WHERE patient_system_id IS NULL) THEN
        ALTER TABLE documents ALTER COLUMN patient_system_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM documents WHERE uploaded_by_system_id IS NULL) THEN
        ALTER TABLE documents ALTER COLUMN uploaded_by_system_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'documents_patient_system_id_fkey') THEN
        ALTER TABLE documents ADD CONSTRAINT documents_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'documents_uploaded_by_system_id_fkey') THEN
        ALTER TABLE documents ADD CONSTRAINT documents_uploaded_by_system_id_fkey
            FOREIGN KEY (uploaded_by_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
END $$;

-- ---- file_url: rows written by the API carry a storage key instead ----
ALTER TABLE documents ALTER COLUMN file_url DROP NOT NULL;

-- ---- Stored file, soft delete, link ----
ALTER TABLE documents ADD COLUMN IF NOT EXISTS deleted_at        TIMESTAMP;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS storage_key       VARCHAR(200);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS content_type      VARCHAR(100);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS size_bytes        BIGINT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS sha256            VARCHAR(64);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS link_type         VARCHAR(30);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS link_id           INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_documents_link_type') THEN
        ALTER TABLE documents ADD CONSTRAINT chk_documents_link_type
            CHECK (link_type IS NULL OR link_type IN
                   ('LAB_RESULT', 'IMAGING_RESULT', 'MEDICAL_RECORD', 'CONSULTATION', 'INSURANCE_CLAIM'));
    END IF;
    -- A link id only exists together with a link type.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_documents_link_id_needs_type') THEN
        ALTER TABLE documents ADD CONSTRAINT chk_documents_link_id_needs_type
            CHECK (link_id IS NULL OR link_type IS NOT NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_documents_size_bytes') THEN
        ALTER TABLE documents ADD CONSTRAINT chk_documents_size_bytes
            CHECK (size_bytes IS NULL OR size_bytes >= 0);
    END IF;
END $$;

-- ---- Indexes ----
CREATE UNIQUE INDEX IF NOT EXISTS uq_documents_storage_key
    ON documents (storage_key) WHERE storage_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_documents_patient_active
    ON documents (patient_system_id, is_deleted, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_documents_patient_type
    ON documents (patient_system_id, document_type);
CREATE INDEX IF NOT EXISTS idx_documents_link
    ON documents (link_type, link_id);

COMMENT ON COLUMN documents.file_url          IS 'Legacy free-text location (V1); null on rows stored through the documents API';
COMMENT ON COLUMN documents.deleted_at        IS 'When the document was soft-deleted (clinic wall-clock); the stored file is kept';
COMMENT ON COLUMN documents.storage_key       IS 'FileStorage key documents/<yyyy>/<mm>/<uuid>; never derived from the client file name';
COMMENT ON COLUMN documents.original_filename IS 'Sanitised client file name used for Content-Disposition on download';
COMMENT ON COLUMN documents.content_type      IS 'Content type detected from the bytes by FileScanner (PDF, JPEG, PNG or DICOM)';
COMMENT ON COLUMN documents.size_bytes        IS 'Stored size in bytes';
COMMENT ON COLUMN documents.sha256            IS 'SHA-256 of the stored bytes (lower-case hex)';
COMMENT ON COLUMN documents.link_type         IS 'What the document is attached to: LAB_RESULT, IMAGING_RESULT, MEDICAL_RECORD, CONSULTATION or INSURANCE_CLAIM';
COMMENT ON COLUMN documents.link_id           IS 'Row id of the linked item (no foreign key: it points at one of five tables); null when not known yet';
