-- be-billing-payment-methods (wave 3)
--
-- The administrator's payment list (GET /api/admin/billing/payments) pages every payment newest first:
-- ORDER BY created_at DESC, payment_id DESC. This index serves that order without a sort of the whole table.
--
-- No column change is needed: V49 already sized payment_methods.provider, insurer_name, policy_number and
-- member_number at 100 characters, mpesa_phone at 20 and display_label at 120, which covers every limit of the API.
-- The name is specific to this slice so it cannot collide with an index of another billing migration.

CREATE INDEX IF NOT EXISTS idx_payments_admin_list ON payments (created_at DESC, payment_id DESC);
