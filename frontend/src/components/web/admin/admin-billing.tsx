import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, decorative, EmptyView, ErrorView, fontSize, FormActions, FormError,
  InfoRow, LoadingView, Modal, NoAccessView, PagedList, ROLE_THEME, Screen, SectionCard, SelectField, SidePanel,
  space, StatusBadge, statusLabel, statusTone, Tabs, TextField,
  type SelectOption, type TabItem,
} from '@/components/web/ui';
import {
  useAdjudicateClaim, useAdminClaim, useAdminClaims, useAdminInvoice, useAdminInvoices, useAdminPayments,
  useClaimDocumentDownload, useDefaultFees, useInvoiceClaims, useInvoicePayments, useOpenClaimsCount,
  usePayableInvoices, useRecordManualPayment, useRefundPayment, useSaveDefaultFees, useVoidInvoice,
} from '@/hooks/use-admin-billing';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  amountText, APPOINTMENT_TYPE_LABEL, APPOINTMENT_TYPES, canRecordPayment, canVoidInvoice, CLAIM_REASON_MAX,
  CLAIM_STATUS_LABEL, CLAIM_STATUSES, claimActions, claimSettlement, defaultFeesSchema, INVOICE_STATUS_LABEL,
  INVOICE_STATUSES, MANUAL_PAYMENT_METHODS, manualPaymentSchema, OPEN_CLAIM_STATUSES, OPEN_CLAIMS_FILTER,
  parseMoney, partialApprovalSchema, PATIENT_ID_PATTERN, PAYABLE_INVOICE_STATUSES, PAYMENT_METHOD_LABEL,
  PAYMENT_METHODS, PAYMENT_REFERENCE_MAX, PAYMENT_STATUS_LABEL, PAYMENT_STATUSES, paymentReference,
  REFUND_REASON_MAX, refundableAmount, refundSchema, rejectClaimSchema, toCents, VOID_REASON_MAX,
  voidInvoiceSchema,
  type AdminPayment, type AppointmentType, type ClaimStatus, type DefaultFeeInput, type DefaultFeesValues,
  type Fee, type InsuranceClaim, type Invoice, type InvoiceStatus, type ManualPaymentMethod,
  type ManualPaymentValues, type PartialApprovalValues, type PaymentMethodCode, type PaymentStatus,
  type RefundValues, type RejectClaimValues, type VoidInvoiceValues,
} from '@/lib/admin/billing-types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { formatMoney } from '@/lib/format/money';
import type { SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';

// Administrator billing back office (section `billing` of the admin dashboard).
//
// Tabs (URL param `tab`): invoices (default), payments, claims, fees. The opened record is the `id` param:
// with tab=claims it is a claim id (the link of the "New insurance claim" notification), on every other tab
// an invoice id. Filters live in the URL too (`status`, `method`, `patient`), so a refresh keeps the view.
//
// Every action that moves money (void, manual payment, refund, claim decision, fee change) is reviewed in a
// confirmation step before it is sent, and a refusal by the server (409) is shown inside that dialog.

type TabKey = 'invoices' | 'payments' | 'claims' | 'fees';
const TAB_KEYS: readonly TabKey[] = ['invoices', 'payments', 'claims', 'fees'];
const TAB_LABEL: Record<TabKey, string> = {
  invoices: 'Invoices', payments: 'Payments', claims: 'Claims', fees: 'Default fees',
};

/** Below this window width the lists are cards with a label beside every value; above it they read as tables. */
const TABLE_BREAKPOINT = 1280;
const DASH = '—';

type SetParams = (params: SectionParamsInput) => void;

function positiveInt(value: string | undefined): number | null {
  if (!value || !/^\d{1,15}$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

function patientParam(value: string | undefined): string | null {
  const id = (value ?? '').trim().toUpperCase();
  return PATIENT_ID_PATTERN.test(id) ? id : null;
}

function money(amount: number | null | undefined, currency: string | null | undefined): string {
  return formatMoney(amount, currency, DASH);
}

function personLabel(name: string | null | undefined, systemId: string | null | undefined): string {
  const who = name?.trim();
  if (who && systemId) return `${who} (${systemId})`;
  return who || systemId || DASH;
}

function invoiceStatusLabel(status: string): string {
  return INVOICE_STATUS_LABEL[status as InvoiceStatus] ?? statusLabel(status);
}
function paymentStatusLabel(status: string): string {
  return PAYMENT_STATUS_LABEL[status as PaymentStatus] ?? statusLabel(status);
}
function paymentMethodLabel(method: string): string {
  return PAYMENT_METHOD_LABEL[method as PaymentMethodCode] ?? statusLabel(method);
}
function claimStatusLabel(status: string): string {
  return CLAIM_STATUS_LABEL[status as ClaimStatus] ?? statusLabel(status);
}

/**
 * The review step of a money dialog. `enter` stores what will be sent and shows the summary; `ready` is false
 * for a press that lands right after the step opened, so the second half of a double click on "Review" can
 * never confirm the action.
 */
function useReviewStep<V>() {
  const [review, setReview] = useState<V | null>(null);
  const enteredAt = useRef(0);
  const enter = useCallback((values: V) => { enteredAt.current = Date.now(); setReview(values); }, []);
  const back = useCallback(() => setReview(null), []);
  const ready = useCallback(() => Date.now() - enteredAt.current > 400, []);
  return { review, enter, back, ready };
}

// ─── Small building blocks ────────────────────────────────────────────────────

function Primary({ children }: { children: ReactNode }) {
  return <Text style={styles.cellPrimary}>{children}</Text>;
}

function Secondary({ children }: { children: ReactNode }) {
  return <Text style={styles.cellSecondary}>{children}</Text>;
}

interface Column<T> {
  key:    string;
  label:  string;
  /** Share of the row's width on a wide screen. */
  flex:   number;
  render: (item: T) => ReactNode;
}

/**
 * One record of a list. On a wide screen it is a table row (the column headings are drawn above the first
 * row, and every cell also carries its heading for assistive technology); on a narrow one it is a card with
 * the heading beside each value, so nothing scrolls sideways.
 */
function RecordRow<T>({ item, index, columns, wide, actions }: {
  item:     T;
  index:    number;
  columns:  readonly Column<T>[];
  wide:     boolean;
  actions?: ReactNode;
}) {
  if (wide) {
    return (
      <View style={styles.rowWrap}>
        {index === 0 ? (
          <View style={styles.headRow} {...decorative}>
            {columns.map(c => <Text key={c.key} style={[styles.headCell, { flex: c.flex }]}>{c.label}</Text>)}
            <View style={styles.actionsWide} />
          </View>
        ) : null}
        <Card padded={false} style={styles.rowCard}>
          {columns.map(c => (
            <View key={c.key} style={[styles.cell, { flex: c.flex }]}>
              <Text style={styles.srOnly}>{`${c.label}:`}</Text>
              {c.render(item)}
            </View>
          ))}
          <View style={styles.actionsWide}>{actions}</View>
        </Card>
      </View>
    );
  }
  return (
    <Card style={styles.stackCard}>
      {columns.map(c => (
        <View key={c.key} style={styles.stackRow}>
          <Text style={styles.stackLabel}>{c.label}</Text>
          <View style={styles.stackValue}>{c.render(item)}</View>
        </View>
      ))}
      {actions ? <View style={styles.stackActions}>{actions}</View> : null}
    </Card>
  );
}

function FilterBar({ children }: { children: ReactNode }) {
  return <View style={styles.filterBar}>{children}</View>;
}

/** The patient filter: a PAT id typed in and applied with the button or Enter. */
function PatientFilter({ value, onApply }: { value: string | null; onApply: (patientSystemId: string | null) => void }) {
  const [text, setText] = useState(value ?? '');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setText(value ?? ''); setError(null); }, [value]);

  const apply = () => {
    const id = text.trim().toUpperCase();
    if (!id) { setError(null); onApply(null); return; }
    if (!PATIENT_ID_PATTERN.test(id)) { setError('Enter a patient ID such as PAT-00001'); return; }
    setError(null);
    onApply(id);
  };

  return (
    <View style={styles.patientFilter}>
      <View style={styles.filterField}>
        <TextField
          label="Patient ID"
          value={text}
          onChange={next => { setText(next); if (error) setError(null); }}
          error={error}
          placeholder="PAT-00001"
          autoCapitalize="characters"
          maxLength={20}
          onSubmit={apply}
        />
      </View>
      <View style={styles.filterButtons}>
        <Button label="Apply" variant="secondary" onPress={apply} accessibilityLabel="Apply patient filter" />
        {value ? (
          <Button label="Clear" variant="ghost" onPress={() => onApply(null)} accessibilityLabel="Clear patient filter" />
        ) : null}
      </View>
    </View>
  );
}

/** What a dialog is about to do, shown before the confirming button is pressed. */
function ConfirmSummary({ intro, lines, warning, error }: {
  intro:    string;
  lines:    { label: string; value: string }[];
  warning?: string | null;
  /** The server's refusal, shown where the decision is made. */
  error?:   string | null;
}) {
  return (
    <>
      <View style={styles.confirm} aria-live="polite">
        <Text style={styles.dialogText}>{intro}</Text>
        <View>
          {lines.map(line => <InfoRow key={line.label} label={line.label} value={line.value} />)}
        </View>
      </View>
      {warning ? <Banner tone="warning" text={warning} /> : null}
      <FormError message={error} />
    </>
  );
}

function PanelHeading({ children }: { children: string }) {
  return <Text style={styles.panelHeading} accessibilityRole="header" {...ariaLevel(3)}>{children}</Text>;
}

// ─── Invoices ─────────────────────────────────────────────────────────────────

const UNPAID_KEY = 'UNPAID';

const INVOICE_STATUS_OPTIONS: readonly SelectOption[] = [
  { value: UNPAID_KEY, label: 'Unpaid (sent, partly paid or overdue)' },
  ...INVOICE_STATUSES.map(s => ({ value: s, label: INVOICE_STATUS_LABEL[s] })),
];

function invoiceStatusKey(param: string | undefined): string {
  const key = (param ?? '').trim().toUpperCase();
  return key === UNPAID_KEY || INVOICE_STATUSES.some(s => s === key) ? key : '';
}

const INVOICE_COLUMNS: readonly Column<Invoice>[] = [
  {
    key: 'invoice', label: 'Invoice', flex: 3,
    render: i => (<><Primary>{i.invoiceNo}</Primary><Secondary>{formatClinicDate(i.createdAt, DASH)}</Secondary></>),
  },
  {
    key: 'patient', label: 'Patient', flex: 3,
    render: i => (<><Primary>{i.patientName?.trim() || i.patientSystemId}</Primary>{i.patientName?.trim() ? <Secondary>{i.patientSystemId}</Secondary> : null}</>),
  },
  {
    key: 'total', label: 'Total', flex: 3,
    render: i => (
      <>
        <Primary>{money(i.total, i.currency)}</Primary>
        {toCents(i.outstanding) > 0 ? <Secondary>{`${money(i.outstanding, i.currency)} outstanding`}</Secondary> : null}
      </>
    ),
  },
  {
    key: 'status', label: 'Status', flex: 2,
    render: i => <StatusBadge label={invoiceStatusLabel(i.status)} tone={statusTone('invoice', i.status)} />,
  },
  { key: 'due', label: 'Due', flex: 2, render: i => <Primary>{formatDateOnly(i.dueDate)}</Primary> },
];

function InvoicesTab({ statusParam, patient, reload, wide, setParams, onOpen }: {
  statusParam: string | undefined;
  patient:     string | null;
  reload:      number;
  wide:        boolean;
  setParams:   SetParams;
  onOpen:      (invoiceId: number) => void;
}) {
  const statusKey = invoiceStatusKey(statusParam);
  const status = statusKey === UNPAID_KEY ? PAYABLE_INVOICE_STATUSES.join(',') : statusKey || undefined;
  const invoices = useAdminInvoices({ status, patientSystemId: patient ?? undefined }, reload);
  const filtered = !!statusKey || !!patient;

  return (
    <PagedList
      state={invoices}
      label="Invoices"
      keyOf={invoice => invoice.invoiceId}
      header={(
        <FilterBar>
          <View style={styles.filterField}>
            <SelectField
              label="Status"
              value={statusKey}
              onChange={value => setParams({ status: value || undefined })}
              options={INVOICE_STATUS_OPTIONS}
              placeholder="All statuses"
              allowEmpty
            />
          </View>
          <PatientFilter value={patient} onApply={id => setParams({ patient: id ?? undefined })} />
        </FilterBar>
      )}
      empty={filtered
        ? { icon: 'receipt-outline', title: 'No invoices match these filters', sub: 'Change or clear the filters to see more invoices.' }
        : { icon: 'receipt-outline', title: 'No invoices yet', sub: 'Invoices appear here once a consultation has been invoiced.' }}
      renderItem={(invoice, index) => (
        <RecordRow
          item={invoice}
          index={index}
          columns={INVOICE_COLUMNS}
          wide={wide}
          actions={(
            <Button
              label="View" variant="secondary" size="sm" icon="open-outline"
              accessibilityLabel={`View invoice ${invoice.invoiceNo}`}
              onPress={() => onOpen(invoice.invoiceId)}
            />
          )}
        />
      )}
    />
  );
}

function PaymentLine({ payment, onRefund }: { payment: AdminPayment; onRefund: (payment: AdminPayment) => void }) {
  const reference = paymentReference(payment);
  const refundable = refundableAmount(payment);
  return (
    <Card style={styles.lineCard}>
      <View style={styles.lineTop}>
        <View style={styles.lineTitles}>
          <Primary>{`${money(payment.amount, payment.currency)} · ${paymentMethodLabel(payment.method)}`}</Primary>
          <Secondary>{`Payment #${payment.paymentId} · ${formatClinicDateTime(payment.paidAt ?? payment.createdAt, DASH)}`}</Secondary>
        </View>
        <StatusBadge label={paymentStatusLabel(payment.status)} tone={statusTone('payment', payment.status)} />
      </View>
      {reference ? <Secondary>{`Reference: ${reference}`}</Secondary> : null}
      {toCents(payment.refundedAmount) > 0 ? (
        <Secondary>
          {`Refunded ${money(payment.refundedAmount, payment.currency)}${payment.refundReason ? `: ${payment.refundReason}` : ''}`}
        </Secondary>
      ) : null}
      {payment.failureReason ? <Secondary>{payment.failureReason}</Secondary> : null}
      {refundable > 0 ? (
        <Button
          label="Refund" variant="secondary" size="sm" icon="return-up-back-outline"
          accessibilityLabel={`Refund payment ${payment.paymentId}`}
          onPress={() => onRefund(payment)}
        />
      ) : null}
    </Card>
  );
}

function ClaimLine({ claim, onOpen }: { claim: InsuranceClaim; onOpen: (claimId: number) => void }) {
  return (
    <Card style={styles.lineCard}>
      <View style={styles.lineTop}>
        <View style={styles.lineTitles}>
          <Primary>{`${claim.claimNo} · ${claim.insuranceProvider}`}</Primary>
          <Secondary>
            {`Claimed ${money(claim.claimAmount, claim.currency)}`
              + (claim.approvedAmount != null ? ` · approved ${money(claim.approvedAmount, claim.currency)}` : '')}
          </Secondary>
        </View>
        <StatusBadge label={claimStatusLabel(claim.status)} tone={statusTone('claim', claim.status)} />
      </View>
      <Button
        label="Open claim" variant="secondary" size="sm" icon="open-outline"
        accessibilityLabel={`Open claim ${claim.claimNo}`}
        onPress={() => onOpen(claim.claimId)}
      />
    </Card>
  );
}

function InvoiceDetail({ invoice, reload, onRefund, onOpenClaim }: {
  invoice:     Invoice;
  reload:      number;
  onRefund:    (payment: AdminPayment) => void;
  onOpenClaim: (claimId: number) => void;
}) {
  const payments = useInvoicePayments(invoice.invoiceId, reload);
  const claims = useInvoiceClaims(invoice.invoiceId, reload);
  const c = invoice.currency;

  return (
    <>
      <View>
        <InfoRow label="Status">
          <StatusBadge label={invoiceStatusLabel(invoice.status)} tone={statusTone('invoice', invoice.status)} />
        </InfoRow>
        <InfoRow label="Patient" value={personLabel(invoice.patientName, invoice.patientSystemId)} />
        <InfoRow label="Doctor" value={personLabel(invoice.doctorName, invoice.doctorSystemId)} />
        <InfoRow label="Created" value={formatClinicDateTime(invoice.createdAt, DASH)} />
        <InfoRow label="Created by" value={invoice.createdBySystemId} emptyText={DASH} />
        <InfoRow label="Sent" value={formatClinicDateTime(invoice.issuedAt)} emptyText="Not sent yet" />
        <InfoRow label="Due" value={formatDateOnly(invoice.dueDate)} />
        {invoice.paidAt ? <InfoRow label="Paid" value={formatClinicDateTime(invoice.paidAt, DASH)} /> : null}
        {invoice.cancelReason ? <InfoRow label="Cancel reason" value={invoice.cancelReason} /> : null}
        {invoice.notes ? <InfoRow label="Notes" value={invoice.notes} /> : null}
      </View>

      <PanelHeading>Items</PanelHeading>
      {invoice.lineItems.length === 0 ? (
        <Secondary>This invoice has no line items.</Secondary>
      ) : (
        <View role="list" aria-label="Invoice items" style={styles.items}>
          {invoice.lineItems.map((line, index) => (
            <View key={index} role="listitem" style={styles.itemRow}>
              <View style={styles.itemText}>
                <Primary>{line.description}</Primary>
                <Secondary>{`${line.quantity} × ${money(line.unitPrice, c)}`}</Secondary>
              </View>
              <Text style={styles.itemAmount}>{money(line.amount, c)}</Text>
            </View>
          ))}
        </View>
      )}
      <View>
        <InfoRow label="Subtotal" value={money(invoice.subtotal, c)} />
        {toCents(invoice.discount) > 0 ? <InfoRow label="Discount" value={money(invoice.discount, c)} /> : null}
        <InfoRow label="Tax" value={money(invoice.tax, c)} />
        <InfoRow label="Total" value={money(invoice.total, c)} />
        <InfoRow label="Paid" value={money(invoice.amountPaid, c)} />
        <InfoRow label="Outstanding" value={money(invoice.outstanding, c)} />
      </View>
      {toCents(invoice.amountPaid) > 0 && invoice.status !== 'CANCELLED' && invoice.status !== 'REFUNDED' ? (
        <Secondary>This invoice has received payments, so it cannot be voided.</Secondary>
      ) : null}

      <PanelHeading>Payments</PanelHeading>
      <PagedList
        state={payments}
        label="Payments of this invoice"
        keyOf={payment => payment.paymentId}
        empty={{ icon: 'card-outline', title: 'No payments yet', sub: 'Payments made or recorded for this invoice appear here.' }}
        renderItem={payment => <PaymentLine payment={payment} onRefund={onRefund} />}
      />

      <PanelHeading>Insurance claims</PanelHeading>
      <PagedList
        state={claims}
        label="Insurance claims of this invoice"
        keyOf={claim => claim.claimId}
        empty={{ icon: 'shield-checkmark-outline', title: 'No insurance claims', sub: 'Claims submitted for this invoice appear here.' }}
        renderItem={claim => <ClaimLine claim={claim} onOpen={onOpenClaim} />}
      />
    </>
  );
}

function InvoicePanel({ invoiceId, reload, notice, onDismissNotice, onClose, onVoid, onRecord, onRefund, onOpenClaim }: {
  invoiceId:       number;
  reload:          number;
  notice:          string | null;
  onDismissNotice: () => void;
  onClose:         () => void;
  onVoid:          (invoice: Invoice) => void;
  onRecord:        (invoice: Invoice) => void;
  onRefund:        (payment: AdminPayment) => void;
  onOpenClaim:     (claimId: number) => void;
}) {
  const invoice = useAdminInvoice(invoiceId, reload);
  const data = invoice.data;
  const voidable = !!data && canVoidInvoice(data);
  const payable = !!data && canRecordPayment(data);

  let body: ReactNode;
  if (invoice.loading) {
    body = <LoadingView label="Loading invoice…" />;
  } else if (!data) {
    if (invoice.errorStatus === 404) {
      body = (
        <EmptyView
          icon="document-text-outline"
          title="This invoice is not available"
          sub="It may have been removed, or the link is out of date."
        />
      );
    } else if (invoice.errorStatus === 403) {
      body = <NoAccessView />;
    } else {
      body = <ErrorView message={invoice.error ?? 'The invoice could not be loaded.'} onRetry={invoice.refetch} />;
    }
  } else {
    body = (
      <>
        {notice ? <Banner tone="success" text={notice} onDismiss={onDismissNotice} /> : null}
        {invoice.error ? <Banner tone="danger" text={invoice.error} action={{ label: 'Retry', onPress: invoice.refetch }} /> : null}
        <InvoiceDetail invoice={data} reload={reload} onRefund={onRefund} onOpenClaim={onOpenClaim} />
      </>
    );
  }

  return (
    <SidePanel
      open
      size="lg"
      title={data ? `Invoice ${data.invoiceNo}` : 'Invoice'}
      onClose={onClose}
      footer={data && (voidable || payable) ? (
        <View style={styles.panelActions}>
          {voidable ? (
            <Button label="Void invoice" variant="danger" icon="close-circle-outline" onPress={() => onVoid(data)} />
          ) : null}
          {payable ? (
            <Button label="Record payment" icon="cash-outline" onPress={() => onRecord(data)} />
          ) : null}
        </View>
      ) : undefined}
    >
      {body}
    </SidePanel>
  );
}

function VoidInvoiceDialog({ invoice, onClose, onDone, onConflict }: {
  invoice:    Invoice;
  onClose:    () => void;
  onDone:     (message: string) => void;
  onConflict: () => void;
}) {
  const action = useVoidInvoice();
  const step = useReviewStep<VoidInvoiceValues>();
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, setError } = useForm<VoidInvoiceValues>({
    resolver: zodResolver(voidInvoiceSchema),
    defaultValues: { reason: '' },
  });

  const review = handleSubmit(values => { setFormError(null); action.reset(); step.enter(values); });
  const confirm = async () => {
    if (!step.review || !step.ready()) return;
    const result = await action.run(invoice.invoiceId, step.review.reason);
    if (result.ok) { onDone(`Invoice ${result.data.invoiceNo} was voided.`); return; }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      setFormError(applyFieldErrors(result.error, setError, ['reason']));
      action.reset();
      step.back();
    } else if (result.error.status === 409) {
      onConflict();
    }
  };

  return (
    <Modal
      open
      title="Void invoice"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={step.review ? (
        <FormActions submitLabel="Void invoice" destructive onSubmit={confirm} onCancel={() => { action.reset(); step.back(); }} cancelLabel="Back" submitting={action.pending} />
      ) : (
        <FormActions submitLabel="Review" onSubmit={review} onCancel={onClose} />
      )}
    >
      {step.review ? (
        <ConfirmSummary
          intro="You are about to void this invoice. The patient will no longer be asked to pay it."
          lines={[
            { label: 'Invoice', value: invoice.invoiceNo },
            { label: 'Patient', value: personLabel(invoice.patientName, invoice.patientSystemId) },
            { label: 'Total', value: money(invoice.total, invoice.currency) },
            { label: 'Reason', value: step.review.reason },
          ]}
          warning="A voided invoice cannot be restored. The visit can be invoiced again afterwards."
          error={action.error}
        />
      ) : (
        <>
          <Text style={styles.dialogText}>
            {`Voiding cancels invoice ${invoice.invoiceNo} (${money(invoice.total, invoice.currency)}) for `
              + `${personLabel(invoice.patientName, invoice.patientSystemId)}. An invoice that has received a payment cannot be voided.`}
          </Text>
          <TextField
            label="Reason"
            control={control}
            name="reason"
            multiline
            rows={3}
            maxLength={VOID_REASON_MAX}
            showCount
            required
            hint="Recorded on the invoice as the cancel reason."
          />
          <FormError message={formError} />
        </>
      )}
    </Modal>
  );
}

// ─── Payments ─────────────────────────────────────────────────────────────────

const PAYMENT_STATUS_OPTIONS: readonly SelectOption[] = PAYMENT_STATUSES.map(s => ({ value: s, label: PAYMENT_STATUS_LABEL[s] }));
const PAYMENT_METHOD_OPTIONS: readonly SelectOption[] = PAYMENT_METHODS.map(m => ({ value: m, label: PAYMENT_METHOD_LABEL[m] }));
const MANUAL_METHOD_OPTIONS: readonly SelectOption<ManualPaymentMethod>[] =
  MANUAL_PAYMENT_METHODS.map(m => ({ value: m, label: PAYMENT_METHOD_LABEL[m] }));

function knownKey(param: string | undefined, known: readonly string[]): string {
  const key = (param ?? '').trim().toUpperCase();
  return known.includes(key) ? key : '';
}

const PAYMENT_COLUMNS: readonly Column<AdminPayment>[] = [
  {
    key: 'payment', label: 'Payment', flex: 3,
    render: p => (<><Primary>{`#${p.paymentId}`}</Primary><Secondary>{formatClinicDateTime(p.paidAt ?? p.createdAt, DASH)}</Secondary></>),
  },
  {
    key: 'for', label: 'Invoice and patient', flex: 4,
    render: p => (<><Primary>{p.invoiceNo ?? 'No invoice'}</Primary><Secondary>{personLabel(p.patientName, p.patientSystemId)}</Secondary></>),
  },
  {
    key: 'amount', label: 'Amount', flex: 3,
    render: p => (
      <>
        <Primary>{money(p.amount, p.currency)}</Primary>
        {toCents(p.refundedAmount) > 0 ? <Secondary>{`${money(p.refundedAmount, p.currency)} refunded`}</Secondary> : null}
      </>
    ),
  },
  {
    key: 'method', label: 'Method', flex: 3,
    render: p => {
      const reference = paymentReference(p);
      return (<><Primary>{paymentMethodLabel(p.method)}</Primary>{reference ? <Secondary>{`Ref ${reference}`}</Secondary> : null}</>);
    },
  },
  {
    key: 'status', label: 'Status', flex: 3,
    render: p => (
      <>
        <StatusBadge label={paymentStatusLabel(p.status)} tone={statusTone('payment', p.status)} />
        {p.failureReason ? <Secondary>{p.failureReason}</Secondary> : null}
      </>
    ),
  },
];

function PaymentsTab({ statusParam, methodParam, patient, reload, wide, setParams, onOpenInvoice, onRefund, onRecord }: {
  statusParam:   string | undefined;
  methodParam:   string | undefined;
  patient:       string | null;
  reload:        number;
  wide:          boolean;
  setParams:     SetParams;
  onOpenInvoice: (invoiceId: number) => void;
  onRefund:      (payment: AdminPayment) => void;
  onRecord:      () => void;
}) {
  const statusKey = knownKey(statusParam, PAYMENT_STATUSES);
  const methodKey = knownKey(methodParam, PAYMENT_METHODS);
  const payments = useAdminPayments(
    { status: statusKey || undefined, method: methodKey || undefined, patientSystemId: patient ?? undefined }, reload,
  );
  const filtered = !!statusKey || !!methodKey || !!patient;

  return (
    <PagedList
      state={payments}
      label="Payments"
      keyOf={payment => payment.paymentId}
      header={(
        <>
          <View style={styles.tabAction}>
            <Button label="Record payment" icon="cash-outline" onPress={onRecord} accessibilityLabel="Record payment received in cash or by bank transfer" />
          </View>
          <FilterBar>
            <View style={styles.filterField}>
              <SelectField
                label="Status"
                value={statusKey}
                onChange={value => setParams({ status: value || undefined })}
                options={PAYMENT_STATUS_OPTIONS}
                placeholder="All statuses"
                allowEmpty
              />
            </View>
            <View style={styles.filterField}>
              <SelectField
                label="Method"
                value={methodKey}
                onChange={value => setParams({ method: value || undefined })}
                options={PAYMENT_METHOD_OPTIONS}
                placeholder="All methods"
                allowEmpty
              />
            </View>
            <PatientFilter value={patient} onApply={id => setParams({ patient: id ?? undefined })} />
          </FilterBar>
        </>
      )}
      empty={filtered
        ? { icon: 'card-outline', title: 'No payments match these filters', sub: 'Change or clear the filters to see more payments.' }
        : { icon: 'card-outline', title: 'No payments yet', sub: 'Payments appear here when patients pay or when you record one.' }}
      renderItem={(payment, index) => {
        const invoiceId = payment.invoiceId;
        return (
          <RecordRow
            item={payment}
            index={index}
            columns={PAYMENT_COLUMNS}
            wide={wide}
            actions={(
              <>
                {invoiceId != null ? (
                  <Button
                    label="Invoice" variant="secondary" size="sm" icon="open-outline"
                    accessibilityLabel={`Invoice ${payment.invoiceNo ?? invoiceId} of payment ${payment.paymentId}`}
                    onPress={() => onOpenInvoice(invoiceId)}
                  />
                ) : null}
                {refundableAmount(payment) > 0 ? (
                  <Button
                    label="Refund" variant="secondary" size="sm" icon="return-up-back-outline"
                    accessibilityLabel={`Refund payment ${payment.paymentId}`}
                    onPress={() => onRefund(payment)}
                  />
                ) : null}
              </>
            )}
          />
        );
      }}
    />
  );
}

const MANUAL_PAYMENT_FIELDS = ['method', 'amount', 'reference'] as const;

/**
 * Records money the clinic already received in cash or by bank transfer. Opened for one invoice (from its
 * panel) or without one (from the Payments tab), in which case the invoice is chosen from the patient's
 * payable invoices first. The invoice is read again when the dialog opens, so the balance shown is current.
 */
function RecordPaymentDialog({ presetInvoiceId, defaultPatient, onClose, onDone, onConflict }: {
  presetInvoiceId: number | null;
  defaultPatient:  string | null;
  onClose:         () => void;
  onDone:          (message: string) => void;
  onConflict:      () => void;
}) {
  const choosing = presetInvoiceId === null;
  const [patientText, setPatientText] = useState(defaultPatient ?? '');
  const [patientError, setPatientError] = useState<string | null>(null);
  const [patientId, setPatientId] = useState<string | null>(defaultPatient);
  const [pickedId, setPickedId] = useState<number | null>(null);
  const invoiceId = presetInvoiceId ?? pickedId;

  const payable = usePayableInvoices(choosing ? patientId : null);
  const invoice = useAdminInvoice(invoiceId);
  const action = useRecordManualPayment();
  const step = useReviewStep<ManualPaymentValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const data = invoice.data;
  const outstanding = data?.outstanding ?? 0;
  const currency = data?.currency;
  const resolver = useMemo(
    () => zodResolver(manualPaymentSchema(outstanding, amount => money(amount, currency))),
    [outstanding, currency],
  );
  const { control, handleSubmit, setError, setValue, watch } = useForm<ManualPaymentValues>({
    resolver,
    defaultValues: { method: '', amount: '', reference: '' },
  });
  const method = watch('method');
  const ready = !!data && canRecordPayment(data);

  const findInvoices = () => {
    const id = patientText.trim().toUpperCase();
    if (!PATIENT_ID_PATTERN.test(id)) { setPatientError('Enter a patient ID such as PAT-00001'); return; }
    setPatientError(null);
    if (id !== patientId) { setPickedId(null); action.renewKey(); }
    setPatientId(id);
  };
  const pickInvoice = (value: string) => {
    const id = positiveInt(value);
    if (id !== pickedId) action.renewKey();
    setPickedId(id);
  };

  const review = handleSubmit(values => { setFormError(null); action.reset(); step.enter(values); });
  const confirm = async () => {
    const values = step.review;
    const amount = values ? parseMoney(values.amount) : null;
    if (!values || !data || amount === null || !step.ready()) return;
    const result = await action.run(data.invoiceId, {
      method:    values.method as ManualPaymentMethod,
      amount,
      reference: values.reference.trim() || null,
    });
    if (result.ok) {
      const paid = result.data;
      onDone(`${paymentMethodLabel(paid.method)} payment of ${money(paid.amount, paid.currency)} was recorded on invoice ${paid.invoiceNo ?? data.invoiceNo}.`);
      return;
    }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      setFormError(applyFieldErrors(result.error, setError, MANUAL_PAYMENT_FIELDS));
      action.reset();
      step.back();
    } else if (result.error.status === 409) {
      // The balance or the status changed under the dialog: show the server's sentence and read the invoice again.
      invoice.refetch();
      onConflict();
    }
  };

  const invoiceOptions: SelectOption[] = payable.invoices.map(i => ({
    value: String(i.invoiceId),
    label: `${i.invoiceNo} · ${money(i.outstanding, i.currency)} outstanding`,
  }));

  let invoicePart: ReactNode = null;
  if (invoiceId !== null) {
    if (invoice.loading) {
      invoicePart = <LoadingView compact label="Loading invoice…" />;
    } else if (!data) {
      invoicePart = invoice.errorStatus === 404
        ? <Banner tone="danger" text="This invoice is not available." />
        : <ErrorView compact message={invoice.error ?? 'The invoice could not be loaded.'} onRetry={invoice.refetch} />;
    } else if (!ready) {
      invoicePart = (
        <Banner
          tone="warning"
          text={`Invoice ${data.invoiceNo} is ${invoiceStatusLabel(data.status).toLowerCase()} with ${money(data.outstanding, data.currency)} outstanding, so it cannot take a payment.`}
        />
      );
    } else {
      const transfer = method === 'BANK_TRANSFER';
      invoicePart = (
        <>
          <View>
            <InfoRow label="Invoice" value={data.invoiceNo} />
            <InfoRow label="Patient" value={personLabel(data.patientName, data.patientSystemId)} />
            <InfoRow label="Outstanding" value={money(data.outstanding, data.currency)} />
          </View>
          <SelectField
            label="Received by"
            control={control}
            name="method"
            options={MANUAL_METHOD_OPTIONS}
            placeholder="Choose cash or bank transfer"
            required
          />
          <TextField
            label={`Amount (${data.currency})`}
            control={control}
            name="amount"
            inputMode="decimal"
            maxLength={13}
            placeholder="0.00"
            required
            hint={`At most the outstanding ${money(data.outstanding, data.currency)}. A lower amount leaves the invoice partly paid.`}
          />
          <Button
            label="Use the full balance" variant="ghost" size="sm"
            onPress={() => setValue('amount', amountText(data.outstanding), { shouldDirty: true, shouldValidate: true })}
          />
          <TextField
            label={transfer ? 'Bank reference' : 'Receipt number'}
            control={control}
            name="reference"
            maxLength={PAYMENT_REFERENCE_MAX}
            required={transfer}
            hint={transfer ? 'The transaction reference of the transfer.' : 'Optional.'}
          />
          <FormError message={formError} />
        </>
      );
    }
  }

  return (
    <Modal
      open
      title="Record payment"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={step.review ? (
        <FormActions submitLabel="Record payment" onSubmit={confirm} onCancel={() => { action.reset(); step.back(); }} cancelLabel="Back" submitting={action.pending} />
      ) : (
        <FormActions submitLabel="Review payment" onSubmit={review} onCancel={onClose} disabled={!ready} />
      )}
    >
      {step.review && data ? (
        <ConfirmSummary
          intro="You are about to record money the clinic has already received."
          lines={[
            { label: 'Invoice', value: data.invoiceNo },
            { label: 'Patient', value: personLabel(data.patientName, data.patientSystemId) },
            { label: 'Received by', value: paymentMethodLabel(step.review.method) },
            { label: 'Amount', value: money(parseMoney(step.review.amount), data.currency) },
            { label: step.review.method === 'BANK_TRANSFER' ? 'Bank reference' : 'Receipt number', value: step.review.reference.trim() || 'None' },
          ]}
          warning="The payment is applied to the invoice straight away and the patient is told. A mistake can only be corrected with a refund."
          error={action.error}
        />
      ) : (
        <>
          {choosing ? (
            <>
              <Text style={styles.dialogText}>
                Find the invoice the money was received for. Only invoices that are sent, partly paid or overdue can take a payment.
              </Text>
              <View style={styles.patientFilter}>
                <View style={styles.filterField}>
                  <TextField
                    label="Patient ID"
                    value={patientText}
                    onChange={next => { setPatientText(next); if (patientError) setPatientError(null); }}
                    error={patientError}
                    placeholder="PAT-00001"
                    autoCapitalize="characters"
                    maxLength={20}
                    onSubmit={findInvoices}
                    required
                  />
                </View>
                <View style={styles.filterButtons}>
                  <Button label="Find invoices" variant="secondary" onPress={findInvoices} />
                </View>
              </View>
              {patientId === null ? null : payable.loading ? (
                <LoadingView compact label="Loading invoices…" />
              ) : payable.error ? (
                <ErrorView compact message={payable.error} onRetry={payable.refetch} />
              ) : payable.invoices.length === 0 ? (
                <EmptyView compact icon="receipt-outline" title="No invoices waiting for payment" sub={`${patientId} has no sent, partly paid or overdue invoice with a balance.`} />
              ) : (
                <>
                  <SelectField
                    label="Invoice"
                    value={pickedId === null ? '' : String(pickedId)}
                    onChange={pickInvoice}
                    options={invoiceOptions}
                    placeholder="Choose an invoice"
                    required
                  />
                  {payable.truncated ? (
                    <Secondary>Only the 100 newest payable invoices of this patient are listed. Open an older one from the Invoices tab.</Secondary>
                  ) : null}
                </>
              )}
            </>
          ) : null}
          {invoicePart}
        </>
      )}
    </Modal>
  );
}

function RefundDialog({ payment, onClose, onDone, onConflict }: {
  payment:    AdminPayment;
  onClose:    () => void;
  onDone:     (message: string) => void;
  onConflict: () => void;
}) {
  const refundable = refundableAmount(payment);
  const currency = payment.currency;
  const action = useRefundPayment();
  const step = useReviewStep<RefundValues>();
  const [formError, setFormError] = useState<string | null>(null);
  const resolver = useMemo(
    () => zodResolver(refundSchema(refundable, amount => money(amount, currency))),
    [refundable, currency],
  );
  const { control, handleSubmit, setError } = useForm<RefundValues>({
    resolver,
    defaultValues: { amount: amountText(refundable), reason: '' },
  });

  /** The amount a reviewed form refunds: what was typed, or everything refundable when the field is blank. */
  const amountOf = (values: RefundValues): number => parseMoney(values.amount) ?? refundable;

  const review = handleSubmit(values => { setFormError(null); action.reset(); step.enter(values); });
  const confirm = async () => {
    const values = step.review;
    if (!values || !step.ready()) return;
    const typed = parseMoney(values.amount);
    const result = await action.run(payment.paymentId, typed === null ? { reason: values.reason } : { amount: typed, reason: values.reason });
    if (result.ok) {
      // What the server refunded since the dialog opened (a replayed request answers with the first attempt).
      const refunded = toCents(result.data.refundedAmount) - toCents(payment.refundedAmount);
      const amount = refunded > 0 ? refunded / 100 : amountOf(values);
      onDone(`${money(amount, currency)} was refunded on payment #${payment.paymentId}.`);
      return;
    }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      setFormError(applyFieldErrors(result.error, setError, ['amount', 'reason']));
      action.reset();
      step.back();
    } else if (result.error.status === 409) {
      onConflict();
    }
  };

  const manual = payment.method === 'CASH' || payment.method === 'BANK_TRANSFER';
  const reviewed = step.review;
  const full = reviewed ? toCents(amountOf(reviewed)) >= toCents(refundable) : false;

  return (
    <Modal
      open
      title="Refund payment"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={reviewed ? (
        <FormActions submitLabel="Refund" destructive onSubmit={confirm} onCancel={() => { action.reset(); step.back(); }} cancelLabel="Back" submitting={action.pending} />
      ) : (
        <FormActions submitLabel="Review refund" onSubmit={review} onCancel={onClose} />
      )}
    >
      {reviewed ? (
        <ConfirmSummary
          intro="You are about to refund this payment."
          lines={[
            { label: 'Payment', value: `#${payment.paymentId} · ${paymentMethodLabel(payment.method)}` },
            { label: 'Invoice', value: payment.invoiceNo ?? DASH },
            { label: 'Patient', value: personLabel(payment.patientName, payment.patientSystemId) },
            { label: 'Refund', value: `${money(amountOf(reviewed), currency)} of ${money(payment.amount, currency)}` },
            { label: 'Reason', value: reviewed.reason },
          ]}
          warning={
            `${full ? 'The payment becomes refunded' : 'The payment stays completed with the refunded part recorded'}, and the invoice shows the amount as unpaid again.`
            + (manual ? ' This only records the refund: return the money to the payer yourself.' : '')
          }
          error={action.error}
        />
      ) : (
        <>
          <View>
            <InfoRow label="Payment" value={`#${payment.paymentId} · ${paymentMethodLabel(payment.method)}`} />
            <InfoRow label="Invoice" value={payment.invoiceNo} emptyText={DASH} />
            <InfoRow label="Patient" value={personLabel(payment.patientName, payment.patientSystemId)} />
            <InfoRow label="Paid" value={money(payment.amount, currency)} />
            {toCents(payment.refundedAmount) > 0 ? <InfoRow label="Refunded so far" value={money(payment.refundedAmount, currency)} /> : null}
            <InfoRow label="Refundable" value={money(refundable, currency)} />
          </View>
          <TextField
            label={`Amount (${currency})`}
            control={control}
            name="amount"
            inputMode="decimal"
            maxLength={13}
            placeholder="0.00"
            hint={`At most ${money(refundable, currency)}. Leave it empty to refund everything that is refundable.`}
          />
          <TextField
            label="Reason"
            control={control}
            name="reason"
            multiline
            rows={3}
            maxLength={REFUND_REASON_MAX}
            showCount
            required
          />
          <FormError message={formError} />
        </>
      )}
    </Modal>
  );
}

// ─── Claims ───────────────────────────────────────────────────────────────────

const ALL_CLAIMS_KEY = 'ALL';

const CLAIM_STATUS_OPTIONS: readonly SelectOption[] = [
  { value: ALL_CLAIMS_KEY, label: 'All claims' },
  ...CLAIM_STATUSES.map(s => ({ value: s, label: CLAIM_STATUS_LABEL[s] })),
];

const CLAIM_COLUMNS: readonly Column<InsuranceClaim>[] = [
  {
    key: 'claim', label: 'Claim', flex: 3,
    render: c => (<><Primary>{c.claimNo}</Primary><Secondary>{formatClinicDate(c.submittedAt, DASH)}</Secondary></>),
  },
  {
    key: 'patient', label: 'Patient', flex: 3,
    render: c => (<><Primary>{c.patientName?.trim() || c.patientSystemId}</Primary>{c.patientName?.trim() ? <Secondary>{c.patientSystemId}</Secondary> : null}</>),
  },
  {
    key: 'insurer', label: 'Insurer and invoice', flex: 4,
    render: c => (<><Primary>{c.insuranceProvider}</Primary><Secondary>{c.invoiceNo ?? `Invoice #${c.invoiceId}`}</Secondary></>),
  },
  {
    key: 'claimed', label: 'Claimed', flex: 3,
    render: c => (
      <>
        <Primary>{money(c.claimAmount, c.currency)}</Primary>
        {c.approvedAmount != null ? <Secondary>{`${money(c.approvedAmount, c.currency)} approved`}</Secondary> : null}
      </>
    ),
  },
  {
    key: 'status', label: 'Status', flex: 3,
    render: c => <StatusBadge label={claimStatusLabel(c.status)} tone={statusTone('claim', c.status)} />,
  },
];

function ClaimsTab({ statusParam, patient, reload, wide, setParams, onOpen }: {
  statusParam: string | undefined;
  patient:     string | null;
  reload:      number;
  wide:        boolean;
  setParams:   SetParams;
  onOpen:      (claimId: number) => void;
}) {
  // No status in the URL is the review queue; ALL lifts the filter.
  const statusKey = knownKey(statusParam, [ALL_CLAIMS_KEY, ...CLAIM_STATUSES]);
  const status = statusKey === '' ? OPEN_CLAIMS_FILTER : statusKey === ALL_CLAIMS_KEY ? undefined : statusKey;
  const claims = useAdminClaims({ status, patientSystemId: patient ?? undefined }, reload);

  return (
    <PagedList
      state={claims}
      label="Insurance claims"
      keyOf={claim => claim.claimId}
      header={(
        <FilterBar>
          <View style={styles.filterField}>
            <SelectField
              label="Status"
              value={statusKey}
              onChange={value => setParams({ status: value || undefined })}
              options={CLAIM_STATUS_OPTIONS}
              placeholder="Waiting for review"
              allowEmpty
            />
          </View>
          <PatientFilter value={patient} onApply={id => setParams({ patient: id ?? undefined })} />
        </FilterBar>
      )}
      empty={statusKey === '' && !patient
        ? { icon: 'shield-checkmark-outline', title: 'No claims are waiting for review', sub: 'New and appealed insurance claims appear here.' }
        : { icon: 'shield-checkmark-outline', title: 'No claims match these filters', sub: 'Change or clear the filters to see more claims.' }}
      renderItem={(claim, index) => {
        const open = OPEN_CLAIM_STATUSES.includes(claim.status);
        return (
          <RecordRow
            item={claim}
            index={index}
            columns={CLAIM_COLUMNS}
            wide={wide}
            actions={(
              <Button
                label={open ? 'Review' : 'View'} variant={open ? 'primary' : 'secondary'} size="sm" icon="open-outline"
                accessibilityLabel={`${open ? 'Review' : 'View'} claim ${claim.claimNo}`}
                onPress={() => onOpen(claim.claimId)}
              />
            )}
          />
        );
      }}
    />
  );
}

function claimLines(claim: InsuranceClaim): { label: string; value: string }[] {
  return [
    { label: 'Claim', value: `${claim.claimNo} · ${claim.insuranceProvider}` },
    { label: 'Invoice', value: claim.invoiceNo ?? `#${claim.invoiceId}` },
    { label: 'Patient', value: personLabel(claim.patientName, claim.patientSystemId) },
    { label: 'Claimed', value: money(claim.claimAmount, claim.currency) },
  ];
}

/** The lines and the warning that describe what a decision does to the invoice. */
function settlementSummary(claim: InsuranceClaim, newApprovedAmount: number): { lines: { label: string; value: string }[]; warning: string } {
  const settlement = claimSettlement(claim, newApprovedAmount);
  const lines = [...claimLines(claim)];
  if (claim.approvedAmount != null) lines.push({ label: 'Approved before', value: money(claim.approvedAmount, claim.currency) });
  lines.push({ label: 'Approved in total', value: money(newApprovedAmount, claim.currency) });
  lines.push({ label: 'Insurance payment', value: settlement > 0 ? money(settlement, claim.currency) : 'None' });
  lines.push({ label: 'Invoice outstanding', value: money(claim.invoiceOutstanding, claim.currency) });

  let warning: string;
  if (settlement === 0) {
    warning = 'No payment is recorded: the amount approved before stands and the appeal is closed.';
  } else if (toCents(settlement) > toCents(claim.invoiceOutstanding)) {
    warning = `The invoice has only ${money(claim.invoiceOutstanding, claim.currency)} outstanding, so this approval will be refused. Approve a lower amount instead.`;
  } else {
    warning = `An insurance payment of ${money(settlement, claim.currency)} is put on the invoice straight away. An approval cannot be undone or refunded.`;
  }
  return { lines, warning };
}

function ApproveClaimDialog({ claim, onClose, onDone, onConflict }: {
  claim:      InsuranceClaim;
  onClose:    () => void;
  onDone:     (updated: InsuranceClaim, message: string) => void;
  onConflict: () => void;
}) {
  const action = useAdjudicateClaim();
  const summary = settlementSummary(claim, claim.claimAmount);
  const confirm = async () => {
    const result = await action.run(claim.claimId, { status: 'APPROVED' });
    if (result.ok) onDone(result.data, `Claim ${result.data.claimNo} was approved in full.`);
    else if (result.error?.status === 409) onConflict();
  };
  return (
    <Modal
      open
      title="Approve claim in full"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={<FormActions submitLabel="Approve in full" onSubmit={confirm} onCancel={onClose} submitting={action.pending} />}
    >
      <ConfirmSummary
        intro="You are about to approve the whole claimed amount."
        lines={summary.lines}
        warning={summary.warning}
        error={action.error}
      />
    </Modal>
  );
}

function PartialApprovalDialog({ claim, onClose, onDone, onConflict }: {
  claim:      InsuranceClaim;
  onClose:    () => void;
  onDone:     (updated: InsuranceClaim, message: string) => void;
  onConflict: () => void;
}) {
  const action = useAdjudicateClaim();
  const step = useReviewStep<PartialApprovalValues>();
  const [formError, setFormError] = useState<string | null>(null);
  const { claimAmount, approvedAmount, currency } = claim;
  const resolver = useMemo(
    () => zodResolver(partialApprovalSchema({ claimAmount, approvedAmount }, amount => money(amount, currency))),
    [claimAmount, approvedAmount, currency],
  );
  const { control, handleSubmit, setError } = useForm<PartialApprovalValues>({
    resolver,
    // An appealed claim that was partly approved starts from that amount: sending it unchanged dismisses the appeal.
    defaultValues: { approvedAmount: amountText(approvedAmount), note: '' },
  });

  const review = handleSubmit(values => { setFormError(null); action.reset(); step.enter(values); });
  const confirm = async () => {
    const values = step.review;
    const amount = values ? parseMoney(values.approvedAmount) : null;
    if (!values || amount === null || !step.ready()) return;
    const result = await action.run(claim.claimId, {
      status: 'PARTIAL', approvedAmount: amount, rejectionReason: values.note.trim() || null,
    });
    if (result.ok) {
      onDone(result.data, `Claim ${result.data.claimNo} was partly approved for ${money(result.data.approvedAmount, result.data.currency)}.`);
      return;
    }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      setFormError(applyFieldErrors(result.error, setError, { approvedAmount: 'approvedAmount', rejectionReason: 'note' }));
      action.reset();
      step.back();
    } else if (result.error.status === 409) {
      onConflict();
    }
  };

  const reviewed = step.review;
  const summary = reviewed ? settlementSummary(claim, parseMoney(reviewed.approvedAmount) ?? 0) : null;

  return (
    <Modal
      open
      title="Approve claim partly"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={reviewed ? (
        <FormActions submitLabel="Approve partly" onSubmit={confirm} onCancel={() => { action.reset(); step.back(); }} cancelLabel="Back" submitting={action.pending} />
      ) : (
        <FormActions submitLabel="Review" onSubmit={review} onCancel={onClose} />
      )}
    >
      {reviewed && summary ? (
        <ConfirmSummary
          intro="You are about to approve part of this claim. The patient owes the rest."
          lines={[...summary.lines, { label: 'Note to the patient', value: reviewed.note.trim() || 'None' }]}
          warning={summary.warning}
          error={action.error}
        />
      ) : (
        <>
          <View>
            {claimLines(claim).map(line => <InfoRow key={line.label} label={line.label} value={line.value} />)}
            {approvedAmount != null ? <InfoRow label="Approved before" value={money(approvedAmount, currency)} /> : null}
            <InfoRow label="Invoice outstanding" value={money(claim.invoiceOutstanding, currency)} />
          </View>
          <TextField
            label={`Approved amount (${currency})`}
            control={control}
            name="approvedAmount"
            inputMode="decimal"
            maxLength={13}
            placeholder="0.00"
            required
            hint={approvedAmount != null
              ? `The total approved for this claim: at least the ${money(approvedAmount, currency)} approved before and below the claimed ${money(claimAmount, currency)}. Keep the same amount to close the appeal without a further payment.`
              : `The total approved for this claim, below the claimed ${money(claimAmount, currency)}.`}
          />
          <TextField
            label="Note to the patient"
            control={control}
            name="note"
            multiline
            rows={3}
            maxLength={CLAIM_REASON_MAX}
            showCount
            hint="Optional. Explains why only part of the claim was approved."
          />
          <FormError message={formError} />
        </>
      )}
    </Modal>
  );
}

function RejectClaimDialog({ claim, onClose, onDone, onConflict }: {
  claim:      InsuranceClaim;
  onClose:    () => void;
  onDone:     (updated: InsuranceClaim, message: string) => void;
  onConflict: () => void;
}) {
  const action = useAdjudicateClaim();
  const step = useReviewStep<RejectClaimValues>();
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, setError } = useForm<RejectClaimValues>({
    resolver: zodResolver(rejectClaimSchema),
    defaultValues: { rejectionReason: '' },
  });

  const review = handleSubmit(values => { setFormError(null); action.reset(); step.enter(values); });
  const confirm = async () => {
    if (!step.review || !step.ready()) return;
    const result = await action.run(claim.claimId, { status: 'REJECTED', rejectionReason: step.review.rejectionReason });
    if (result.ok) { onDone(result.data, `Claim ${result.data.claimNo} was rejected.`); return; }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      setFormError(applyFieldErrors(result.error, setError, ['rejectionReason']));
      action.reset();
      step.back();
    } else if (result.error.status === 409) {
      onConflict();
    }
  };

  return (
    <Modal
      open
      title="Reject claim"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={action.pending}
      footer={step.review ? (
        <FormActions submitLabel="Reject claim" destructive onSubmit={confirm} onCancel={() => { action.reset(); step.back(); }} cancelLabel="Back" submitting={action.pending} />
      ) : (
        <FormActions submitLabel="Review" onSubmit={review} onCancel={onClose} />
      )}
    >
      {step.review ? (
        <ConfirmSummary
          intro="You are about to reject this claim. Nothing is paid onto the invoice."
          lines={[...claimLines(claim), { label: 'Reason', value: step.review.rejectionReason }]}
          warning="The patient is told the reason and can appeal the decision."
          error={action.error}
        />
      ) : (
        <>
          <View>
            {claimLines(claim).map(line => <InfoRow key={line.label} label={line.label} value={line.value} />)}
          </View>
          <TextField
            label="Reason"
            control={control}
            name="rejectionReason"
            multiline
            rows={4}
            maxLength={CLAIM_REASON_MAX}
            showCount
            required
            hint="Shown to the patient with the decision."
          />
          <FormError message={formError} />
        </>
      )}
    </Modal>
  );
}

type ClaimDecision = 'approve' | 'partial' | 'reject';

function ClaimPanel({ claimId, reload, notice, onDismissNotice, onClose, onChanged, onRefresh, onOpenInvoice }: {
  claimId:         number;
  reload:          number;
  notice:          string | null;
  onDismissNotice: () => void;
  onClose:         () => void;
  /** A step succeeded: show the message and reload the lists. */
  onChanged:       (message: string) => void;
  /** The server refused a step because something changed: reload what is on screen. */
  onRefresh:       () => void;
  onOpenInvoice:   (invoiceId: number) => void;
}) {
  const claim = useAdminClaim(claimId, reload);
  const move = useAdjudicateClaim();
  const files = useClaimDocumentDownload();
  const [decision, setDecision] = useState<ClaimDecision | null>(null);

  const data = claim.data;
  const can = data ? claimActions(data) : null;

  const startReview = async () => {
    if (!data) return;
    const result = await move.run(data.claimId, { status: 'PENDING' });
    if (result.ok) {
      claim.setData(result.data);
      onChanged(`Claim ${result.data.claimNo} is now under review.`);
    } else if (result.error?.status === 409) {
      onRefresh();
    }
  };
  const decided = (updated: InsuranceClaim, message: string) => {
    setDecision(null);
    claim.setData(updated);
    onChanged(message);
  };

  let body: ReactNode;
  if (claim.loading) {
    body = <LoadingView label="Loading claim…" />;
  } else if (!data) {
    if (claim.errorStatus === 404) {
      body = (
        <EmptyView
          icon="shield-checkmark-outline"
          title="This claim is not available"
          sub="It may have been removed, or the link is out of date."
        />
      );
    } else if (claim.errorStatus === 403) {
      body = <NoAccessView />;
    } else {
      body = <ErrorView message={claim.error ?? 'The claim could not be loaded.'} onRetry={claim.refetch} />;
    }
  } else {
    const c = data.currency;
    const titles = new Map(data.documents.map(d => [d.documentId, d.title]));
    body = (
      <>
        {notice ? <Banner tone="success" text={notice} onDismiss={onDismissNotice} /> : null}
        {claim.error ? <Banner tone="danger" text={claim.error} action={{ label: 'Retry', onPress: claim.refetch }} /> : null}
        {move.error ? <Banner tone="danger" text={move.error} onDismiss={move.reset} /> : null}
        {can?.approve && data.approvedAmount != null ? (
          <Banner
            tone="info"
            text={`${money(data.approvedAmount, c)} of this claim is already approved and paid onto the invoice, so it can no longer be rejected. To turn the appeal down, approve partly with the same amount.`}
          />
        ) : null}
        {can?.approve && data.approvedAmount == null && toCents(data.invoiceOutstanding) === 0 ? (
          <Banner tone="warning" text="The invoice has nothing outstanding, so this claim can only be rejected." />
        ) : null}

        <View>
          <InfoRow label="Status">
            <StatusBadge label={claimStatusLabel(data.status)} tone={statusTone('claim', data.status)} />
          </InfoRow>
          <InfoRow label="Patient" value={personLabel(data.patientName, data.patientSystemId)} />
          <InfoRow label="Invoice">
            <Button
              label={data.invoiceNo ?? `Invoice #${data.invoiceId}`} variant="ghost" size="sm" icon="open-outline"
              accessibilityLabel={`${data.invoiceNo ?? `Invoice #${data.invoiceId}`}, open invoice`}
              onPress={() => onOpenInvoice(data.invoiceId)}
            />
          </InfoRow>
          <InfoRow label="Invoice outstanding" value={money(data.invoiceOutstanding, c)} />
          <InfoRow label="Claimed" value={money(data.claimAmount, c)} />
          <InfoRow label="Approved" value={data.approvedAmount != null ? money(data.approvedAmount, c) : null} emptyText="Nothing approved" />
          {data.paymentId != null ? <InfoRow label="Insurance payment" value={`Payment #${data.paymentId}`} /> : null}
        </View>

        <PanelHeading>Insurance</PanelHeading>
        <View>
          <InfoRow label="Insurer" value={data.insuranceProvider} />
          <InfoRow label="Policy number" value={data.policyNumber} />
          <InfoRow label="Member number" value={data.memberNumber} />
          <InfoRow label="Claim reference" value={data.providerReference} emptyText={DASH} />
        </View>

        <PanelHeading>Review</PanelHeading>
        <View>
          <InfoRow label="Submitted" value={`${formatClinicDateTime(data.submittedAt, DASH)} by ${data.submittedBySystemId}`} />
          <InfoRow label="Reviewed by" value={data.reviewedBySystemId} emptyText="Not reviewed yet" />
          <InfoRow label="Decided" value={formatClinicDateTime(data.resolvedAt)} emptyText="Waiting for a decision" />
          {data.rejectionReason ? (
            <InfoRow label={data.status === 'REJECTED' ? 'Rejection reason' : 'Reviewer note'} value={data.rejectionReason} />
          ) : null}
          {data.appealReason ? <InfoRow label="Appeal reason" value={data.appealReason} /> : null}
        </View>

        <PanelHeading>Documents</PanelHeading>
        {files.error ? <Banner tone="danger" text={files.error} onDismiss={files.reset} /> : null}
        {data.documentIds.length === 0 ? (
          <Secondary>No documents were attached to this claim.</Secondary>
        ) : (
          <View role="list" aria-label="Documents attached to this claim" style={styles.items}>
            {data.documentIds.map(documentId => {
              const title = titles.get(documentId);
              return (
                <View key={documentId} role="listitem" style={styles.itemRow}>
                  <View style={styles.itemText}>
                    <Primary>{title ?? 'Document no longer available'}</Primary>
                    <Secondary>{`Document #${documentId}`}</Secondary>
                  </View>
                  {title !== undefined ? (
                    <Button
                      label="Download" variant="secondary" size="sm" icon="download-outline"
                      accessibilityLabel={`Download ${title}, document ${documentId}`}
                      loading={files.downloadingId === documentId}
                      disabled={files.downloadingId !== null && files.downloadingId !== documentId}
                      onPress={() => files.download(documentId)}
                    />
                  ) : null}
                </View>
              );
            })}
          </View>
        )}
      </>
    );
  }

  const hasActions = !!can && (can.startReview || can.reopenReview || can.approve);

  return (
    <>
      <SidePanel
        open
        size="lg"
        title={data ? `Claim ${data.claimNo}` : 'Insurance claim'}
        onClose={onClose}
        busy={move.pending}
        footer={data && can && hasActions ? (
          <View style={styles.panelActions}>
            {can.startReview ? <Button label="Start review" icon="play-outline" onPress={startReview} loading={move.pending} /> : null}
            {can.reopenReview ? <Button label="Reopen review" icon="refresh-outline" onPress={startReview} loading={move.pending} /> : null}
            {can.reject ? <Button label="Reject" variant="danger" icon="close-circle-outline" onPress={() => setDecision('reject')} /> : null}
            {can.approvePartly ? <Button label="Approve partly" variant="secondary" icon="remove-circle-outline" onPress={() => setDecision('partial')} /> : null}
            {can.approve ? <Button label="Approve in full" icon="checkmark-circle-outline" onPress={() => setDecision('approve')} /> : null}
          </View>
        ) : undefined}
      >
        {body}
      </SidePanel>
      {data && decision === 'approve' ? (
        <ApproveClaimDialog claim={data} onClose={() => setDecision(null)} onDone={decided} onConflict={onRefresh} />
      ) : null}
      {data && decision === 'partial' ? (
        <PartialApprovalDialog claim={data} onClose={() => setDecision(null)} onDone={decided} onConflict={onRefresh} />
      ) : null}
      {data && decision === 'reject' ? (
        <RejectClaimDialog claim={data} onClose={() => setDecision(null)} onDone={decided} onConflict={onRefresh} />
      ) : null}
    </>
  );
}

// ─── Default fees ─────────────────────────────────────────────────────────────

/** The rows in the fixed order VIDEO, IN_PERSON, PHONE. */
function orderedFees(fees: Fee[] | null): Fee[] {
  const rows: Fee[] = [];
  for (const type of APPOINTMENT_TYPES) {
    const row = fees?.find(f => f.appointmentType === type);
    if (row) rows.push(row);
  }
  return rows;
}

/** The text each fee field starts with: the stored default of its appointment type. */
function feeValues(rows: Fee[]): DefaultFeesValues {
  const values: DefaultFeesValues = { VIDEO: '', IN_PERSON: '', PHONE: '' };
  for (const row of rows) values[row.appointmentType] = amountText(row.amount);
  return values;
}

/** The fees whose typed amount differs from the stored default. */
function feeChanges(rows: Fee[], values: DefaultFeesValues): DefaultFeeInput[] {
  const changes: DefaultFeeInput[] = [];
  for (const row of rows) {
    const amount = parseMoney(values[row.appointmentType], 6);
    if (amount !== null && toCents(amount) !== toCents(row.amount)) {
      changes.push({ appointmentType: row.appointmentType, amount });
    }
  }
  return changes;
}

/** True when a field no longer shows its stored default: a different amount, or text that is not an amount. */
function feesEdited(rows: Fee[], values: DefaultFeesValues): boolean {
  return rows.some(row => {
    const amount = parseMoney(values[row.appointmentType], 6);
    return amount === null || toCents(amount) !== toCents(row.amount);
  });
}

/** The fee form for one loaded schedule. It is remounted when the stored fees change, so it always starts from them. */
function FeesEditor({ rows, loadError, onRetry, onSaved }: {
  rows:      Fee[];
  /** A reload that failed while the fees were on screen. */
  loadError: string | null;
  onRetry:   () => void;
  onSaved:   (fees: Fee[]) => void;
}) {
  const save = useSaveDefaultFees();
  const step = useReviewStep<DefaultFeeInput[]>();
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError, watch } = useForm<DefaultFeesValues>({
    resolver: zodResolver(defaultFeesSchema),
    defaultValues: feeValues(rows),
  });

  const currency = rows[0]?.currency;
  const edited = feesEdited(rows, watch());
  const byType = new Map<AppointmentType, Fee>(rows.map(row => [row.appointmentType, row]));

  const review = handleSubmit(values => {
    const changes = feeChanges(rows, values);
    if (changes.length === 0) return;
    setFormError(null);
    save.reset();
    step.enter(changes);
  });
  const closeReview = () => { save.reset(); step.back(); };
  const confirm = async () => {
    const changes = step.review;
    if (!changes || !step.ready()) return;
    const result = await save.run(changes);
    if (result.ok) {
      step.back();
      onSaved(result.data);
      return;
    }
    if (!result.error) return;
    if (result.error.fieldErrors) {
      // The server names a field by its position in the request ("fees[1].amount").
      const fields: Partial<Record<string, AppointmentType>> = {};
      changes.forEach((change, index) => { fields[`fees[${index}].amount`] = change.appointmentType; });
      setFormError(applyFieldErrors(result.error, setError, fields));
      save.reset();
      step.back();
    }
  };

  return (
    <>
      <SectionCard
        title="Default consultation fees"
        subtitle={`Charged when a doctor has not set a fee of their own. Amounts are in ${currency}, the clinic's billing currency. A change applies to invoices created from now on.`}
      >
        {loadError ? <Banner tone="danger" text={loadError} action={{ label: 'Retry', onPress: onRetry }} /> : null}
        <View style={styles.feeFields}>
          {rows.map(row => (
            <View key={row.appointmentType} style={styles.feeField}>
              <TextField
                label={`${APPOINTMENT_TYPE_LABEL[row.appointmentType]} (${row.currency})`}
                control={control}
                name={row.appointmentType}
                inputMode="decimal"
                maxLength={9}
                placeholder="0.00"
                required
                hint={row.source === 'NONE'
                  ? 'No default is set yet, so nothing is charged. Use 0 for a free consultation.'
                  : `Current default: ${money(row.amount, row.currency)}. Use 0 for a free consultation.`}
              />
            </View>
          ))}
        </View>
        <FormError message={formError} />
        <FormActions
          submitLabel="Review changes"
          onSubmit={review}
          disabled={!edited}
          onCancel={edited ? () => { setFormError(null); reset(); } : undefined}
          cancelLabel="Discard changes"
        />
      </SectionCard>

      <Modal
        open={step.review !== null}
        title="Change default fees"
        onClose={closeReview}
        dismissOnBackdrop={false}
        busy={save.pending}
        footer={(
          <FormActions submitLabel="Save fees" onSubmit={confirm} onCancel={closeReview} cancelLabel="Back" submitting={save.pending} />
        )}
      >
        <ConfirmSummary
          intro="You are about to change what the clinic charges by default."
          lines={(step.review ?? []).map(change => {
            const before = byType.get(change.appointmentType);
            const was = !before || before.source === 'NONE' ? 'not set before' : `was ${money(before.amount, currency)}`;
            return { label: APPOINTMENT_TYPE_LABEL[change.appointmentType], value: `${money(change.amount, currency)} (${was})` };
          })}
          warning="Doctors without a fee of their own charge these amounts on new invoices. Invoices that already exist do not change."
          error={save.error}
        />
      </Modal>
    </>
  );
}

function FeesTab({ onSaved }: { onSaved: (message: string) => void }) {
  const fees = useDefaultFees();
  const rows = useMemo(() => orderedFees(fees.data), [fees.data]);

  if (fees.loading) return <LoadingView label="Loading default fees…" />;
  if (fees.error && rows.length === 0) {
    return fees.errorStatus === 403
      ? <NoAccessView />
      : <ErrorView message={fees.error} onRetry={fees.refetch} />;
  }
  if (rows.length === 0) {
    return (
      <EmptyView
        icon="pricetag-outline"
        title="The default fees are not available"
        sub="The server returned no fee schedule."
        action={{ label: 'Retry', onPress: fees.refetch, icon: 'refresh-outline' }}
      />
    );
  }

  return (
    <FeesEditor
      // A new schedule (after a save or a reload that brought other amounts) starts the form again from it.
      key={rows.map(row => `${row.appointmentType}:${row.amount}:${row.source}`).join('|')}
      rows={rows}
      loadError={fees.error}
      onRetry={fees.refetch}
      onSaved={saved => { fees.setData(saved); onSaved('The default fees were updated.'); }}
    />
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

export default function AdminBilling({ params: paramsProp, theme }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams } = nav;
  const params = paramsProp ?? nav.params;
  const { width } = useWindowDimensions();
  const wide = width >= TABLE_BREAKPOINT;

  const tab: TabKey = TAB_KEYS.find(key => key === params.tab) ?? 'invoices';
  const openId = positiveInt(params.id);
  const patient = patientParam(params.patient);

  const [reload, setReload] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [voiding, setVoiding] = useState<Invoice | null>(null);
  const [recording, setRecording] = useState<{ invoiceId: number | null } | null>(null);
  const [refunding, setRefunding] = useState<AdminPayment | null>(null);

  const openClaims = useOpenClaimsCount(reload);

  const refresh = useCallback(() => setReload(r => r + 1), []);
  const changed = useCallback((message: string) => { setNotice(message); setReload(r => r + 1); }, []);
  const dismissNotice = useCallback(() => setNotice(null), []);

  const selectTab = useCallback((key: TabKey) => {
    // Status and method values belong to one tab's records, and `id` to the record that was open there.
    setNotice(null);
    setParams({ tab: key === 'invoices' ? undefined : key, id: undefined, status: undefined, method: undefined });
  }, [setParams]);
  const openInvoice = useCallback((invoiceId: number) => {
    // With tab=claims the id is a claim, so an invoice opened from a claim moves to the Invoices tab.
    if (tab === 'claims') setParams({ tab: undefined, id: invoiceId, status: undefined, method: undefined });
    else setParams({ id: invoiceId });
  }, [setParams, tab]);
  const openClaim = useCallback((claimId: number) => {
    if (tab === 'claims') setParams({ id: claimId });
    else setParams({ tab: 'claims', id: claimId, status: undefined, method: undefined });
  }, [setParams, tab]);
  const closePanel = useCallback(() => setParams({ id: undefined }), [setParams]);

  const tabs: TabItem<TabKey>[] = [
    { key: 'invoices', label: TAB_LABEL.invoices },
    { key: 'payments', label: TAB_LABEL.payments },
    { key: 'claims', label: TAB_LABEL.claims, badge: openClaims.count },
    { key: 'fees', label: TAB_LABEL.fees },
  ];

  return (
    <Screen
      title="Billing"
      subtitle="Invoices, payments, insurance claims and the clinic's default fees"
      theme={theme ?? ROLE_THEME.admin}
      actions={tab !== 'fees' ? (
        <Button label="Refresh" variant="secondary" icon="refresh-outline" onPress={refresh} accessibilityLabel={`Refresh ${TAB_LABEL[tab].toLowerCase()}`} />
      ) : undefined}
    >
      {notice ? <Banner tone="success" text={notice} onDismiss={dismissNotice} /> : null}
      <Tabs<TabKey> tabs={tabs} active={tab} onChange={selectTab} label="Billing areas" />

      <View role="tabpanel" aria-label={TAB_LABEL[tab]} style={styles.tabPanel}>
        {tab === 'invoices' ? (
          <InvoicesTab statusParam={params.status} patient={patient} reload={reload} wide={wide} setParams={setParams} onOpen={openInvoice} />
        ) : null}
        {tab === 'payments' ? (
          <PaymentsTab
            statusParam={params.status}
            methodParam={params.method}
            patient={patient}
            reload={reload}
            wide={wide}
            setParams={setParams}
            onOpenInvoice={openInvoice}
            onRefund={setRefunding}
            onRecord={() => setRecording({ invoiceId: null })}
          />
        ) : null}
        {tab === 'claims' ? (
          <ClaimsTab statusParam={params.status} patient={patient} reload={reload} wide={wide} setParams={setParams} onOpen={openClaim} />
        ) : null}
        {tab === 'fees' ? <FeesTab onSaved={changed} /> : null}
      </View>

      {openId !== null && tab !== 'claims' ? (
        <InvoicePanel
          key={`invoice-${openId}`}
          invoiceId={openId}
          reload={reload}
          notice={notice}
          onDismissNotice={dismissNotice}
          onClose={closePanel}
          onVoid={setVoiding}
          onRecord={invoice => setRecording({ invoiceId: invoice.invoiceId })}
          onRefund={setRefunding}
          onOpenClaim={openClaim}
        />
      ) : null}
      {openId !== null && tab === 'claims' ? (
        <ClaimPanel
          key={`claim-${openId}`}
          claimId={openId}
          reload={reload}
          notice={notice}
          onDismissNotice={dismissNotice}
          onClose={closePanel}
          onChanged={changed}
          onRefresh={refresh}
          onOpenInvoice={openInvoice}
        />
      ) : null}

      {voiding ? (
        <VoidInvoiceDialog
          invoice={voiding}
          onClose={() => setVoiding(null)}
          onDone={message => { setVoiding(null); changed(message); }}
          onConflict={refresh}
        />
      ) : null}
      {recording ? (
        <RecordPaymentDialog
          presetInvoiceId={recording.invoiceId}
          defaultPatient={patient}
          onClose={() => setRecording(null)}
          onDone={message => { setRecording(null); changed(message); }}
          onConflict={refresh}
        />
      ) : null}
      {refunding ? (
        <RefundDialog
          payment={refunding}
          onClose={() => setRefunding(null)}
          onDone={message => { setRefunding(null); changed(message); }}
          onConflict={refresh}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  tabPanel: { gap: space.md, minWidth: 0 },
  tabAction: { flexDirection: 'row', justifyContent: 'flex-end' },

  filterBar:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filterField:   { flexGrow: 1, flexShrink: 1, flexBasis: 200, maxWidth: 320, minWidth: 0 },
  patientFilter: { flexGrow: 2, flexShrink: 1, flexBasis: 280, minWidth: 0, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.sm },
  // The label above the input is one line of text plus the field gap: the buttons start level with the input.
  filterButtons: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: 22 },

  rowWrap:  { gap: space.xs, minWidth: 0 },
  headRow:  { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg, paddingVertical: space.xs },
  headCell: { minWidth: 0, fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.4 },
  rowCard:  { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg, paddingVertical: space.md },
  cell:     { minWidth: 0, gap: 2 },
  actionsWide: { width: 210, flexShrink: 0, flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm },
  // Read by assistive technology, not drawn: the column heading of a cell on the wide layout.
  srOnly:   { position: 'absolute', width: 1, height: 1, overflow: 'hidden', opacity: 0 },

  stackCard:    { gap: space.sm },
  stackRow:     { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 2 },
  stackLabel:   { width: 132, maxWidth: '100%', fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 20 },
  stackValue:   { flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, gap: 2 },
  stackActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.xs },

  cellPrimary:   { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  cellSecondary: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  lineCard:   { gap: space.sm, padding: space.md },
  lineTop:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  lineTitles: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },

  panelHeading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, marginTop: space.sm },
  panelActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm },
  items:        { gap: space.sm },
  itemRow: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm,
    paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  itemText:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  itemAmount: { fontSize: fontSize.md, fontWeight: '600', color: colors.text },

  dialogText: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  confirm:    { gap: space.sm },

  feeFields: { flexDirection: 'row', flexWrap: 'wrap', gap: space.lg },
  feeField:  { flexGrow: 1, flexShrink: 1, flexBasis: 220, maxWidth: 360, minWidth: 0 },
});
