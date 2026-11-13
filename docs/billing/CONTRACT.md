# CareNexus Billing Contract

Status: reference document for billing (invoices, payments, saved payment methods, insurance claims, notifications,
the overdue sweeper and the printable receipt), written from the wave 1 to 3 billing slices: be-billing-schema,
be-billing-invoices, be-billing-payments, be-billing-payment-methods, be-billing-claims and
be-billing-notify-receipt.

Precedence: the code and the Flyway migrations win over this document. Where this document and one of the per-slice
API contracts (`api/be-billing-*.md` in the build plan) disagree, the API contract wins and this document is
corrected. No code reads this file.

Contents:

1. Conventions shared by every billing endpoint
2. Tables
3. State machines (invoice, payment, saved payment method, claim)
4. Endpoints and status codes
5. Authorization matrix
6. Idempotency rules
7. Local payment gateway: webhook payload and signature
8. Events
9. Notification kinds
10. Jobs
11. Configuration
12. Provider seams: what a real gateway or insurer implements

---

## 1. Conventions shared by every billing endpoint

- Bearer token on every path except the gateway webhook (section 7). JSON in and out, except the receipt (text/html).
- Error envelope (all errors, including those of the receipt):
  `{ "timestamp", "status", "error", "message", "path" }` plus, when they apply, `code`, `errors`, `requestId`,
  `resourceType`, `resourceId`. Clients show `message` and never match on its text.
- Errors every billing endpoint shares:

  | Status | Condition | `message` | `code` |
  |---|---|---|---|
  | 401 | missing, invalid or expired token | `Authentication required` | - |
  | 400 | body is not parseable JSON | `Malformed request body` | - |
  | 400 | a path or query value has the wrong type | `Invalid value for parameter '<name>'` | - |
  | 400 | Bean Validation failure | `<first invalid field, alphabetical>: <its message>` | `VALIDATION_FAILED` (+ `errors`) |
  | 403 | URL role gate (`/api/admin/**` for a non-admin; `/api/doctors/**` for a patient or caregiver) | `You don't have permission to access this resource` | - |
  | 500 | unexpected | `An unexpected error occurred` | - |

- Lists: `PageResponse<T>` = `{ content, page, size, totalElements, totalPages }`, `page` from 0, `size` default 20,
  maximum 100. Every billing list is sorted newest first with the id as tie-breaker.
- Money: `NUMERIC(12,2)` / `BigDecimal` scale 2 `HALF_UP`, a JSON number. `currency` is a 3-letter ISO-4217 code
  (default `KES`). There is no currency conversion. Text form (notifications, receipt, web `formatMoney`):
  `KES 2,900.00`.
- Times: stored as `TIMESTAMP` clinic wall clock (`carenexus.scheduling.time-zone`, default `Africa/Harare`), serialized
  without offset (`2026-11-10T09:00:00`); `dueDate` is a date (`2026-11-24`). `payment_events` times are
  `TIMESTAMPTZ` and never serialized.
- Enum-like inputs are trimmed and upper-cased by the server.
- Audit: resource types `INVOICE`, `PAYMENT` and `INSURANCE_CLAIM`. Reads
  write `READ`, list pages one `READ` per distinct patient, changes `UPDATE`/`CREATE`. Changes made by the gateway
  webhook or by a job have no authenticated actor: the webhook's trail is `payment_events`, the reconciliation
  sweeper's is the payment's `failureReason`; `InvoiceService.markOverdue` (overdue sweeper) still writes its
  `UPDATE INVOICE` row.

---

## 2. Tables

All billing tables are keyed to people by `system_id` (`PAT-`, `DOC-`, `CGV-`, `ADM-` ids, `VARCHAR(20)` referencing
`users(system_id)`). The V1 integer person keys (`patient_id`, `user_id`) are kept nullable and unused. Patient and
issuing-doctor keys are `ON DELETE RESTRICT`; actor columns (`created_by`, `initiated_by`, `submitted_by`,
`reviewed_by`) are `SET NULL`; `payment_methods.system_id` and `billing_fee_schedule.doctor_system_id` are `CASCADE`.

### 2.1 `invoices` (entity `billing.model.Invoice`)

| Column | Type | Notes |
|---|---|---|
| `invoice_id` | SERIAL PK | |
| `patient_system_id` | VARCHAR(20) NOT NULL | the billed patient |
| `doctor_system_id` | VARCHAR(20) | the issuing doctor |
| `created_by_system_id` | VARCHAR(20) | doctor or admin who generated it; null for the auto-invoice listener |
| `appointment_id`, `consultation_id` | INTEGER | the visit; one live invoice per visit (below) |
| `invoice_no` | VARCHAR(50) NOT NULL UNIQUE | `INV-<yyyy>-<6 digits of invoice_no_seq>` |
| `line_items` | JSONB | `[{ "description", "quantity", "unitPrice", "amount" }]`, `amount = quantity x unitPrice` |
| `subtotal`, `tax`, `discount`, `total` | NUMERIC(12,2) NOT NULL | all `>= 0`; `total = subtotal - discount + tax` |
| `amount_paid` | NUMERIC(12,2) NOT NULL DEFAULT 0 | `0 <= amount_paid <= total` |
| `currency` | VARCHAR(3) NOT NULL DEFAULT 'KES' | |
| `status` | VARCHAR(20) NOT NULL DEFAULT 'DRAFT' | `DRAFT`, `SENT`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `CANCELLED`, `REFUNDED` |
| `due_date` | DATE | set when sent: issue date + payment terms |
| `issued_at`, `paid_at` | TIMESTAMP | `issued_at` null = never issued |
| `cancel_reason` | VARCHAR(500) | |
| `notes` | TEXT | |
| `created_at`, `updated_at` | TIMESTAMP NOT NULL | `updated_at` maintained by trigger |

Indexes and invariants: `ux_invoices_appointment_active` and `ux_invoices_consultation_active` (one invoice per
appointment and per consultation among rows with `status <> 'CANCELLED'`; a `REFUNDED` invoice still blocks a second
one), `idx_inv_patient_system_status`, `idx_inv_doctor_system_status`, `idx_inv_consultation`, `idx_inv_due_open`
(overdue sweep), `idx_consultations_doctor_completed` (V63, uninvoiced list). Sequence `invoice_no_seq`.

### 2.2 `payments` (entity `Payment`)

| Column | Type | Notes |
|---|---|---|
| `payment_id` | SERIAL PK | |
| `patient_system_id` | VARCHAR(20) NOT NULL | the invoice's patient |
| `invoice_id` | INTEGER | the paid invoice (`ON DELETE SET NULL`) |
| `appointment_id` | INTEGER | legacy |
| `payment_method_id` | INTEGER | saved method used, if any |
| `amount` | NUMERIC(12,2) NOT NULL | `> 0` |
| `currency` | VARCHAR(3) NOT NULL | |
| `status` | VARCHAR(20) NOT NULL DEFAULT 'PENDING' | `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED`, `CANCELLED` |
| `method` | VARCHAR(20) NOT NULL | `CARD`, `INSURANCE`, `CASH`, `MPESA`, `BANK_TRANSFER` |
| `gateway` | VARCHAR(50) | `local` (or a real gateway's name), `MANUAL` (cash and bank transfers recorded by an admin), `INSURANCE` (claim settlements) |
| `provider_reference` | VARCHAR(255) | gateway reference; the typed reference of a manual payment; the claim number of a settlement |
| `transaction_id` | VARCHAR(255) UNIQUE | confirmation code |
| `failure_reason`, `refund_reason` | VARCHAR(500) | |
| `refunded_amount` | NUMERIC(12,2) NOT NULL DEFAULT 0 | cumulative, `0 <= refunded_amount <= amount` |
| `initiated_by_system_id` | VARCHAR(20) | payer, caregiver or admin |
| `payer_phone` | VARCHAR(20) | M-PESA only |
| `gateway_response` | JSONB | stored only, never returned |
| `paid_at`, `created_at`, `updated_at` | TIMESTAMP | |

Indexes: `idx_pay_patient_system_status`, `idx_pay_method`, `idx_pay_gateway_reference (gateway, provider_reference)`,
`idx_pay_pending_created` (reconciliation).

### 2.3 `payment_methods` (entity `PaymentMethod`)

`method_id` SERIAL PK; `system_id` VARCHAR(20) NOT NULL (owning patient); `type` (`CARD`, `INSURANCE`, `CASH`, `MPESA`,
`BANK_TRANSFER`); `provider` VARCHAR(100); `last_four` VARCHAR(4); `mpesa_phone` VARCHAR(20); `insurer_name`,
`policy_number`, `member_number` VARCHAR(100); `display_label` VARCHAR(120); `provider_token` VARCHAR(255) (stored
only); `is_default`, `is_active`, `is_deleted` BOOLEAN; `created_at`. The legacy `token` column is unmapped.
`ux_payment_methods_default`: one default per owner among `is_default AND is_active AND NOT is_deleted`. At most 10
active methods per patient (service rule).

### 2.4 `insurance_claims` (entity `InsuranceClaim`)

`claim_id` SERIAL PK; `patient_system_id` NOT NULL; `invoice_id`; `payment_id` (latest settlement payment);
`claim_no` VARCHAR(50) UNIQUE (`CLM-<yyyy>-<6 digits of claim_no_seq>`); `insurance_provider` VARCHAR(150) NOT NULL;
`policy_number` NOT NULL, `member_number` VARCHAR(100); `claim_amount` NUMERIC(12,2) NOT NULL `> 0`;
`approved_amount` NUMERIC(12,2) `>= 0` (cumulative); `rejection_reason`, `appeal_reason` TEXT; `provider_reference`
VARCHAR(255); `document_ids` JSONB NOT NULL DEFAULT `[]` (document ids); `status` (`SUBMITTED`, `PENDING`,
`APPROVED`, `REJECTED`, `PARTIAL`, `APPEALED`); `submitted_by_system_id`, `reviewed_by_system_id`; `submitted_at`,
`resolved_at`, `updated_at`. `ux_insurance_claims_open`: one open claim (`SUBMITTED`, `PENDING`, `APPEALED`) per
invoice. The V1 `documents` column is unmapped.

### 2.5 `payment_events` (entity `PaymentEvent`)

`payment_event_id` BIGSERIAL PK; `provider` VARCHAR(50); `event_id` VARCHAR(100); `event_type` VARCHAR(50);
`payment_id`; `outcome` (`APPLIED`, `IGNORED`, `STALE`, null until processed); `received_at`, `processed_at`
TIMESTAMPTZ. `UNIQUE (provider, event_id)`: the webhook inserts with `ON CONFLICT DO NOTHING`. No payload is stored.

### 2.6 `billing_fee_schedule` (entity `FeeSchedule`)

`fee_id` SERIAL PK; `doctor_system_id` (null = clinic default); `appointment_type` (`VIDEO`, `IN_PERSON`, `PHONE`);
`amount` `>= 0`; `currency`; `is_active`; `created_at`, `updated_at`. One row per (doctor, type), null counting as one
value. Shipped defaults: `VIDEO` 1500.00, `IN_PERSON` 2000.00, `PHONE` 1000.00 (KES). A fee resolves to the doctor's
active override, else the clinic default, else 0.00.

### 2.7 Notification columns used by billing

`notifications` rows of type `PAYMENT` carry `category` `BILLING`, `target` `BILLING`, `resource_type` `INVOICE`,
`resource_id` = invoice id and a `dedupe_key` unique per recipient (`ux_notifications_recipient_dedupe`).
`user_settings.billing_notifications` (default true) switches them off.

---

## 3. State machines

Every transition not listed is refused with 409. A request whose target equals the current status is a no-op that
returns the row with 200 and publishes nothing.

### 3.1 Invoice (owner: `InvoiceService`)

| From | To | Trigger |
|---|---|---|
| (none) | `DRAFT` | `POST /api/billing/invoices`; auto-invoice listener on `ConsultationCompletedEvent` |
| `DRAFT` | `SENT` | `POST .../{id}/send` (total must be above zero); auto-invoice mode `SENT` |
| `DRAFT` | `CANCELLED` | `POST .../{id}/cancel`; admin void (`cancelBySystem`) |
| `SENT` | `PARTIALLY_PAID` / `PAID` | `applyPayment` (gateway payment, manual payment, claim settlement) |
| `SENT` | `OVERDUE` | `markOverdue` (overdue sweeper) |
| `SENT` | `CANCELLED` | cancel or void, only while `amountPaid` is 0.00 |
| `PARTIALLY_PAID` | `PAID` | `applyPayment` |
| `PARTIALLY_PAID` | `OVERDUE` | `markOverdue` |
| `PARTIALLY_PAID` | `REFUNDED` | `reversePayment` to 0.00 |
| `OVERDUE` | `PARTIALLY_PAID` / `PAID` | `applyPayment` |
| `OVERDUE` | `CANCELLED` | cancel or void, only while `amountPaid` is 0.00 |
| `OVERDUE` | `REFUNDED` | `reversePayment` to 0.00 |
| `PAID` | `PARTIALLY_PAID` / `REFUNDED` | `reversePayment` |

- `CANCELLED` and `REFUNDED` are terminal. Nothing can be edited after `DRAFT`.
- A part payment or part reversal that leaves the status unchanged publishes no `InvoiceStatusChangedEvent`.
- Never-issued invoices (`DRAFT`, or `CANCELLED` with `issuedAt` null) are invisible to patients and caregivers
  (excluded from lists, 404 by id).
- An `OVERDUE` invoice that receives a part payment becomes `PARTIALLY_PAID` and is moved back to `OVERDUE` by the next
  sweep. Clients treat `SENT`, `PARTIALLY_PAID` and `OVERDUE` alike as open and read `outstanding`.

Facade used by the other billing slices (no caller authorization except `getForCaller`):

| Method | Behaviour | 409 message |
|---|---|---|
| `getForCaller(id)` | the invoice read with its authorization and audit | - |
| `outstanding(id)` | `total - amountPaid` for open statuses, else 0.00 | - |
| `applyPayment(id, amount)` | row lock; `SENT`, `PARTIALLY_PAID`, `OVERDUE`; `amount <= outstanding` | `An invoice in status <S> cannot be paid`; `Payment exceeds the outstanding balance` |
| `reversePayment(id, amount)` | row lock; `PAID`, `PARTIALLY_PAID`, `OVERDUE`; `amount <= amountPaid` | `An invoice in status <S> cannot be refunded`; `Refund exceeds the amount paid` |
| `markOverdue(id)` | `SENT`/`PARTIALLY_PAID` with `dueDate` before today becomes `OVERDUE`, else no-op | - |
| `cancelBySystem(id, reason)` | `DRAFT`, `SENT`, `OVERDUE` with nothing paid become `CANCELLED` | `An invoice with payments cannot be cancelled`; `An invoice in status REFUNDED cannot be cancelled` |

### 3.2 Payment (owner: `PaymentService`, be-billing-payments)

| From | To | Trigger |
|---|---|---|
| (none) | `PENDING` | `POST /api/billing/invoices/{invoiceId}/payments`, online method (`CARD`, `MPESA`) |
| (none) | `COMPLETED` | same endpoint when the gateway settles at once (local gateway: offline methods, admin only); manual payment (`POST /api/admin/billing/invoices/{id}/payments/manual`); claim settlement |
| (none) | `FAILED` | the gateway answers `FAILED` at initiation (real gateways only) |
| `PENDING` | `COMPLETED` | webhook `PAYMENT_COMPLETED` (or dev simulation) and the invoice accepts the amount |
| `PENDING` | `FAILED` | webhook `PAYMENT_FAILED`; `PAYMENT_COMPLETED` refused by the invoice (`The invoice could no longer accept this payment`); reconciliation after 30 minutes (`Payment was not confirmed in time`) |
| `COMPLETED` | `COMPLETED` | partial refund (`refundedAmount` grows) |
| `COMPLETED` | `REFUNDED` | refund that reaches `amount` |

`FAILED`, `REFUNDED`, `CANCELLED` are terminal; terminal states never regress (a late webhook event is stored as
`STALE`). `CANCELLED` is a stored value no operation produces. Every move to `COMPLETED` calls `applyPayment` and every
refund `reversePayment` in the same transaction. Reservation rule: a new payment may not exceed
`outstanding - sum(PENDING payments of the invoice)`. An `INSURANCE` settlement cannot be refunded.

### 3.3 Saved payment method (owner: be-billing-payment-methods)

| From | To | Trigger |
|---|---|---|
| (none) | active, default | `POST /api/billing/payment-methods` when the patient has no active method, or with `makeDefault: true` |
| (none) | active | `POST` otherwise |
| active | active, default | `PATCH .../{id}/default`; automatic promotion of the newest remaining method when the default is deleted |
| active | deleted | `DELETE .../{id}` (soft: `is_deleted`, not active, not default); terminal |

### 3.4 Insurance claim (owner: be-billing-claims)

| From | To | Trigger | Who |
|---|---|---|---|
| (none) | `SUBMITTED` | `POST /api/billing/invoices/{invoiceId}/claims` | patient, caregiver with MANAGE_BILLING, admin |
| `SUBMITTED` | `PENDING` | `PATCH /api/admin/billing/claims/{claimId}` `{status: PENDING}` | admin |
| `PENDING` | `APPROVED` | `PATCH` `APPROVED` (approves the whole `claimAmount`) | admin |
| `PENDING` | `PARTIAL` | `PATCH` `PARTIAL` + `approvedAmount` (below `claimAmount`) | admin |
| `PENDING` | `REJECTED` | `PATCH` `REJECTED` + `rejectionReason` (only when nothing was approved before) | admin |
| `REJECTED`, `PARTIAL` | `APPEALED` | `POST /api/billing/claims/{claimId}/appeal` | patient, caregiver with MANAGE_BILLING, admin |
| `APPEALED` | `PENDING` | `PATCH` `PENDING` | admin |

`APPROVED` is terminal. Adjudication is two-step (`SUBMITTED`/`APPEALED` go to `PENDING` first). A decision with a
settlement above zero (new `approvedAmount` minus the previous one) creates, in the same transaction, a `COMPLETED`
payment with method and gateway `INSURANCE`, `providerReference` = `claimNo`, applied to the invoice. An approval
cannot be undone. One open claim per invoice; an appeal is refused while another claim of the invoice is open.

---

## 4. Endpoints and status codes

Success codes and the endpoint-specific errors; the shared errors of section 1 apply everywhere.

### 4.1 Fees and invoices (be-billing-invoices)

| Method and path | Success | Endpoint-specific errors |
|---|---|---|
| `GET /api/billing/fees?doctorSystemId=` | 200 `FeeResponse[3]` | - |
| `PUT /api/doctors/billing/fees` | 200 `FeeResponse[3]` | 400 duplicate type; 403 admin |
| `PUT /api/admin/billing/fees` | 200 `FeeResponse[3]` | 400 validation |
| `POST /api/billing/invoices` | 201 created; 200 existing visit invoice or `Idempotency-Key` replay | 400 no visit id / visit mismatch / discount above subtotal; 403 patient, caregiver, another doctor's visit, inactive clinician; 404 consultation or appointment; 409 consultation not completed, appointment cancelled, key in flight |
| `GET /api/billing/invoices/{id}` | 200 `InvoiceResponse` | 403 (section 5); 404 unknown or never issued (patient, caregiver) |
| `GET /api/billing/invoices?patientSystemId=&status=&consultationId=&appointmentId=` | 200 page | 400 missing or malformed patient, unknown status; 403 |
| `GET /api/billing/invoices/me` | 200 page | 400 unknown status |
| `GET /api/doctors/billing/invoices` | 200 page (invoices the caller issued) | 400 unknown status |
| `GET /api/doctors/billing/uninvoiced` | 200 page | - |
| `PUT /api/billing/invoices/{id}` | 200 (still `DRAFT`) | 400 validation, discount; 403; 404; 409 not `DRAFT` |
| `POST /api/billing/invoices/{id}/send` | 200 `SENT` (no-op when already `SENT`) | 403; 404; 409 zero total, status not sendable |
| `POST /api/billing/invoices/{id}/cancel` | 200 `CANCELLED` (no-op when already) | 400 reason over 500; 403; 404; 409 payments recorded, `REFUNDED` |
| `GET /api/admin/billing/invoices?status=&patientSystemId=` | 200 page | 400 unknown status |
| `GET /api/billing/invoices/{id}/receipt` | 200 `text/html` document (4.6) | 403 (section 5); 404 unknown or never issued (patient, caregiver) |

### 4.2 Payments (be-billing-payments)

| Method and path | Success | Endpoint-specific errors |
|---|---|---|
| `POST /api/billing/invoices/{invoiceId}/payments` body `{method, paymentMethodId?, amount?, payerPhone?}` | 201 `PaymentResponse` (`PENDING` with `nextAction`, or `COMPLETED`); 200 replay | 400 method not available, saved method type mismatch, M-PESA without phone; 403 doctor, access (MANAGE_BILLING), offline method by a non-admin; 404 invoice (unknown or never issued), saved method; 409 invoice status, exceeds balance, another payment pending, key in flight; 429 `payment-initiate` (30/h); 503 `PROVIDER_UNAVAILABLE` |
| `GET /api/billing/payments/{id}` | 200 | 403 (BILLING; doctor only for invoices they issued); 404 `Payment not found` |
| `GET /api/billing/payments?patientSystemId=` | 200 page | 400 missing patient; 403 doctor, access |
| `GET /api/billing/invoices/{id}/payments` | 200 page | as the invoice read |
| `GET /api/billing/config` | 200 `{provider, liveGateway, supportedMethods, onlineMethods, currency, simulationEnabled}` | - |
| `POST /api/integrations/payments/{provider}/webhook` | 200 `{"received": true}` | 404 provider not active or not configured; 400 body over 64 KB, malformed payload; 401 `Invalid webhook signature` |
| `POST /api/billing/payments/{id}/simulate` body `{outcome}` (dev profiles with the local gateway only) | 200 | 404 when unavailable or unknown; 403; 409 not `PENDING` |
| `POST /api/admin/billing/payments/{id}/refund` body `{amount?, reason}` | 200; 200 replay | 404; 409 insurance settlement, no invoice, already refunded, not completed, above refundable, invoice refuses, key in flight; 503 |

### 4.3 Saved payment methods and administrator payments (be-billing-payment-methods)

| Method and path | Success | Endpoint-specific errors |
|---|---|---|
| `GET /api/billing/payment-methods` | 200 array (masked) | 403 not a patient |
| `POST /api/billing/payment-methods` | 201; 200 replay | 400 missing type-specific field; 403 not a patient; 409 duplicate instrument, 10 methods, key in flight |
| `PATCH /api/billing/payment-methods/{id}/default` | 200 | 403; 404 `Payment method not found` |
| `DELETE /api/billing/payment-methods/{id}` | 204 | 403; 404 |
| `GET /api/billing/payment-methods/patient/{patientSystemId}` | 200 array | 403 doctor, access (MANAGE_BILLING) |
| `GET /api/admin/billing/payments?status=&method=&patientSystemId=` | 200 page | 400 unknown status or method |
| `POST /api/admin/billing/invoices/{id}/payments/manual` body `{method: CASH or BANK_TRANSFER, amount, reference (required for BANK_TRANSFER)}` | 201 (`COMPLETED`, gateway `MANUAL`); 200 replay | 400 bank transfer without reference; 404 invoice; 409 invoice status, exceeds balance, key in flight |

### 4.4 Insurance claims and void (be-billing-claims)

| Method and path | Success | Endpoint-specific errors |
|---|---|---|
| `POST /api/billing/invoices/{invoiceId}/claims` | 201; 200 replay | 400 foreign document ids; 403 doctor, access (MANAGE_BILLING); 404 invoice; 409 invoice status, open claim exists, above balance, key in flight; 503 provider |
| `GET /api/billing/claims/{claimId}` | 200 | 403 doctor, access (BILLING); 404 `Claim not found` |
| `GET /api/billing/claims?patientSystemId=&invoiceId=&status=` | 200 page | 400 missing patient, unknown status; 403 |
| `POST /api/billing/claims/{claimId}/appeal` body `{reason}` | 200 `APPEALED` | 403; 404; 409 status not appealable, another open claim |
| `GET /api/admin/billing/claims?status=` | 200 page | - |
| `PATCH /api/admin/billing/claims/{claimId}` body `{status, approvedAmount?, rejectionReason?}` | 200 | 400 missing amount or reason, partial not below claim, amount lowered; 404; 409 transition, rejecting an approved amount, settlement above balance |
| `POST /api/admin/billing/invoices/{id}/void` body `{reason}` | 200 `CANCELLED` | 404; 409 payments recorded, `REFUNDED` |

### 4.5 Notifications

No billing-specific endpoint. Billing notifications arrive through `GET /api/notifications` (filter `type=PAYMENT`),
`GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`.

### 4.6 `GET /api/billing/invoices/{id}/receipt`

- 200 with a self-contained HTML5 document (one inline style element, print stylesheet for A4, no script, no event
  handler, no link, image, form or URL). Heading and `<title>` `Receipt` when the invoice is `PAID`, `Invoice`
  otherwise. Every stored value is HTML-escaped and placed in element text only.
- Headers: `Content-Type: text/html;charset=UTF-8` (for every `Accept` header, no 406),
  `Content-Disposition: attachment; filename="receipt-<invoiceNo>.html"` or `invoice-<invoiceNo>.html` (characters
  outside `[A-Za-z0-9-]` become `_`), `Cache-Control: private, no-store`,
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, `X-Content-Type-Options: nosniff`.
- Errors are the JSON envelope with `Content-Type: application/json` whatever the `Accept` header.
- Stable element ids: `clinic-name` (setting `clinic.name`), `doc-title`, `invoice-no`, `invoice-status`, `issued-on`
  (`Not issued` when null), `due-date`, `paid-on`, `billed-to`, `issued-by`, `line-items`, `totals` (Subtotal,
  Discount when above zero, Tax, Total, Amount paid, Balance due), `payments`, `cancel-reason`, `notes`,
  `generated-at`.
- Payments listed: `COMPLETED` and `REFUNDED` rows of the invoice (every method), `paidAt` ascending then id, at most
  200; reference = `transactionId`, else `providerReference`, else `-`. Pending and failed attempts, phone numbers,
  saved-method labels, gateway data and clinical text are never printed. Totals come from the invoice.
- Audit: exactly one `READ INVOICE` row per success (from `getForCaller`); no `PAYMENT` row.
- Web: fetch as a Blob through the shared axios instance and print it from a sandboxed frame; never navigate to the
  API path and never inject the HTML into the app DOM.

---

## 5. Authorization matrix

Caregiver permissions are evaluated on every request from an active grant (active, not revoked, `expires_at` null or
in the future):
- BILLING (view): `canViewBilling` or access level `FULL_ACCESS`.
- MANAGE_BILLING: `FULL_ACCESS`, or `canManageBilling` with an access level other than `VIEW_ONLY`.
- The two are independent: MANAGE_BILLING alone allows writes (pay, submit or appeal a claim) whose result the
  caregiver cannot read back. The web offers Pay, Submit claim and Appeal only when both hold.

| Operation | Patient | Caregiver | Doctor | Admin |
|---|---|---|---|---|
| Read fees | yes | yes | yes | yes |
| Change fees | no | no | own overrides | clinic defaults |
| Create, edit (DRAFT), send, cancel an invoice | no | no | own visits / invoices they issued | yes |
| Read an invoice, its payments, its receipt | own, issued only | BILLING, issued only | invoices they issued (any status) | all |
| List invoices of a patient | own | BILLING | care relationship, only invoices they issued | all |
| Uninvoiced consultations | no | no | own | no |
| Pay an invoice (online methods) | own | MANAGE_BILLING | no | yes (every method) |
| Record a manual payment, refund, list all payments | no | no | no | yes |
| Read a payment | own | BILLING | of invoices they issued | all |
| Saved payment methods (list, add, default, delete) | own | no | no | no |
| Read a patient's saved methods (masked) | own | MANAGE_BILLING | no | yes |
| Submit a claim, appeal | own | MANAGE_BILLING | no (403) | yes |
| Read claims | own | BILLING | no (403) | all |
| Adjudicate claims, void an invoice | no | no | no | yes |
| Dev payment simulation | own | MANAGE_BILLING | no | yes |
| Billing notifications received | yes | BILLING | `Invoice paid` for invoices they issued | admin `SYSTEM` notifications for new and appealed claims |

403 texts: `Patients can only access their own data`; `You don't have active proxy access to this patient`;
`Your proxy access for this patient does not allow viewing billing`; `Your proxy access for this patient does not
allow managing billing and payments`; `You can only view invoices you issued`; `You can only manage invoices you
issued`; `You can only view payments of invoices you issued`; `Your role is not authorized to perform this action`.
403 answers about one invoice or payment carry `resourceType` and `resourceId`; refused patient-data reads write an
`ACCESS_DENIED` audit row.

---

## 6. Idempotency rules

- `Idempotency-Key` request header (optional, 8 to 64 characters of `[A-Za-z0-9_-]`, owner = caller, scope = method +
  path template, kept 24 h) on: `POST /api/billing/invoices`, `POST /api/billing/invoices/{invoiceId}/payments`,
  `POST /api/admin/billing/payments/{id}/refund`, `POST /api/billing/payment-methods`,
  `POST /api/admin/billing/invoices/{id}/payments/manual`, `POST /api/billing/invoices/{invoiceId}/claims`.
  A replay answers 200 with the stored body of the first response (refetch for the current state); the same key still
  in flight answers 409 `This request is already being processed. Please wait a moment.`
  (`IDEMPOTENCY_IN_PROGRESS`); a malformed key answers 400.
- Natural rules independent of the key: one live invoice per consultation and per appointment (an existing one is
  returned with 200); the invoice row lock plus the pending reservation stop two payments from overpaying; one open
  claim per invoice; the refundable amount bounds refunds; the manual endpoint records a new payment on every call
  without a key, so the web always sends one.
- Same-status no-ops (200, nothing published): send, cancel, void, claim appeal, claim adjudication, setting the
  default payment method again.
- Webhook: `payment_events (provider, event_id)` insert-if-absent; duplicates and stale events answer 200 and change
  nothing.
- Jobs select only rows still in the source status, so a re-run changes nothing.
- Notifications: per-recipient dedupe keys (section 9) make every repeated event a no-op.

---

## 7. Local payment gateway: webhook payload and signature

`POST /api/integrations/payments/local/webhook` (public path, no bearer token; the `{provider}` segment must equal the
active gateway's `name()`). Body read raw, at most 64 KB; body and headers are never logged; the payload is not stored.

Signature header:

```
X-CareNexus-Signature: sha256=<lower-case hex HMAC-SHA256 of the exact raw body bytes,
                                key = carenexus.billing.local.webhook-secret>
```

Compared in constant time. No timestamp header: replays are harmless because events are idempotent.

Body:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `eventId` | string | no | 1 to 100 characters, unique per event; default the hex SHA-256 of the raw body |
| `eventType` | string | yes | `PAYMENT_COMPLETED` or `PAYMENT_FAILED` (any other value is accepted and ignored) |
| `providerReference` | string | yes | the payment's `providerReference` |
| `transactionId` | string | no | confirmation code, at most 255 characters |
| `failureReason` | string | no | at most 500 characters; default `Payment was declined` |
| `occurredAt` | string (instant) | no | informational |

```json
{ "eventId": "evt_0b7d3c1e52a94f08", "eventType": "PAYMENT_COMPLETED",
  "providerReference": "local_pay_5f0c2a7e9b1d4c6fa3e8d2b47c915a60",
  "transactionId": "LOC-8K2M4QX7P1ZD", "failureReason": null, "occurredAt": "2026-11-10T06:21:07Z" }
```

Processing, one transaction: record the event (duplicate: answer 200, nothing else); find the payment by gateway and
`providerReference` (unknown reference or event type: outcome `IGNORED`); payment not `PENDING`: outcome `STALE`;
`PAYMENT_COMPLETED`: `applyPayment`, payment `COMPLETED`, or `FAILED` when the invoice refuses; `PAYMENT_FAILED`:
payment `FAILED`. Success body `{"received": true}` for every correctly signed, parseable event.

Errors: 404 `Not found` (provider not active or not configured), 400 `Webhook body is too large`, 401
`Invalid webhook signature`, 400 `Malformed webhook payload`.

Local behaviour at initiation: `MPESA` returns `PENDING` + `STK_PUSH`, `CARD` `PENDING` + `AWAIT_CONFIRMATION`,
`CASH`/`BANK_TRANSFER`/`INSURANCE` `COMPLETED` + `NONE`. It never moves money and never calls anything outside the
process. In dev profiles `POST /api/billing/payments/{id}/simulate` builds and signs the same body and feeds it
through the webhook service.

---

## 8. Events

All are Spring application events in `com.carenexus.api.common.event`, published inside the changing transaction
after the rows are saved.

| Event | Publisher | When |
|---|---|---|
| `InvoiceIssuedEvent(invoiceId, invoiceNo, patientSystemId, doctorSystemId, total, currency, dueDate)` | `InvoiceService` | every `DRAFT` to `SENT` |
| `InvoiceStatusChangedEvent(invoiceId, invoiceNo, patientSystemId, previousStatus, status)` | `InvoiceService` | every status change (not on a no-op or a status-preserving part payment) |
| `PaymentRecordedEvent(paymentId, invoiceId, patientSystemId, initiatedBySystemId, amount, currency, method, status)` | payments (gateway, webhook, sweeper, refund), payment methods (manual), claims (settlement) | `COMPLETED`: payment settled (`amount` = payment amount); `FAILED`: payment failed (initiator); `REFUNDED`: once per refund call (`amount` = that refund, initiator = the admin) |
| `InsuranceClaimStatusChangedEvent(claimId, invoiceId, patientSystemId, previousStatus, status)` | claims | every status change, submission with `previousStatus` null |

Consumers: `AutoInvoiceListener` (`ConsultationCompletedEvent`) creates the visit invoice;
`BillingNotificationListener` turns the four billing events into notifications (plain `@EventListener`, synchronous,
inside the publisher's transaction, never throwing for missing data).

---

## 9. Notification kinds

Every patient-facing billing notification: type `PAYMENT`, category `BILLING`, target `BILLING`, resource `INVOICE`
with the invoice id, `patientSystemId` = the invoice's patient. The text names only the invoice number, an amount and
(for a new invoice) the due date. "Circle" = the patient plus caregivers holding BILLING when the event is handled.
`billingNotifications = false` silences every kind for that recipient (nothing here is `CRITICAL` or `SYSTEM`).

| Kind (dedupe key) | Trigger | Recipients | Priority | Title / message |
|---|---|---|---|---|
| `INVOICE_ISSUED:<invoiceId>` | `InvoiceIssuedEvent` | circle | NORMAL | `New invoice` / `Invoice {no} for {amount} was issued. Payment is due by {d MMM yyyy}.` |
| `PAYMENT_RECEIVED:<paymentId>` | `PaymentRecordedEvent` `COMPLETED` | circle except the payer | NORMAL | `Payment received` / `A payment of {amount} was received for invoice {no}.` |
| `PAYMENT_FAILED:<paymentId>` | `PaymentRecordedEvent` `FAILED` | circle, payer included | NORMAL | `Payment failed` / `A payment of {amount} for invoice {no} did not go through.` |
| `REFUND_ISSUED:<paymentId>:<cumulative refundedAmount>` | `PaymentRecordedEvent` `REFUNDED` | circle | NORMAL | `Refund issued` / `A refund of {amount} was issued for invoice {no}.` |
| `INVOICE_OVERDUE:<invoiceId>` | `InvoiceStatusChangedEvent` to `OVERDUE` | circle | HIGH | `Invoice overdue` / `Invoice {no} is overdue. {total - amountPaid} is still to be paid.` |
| `INVOICE_PAID:<invoiceId>` | `InvoiceStatusChangedEvent` to `PAID` | issuing doctor | NORMAL | `Invoice paid` / `Invoice {no} for {total} has been paid in full.` |
| `CLAIM_STATUS:<claimId>:<status>:<epoch second>` | `InsuranceClaimStatusChangedEvent` | circle (submission: except the submitter) | NORMAL | `Insurance claim submitted / under review / approved / partly approved / rejected / appealed` with the invoice number |

Other status changes (`SENT`, `PARTIALLY_PAID`, `CANCELLED`, `REFUNDED`) notify nobody. Consequences of the keys: one
`New invoice`, one `Invoice overdue` and one `Invoice paid` per invoice and recipient ever; one row per payment; one
per refund; claim rows can repeat for a status reached again after an appeal.

Action URLs (`id` is always the invoice id): patient `/dashboard/patient?section=billing&id=<invoiceId>`; caregiver
`/dashboard/proxy?section=billing&patient=<PAT id>&id=<invoiceId>`; doctor `/dashboard/doctor?section=billing&id=<invoiceId>`.

Administrator notifications (be-billing-claims): type and category `SYSTEM`, target `ADMIN_BILLING`, resource
`INSURANCE_CLAIM`; `New insurance claim` (`CLAIM_SUBMITTED:<claimId>`) and `Insurance claim appealed`
(`CLAIM_APPEALED:<claimId>:<resolvedAt yyyyMMddHHmmss>`) to every active admin except the caller, link
`/dashboard/admin?section=billing&id=<claimId>&tab=claims`.

Email, SMS and push copies are created by the notification fan-out from the stored rows (recipient channel switches,
quiet hours); billing code never sends messages itself.

---

## 10. Jobs

All jobs run through `JobSupport.runExclusive` (Postgres advisory lock, one instance at a time, one transaction per
run, metrics `carenexus.jobs.runs{job,outcome}` and `carenexus.jobs.duration{job}`), only when
`carenexus.jobs.enabled` is true; tests call `sweepOnce()`.

| Job | Class | Interval property (default) | Work |
|---|---|---|---|
| `invoice-overdue` | `billing.job.InvoiceOverdueSweeper` | `carenexus.jobs.invoice-overdue.interval-ms` (3600000) | invoices `SENT` or `PARTIALLY_PAID` with `due_date < today - overdueGraceDays` (clinic calendar date), ordered by due date and id, batches of 200, each through `InvoiceService.markOverdue` |
| `payment-reconcile` | `PaymentReconciliationSweeper` | `carenexus.jobs.payment-reconcile.interval-ms` (300000) | `PENDING` payments older than 30 minutes become `FAILED` (`Payment was not confirmed in time`) |

With grace 0 an invoice due on 24 Nov is `SENT` all of 24 Nov and becomes `OVERDUE` at the first sweep after 00:00
clinic time on 25 Nov.

---

## 11. Configuration

| Property | Env (suggested) | Default | Meaning |
|---|---|---|---|
| `carenexus.billing.default-currency` | `BILLING_DEFAULT_CURRENCY` | `KES` | fallback of the admin setting `billing.default-currency` |
| `carenexus.billing.tax-rate` | `BILLING_TAX_RATE` | `0` | fraction 0 to 1; fallback of `billing.tax-rate` |
| `carenexus.billing.payment-terms-days` | `BILLING_PAYMENT_TERMS_DAYS` | `14` | due date = issue date + days; fallback of `billing.payment-terms-days` |
| `carenexus.billing.auto-invoice` | `BILLING_AUTO_INVOICE` | `DRAFT` | `DRAFT`, `SENT` or `OFF` on consultation completion |
| `carenexus.billing.provider` | `BILLING_PROVIDER` | `local` | payment gateway selector |
| `carenexus.billing.claims-provider` | `BILLING_CLAIMS_PROVIDER` | `manual` | insurance claims provider selector |
| `carenexus.billing.local.webhook-secret` | `BILLING_LOCAL_WEBHOOK_SECRET` | dev-only value | HMAC key of the local webhook; refused outside dev profiles when it is the dev value or shorter than 32 characters |
| `carenexus.billing.overdue-grace-days` | `BILLING_OVERDUE_GRACE_DAYS` | `0` | days after the due date before the sweeper acts (negative read as 0) |
| `carenexus.jobs.invoice-overdue.interval-ms` | `JOBS_INVOICE_OVERDUE_INTERVAL_MS` | `3600000` | overdue sweep interval |
| `carenexus.jobs.payment-reconcile.interval-ms` | `JOBS_PAYMENT_RECONCILE_INTERVAL_MS` | `300000` | reconciliation interval |
| `clinic.name` (admin setting) | - | `CareNexus` | clinic name printed on the receipt |

---

## 12. Provider seams: what a real gateway or insurer implements

Everything else in billing is provider-neutral. Replacing the local implementations means adding a bean and selecting
it by property; no endpoint, table or event changes.

### 12.1 `com.carenexus.api.billing.provider.PaymentGateway`

```java
public interface PaymentGateway {
    String name();                       // the {provider} of the webhook path and Payment.gateway, e.g. "mpesa"
    boolean isConfigured();              // false: webhook answers 404, startup checks report it
    Set<String> supportedMethods();      // subset of CARD, INSURANCE, CASH, MPESA, BANK_TRANSFER
    InitiateResult initiate(InitiateRequest request);    // throws PaymentGatewayException (503 to the client)
    RefundResult refund(String providerReference, BigDecimal amount, String currency, String idempotencyKey);
    String tokenize(String method, Map<String, String> details);   // opaque instrument token
    WebhookEvent parseWebhook(String rawBody, Map<String, String> headers);  // verifies the signature,
                                                                             // throws InvalidWebhookSignatureException
    record InitiateRequest(String paymentRef, String idempotencyKey, BigDecimal amount, String currency,
                           String method, String payerPhone, String methodToken, String description) {}
    record InitiateResult(String providerReference, String status /* PENDING, COMPLETED, FAILED */,
                          String nextAction /* NONE, STK_PUSH, AWAIT_CONFIRMATION, REDIRECT */,
                          String redirectUrl, String failureReason) {}
    record RefundResult(String providerReference, String status) {}
    record WebhookEvent(String eventId, String eventType /* PAYMENT_COMPLETED, PAYMENT_FAILED, other */,
                        String providerReference, String transactionId, String failureReason) {}
}
```

Built-in: `LocalPaymentGateway` (`name()` = `local`). Selected by `carenexus.billing.provider`. A real adapter must:
pass the `idempotencyKey` to the provider, map the provider's callback onto `WebhookEvent`, return `REDIRECT` with a
`redirectUrl` for hosted checkouts, and void or refund a charge whose completion the invoice refused (the payment is
then `FAILED` with `The invoice could no longer accept this payment`).

### 12.2 `com.carenexus.api.billing.provider.InsuranceClaimProvider`

```java
public interface InsuranceClaimProvider {
    String name();                                        // "manual"
    String submit(InsuranceClaim claim);                  // returns the provider reference stored on the claim
    Optional<ClaimStatusUpdate> parseStatusUpdate(String rawBody, Map<String, String> headers);
}
```

Built-in: `ManualInsuranceClaimProvider` (`submit` returns `MANUAL-<claimNo>` without any external call; statuses are
moved by an administrator; `parseStatusUpdate` returns empty). Selected by `carenexus.billing.claims-provider`. A
real insurer adapter adds a signed status callback endpoint that applies the update through the same claim service
as the administrator `PATCH`, so the state machine, the settlement payment, the events and the notifications stay the
same.

### 12.3 Not seams

- Notifications: in-app rows are stored by `NotificationPublisher`; email, SMS and push go through the delivery
  module's provider seams (`carenexus.delivery.*`), not through billing.
- The receipt is rendered on every request and never stored; there is no PDF or document-storage dependency.
