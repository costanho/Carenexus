import { useEffect, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, ErrorView, fontSize, Icon, LoadingView, NoAccessView, SectionCard, space, StatusBadge,
  STATUS_TONES,
} from '@/components/web/ui';
import { useAmountDue } from '@/hooks/use-invoices';
import { formatBillingDate } from '@/lib/billing/format';
import type { AmountDueSummary, AmountDueTotals } from '@/lib/billing/summary';
import { formatMoney } from '@/lib/format/money';

export interface AmountDueCardProps {
  /** Whose balance to show: the patient's own id, or the loved one's for a caregiver. Nothing loads until it is known. */
  patientSystemId: string | null | undefined;
  /** Shows a "View billing" button that calls it (open the billing section). */
  onViewBilling?:  () => void;
  /** Card heading. Default "Amount due". */
  title?:          string;
  /** Change the value to make the card load again (after a payment). */
  refreshKey?:     string | number;
  /** Called when the server refuses the read with 403 (a caregiver grant that no longer allows billing). */
  onForbidden?:    () => void;
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

function Totals({ totals, truncated }: { totals: AmountDueTotals; truncated: boolean }) {
  const outstanding = formatMoney(totals.outstanding, totals.currency);
  return (
    <View style={styles.totals}>
      <Text style={styles.amount}>{truncated ? `At least ${outstanding}` : outstanding}</Text>
      <Text style={styles.meta}>Outstanding across {plural(totals.invoiceCount, 'unpaid invoice', 'unpaid invoices')}</Text>
      {totals.overdueCount > 0 ? (
        <View style={styles.overdue}>
          <StatusBadge label="Overdue" tone="danger" />
          <Text style={styles.overdueText}>
            {formatMoney(totals.overdue, totals.currency)} on {plural(totals.overdueCount, 'invoice', 'invoices')}
          </Text>
        </View>
      ) : null}
    </View>
  );
}

function Summary({ summary }: { summary: AmountDueSummary }) {
  if (summary.totals.length === 0) {
    return (
      <View style={styles.clear}>
        <Icon name="checkmark-circle-outline" size={22} color={STATUS_TONES.success.fg} />
        <View style={styles.clearText}>
          <Text style={styles.clearTitle}>Nothing to pay</Text>
          <Text style={styles.meta}>There are no unpaid invoices.</Text>
        </View>
      </View>
    );
  }
  return (
    <View style={styles.summary}>
      {summary.totals.map(totals => <Totals key={totals.currency} totals={totals} truncated={summary.truncated} />)}
      {summary.nextDueDate ? (
        <Text style={styles.meta}>Next payment due {formatBillingDate(summary.nextDueDate)}</Text>
      ) : null}
      {summary.truncated ? (
        <Banner tone="info" text="There are more unpaid invoices than this summary counts. Open billing for the full list." />
      ) : null}
    </View>
  );
}

/**
 * What a patient still owes, for a dashboard: the outstanding total of their unpaid invoices, how much of it is
 * overdue and when the next payment is due. The totals are computed from the patient's open invoices; an invoice
 * in another currency gets its own total. Renders loading, failed (with Retry), no-access and nothing-to-pay
 * states.
 */
export function AmountDueCard({ patientSystemId, onViewBilling, title = 'Amount due', refreshKey, onForbidden }: AmountDueCardProps) {
  const patientId = patientSystemId?.trim() || null;
  const due = useAmountDue(patientId);

  const { refetch } = due;
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = due.errorStatus === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  let body;
  if (patientId === null || due.loading) {
    body = <LoadingView compact label="Loading amount due" />;
  } else if (forbidden) {
    body = <NoAccessView compact message="You don't have access to this person's billing." />;
  } else if (due.data === null) {
    body = <ErrorView compact message={due.error ?? 'We could not load the amount due. Please try again.'} onRetry={refetch} />;
  } else {
    body = (
      <>
        {due.error ? <Banner tone="danger" text={due.error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        <Summary summary={due.data} />
      </>
    );
  }

  return (
    <SectionCard
      title={title}
      action={onViewBilling ? (
        <Button label="View billing" icon="card-outline" variant="secondary" size="sm" onPress={onViewBilling} />
      ) : undefined}
    >
      {body}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  summary: { gap: space.md, minWidth: 0 },
  totals:  { gap: space.xs, minWidth: 0 },
  amount:  { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  overdue:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, paddingTop: space.xs },
  overdueText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.danger },

  clear:      { flexDirection: 'row', alignItems: 'center', gap: space.md },
  clearText:  { flex: 1, minWidth: 0, gap: 2 },
  clearTitle: { fontSize: fontSize.lg, fontWeight: '600', color: colors.text },
});
