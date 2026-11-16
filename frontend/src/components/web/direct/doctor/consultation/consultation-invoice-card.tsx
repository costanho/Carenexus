import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, ErrorView, fontSize, InfoRow, LoadingView, NoAccessView, SectionCard, space, StatusBadge, statusTone,
} from '@/components/web/ui';
import { useResource } from '@/hooks/use-resource';
import { listDoctorInvoices } from '@/lib/billing/api';
import { invoiceStatusLabel } from '@/lib/billing/format';
import type { Invoice } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import type { ConsultationNav } from './consultation-view';

export interface ConsultationInvoiceCardProps {
  consultationId: number;
  /** The appointment the consultation documents; an invoice raised for the appointment is found through it. */
  appointmentId?: number | null;
  patientName:    string;
  nav:            ConsultationNav;
}

/**
 * The invoice of a completed consultation. It is looked up with the `consultationId` filter of
 * GET /api/doctors/billing/invoices (the doctor's own invoices). That filter is an exact match, so an invoice
 * raised for the appointment (consultationId null) is looked up again with the `appointmentId` filter. The
 * newest match is shown with its status and opens in Billing (`?section=billing&id=<invoiceId>`). Without one, Create invoice opens Billing on that
 * consultation (`?section=billing&consultationId=<id>`), which starts the invoice form prefilled from the fee
 * schedule.
 */
export function ConsultationInvoiceCard({ consultationId, appointmentId, patientName, nav }: ConsultationInvoiceCardProps) {
  const visitId = appointmentId != null && appointmentId > 0 ? appointmentId : null;
  const lookup = useResource<Invoice | null>(
    async () => {
      const byConsultation = await listDoctorInvoices({ consultationId }, 0, 1);
      const found = byConsultation.content[0] ?? null;
      if (found || visitId === null) return found;
      const byAppointment = await listDoctorInvoices({ appointmentId: visitId }, 0, 1);
      return byAppointment.content[0] ?? null;
    },
    [consultationId, visitId],
    { errorMessage: 'The invoice of this consultation could not be looked up.' },
  );
  const { data: invoice, loading, error, errorStatus, refetch, refreshing } = lookup;

  let body;
  if (loading) {
    body = <LoadingView label="Looking up the invoice…" compact />;
  } else if (error && invoice === null) {
    body = errorStatus === 403
      ? <NoAccessView message="Invoices are not available to your account." compact />
      : <ErrorView message={error} onRetry={refetch} compact />;
  } else if (invoice) {
    body = (
      <View style={styles.body}>
        <View style={styles.head}>
          <Text style={styles.number} selectable>{invoice.invoiceNo}</Text>
          <StatusBadge label={invoiceStatusLabel(invoice.status)} tone={statusTone('invoice', invoice.status)} />
        </View>
        <View>
          <InfoRow label="Total" value={formatMoney(invoice.total, invoice.currency)} />
          {invoice.outstanding > 0 ? <InfoRow label="Outstanding" value={formatMoney(invoice.outstanding, invoice.currency)} /> : null}
        </View>
        <View style={styles.actions}>
          <Button
            label="Open invoice"
            icon="receipt-outline"
            variant="secondary"
            onPress={() => nav.navigate('billing', { id: invoice.invoiceId })}
            accessibilityLabel={`Open invoice ${invoice.invoiceNo} for ${patientName}`}
          />
          <Button label="Check again" icon="refresh-outline" variant="ghost" size="sm" onPress={refetch} loading={refreshing} />
        </View>
      </View>
    );
  } else {
    body = (
      <View style={styles.body}>
        <Text style={styles.text}>No invoice has been created for this consultation yet.</Text>
        <View style={styles.actions}>
          <Button
            label="Create invoice"
            icon="add"
            onPress={() => nav.navigate('billing', { consultationId })}
            accessibilityLabel={`Create invoice for the consultation with ${patientName}`}
          />
          <Button label="Check again" icon="refresh-outline" variant="ghost" size="sm" onPress={refetch} loading={refreshing} />
        </View>
      </View>
    );
  }

  return <SectionCard title="Invoice">{body}</SectionCard>;
}

const styles = StyleSheet.create({
  body:    { gap: space.md, minWidth: 0 },
  head:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  number:  { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  text:    { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
