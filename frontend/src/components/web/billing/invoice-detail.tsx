import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, colors, EmptyView, ErrorView, fontSize, InfoRow, LoadingView, NoAccessView, SectionCard, space,
  StatusBadge, statusTone,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useInsuranceClaims } from '@/hooks/use-insurance-claims';
import { useInvoice } from '@/hooks/use-invoice';
import { useInvoicePayments } from '@/hooks/use-payments';
import {
  claimStatusLabel, claimStatusMeaning, formatBillingDate, formatBillingDateTime, formatBillingDay, invoiceStatusLabel,
  isOpenClaim, isOpenInvoice, isPendingPayment, paymentMethodSummary, paymentReference, paymentStatusLabel,
} from '@/lib/billing/format';
import { canPrintReceipt, printInvoiceReceipt, receiptActionLabel } from '@/lib/billing/receipt';
import type { BillingViewer, InsuranceClaim, Invoice, Payment } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import { AmountRow, InlinePagedList } from './parts';

/** What the `actions` and `claimActions` render props receive. */
export interface InvoiceDetailContext {
  invoice: Invoice;
  viewer:  BillingViewer;
  /** The invoice's payments loaded so far, newest first (failed attempts included). */
  payments: Payment[];
  /** True when a loaded payment is still waiting for the gateway; its amount is reserved on the invoice. */
  hasPendingPayment: boolean;
  /** The invoice's claims loaded so far, newest first. Always empty for a doctor, who has no access to claims. */
  claims:   InsuranceClaim[];
  /**
   * True once it is known whether the invoice has an open claim (the claims were read in full). Offer
   * "Submit claim" only when this is true and `openClaim` is null.
   */
  claimsKnown: boolean;
  /** The claim that is still being decided (submitted, under review or appealed), if there is one. */
  openClaim:   InsuranceClaim | null;
  /** Loads the invoice, its payments and its claims again. Call it after a payment, a claim or an appeal. */
  refresh:  () => void;
}

export interface InvoiceDetailProps {
  invoiceId: number;
  /**
   * The patient the screen is showing: their own id for a patient, the loved one's for a caregiver. An invoice
   * that belongs to someone else is reported as not available. Not needed for a doctor.
   */
  patientSystemId?: string | null;
  /** Who is looking. Default `patient`. A doctor sees no claims section (claims are refused to that role). */
  viewer?: BillingViewer;
  /**
   * Buttons for this invoice, shown beside the print control: Pay and Submit claim for a patient or caregiver,
   * Edit, Send and Cancel for the issuing doctor. Return null to show none.
   */
  actions?: (context: InvoiceDetailContext) => ReactNode;
  /** Buttons for one claim of the invoice (Appeal). Return null to show none. */
  claimActions?: (claim: InsuranceClaim, context: InvoiceDetailContext) => ReactNode;
  /** Shows a Back button that calls it (back to the invoice list). */
  onBack?:    () => void;
  backLabel?: string;
  /** Makes the linked appointment / consultation a button that calls it. */
  onOpenAppointment?:  (appointmentId: number) => void;
  onOpenConsultation?: (consultationId: number) => void;
  /** Called when the server refuses the invoice with 403 (a caregiver grant that no longer allows billing). */
  onForbidden?: () => void;
  /** Change the value to make the detail load again. */
  refreshKey?: string | number;
}

const NOT_AVAILABLE = 'This invoice is not available';

/** Every claim of one invoice fits in a single page of this size in practice, so the open claim is always in it. */
const CLAIMS_PAGE_SIZE = 100;

/** Payments that an administrator records (cash, bank transfer, an insurance settlement) rather than the payer starting them. */
const OFFLINE_METHODS: ReadonlySet<string> = new Set(['CASH', 'BANK_TRANSFER', 'INSURANCE']);

function PaymentRow({ payment }: { payment: Payment }) {
  const when = formatBillingDateTime(payment.paidAt ?? payment.createdAt, 'Date not recorded');
  const reference = paymentReference(payment);
  const startedBy = payment.initiatedByName?.trim();
  return (
    <View style={styles.entry}>
      <View style={styles.entryHead}>
        <View style={styles.entryTitles}>
          <Text style={styles.entryTitle}>{paymentMethodSummary(payment)}</Text>
          <Text style={styles.meta}>{when}</Text>
        </View>
        <View style={styles.entrySide}>
          <Text style={styles.entryAmount}>{formatMoney(payment.amount, payment.currency)}</Text>
          <StatusBadge label={paymentStatusLabel(payment.status)} tone={statusTone('payment', payment.status)} />
        </View>
      </View>
      {isPendingPayment(payment) ? (
        <Text style={styles.meta}>Waiting for confirmation. The invoice is updated when the payment is confirmed.</Text>
      ) : null}
      {payment.status === 'FAILED' && payment.failureReason ? (
        <Text style={styles.meta}>Reason: {payment.failureReason}</Text>
      ) : null}
      {payment.refundedAmount > 0 ? (
        <Text style={styles.meta}>
          Refunded {formatMoney(payment.refundedAmount, payment.currency)}
          {payment.refundReason ? `. Reason: ${payment.refundReason}` : ''}
        </Text>
      ) : null}
      {reference ? <Text style={styles.meta} selectable>Reference: {reference}</Text> : null}
      {payment.payerPhone ? <Text style={styles.meta}>Phone: {payment.payerPhone}</Text> : null}
      {startedBy ? <Text style={styles.meta}>{OFFLINE_METHODS.has(payment.method) ? 'Recorded by' : 'Started by'} {startedBy}</Text> : null}
    </View>
  );
}

function ClaimRow({ claim, actions }: { claim: InsuranceClaim; actions?: ReactNode }) {
  const approved = claim.approvedAmount !== null ? formatMoney(claim.approvedAmount, claim.currency) : null;
  const meaning = claimStatusMeaning(claim);
  return (
    <View style={styles.entry}>
      <View style={styles.entryHead}>
        <View style={styles.entryTitles}>
          <Text style={styles.entryTitle}>{claim.insuranceProvider}</Text>
          <Text style={styles.meta} selectable>Claim {claim.claimNo}</Text>
        </View>
        <View style={styles.entrySide}>
          <Text style={styles.entryAmount}>{formatMoney(claim.claimAmount, claim.currency)}</Text>
          <StatusBadge label={claimStatusLabel(claim.status)} tone={statusTone('claim', claim.status)} />
        </View>
      </View>
      {meaning ? <Text style={styles.body}>{meaning}</Text> : null}
      <Text style={styles.meta}>
        Submitted {formatBillingDateTime(claim.submittedAt, 'date not recorded')}
        {claim.resolvedAt ? ` · Decided ${formatBillingDateTime(claim.resolvedAt)}` : ''}
      </Text>
      <Text style={styles.meta} selectable>
        Policy {claim.policyNumber}{claim.memberNumber ? ` · Member ${claim.memberNumber}` : ''}
      </Text>
      {approved && claim.status !== 'PARTIAL' ? <Text style={styles.meta}>Approved amount: {approved}</Text> : null}
      {claim.rejectionReason ? (
        <Text style={styles.meta}>
          {claim.status === 'PARTIAL' ? "Reviewer's note" : 'Reason for the decision'}: {claim.rejectionReason}
        </Text>
      ) : null}
      {claim.appealReason ? <Text style={styles.meta}>Appeal: {claim.appealReason}</Text> : null}
      {claim.documents.length > 0 ? (
        <Text style={styles.meta}>Attached: {claim.documents.map(d => d.title).join(', ')}</Text>
      ) : null}
      {actions ? <View style={styles.entryActions}>{actions}</View> : null}
    </View>
  );
}

/**
 * One invoice in full: who billed whom, the line items and totals, the linked visit, the payment history, the
 * insurance claims and a control that prints the invoice (or the receipt once it is paid). Renders its own
 * loading, failed (with Retry), not-available and no-access states and fits a phone-width window.
 *
 * The buttons that act on the invoice are supplied by the screen through `actions` and `claimActions`, so the
 * same component serves the patient, the caregiver and the doctor.
 */
export function InvoiceDetail({
  invoiceId, patientSystemId, viewer = 'patient', actions, claimActions, onBack, backLabel = 'Back to invoices',
  onOpenAppointment, onOpenConsultation, onForbidden, refreshKey,
}: InvoiceDetailProps) {
  const seesClaims = viewer !== 'doctor';
  const expectedPatient = seesClaims ? patientSystemId?.trim() || null : null;

  const invoiceState = useInvoice(invoiceId);
  const invoice = invoiceState.data;
  // An invoice of another patient than the one on screen is not shown under this patient's name.
  const wrongPatient = !!invoice && expectedPatient !== null && invoice.patientSystemId !== expectedPatient;
  // A refusal also ends an invoice that was already on screen (a reload after the grant changed or the invoice
  // was withdrawn): the copy that was loaded earlier is not shown any longer.
  const refused = invoiceState.errorStatus === 403;
  // An id that cannot be an invoice id (a mistyped link) is never requested and is reported like a missing invoice.
  const validId = Number.isInteger(invoiceId) && invoiceId > 0;
  const gone = !validId || invoiceState.errorStatus === 404 || wrongPatient;
  const shown = invoice && !refused && !gone ? invoice : null;

  const payments = useInvoicePayments(invoiceId, { enabled: shown !== null });
  const claims = useInsuranceClaims(shown?.patientSystemId, {
    invoiceId,
    pageSize: CLAIMS_PAGE_SIZE,
    enabled:  shown !== null && seesClaims,
  });

  const refetchInvoice = invoiceState.refetch;
  const refetchPayments = payments.refetch;
  const refetchClaims = claims.refetch;
  const refresh = useCallback(() => {
    refetchInvoice();
    refetchPayments();
    refetchClaims();
  }, [refetchInvoice, refetchPayments, refetchClaims]);

  // Reload when the caller says something changed. The first render already loads.
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refresh();
  }, [refreshKey, refresh]);

  const [printError, setPrintError] = useState<string | null>(null);
  const print = useAction(
    (target: Invoice) => printInvoiceReceipt(target),
    { errorMessage: 'The document could not be opened for printing. Please try again.' },
  );

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = invoiceState.errorStatus === 403 || payments.errorStatus === 403
    || claims.errorStatus === 403 || print.failure?.status === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  // A different invoice starts with a clean print message.
  useEffect(() => { setPrintError(null); }, [invoiceId]);

  const back = onBack ? (
    <Button label={backLabel} icon="arrow-back" variant="ghost" size="sm" onPress={onBack} />
  ) : null;

  if (invoiceState.loading) {
    return <View style={styles.wrap}>{back}<LoadingView label="Loading invoice" /></View>;
  }
  if (!shown) {
    if (refused) {
      return <View style={styles.wrap}>{back}<NoAccessView message="You don't have access to this invoice." /></View>;
    }
    if (gone) {
      return (
        <View style={styles.wrap}>
          {back}
          <EmptyView
            icon="document-outline"
            title={NOT_AVAILABLE}
            sub="It may have been withdrawn, or it has not been issued yet."
          />
        </View>
      );
    }
    return (
      <View style={styles.wrap}>
        {back}
        <ErrorView message={invoiceState.error ?? 'We could not load this invoice. Please try again.'} onRetry={refetchInvoice} />
      </View>
    );
  }

  // Every claim of the invoice is on the loaded page(s) unless the server still has more.
  const claimsKnown = seesClaims && !claims.loading && claims.error === null && !claims.hasMore;
  const openClaim = claims.items.find(isOpenClaim) ?? null;
  const context: InvoiceDetailContext = {
    invoice: shown,
    viewer,
    payments: payments.items,
    hasPendingPayment: payments.items.some(isPendingPayment),
    claims: seesClaims ? claims.items : [],
    claimsKnown: claimsKnown || openClaim !== null,
    openClaim,
    refresh,
  };

  const money = (amount: number) => formatMoney(amount, shown.currency);
  const status = invoiceStatusLabel(shown.status);
  const open = isOpenInvoice(shown);
  const draft = shown.status === 'DRAFT';
  const billedTo = shown.patientName?.trim()
    ? `${shown.patientName.trim()} (${shown.patientSystemId})`
    : shown.patientSystemId;
  const extraActions = actions ? actions(context) : null;

  const runPrint = async () => {
    setPrintError(null);
    const result = await print.run(shown);
    if (result.ok || !result.error) return;
    if (result.error.status === 404) {
      setPrintError(`${NOT_AVAILABLE}.`);
      refetchInvoice();
    } else if (result.error.status !== 403) {
      setPrintError(result.error.message);
    }
  };

  return (
    <View style={styles.wrap}>
      {back}

      <View style={styles.header}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>Invoice {shown.invoiceNo}</Text>
          <StatusBadge label={status} tone={statusTone('invoice', shown.status)} />
        </View>
        <View style={styles.actions}>
          {extraActions}
          {canPrintReceipt() ? (
            <Button
              label={receiptActionLabel(shown.status)}
              icon="print-outline"
              variant="secondary"
              onPress={runPrint}
              loading={print.pending}
              accessibilityLabel={`${receiptActionLabel(shown.status)} ${shown.invoiceNo}`}
            />
          ) : null}
        </View>
      </View>

      {invoiceState.error ? (
        <Banner tone="danger" text={invoiceState.error} action={{ label: 'Retry', onPress: refetchInvoice }} />
      ) : null}
      {printError ? <Banner tone="danger" text={printError} onDismiss={() => setPrintError(null)} /> : null}
      {shown.status === 'OVERDUE' ? (
        <Banner tone="warning" text={`This invoice is overdue. ${money(shown.outstanding)} is still owed.`} />
      ) : null}
      {draft ? (
        <Banner tone="info" text="This invoice is a draft. The patient cannot see it until it is sent." />
      ) : null}
      {shown.status === 'CANCELLED' ? (
        <Banner tone="info" text={shown.cancelReason ? `This invoice was cancelled. Reason: ${shown.cancelReason}` : 'This invoice was cancelled. Nothing is owed.'} />
      ) : null}
      {shown.status === 'REFUNDED' ? (
        <Banner tone="info" text="The payments for this invoice were refunded. Nothing is owed." />
      ) : null}

      <SectionCard title="Summary">
        <View>
          <InfoRow label="Billed to" value={billedTo} />
          <InfoRow label="Issued by" value={shown.doctorName?.trim() ? `Dr. ${shown.doctorName.trim()}` : null} emptyText="Not recorded" />
          <InfoRow label="Issued on" value={shown.issuedAt ? formatBillingDay(shown.issuedAt) : null} emptyText="Not issued yet" />
          {/* A draft's due date is set again when it is sent, so it is not shown as a commitment yet. */}
          {shown.dueDate && !draft ? <InfoRow label="Due date" value={formatBillingDate(shown.dueDate)} /> : null}
          {shown.paidAt ? <InfoRow label="Paid on" value={formatBillingDay(shown.paidAt)} /> : null}
          {shown.appointmentId !== null || shown.consultationId !== null ? (
            <InfoRow label="Linked visit">
              <View style={styles.links}>
                {shown.consultationId !== null ? (
                  onOpenConsultation ? (
                    <Button
                      label="View consultation"
                      icon="medical-outline"
                      variant="ghost"
                      size="sm"
                      onPress={() => onOpenConsultation(shown.consultationId as number)}
                      accessibilityLabel={`View consultation for invoice ${shown.invoiceNo}`}
                    />
                  ) : <Text style={styles.body}>Consultation #{shown.consultationId}</Text>
                ) : null}
                {shown.appointmentId !== null ? (
                  onOpenAppointment ? (
                    <Button
                      label="View appointment"
                      icon="calendar-outline"
                      variant="ghost"
                      size="sm"
                      onPress={() => onOpenAppointment(shown.appointmentId as number)}
                      accessibilityLabel={`View appointment for invoice ${shown.invoiceNo}`}
                    />
                  ) : <Text style={styles.body}>Appointment #{shown.appointmentId}</Text>
                ) : null}
              </View>
            </InfoRow>
          ) : null}
          {shown.notes?.trim() ? <InfoRow label="Note" value={shown.notes.trim()} /> : null}
        </View>
      </SectionCard>

      <SectionCard title="Items">
        {shown.lineItems.length === 0 ? (
          <Text style={styles.body}>No items</Text>
        ) : (
          <View role="list" aria-label="Invoice items">
            {shown.lineItems.map((item, index) => (
              <View key={index} role="listitem" style={[styles.item, index > 0 && styles.divider]}>
                <View style={styles.itemText}>
                  <Text style={styles.body}>{item.description}</Text>
                  <Text style={styles.meta}>{item.quantity} × {money(item.unitPrice)}</Text>
                </View>
                <Text style={styles.itemAmount}>{money(item.amount)}</Text>
              </View>
            ))}
          </View>
        )}
        <View style={styles.totals}>
          <AmountRow label="Subtotal" value={money(shown.subtotal)} />
          {shown.discount > 0 ? <AmountRow label="Discount" value={money(-shown.discount)} /> : null}
          <AmountRow label="Tax" value={money(shown.tax)} />
          <AmountRow label="Total" value={money(shown.total)} strong />
          {draft ? null : (
            <>
              <AmountRow label="Amount paid" value={money(shown.amountPaid)} />
              <AmountRow
                label="Balance due"
                value={money(shown.outstanding)}
                strong
                tone={shown.status === 'OVERDUE' ? 'danger' : undefined}
              />
            </>
          )}
        </View>
      </SectionCard>

      <SectionCard title="Payments">
        <InlinePagedList
          state={payments}
          keyOf={payment => payment.paymentId}
          label="Payments"
          emptyText={open ? 'No payments have been made for this invoice yet.' : 'No payments were recorded for this invoice.'}
          noAccessMessage="You don't have access to the payments of this invoice."
          renderItem={payment => <PaymentRow payment={payment} />}
        />
      </SectionCard>

      {seesClaims ? (
        <SectionCard title="Insurance claims">
          <InlinePagedList
            state={claims}
            keyOf={claim => claim.claimId}
            label="Insurance claims"
            emptyText="No insurance claim has been submitted for this invoice."
            noAccessMessage="You don't have access to the insurance claims of this invoice."
            renderItem={claim => (
              <ClaimRow claim={claim} actions={claimActions ? claimActions(claim, context) : null} />
            )}
          />
        </SectionCard>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.lg, minWidth: 0 },

  header:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.md },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  title:   { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
  links:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },

  body: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  meta: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  item:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', columnGap: space.lg, rowGap: 2, paddingVertical: space.sm },
  divider:    { borderTopWidth: 1, borderTopColor: colors.border },
  itemText:   { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  itemAmount: { fontSize: fontSize.md, fontWeight: '600', color: colors.text, marginLeft: 'auto' },
  totals:     { gap: space.xs, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },

  entry:        { gap: space.xs, minWidth: 0 },
  entryHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', columnGap: space.lg, rowGap: space.xs },
  entryTitles:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  entryTitle:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  entrySide:    { gap: space.xs, minWidth: 0 },
  entryAmount:  { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  entryActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, paddingTop: space.xs },
});
