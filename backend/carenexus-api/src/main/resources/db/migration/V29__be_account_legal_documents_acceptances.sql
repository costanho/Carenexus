-- ============================================================
-- V29: terms of service and privacy policy with acceptance records (be-account-legal)
--   legal_documents:   every published version of the Terms of Service (TERMS) and the Privacy Policy (PRIVACY).
--                      The current version of a type is the row with the greatest effective_at (tie: greatest
--                      document_id). Versions are immutable: the API never updates or deletes a row.
--                      Reference table: the version "1" rows below ship with the schema and production needs them.
--   legal_acceptances: one row per account, document type and version the account accepted, with the client
--                      address and User-Agent recorded at that moment. Append-only.
-- References only users (V1). Written defensively (IF NOT EXISTS, ON CONFLICT). Nothing is dropped.
-- ============================================================

CREATE TABLE IF NOT EXISTS legal_documents (
  document_id            SERIAL       PRIMARY KEY,
  doc_type               VARCHAR(20)  NOT NULL,
  version                VARCHAR(20)  NOT NULL,
  title                  VARCHAR(200) NOT NULL,
  content_md             TEXT         NOT NULL,
  effective_at           TIMESTAMPTZ  NOT NULL,
  created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
  -- Administrator who published the version; NULL for the versions shipped by migrations. No foreign key, so a
  -- published version never depends on the publishing account (the audit log keeps the actor as well).
  published_by_system_id VARCHAR(20),
  CONSTRAINT legal_documents_doc_type_allowed CHECK (doc_type IN ('TERMS', 'PRIVACY')),
  CONSTRAINT ux_legal_documents_type_version UNIQUE (doc_type, version)
);

-- Current-version lookup: newest effective_at per type.
CREATE INDEX IF NOT EXISTS idx_legal_documents_type_effective
  ON legal_documents (doc_type, effective_at DESC, document_id DESC);

COMMENT ON TABLE legal_documents IS 'Published versions of the Terms of Service and Privacy Policy (be-account-legal); reference rows ship in V29';
COMMENT ON COLUMN legal_documents.doc_type               IS 'TERMS or PRIVACY';
COMMENT ON COLUMN legal_documents.version                IS 'Opaque version label, unique per doc_type, compared exactly';
COMMENT ON COLUMN legal_documents.content_md             IS 'Document text in the restricted Markdown subset (headings, paragraphs, bullets, bold, https links)';
COMMENT ON COLUMN legal_documents.effective_at           IS 'When this version took effect; the greatest value per doc_type is the current version';
COMMENT ON COLUMN legal_documents.published_by_system_id IS 'Administrator who published the version; NULL for migration-shipped rows';

CREATE TABLE IF NOT EXISTS legal_acceptances (
  acceptance_id SERIAL       PRIMARY KEY,
  system_id     VARCHAR(20)  NOT NULL REFERENCES users(system_id) ON DELETE CASCADE,
  doc_type      VARCHAR(20)  NOT NULL,
  version       VARCHAR(20)  NOT NULL,
  accepted_at   TIMESTAMPTZ  NOT NULL,
  ip_address    VARCHAR(45),
  user_agent    VARCHAR(500),
  CONSTRAINT legal_acceptances_doc_type_allowed CHECK (doc_type IN ('TERMS', 'PRIVACY')),
  CONSTRAINT ux_legal_acceptances_user_type_version UNIQUE (system_id, doc_type, version)
);

CREATE INDEX IF NOT EXISTS idx_legal_acceptances_system_accepted
  ON legal_acceptances (system_id, accepted_at DESC);

COMMENT ON TABLE legal_acceptances IS 'Which account accepted which version of the terms or privacy policy, with the evidence (be-account-legal)';
COMMENT ON COLUMN legal_acceptances.ip_address IS 'Client address of the accepting request (request remote address)';
COMMENT ON COLUMN legal_acceptances.user_agent IS 'User-Agent header of the accepting request, cut to 500 characters';

-- Version "1" of both documents. The text is placeholder copy, clearly marked as pending legal review; the
-- reviewed text is published later as a new version through POST /api/admin/legal/documents.
INSERT INTO legal_documents (doc_type, version, title, content_md, effective_at, created_at)
VALUES
  ('TERMS', '1', 'Terms of Service',
   E'**Placeholder text - pending legal review.**\n\n'
   || E'# Terms of Service\n\n'
   || E'This is placeholder text. It will be replaced by the reviewed Terms of Service before CareNexus is offered to the public, and you will be asked to accept the new version.\n\n'
   || E'## Your account\n\n'
   || E'- Keep your sign-in details private.\n'
   || E'- Tell us when your contact details change.\n'
   || E'- Use your own account; caregivers act for a dependent only through the access the dependent or their guardian granted.\n\n'
   || E'## Care and emergencies\n\n'
   || E'CareNexus helps you organise appointments, messages and records with your care team. It is **not** an emergency service: in an emergency, contact your local emergency number.\n\n'
   || E'## Changes to these terms\n\n'
   || E'When a new version of these terms is published, you are asked to review and accept it the next time you sign in.',
   TIMESTAMPTZ '2026-11-01 00:00:00+00', TIMESTAMPTZ '2026-11-01 00:00:00+00'),
  ('PRIVACY', '1', 'Privacy Policy',
   E'**Placeholder text - pending legal review.**\n\n'
   || E'# Privacy Policy\n\n'
   || E'This is placeholder text. It will be replaced by the reviewed Privacy Policy before CareNexus is offered to the public, and you will be asked to accept the new version.\n\n'
   || E'## What this policy will cover\n\n'
   || E'- Which personal and health information CareNexus keeps about you.\n'
   || E'- Who in your care circle can see it, and how you control caregiver access.\n'
   || E'- How long information is kept and how you can ask for a copy or for deletion.\n\n'
   || E'## Changes to this policy\n\n'
   || E'When a new version of this policy is published, you are asked to review and accept it the next time you sign in.',
   TIMESTAMPTZ '2026-11-01 00:00:00+00', TIMESTAMPTZ '2026-11-01 00:00:00+00')
ON CONFLICT (doc_type, version) DO NOTHING;
