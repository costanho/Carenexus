-- =====================================================================================================================
-- V56 be-billing-payments: what the payer must do while a gateway payment is PENDING, and the payment list indexes.
--
--   payments.next_action      NONE, STK_PUSH, AWAIT_CONFIRMATION or REDIRECT, as reported by the gateway when the
--                             payment was started. Read back while the payment is PENDING; the API answers NONE for
--                             every other status and for rows where the column is null (manual, insurance, legacy).
--   payments.next_action_url  the gateway page for REDIRECT (real gateways only), at most 500 characters.
--
-- failure_reason and refund_reason are already VARCHAR(500) since V49: nothing to widen.
-- Nothing is dropped; every statement is safe to run on a database with data.
-- =====================================================================================================================

ALTER TABLE payments ADD COLUMN IF NOT EXISTS next_action     VARCHAR(30);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS next_action_url VARCHAR(500);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_next_action_allowed') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_next_action_allowed
            CHECK (next_action IS NULL OR next_action IN ('NONE', 'STK_PUSH', 'AWAIT_CONFIRMATION', 'REDIRECT'));
    END IF;
END $$;

-- GET /api/billing/payments?patientSystemId=  (newest first, ties by id)
CREATE INDEX IF NOT EXISTS idx_pay_patient_created ON payments (patient_system_id, created_at DESC, payment_id DESC);

-- GET /api/billing/invoices/{id}/payments     (newest first, ties by id)
CREATE INDEX IF NOT EXISTS idx_pay_invoice_created ON payments (invoice_id, created_at DESC, payment_id DESC);

-- Reservation rule of a new payment: the sum of the invoice's PENDING payments.
CREATE INDEX IF NOT EXISTS idx_pay_invoice_pending ON payments (invoice_id) WHERE status = 'PENDING';

COMMENT ON COLUMN payments.next_action     IS 'While PENDING: what the payer must do (NONE, STK_PUSH, AWAIT_CONFIRMATION, REDIRECT)';
COMMENT ON COLUMN payments.next_action_url IS 'Gateway page for next_action REDIRECT; null otherwise';
