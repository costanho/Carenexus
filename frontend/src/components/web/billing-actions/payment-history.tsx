import { useEffect, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Button, colors, fontSize, PagedList, radius, space, StatusBadge, statusTone } from '@/components/web/ui';
import { usePatientPayments } from '@/hooks/use-payments';
import {
  formatBillingDateTime, isPendingPayment, paymentMethodSummary, paymentReference, paymentStatusLabel,
} from '@/lib/billing/format';
import type { Payment } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';

// Every payment of one patient across invoices, newest first (GET /api/billing/payments?patientSystemId=), failed
// and pending attempts included, with a link to each payment's invoice.

export interface PaymentHistoryProps {
  /** Whose payments: the patient's own id, or the loved one's for a caregiver. Nothing loads until it is known. */
  patientSystemId: string | null | undefined;
  /** Makes each payment's invoice number a button that opens the invoice. */
  onOpenInvoice?:  (invoiceId: number) => void;
  /** Change the value to make the list load again. */
  refreshKey?:     string | number;
  /** Called when the server refuses the list with 403. */
  onForbidden?:    () => void;
}

/** Payments made by an administrator (at the desk, or an insurance settlement) rather than started by the payer. */
const RECORDED_METHODS: ReadonlySet<string> = new Set(['CASH', 'BANK_TRANSFER', 'INSURANCE']);

function PaymentCard({ payment, onOpenInvoice }: { payment: Payment; onOpenInvoice?: (invoiceId: number) => void }) {
  const reference = paymentReference(payment);
  const by = payment.initiatedByName?.trim();
  const invoiceLabel = payment.invoiceNo ?? (payment.invoiceId !== null ? `Invoice #${payment.invoiceId}` : null);
  return (
    <View style={styles.card}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title}>{paymentMethodSummary(payment)}</Text>
          <Text style={styles.meta}>{formatBillingDateTime(payment.paidAt ?? payment.createdAt, 'Date not recorded')}</Text>
        </View>
        <View style={styles.side}>
          <Text style={styles.amount}>{formatMoney(payment.amount, payment.currency)}</Text>
          <StatusBadge label={paymentStatusLabel(payment.status)} tone={statusTone('payment', payment.status)} />
        </View>
      </View>
      {isPendingPayment(payment) ? (
        <Text style={styles.meta}>Waiting for confirmation from the payment provider.</Text>
      ) : null}
      {payment.status === 'FAILED' && payment.failureReason ? (
        <Text style={styles.meta}>Reason: {payment.failureReason}</Text>
      ) : null}
      {payment.refundedAmount > 0 ? (
        <Text style={styles.meta}>
          Refunded {formatMoney(payment.refundedAmount, payment.currency)}{payment.refundReason ? `. Reason: ${payment.refundReason}` : ''}
        </Text>
      ) : null}
      {reference ? <Text style={styles.meta} selectable>Reference: {reference}</Text> : null}
      {by ? <Text style={styles.meta}>{RECORDED_METHODS.has(payment.method) ? 'Recorded by' : 'Paid by'} {by}</Text> : null}
      {invoiceLabel ? (
        onOpenInvoice && payment.invoiceId !== null ? (
          <View style={styles.actions}>
            <Button
              label={`View invoice ${invoiceLabel}`}
              icon="receipt-outline"
              variant="secondary"
              size="sm"
              onPress={() => onOpenInvoice(payment.invoiceId as number)}
            />
          </View>
        ) : <Text style={styles.meta}>{invoiceLabel}</Text>
      ) : null}
    </View>
  );
}

/** The payment history of one patient. Renders its own loading, empty, failed (with Retry) and no-access states. */
export function PaymentHistory({ patientSystemId, onOpenInvoice, refreshKey, onForbidden }: PaymentHistoryProps) {
  const payments = usePatientPayments(patientSystemId);

  const { refetch } = payments;
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = payments.errorStatus === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  return (
    <PagedList
      state={payments}
      keyOf={payment => payment.paymentId}
      label="Payments"
      noAccessMessage="You don't have access to these payments."
      empty={{
        icon:  'cash-outline',
        title: 'No payments yet',
        sub:   'Payments made for invoices, and settlements from insurance claims, appear here.',
      }}
      renderItem={payment => <PaymentCard payment={payment} onOpenInvoice={onOpenInvoice} />}
    />
  );
}

const styles = StyleSheet.create({
  card: {
    gap: space.xs, padding: space.lg, minWidth: 0,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  head:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', columnGap: space.lg, rowGap: space.xs },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  side:    { gap: space.xs, minWidth: 0 },
  title:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  amount:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, paddingTop: space.xs },
});
