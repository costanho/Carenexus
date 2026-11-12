-- ============================================================
-- DEV ONLY (local and docker profiles): demo payment and insurance claim for the billing screens.
--
-- Builds on the invoices of R__dev_seed_49_billing.sql (patient linda.moyo@carenexus.com):
--   * one COMPLETED payment (method MPESA, gateway local, initiated by the demo patient) for the full total of
--     the PAID invoice INV-2026-900002, which has no payments row of its own;
--   * one claim CLM-2026-900001 in status SUBMITTED for the SENT invoice INV-2026-900001 (insurer Jubilee Health,
--     claim amount = the invoice total, submitted by the demo patient, provider reference MANUAL-CLM-2026-900001,
--     no documents), so the patient's claim tracker and the administrator review queue are not empty.
--
-- The claim number 900001 lies far above anything claim_no_seq (V57) hands out, so it never collides.
-- Repeatable and idempotent: a re-run changes nothing. Skipped when the demo patient or an invoice is missing.
-- The claim is not inserted when the invoice already has an open claim, or is no longer SENT.
-- ============================================================
DO $$
DECLARE
    v_patient      VARCHAR(20);
    v_paid_id      INTEGER;
    v_paid_total   NUMERIC(12,2);
    v_paid_cur     VARCHAR(3);
    v_paid_at      TIMESTAMP;
    v_sent_id      INTEGER;
    v_sent_total   NUMERIC(12,2);
    v_sent_status  VARCHAR(20);
    v_now          TIMESTAMP := date_trunc('second', LOCALTIMESTAMP);
BEGIN
    SELECT u.system_id INTO v_patient
      FROM users u
      JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'linda.moyo@carenexus.com' AND u.role = 'PATIENT';

    IF v_patient IS NULL THEN
        RAISE NOTICE 'dev seed 57 (billing claims): demo patient not found, nothing inserted';
        RETURN;
    END IF;

    -- Payment of the PAID demo invoice.
    SELECT invoice_id, total, currency, COALESCE(paid_at, v_now)
      INTO v_paid_id, v_paid_total, v_paid_cur, v_paid_at
      FROM invoices
     WHERE invoice_no = 'INV-2026-900002' AND patient_system_id = v_patient;

    IF v_paid_id IS NULL THEN
        RAISE NOTICE 'dev seed 57 (billing claims): invoice INV-2026-900002 not found, no payment inserted';
    ELSIF NOT EXISTS (SELECT 1 FROM payments WHERE invoice_id = v_paid_id)
          AND NOT EXISTS (SELECT 1 FROM payments WHERE transaction_id = 'SEEDMPESA900002') THEN
        INSERT INTO payments (patient_system_id, invoice_id, amount, currency, status, method, transaction_id,
                              gateway, provider_reference, initiated_by_system_id, refunded_amount,
                              paid_at, created_at, updated_at)
        VALUES (v_patient, v_paid_id, v_paid_total, v_paid_cur, 'COMPLETED', 'MPESA', 'SEEDMPESA900002',
                'local', 'local_seed_INV-2026-900002', v_patient, 0.00,
                v_paid_at, v_paid_at - INTERVAL '2 minutes', v_paid_at);
    END IF;

    -- Claim for the SENT demo invoice.
    SELECT invoice_id, total, status
      INTO v_sent_id, v_sent_total, v_sent_status
      FROM invoices
     WHERE invoice_no = 'INV-2026-900001' AND patient_system_id = v_patient;

    IF v_sent_id IS NULL THEN
        RAISE NOTICE 'dev seed 57 (billing claims): invoice INV-2026-900001 not found, no claim inserted';
    ELSIF v_sent_status = 'SENT'
          AND NOT EXISTS (SELECT 1 FROM insurance_claims WHERE claim_no = 'CLM-2026-900001')
          AND NOT EXISTS (SELECT 1 FROM insurance_claims
                           WHERE invoice_id = v_sent_id AND status IN ('SUBMITTED', 'PENDING', 'APPEALED')) THEN
        INSERT INTO insurance_claims (patient_system_id, invoice_id, claim_no, insurance_provider, policy_number,
                                      member_number, claim_amount, approved_amount, status, provider_reference,
                                      document_ids, submitted_by_system_id, submitted_at, updated_at)
        VALUES (v_patient, v_sent_id, 'CLM-2026-900001', 'Jubilee Health', 'JH-DEMO-0001',
                'DEMO-0045712', v_sent_total, NULL, 'SUBMITTED', 'MANUAL-CLM-2026-900001',
                '[]'::jsonb, v_patient, v_now - INTERVAL '1 day', v_now - INTERVAL '1 day');
    END IF;
END
$$;
