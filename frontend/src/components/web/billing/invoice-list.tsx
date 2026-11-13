import { type ReactNode, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  colors, type EmptyAction, fontSize, LoadingView, PagedList, radius, space, StatusBadge, statusTone, useUiTheme, webOnly,
} from '@/components/web/ui';
import { useInvoices } from '@/hooks/use-invoices';
import {
  DEFAULT_INVOICE_FILTER, type InvoiceFilterKey, invoiceFiltersFor, invoiceFilterStatuses, parseInvoiceFilter,
} from '@/lib/billing/filters';
import {
  formatBillingDate, formatBillingDay, invoiceDoctorLabel, invoiceStatusLabel, isOpenInvoice,
} from '@/lib/billing/format';
import type { BillingViewer, Invoice } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import { FilterChips } from './parts';

export interface InvoiceListProps {
  /**
   * Whose invoices to list. Required for a patient (their own id) and a caregiver (the loved one's id); nothing
   * is requested until it is known. For a doctor it is an optional filter on the invoices they issued.
   */
  patientSystemId: string | null | undefined;
  /** Who is looking. Default `patient`. A doctor sees the invoices they issued, drafts included. */
  viewer?:         BillingViewer;
  /**
   * The selected status filter. Pass it together with `onFilterChange` to keep the filter in the URL
   * (`params.status`); without it the list keeps the filter itself.
   */
  filter?:         InvoiceFilterKey;
  onFilterChange?: (filter: InvoiceFilterKey) => void;
  /** Called when a row is activated. */
  onSelect:        (invoice: Invoice) => void;
  /** The invoice shown in the detail view, marked in the list. */
  selectedInvoiceId?: number | null;
  /** Only invoices of this consultation or appointment. */
  consultationId?: number | null;
  appointmentId?:  number | null;
  /** Change the value to make the list load again (after a payment, an edit, a new invoice). */
  refreshKey?:     string | number;
  /** Called when the server refuses the list with 403 (a caregiver grant that no longer allows billing). */
  onForbidden?:    () => void;
  /** What the person can do when there is no invoice (for example "Create invoice" for a doctor). */
  emptyAction?:    EmptyAction | ReactNode;
  /** Content shown above the filter chips (a patient chip, a summary). */
  header?:         ReactNode;
}

/** What a row says next to the amount: the balance while the invoice is open, otherwise how it ended. */
function balanceLine(invoice: Invoice): string {
  if (isOpenInvoice(invoice)) return `Balance due ${formatMoney(invoice.outstanding, invoice.currency)}`;
  switch (invoice.status) {
    case 'PAID':     return invoice.paidAt ? `Paid ${formatBillingDay(invoice.paidAt)}` : 'Paid in full';
    case 'DRAFT':    return 'Not sent yet';
    case 'REFUNDED': return 'Payments refunded';
    default:         return 'Nothing to pay';
  }
}

function dateLine(invoice: Invoice): string {
  const parts: string[] = [];
  if (invoice.issuedAt) parts.push(`Issued ${formatBillingDay(invoice.issuedAt)}`);
  else parts.push(`Created ${formatBillingDay(invoice.createdAt, 'recently')}`);
  if (isOpenInvoice(invoice) && invoice.dueDate) parts.push(`Due ${formatBillingDate(invoice.dueDate)}`);
  return parts.join(' · ');
}

function InvoiceRow({ invoice, viewer, selected, onPress }: {
  invoice:  Invoice;
  viewer:   BillingViewer;
  selected: boolean;
  onPress:  () => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const status = invoiceStatusLabel(invoice.status);
  const total = formatMoney(invoice.total, invoice.currency);
  // A patient or caregiver wants to know who billed them; a doctor or administrator wants to know who was billed.
  const party = viewer === 'doctor' || viewer === 'admin'
    ? invoice.patientName?.trim() || invoice.patientSystemId
    : invoiceDoctorLabel(invoice);
  const balance = balanceLine(invoice);
  const dates = dateLine(invoice);

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      // The visible text in visible order (number, status, who, dates, total, balance), so the name contains what
      // is shown (WCAG 2.5.3); "invoice" is added at the end for context.
      accessibilityLabel={`${invoice.invoiceNo}, ${status}, ${party}. ${dates}. ${total}, ${balance}, invoice`}
      {...webOnly({ 'aria-current': selected ? 'true' : undefined })}
      style={({ pressed }) => [
        styles.row,
        selected && { borderColor: accent, backgroundColor: accentBg },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.rowMain}>
        <View style={styles.rowTitle}>
          <Text style={styles.invoiceNo}>{invoice.invoiceNo}</Text>
          <StatusBadge label={status} tone={statusTone('invoice', invoice.status)} />
        </View>
        <Text style={styles.party} numberOfLines={2}>{party}</Text>
        <Text style={styles.meta}>{dates}</Text>
      </View>
      <View style={styles.rowAmounts}>
        <Text style={styles.total}>{total}</Text>
        <Text style={[styles.meta, invoice.status === 'OVERDUE' && styles.overdue]}>{balance}</Text>
      </View>
    </Pressable>
  );
}

/**
 * The invoices of one patient (or, for a doctor, the invoices they issued) with status filter chips, newest
 * first. Renders its own loading, empty, failed (with Retry) and no-access states and a "Load more" button; rows
 * stack on a phone.
 */
export function InvoiceList({
  patientSystemId, viewer = 'patient', filter, onFilterChange, onSelect, selectedInvoiceId = null,
  consultationId, appointmentId, refreshKey, onForbidden, emptyAction, header,
}: InvoiceListProps) {
  const [ownFilter, setOwnFilter] = useState<InvoiceFilterKey>(DEFAULT_INVOICE_FILTER);
  const active = parseInvoiceFilter(filter ?? ownFilter, viewer);
  const filters = invoiceFiltersFor(viewer);
  // A doctor lists the invoices they issued; everyone else lists one patient's invoices.
  const issuerList = viewer === 'doctor';
  const patientId = patientSystemId?.trim() || null;

  const invoices = useInvoices({
    scope:          issuerList ? 'doctor' : 'patient',
    patientSystemId: patientId,
    status:         invoiceFilterStatuses(active),
    consultationId,
    appointmentId,
  });

  // Reload when the caller says something changed. The first render already loads.
  const { refetch } = invoices;
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = invoices.errorStatus === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  const changeFilter = (next: InvoiceFilterKey) => {
    if (filter === undefined) setOwnFilter(next);
    onFilterChange?.(next);
  };

  // A patient's or a loved one's list cannot be asked for before we know whose it is.
  if (!issuerList && patientId === null) return <LoadingView label="Loading invoices" />;

  const activeLabel = filters.find(f => f.key === active)?.label ?? 'All';
  const empty = active === 'all'
    ? {
        icon:  'receipt-outline' as const,
        title: 'No invoices yet',
        sub:   issuerList
          ? 'Invoices you create for your consultations appear here.'
          : 'Invoices appear here after a consultation has been billed.',
        action: emptyAction,
      }
    : {
        icon:  'funnel-outline' as const,
        title: `No ${activeLabel.toLowerCase()} invoices`,
        sub:   'Choose another filter to see other invoices.',
        action: { label: 'Show all invoices', onPress: () => changeFilter('all') },
      };

  return (
    <PagedList
      state={invoices}
      keyOf={invoice => invoice.invoiceId}
      label="Invoices"
      noAccessMessage="You don't have access to this person's billing."
      empty={empty}
      header={(
        <View style={styles.header}>
          {header}
          <FilterChips
            label="Filter invoices by status"
            options={filters}
            value={active}
            onChange={changeFilter}
          />
        </View>
      )}
      renderItem={invoice => (
        <InvoiceRow
          invoice={invoice}
          viewer={viewer}
          selected={invoice.invoiceId === selectedInvoiceId}
          onPress={() => onSelect(invoice)}
        />
      )}
    />
  );
}

const styles = StyleSheet.create({
  header: { gap: space.md, minWidth: 0 },

  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between',
    columnGap: space.lg, rowGap: space.sm, padding: space.lg, minWidth: 0,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  pressed: { opacity: 0.9 },
  // flexBasis lets the two blocks sit side by side on a wide screen and stack on a phone.
  rowMain:    { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, gap: space.xs },
  rowTitle:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  rowAmounts: { flexGrow: 0, flexShrink: 1, minWidth: 0, gap: 2 },

  invoiceNo: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  party:     { fontSize: fontSize.md, color: colors.text },
  meta:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  total:     { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  overdue:   { color: colors.danger, fontWeight: '600' },
});
