-- ============================================================
-- DEV ONLY (local and docker profiles): demo invoices for the billing screens.
--
-- Three invoices for the demo patient linda.moyo@carenexus.com issued by the demo doctor
-- james.banda@carenexus.com, with fixed numbers outside the range of invoice_no_seq:
--   INV-2026-900001  SENT     (issued two days ago, due in twelve days)
--   INV-2026-900002  PAID     (paid in full; no payments row here, R__dev_seed_57 adds it)
--   INV-2026-900003  OVERDUE  (due date in the past)
-- They are not linked to a visit, so they never collide with an invoice created for a seeded appointment.
--
-- Repeatable and idempotent on invoice_no: a re-run changes nothing. Skipped when the demo users are missing.
-- Creates no payment, payment method, claim or payment event.
-- ============================================================
DO $$
DECLARE
    v_patient VARCHAR(20);
    v_doctor  VARCHAR(20);
    v_now     TIMESTAMP := date_trunc('second', LOCALTIMESTAMP);
BEGIN
    SELECT u.system_id INTO v_patient
      FROM users u
      JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'linda.moyo@carenexus.com' AND u.role = 'PATIENT';

    SELECT u.system_id INTO v_doctor
      FROM users u
      JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'james.banda@carenexus.com' AND u.role = 'DOCTOR';

    IF v_patient IS NULL OR v_doctor IS NULL THEN
        RAISE NOTICE 'dev seed 49 (billing): demo patient or demo doctor not found, nothing inserted';
        RETURN;
    END IF;

    -- SENT: issued two days ago, payment terms of 14 days.
    INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, invoice_no, line_items,
                          subtotal, tax, discount, total, amount_paid, currency, status,
                          due_date, issued_at, paid_at, notes, created_at, updated_at)
    VALUES (v_patient, v_doctor, v_doctor, 'INV-2026-900001',
            '[{"description": "Video consultation", "quantity": 1, "unitPrice": 1500.00, "amount": 1500.00}]'::jsonb,
            1500.00, 0.00, 0.00, 1500.00, 0.00, 'KES', 'SENT',
            CURRENT_DATE + 12, v_now - INTERVAL '2 days', NULL, 'Demo invoice',
            v_now - INTERVAL '2 days', v_now - INTERVAL '2 days')
    ON CONFLICT (invoice_no) DO NOTHING;

    -- PAID: issued a month ago, paid in full five days later.
    INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, invoice_no, line_items,
                          subtotal, tax, discount, total, amount_paid, currency, status,
                          due_date, issued_at, paid_at, notes, created_at, updated_at)
    VALUES (v_patient, v_doctor, v_doctor, 'INV-2026-900002',
            '[{"description": "In-person consultation", "quantity": 1, "unitPrice": 2000.00, "amount": 2000.00}]'::jsonb,
            2000.00, 0.00, 0.00, 2000.00, 2000.00, 'KES', 'PAID',
            CURRENT_DATE - 16, v_now - INTERVAL '30 days', v_now - INTERVAL '25 days', 'Demo invoice',
            v_now - INTERVAL '30 days', v_now - INTERVAL '25 days')
    ON CONFLICT (invoice_no) DO NOTHING;

    -- OVERDUE: issued six weeks ago, due four weeks ago, nothing paid.
    INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, invoice_no, line_items,
                          subtotal, tax, discount, total, amount_paid, currency, status,
                          due_date, issued_at, paid_at, notes, created_at, updated_at)
    VALUES (v_patient, v_doctor, v_doctor, 'INV-2026-900003',
            '[{"description": "Phone consultation", "quantity": 1, "unitPrice": 1000.00, "amount": 1000.00},
              {"description": "Medical report", "quantity": 1, "unitPrice": 500.00, "amount": 500.00}]'::jsonb,
            1500.00, 0.00, 0.00, 1500.00, 0.00, 'KES', 'OVERDUE',
            CURRENT_DATE - 28, v_now - INTERVAL '42 days', NULL, 'Demo invoice',
            v_now - INTERVAL '42 days', v_now - INTERVAL '28 days')
    ON CONFLICT (invoice_no) DO NOTHING;
END
$$;
