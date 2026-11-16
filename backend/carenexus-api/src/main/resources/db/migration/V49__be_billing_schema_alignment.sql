-- ============================================================
-- V49: billing schema alignment (be-billing-schema)
--   1. payment_methods : owner re-keyed to system_id, instrument detail columns, soft delete, type CHECK
--                        without CRYPTO, one default method per owner
--   2. invoices        : patient re-keyed to patient_system_id, issuing doctor, creator, issue time,
--                        updated_at, amount_paid, cancel reason, PARTIALLY_PAID status, amount CHECKs,
--                        one non-cancelled invoice per appointment and per consultation, invoice_no_seq
--   3. payments        : patient re-keyed to patient_system_id, method, provider reference, failure and
--                        refund columns, payer phone, initiator, updated_at, amount CHECKs
--   4. insurance_claims: patient re-keyed to patient_system_id, submitter, reviewer, claim number, appeal
--                        reason, provider reference, document ids, updated_at, one open claim per invoice
--   5. payment_events  : idempotent log of payment gateway webhooks (no payload stored)
--   6. billing_fee_schedule: consultation fees per appointment type (clinic default and doctor override)
--                        with the clinic default rows
--   7. validation of the CHECK constraints added above
--
-- Nothing is dropped: the legacy integer keys (invoices.patient_id, payments.patient_id,
-- insurance_claims.patient_id, payment_methods.user_id) stay as nullable, unmapped columns, and so do
-- payment_methods.token and insurance_claims.documents. The V3 views (v_patient_dashboard,
-- v_doctor_workload) do not read any billing table.
--
-- No code path and no seed script has written these four V1 tables before this migration, so they are
-- expected to be empty. The migration is still written for rows of the V1 shape: keys are backfilled,
-- each new CHECK is added NOT VALID (it applies to every new or changed row at once) and validated in
-- step 7 when the existing rows satisfy it, and of several default payment methods of one owner the newest
-- stays the default. Rows that break one of the new uniqueness rules (two non-cancelled invoices for one
-- visit, two open claims for one invoice) are not rewritten: the migration stops at the unique index and
-- names the duplicated key, so the rows can be resolved by a person.
-- ============================================================

-- ------------------------------------------------------------
-- 1. payment_methods
-- ------------------------------------------------------------
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS system_id      VARCHAR(20);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS mpesa_phone    VARCHAR(20);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS insurer_name   VARCHAR(100);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS policy_number  VARCHAR(100);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS member_number  VARCHAR(100);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS display_label  VARCHAR(120);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS provider_token VARCHAR(255);
ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS is_deleted     BOOLEAN NOT NULL DEFAULT FALSE;

-- user_id was NOT NULL with a foreign key, so every existing row finds its owner.
UPDATE payment_methods pm
   SET system_id = u.system_id
  FROM users u
 WHERE u.user_id = pm.user_id
   AND pm.system_id IS NULL;

ALTER TABLE payment_methods ALTER COLUMN system_id SET NOT NULL;
ALTER TABLE payment_methods ALTER COLUMN user_id DROP NOT NULL;

-- The gateway token moves to provider_token; the legacy column keeps its value and is no longer read.
UPDATE payment_methods
   SET provider_token = token
 WHERE provider_token IS NULL
   AND token IS NOT NULL;

-- A type outside the five supported ones (V1 also allowed CRYPTO) cannot be used any more: the row is kept
-- but retired.
UPDATE payment_methods
   SET is_active = FALSE, is_default = FALSE, is_deleted = TRUE
 WHERE type NOT IN ('CARD', 'INSURANCE', 'CASH', 'MPESA', 'BANK_TRANSFER');

-- V1 had no rule for the default method: keep the newest default of each owner.
UPDATE payment_methods pm
   SET is_default = FALSE
 WHERE pm.is_default AND pm.is_active AND NOT pm.is_deleted
   AND EXISTS (SELECT 1
                 FROM payment_methods newer
                WHERE newer.system_id = pm.system_id
                  AND newer.is_default AND newer.is_active AND NOT newer.is_deleted
                  AND newer.method_id > pm.method_id);

DO $$
DECLARE
    old_check RECORD;
BEGIN
    -- Replace the V1 CHECK on type (the one that still lists CRYPTO), whatever it was named.
    FOR old_check IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'payment_methods'::regclass
           AND c.contype = 'c'
           AND c.conname <> 'payment_methods_type_allowed'
           AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                                  WHERE a.attrelid = 'payment_methods'::regclass AND a.attname = 'type')]
    LOOP
        EXECUTE format('ALTER TABLE payment_methods DROP CONSTRAINT %I', old_check.conname);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payment_methods_type_allowed') THEN
        ALTER TABLE payment_methods
            ADD CONSTRAINT payment_methods_type_allowed
            CHECK (type IN ('CARD', 'INSURANCE', 'CASH', 'MPESA', 'BANK_TRANSFER')) NOT VALID;
    END IF;

    -- Same rule as the V1 key: the methods go with the account.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payment_methods_system_id_fkey') THEN
        ALTER TABLE payment_methods
            ADD CONSTRAINT payment_methods_system_id_fkey
            FOREIGN KEY (system_id) REFERENCES users(system_id) ON DELETE CASCADE;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_pm_system ON payment_methods (system_id);
-- One default per owner among the methods that can still be used.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_methods_default
    ON payment_methods (system_id)
    WHERE is_default AND is_active AND NOT is_deleted;

COMMENT ON COLUMN payment_methods.system_id      IS 'Owner of the method (users.system_id)';
COMMENT ON COLUMN payment_methods.user_id        IS 'Legacy integer owner key, replaced by system_id in V49; not written any more';
COMMENT ON COLUMN payment_methods.mpesa_phone    IS 'MPESA: the phone number as the owner typed it';
COMMENT ON COLUMN payment_methods.insurer_name   IS 'INSURANCE: name of the insurer';
COMMENT ON COLUMN payment_methods.policy_number  IS 'INSURANCE: policy number';
COMMENT ON COLUMN payment_methods.member_number  IS 'INSURANCE: member number, when the insurer issues one';
COMMENT ON COLUMN payment_methods.display_label  IS 'Masked label shown in lists; never holds a full card or phone number';
COMMENT ON COLUMN payment_methods.provider_token IS 'Opaque token of the payment gateway; never returned by the API';
COMMENT ON COLUMN payment_methods.token          IS 'Legacy token column, replaced by provider_token in V49; not written any more';
COMMENT ON COLUMN payment_methods.is_deleted     IS 'Soft delete: removed by the owner, kept for the payments that used it';

-- ------------------------------------------------------------
-- 2. invoices
-- ------------------------------------------------------------
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS patient_system_id    VARCHAR(20);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS doctor_system_id     VARCHAR(20);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS created_by_system_id VARCHAR(20);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS issued_at            TIMESTAMP;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS updated_at           TIMESTAMP;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS amount_paid          NUMERIC(12,2) NOT NULL DEFAULT 0.00;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS currency             VARCHAR(3) NOT NULL DEFAULT 'KES';
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS cancel_reason        VARCHAR(500);

-- patient_id was NOT NULL with a foreign key, so every existing row finds its patient.
UPDATE invoices i
   SET patient_system_id = p.system_id
  FROM patients p
 WHERE p.patient_id = i.patient_id
   AND i.patient_system_id IS NULL;

ALTER TABLE invoices ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE invoices ALTER COLUMN patient_id DROP NOT NULL;

-- Existing rows: the issuing doctor is the doctor of the linked visit, where there is one.
UPDATE invoices i
   SET doctor_system_id = c.doctor_system_id
  FROM consultations c
 WHERE c.consultation_id = i.consultation_id
   AND i.doctor_system_id IS NULL;
UPDATE invoices i
   SET doctor_system_id = a.doctor_system_id
  FROM appointments a
 WHERE a.appointment_id = i.appointment_id
   AND i.doctor_system_id IS NULL;

-- Existing rows that already left DRAFT were issued when they were created (V1 kept no issue time), and a
-- PAID one is paid in full.
UPDATE invoices
   SET issued_at = created_at
 WHERE issued_at IS NULL
   AND status IN ('SENT', 'PAID', 'OVERDUE', 'REFUNDED');
UPDATE invoices
   SET amount_paid = total
 WHERE status = 'PAID'
   AND amount_paid = 0
   AND total > 0;

UPDATE invoices SET updated_at = COALESCE(paid_at, created_at) WHERE updated_at IS NULL;
ALTER TABLE invoices ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE invoices ALTER COLUMN updated_at SET NOT NULL;

DO $$
DECLARE
    old_check RECORD;
BEGIN
    -- Replace the V1 CHECK on status (no PARTIALLY_PAID), whatever it was named.
    FOR old_check IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'invoices'::regclass
           AND c.contype = 'c'
           AND c.conname <> 'invoices_status_allowed'
           AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                                  WHERE a.attrelid = 'invoices'::regclass AND a.attname = 'status')]
    LOOP
        EXECUTE format('ALTER TABLE invoices DROP CONSTRAINT %I', old_check.conname);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_status_allowed') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_status_allowed
            CHECK (status IN ('DRAFT', 'SENT', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED', 'REFUNDED')) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_amounts_not_negative') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_amounts_not_negative
            CHECK (subtotal >= 0 AND tax >= 0 AND discount >= 0 AND total >= 0 AND amount_paid >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_amount_paid_within_total') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_amount_paid_within_total
            CHECK (amount_paid <= total) NOT VALID;
    END IF;

    -- Financial records keep their patient and doctor (same rule as the clinical tables since V3).
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_patient_system_id_fkey') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_doctor_system_id_fkey') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_doctor_system_id_fkey
            FOREIGN KEY (doctor_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    -- The invoice outlives the account of whoever keyed it in.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'invoices_created_by_system_id_fkey') THEN
        ALTER TABLE invoices
            ADD CONSTRAINT invoices_created_by_system_id_fkey
            FOREIGN KEY (created_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END
$$;

DROP TRIGGER IF EXISTS update_invoices_updated_at ON invoices;
CREATE TRIGGER update_invoices_updated_at BEFORE UPDATE ON invoices
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- One invoice per visit. A CANCELLED invoice frees the visit; a REFUNDED one still counts as its invoice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_invoices_appointment_active
    ON invoices (appointment_id)
    WHERE appointment_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX IF NOT EXISTS ux_invoices_consultation_active
    ON invoices (consultation_id)
    WHERE consultation_id IS NOT NULL AND status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS idx_inv_patient_system_status ON invoices (patient_system_id, status);
CREATE INDEX IF NOT EXISTS idx_inv_doctor_system_status  ON invoices (doctor_system_id, status);
CREATE INDEX IF NOT EXISTS idx_inv_consultation          ON invoices (consultation_id);
-- The overdue sweeper reads only invoices that can still become OVERDUE.
CREATE INDEX IF NOT EXISTS idx_inv_due_open ON invoices (due_date, invoice_id)
    WHERE status IN ('SENT', 'PARTIALLY_PAID');

-- Numeric part of invoice numbers: INV-<yyyy>-<6 digits of nextval>. Numbers from 900000 upwards are reserved
-- for fixed demo rows (dev seed), so they are ignored when the start value is chosen.
CREATE SEQUENCE IF NOT EXISTS invoice_no_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;
SELECT setval('invoice_no_seq',
              COALESCE((SELECT MAX(numbered.n)
                          FROM (SELECT CASE WHEN invoice_no ~ '^INV-[0-9]{4}-[0-9]{6}$'
                                            THEN CAST(SUBSTRING(invoice_no FROM 10) AS BIGINT) END AS n
                                  FROM invoices) numbered
                         WHERE numbered.n < 900000), 0) + 1,
              false);
COMMENT ON SEQUENCE invoice_no_seq IS 'Numeric part of invoice numbers (INV-yyyy-nnnnnn)';

COMMENT ON COLUMN invoices.patient_system_id    IS 'Billed patient (users.system_id)';
COMMENT ON COLUMN invoices.patient_id           IS 'Legacy integer patient key, replaced by patient_system_id in V49; not written any more';
COMMENT ON COLUMN invoices.doctor_system_id     IS 'Issuing doctor: the doctor of the linked consultation or appointment';
COMMENT ON COLUMN invoices.created_by_system_id IS 'Account that created the invoice (the doctor or an administrator)';
COMMENT ON COLUMN invoices.issued_at            IS 'When the invoice was sent to the patient; NULL while it was never issued';
COMMENT ON COLUMN invoices.amount_paid          IS 'Sum of the payments applied so far, never above total';
COMMENT ON COLUMN invoices.cancel_reason        IS 'Reason given when the invoice was cancelled or voided';
COMMENT ON COLUMN invoices.line_items           IS 'JSON array of {description, quantity, unitPrice, amount}';

-- ------------------------------------------------------------
-- 3. payments
-- ------------------------------------------------------------
ALTER TABLE payments ADD COLUMN IF NOT EXISTS patient_system_id      VARCHAR(20);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS method                 VARCHAR(20);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider_reference     VARCHAR(255);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS failure_reason         VARCHAR(500);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS initiated_by_system_id VARCHAR(20);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS refunded_amount        NUMERIC(12,2) NOT NULL DEFAULT 0.00;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS refund_reason          VARCHAR(500);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS payer_phone            VARCHAR(20);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS payment_method_id      INTEGER REFERENCES payment_methods(method_id) ON DELETE SET NULL;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS updated_at             TIMESTAMP;

UPDATE payments pay
   SET patient_system_id = p.system_id
  FROM patients p
 WHERE p.patient_id = pay.patient_id
   AND pay.patient_system_id IS NULL;

ALTER TABLE payments ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE payments ALTER COLUMN patient_id DROP NOT NULL;

-- V1 recorded the method only through the saved payment method.
UPDATE payments pay
   SET method = pm.type
  FROM payment_methods pm
 WHERE pm.method_id = pay.payment_method_id
   AND pay.method IS NULL
   AND pm.type IN ('CARD', 'INSURANCE', 'CASH', 'MPESA', 'BANK_TRANSFER');

UPDATE payments SET updated_at = COALESCE(paid_at, created_at) WHERE updated_at IS NULL;
ALTER TABLE payments ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE payments ALTER COLUMN updated_at SET NOT NULL;

DO $$
BEGIN
    -- NOT NULL as soon as no V1 row is left without a method (always the case on an empty table). The CHECK
    -- below requires a method on every new or changed row either way.
    IF NOT EXISTS (SELECT 1 FROM payments WHERE method IS NULL) THEN
        ALTER TABLE payments ALTER COLUMN method SET NOT NULL;
    ELSE
        RAISE NOTICE 'V49: payments rows without a recorded method were found; payments.method stays nullable for them';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_method_allowed') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_method_allowed
            CHECK (method IS NOT NULL AND method IN ('CARD', 'INSURANCE', 'CASH', 'MPESA', 'BANK_TRANSFER')) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_amount_positive') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_amount_positive
            CHECK (amount > 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_refunded_amount_range') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_refunded_amount_range
            CHECK (refunded_amount >= 0 AND refunded_amount <= amount) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_patient_system_id_fkey') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    -- The payment outlives the account of whoever started it (patient, caregiver or administrator).
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_initiated_by_system_id_fkey') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_initiated_by_system_id_fkey
            FOREIGN KEY (initiated_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END
$$;

DROP TRIGGER IF EXISTS update_payments_updated_at ON payments;
CREATE TRIGGER update_payments_updated_at BEFORE UPDATE ON payments
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE INDEX IF NOT EXISTS idx_pay_patient_system_status ON payments (patient_system_id, status);
CREATE INDEX IF NOT EXISTS idx_pay_method                ON payments (payment_method_id);
-- Webhook lookup: the gateway reports its own reference. Not unique: manual payments store a typed reference.
CREATE INDEX IF NOT EXISTS idx_pay_gateway_reference ON payments (gateway, provider_reference)
    WHERE provider_reference IS NOT NULL;
-- The reconciliation sweeper reads only payments that are still waiting for the gateway.
CREATE INDEX IF NOT EXISTS idx_pay_pending_created ON payments (created_at)
    WHERE status = 'PENDING';

COMMENT ON COLUMN payments.patient_system_id      IS 'Patient the payment is for (users.system_id)';
COMMENT ON COLUMN payments.patient_id             IS 'Legacy integer patient key, replaced by patient_system_id in V49; not written any more';
COMMENT ON COLUMN payments.method                 IS 'CARD, INSURANCE, CASH, MPESA or BANK_TRANSFER; recorded on the payment itself';
COMMENT ON COLUMN payments.provider_reference     IS 'Reference of the payment at the gateway (or the reference typed for a manual payment)';
COMMENT ON COLUMN payments.failure_reason         IS 'Short displayable reason when the payment failed';
COMMENT ON COLUMN payments.initiated_by_system_id IS 'Account that started or recorded the payment';
COMMENT ON COLUMN payments.refunded_amount        IS 'Cumulative amount refunded, never above amount';
COMMENT ON COLUMN payments.refund_reason          IS 'Reason recorded with the last refund';
COMMENT ON COLUMN payments.payer_phone            IS 'MPESA: phone number the payment prompt was sent to; returned masked only';
COMMENT ON COLUMN payments.gateway_response       IS 'Sanitised gateway response (no card data, no secrets); never returned by the API';

-- ------------------------------------------------------------
-- 4. insurance_claims
-- ------------------------------------------------------------
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS patient_system_id      VARCHAR(20);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS submitted_by_system_id VARCHAR(20);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS reviewed_by_system_id  VARCHAR(20);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS updated_at             TIMESTAMP;
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS claim_no               VARCHAR(50);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS appeal_reason          TEXT;
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS provider_reference     VARCHAR(255);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS payment_id             INTEGER REFERENCES payments(payment_id) ON DELETE SET NULL;
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS member_number          VARCHAR(100);
ALTER TABLE insurance_claims ADD COLUMN IF NOT EXISTS document_ids           JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE insurance_claims ic
   SET patient_system_id = p.system_id
  FROM patients p
 WHERE p.patient_id = ic.patient_id
   AND ic.patient_system_id IS NULL;

ALTER TABLE insurance_claims ALTER COLUMN patient_system_id SET NOT NULL;
ALTER TABLE insurance_claims ALTER COLUMN patient_id DROP NOT NULL;

UPDATE insurance_claims SET updated_at = COALESCE(resolved_at, submitted_at) WHERE updated_at IS NULL;
ALTER TABLE insurance_claims ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE insurance_claims ALTER COLUMN updated_at SET NOT NULL;

DO $$
DECLARE
    old_check RECORD;
BEGIN
    -- Restate the status CHECK under a fixed name (same six values as V1).
    FOR old_check IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'insurance_claims'::regclass
           AND c.contype = 'c'
           AND c.conname <> 'insurance_claims_status_allowed'
           AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                                  WHERE a.attrelid = 'insurance_claims'::regclass AND a.attname = 'status')]
    LOOP
        EXECUTE format('ALTER TABLE insurance_claims DROP CONSTRAINT %I', old_check.conname);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_status_allowed') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_status_allowed
            CHECK (status IN ('SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'PARTIAL', 'APPEALED')) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_claim_amount_positive') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_claim_amount_positive
            CHECK (claim_amount > 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_approved_amount_not_negative') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_approved_amount_not_negative
            CHECK (approved_amount IS NULL OR approved_amount >= 0) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_claim_no_key') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_claim_no_key UNIQUE (claim_no);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_patient_system_id_fkey') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_patient_system_id_fkey
            FOREIGN KEY (patient_system_id) REFERENCES users(system_id) ON DELETE RESTRICT;
    END IF;
    -- The claim outlives the accounts of its submitter and its reviewer.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_submitted_by_system_id_fkey') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_submitted_by_system_id_fkey
            FOREIGN KEY (submitted_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'insurance_claims_reviewed_by_system_id_fkey') THEN
        ALTER TABLE insurance_claims
            ADD CONSTRAINT insurance_claims_reviewed_by_system_id_fkey
            FOREIGN KEY (reviewed_by_system_id) REFERENCES users(system_id) ON DELETE SET NULL;
    END IF;
END
$$;

DROP TRIGGER IF EXISTS update_insurance_claims_updated_at ON insurance_claims;
CREATE TRIGGER update_insurance_claims_updated_at BEFORE UPDATE ON insurance_claims
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- One open claim (SUBMITTED, PENDING, APPEALED) per invoice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_insurance_claims_open
    ON insurance_claims (invoice_id)
    WHERE invoice_id IS NOT NULL AND status IN ('SUBMITTED', 'PENDING', 'APPEALED');

CREATE INDEX IF NOT EXISTS idx_claim_patient_system_status ON insurance_claims (patient_system_id, status);
CREATE INDEX IF NOT EXISTS idx_claim_invoice               ON insurance_claims (invoice_id);

COMMENT ON COLUMN insurance_claims.patient_system_id      IS 'Patient the claim is for (users.system_id)';
COMMENT ON COLUMN insurance_claims.patient_id             IS 'Legacy integer patient key, replaced by patient_system_id in V49; not written any more';
COMMENT ON COLUMN insurance_claims.submitted_by_system_id IS 'Account that submitted the claim (patient, caregiver or administrator)';
COMMENT ON COLUMN insurance_claims.reviewed_by_system_id  IS 'Administrator who recorded the last decision';
COMMENT ON COLUMN insurance_claims.claim_no               IS 'Human-readable claim number, unique';
COMMENT ON COLUMN insurance_claims.appeal_reason          IS 'Reason given with the last appeal';
COMMENT ON COLUMN insurance_claims.provider_reference     IS 'Reference of the claim at the claims provider';
COMMENT ON COLUMN insurance_claims.payment_id             IS 'Payment that settled the approved amount onto the invoice';
COMMENT ON COLUMN insurance_claims.document_ids           IS 'JSON array of documents.document_id attached to the claim';
COMMENT ON COLUMN insurance_claims.documents              IS 'Legacy column, replaced by document_ids in V49; not written any more';

-- ------------------------------------------------------------
-- 5. payment_events: one row per webhook event of a payment gateway, for idempotency and ordering.
--    No payload is stored (no names, no phone numbers, no tokens).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_events (
  payment_event_id BIGSERIAL PRIMARY KEY,
  provider         VARCHAR(50)  NOT NULL,
  event_id         VARCHAR(100) NOT NULL,
  event_type       VARCHAR(50)  NOT NULL,
  payment_id       INTEGER REFERENCES payments(payment_id) ON DELETE SET NULL,
  outcome          VARCHAR(30) CHECK (outcome IN ('APPLIED', 'IGNORED', 'STALE')),
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at     TIMESTAMPTZ,
  CONSTRAINT ux_payment_events_event UNIQUE (provider, event_id)
);
COMMENT ON TABLE payment_events IS 'Received payment gateway webhook events, stored without payload for idempotency';
COMMENT ON COLUMN payment_events.outcome IS 'APPLIED, IGNORED or STALE; NULL until the event was processed';
CREATE INDEX IF NOT EXISTS idx_payment_events_payment ON payment_events (payment_id);

-- ------------------------------------------------------------
-- 6. billing_fee_schedule: the price source of invoices. doctor_system_id NULL = clinic default for the
--    appointment type; a row with a doctor overrides the default for that doctor.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS billing_fee_schedule (
  fee_id           SERIAL PRIMARY KEY,
  doctor_system_id VARCHAR(20) REFERENCES users(system_id) ON DELETE CASCADE,
  appointment_type VARCHAR(20) NOT NULL CHECK (appointment_type IN ('VIDEO', 'IN_PERSON', 'PHONE')),
  amount           NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
  currency         VARCHAR(3) NOT NULL DEFAULT 'KES',
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE billing_fee_schedule IS 'Consultation fees per appointment type: clinic defaults and doctor overrides';
COMMENT ON COLUMN billing_fee_schedule.doctor_system_id IS 'Doctor the fee applies to; NULL = clinic default';

-- One row per (doctor, appointment type); NULL (the clinic default) counts as one value.
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_fee_schedule_owner_type
    ON billing_fee_schedule (COALESCE(doctor_system_id, ''), appointment_type);

DROP TRIGGER IF EXISTS update_billing_fee_schedule_updated_at ON billing_fee_schedule;
CREATE TRIGGER update_billing_fee_schedule_updated_at BEFORE UPDATE ON billing_fee_schedule
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Clinic defaults (reference data). Starting amounts that an administrator changes through the fee
-- administration endpoint.
INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount, currency, is_active)
VALUES (NULL, 'VIDEO',     1500.00, 'KES', TRUE),
       (NULL, 'IN_PERSON', 2000.00, 'KES', TRUE),
       (NULL, 'PHONE',     1000.00, 'KES', TRUE)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 7. Validate the CHECK constraints added above. A constraint that a V1 row violates stays NOT VALID: it
--    is enforced for every new or changed row and the old row is left as it is.
-- ------------------------------------------------------------
DO $$
DECLARE
    pending RECORD;
BEGIN
    FOR pending IN
        SELECT c.conrelid::regclass AS table_name, c.conname
          FROM pg_constraint c
         WHERE c.contype = 'c'
           AND NOT c.convalidated
           AND c.conrelid IN ('payment_methods'::regclass, 'invoices'::regclass,
                              'payments'::regclass, 'insurance_claims'::regclass)
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', pending.table_name, pending.conname);
        EXCEPTION WHEN check_violation THEN
            RAISE NOTICE 'V49: existing rows of % violate %; the constraint stays NOT VALID and applies to new and changed rows only',
                pending.table_name, pending.conname;
        END;
    END LOOP;
END
$$;
