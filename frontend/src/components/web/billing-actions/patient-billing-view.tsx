import { useCallback, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { AmountDueCard, type InvoiceDetailContext, InvoiceDetail, InvoiceList } from '@/components/web/billing';
import { Banner, Button, space, Tabs, type TabItem } from '@/components/web/ui';
import { useBillingConfig } from '@/hooks/use-billing-config';
import { type InvoiceFilterKey, parseInvoiceFilter } from '@/lib/billing/filters';
import { isAppealableClaim, isPayableInvoice } from '@/lib/billing/format';
import type { InsuranceClaim, Invoice } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import { AppealClaimDialog } from './appeal-claim-dialog';
import { ClaimForm } from './claim-form';
import { ClaimTracker } from './claim-tracker';
import { PayDialog } from './pay-dialog';
import { PaymentHistory } from './payment-history';
import { PaymentMethodsManager } from './payment-methods-manager';
import { canSubmitClaimFor, reservedAmount } from './rules';

// The body of the patient's and the caregiver's Billing & Payments section for one patient: tabs for invoices
// (with the amount-due summary), payments, insurance claims and, for the patient, saved payment methods; the
// invoice detail opened by the `id` URL param, with Pay, Submit claim and Appeal in its action slots. Every
// in-screen choice that should survive a refresh (tab, status filter, opened invoice) lives in the URL.

export type BillingTabKey = 'invoices' | 'payments' | 'claims' | 'methods';

export interface PatientBillingViewProps {
  /** The patient whose billing is shown: the patient's own id, or the loved one's for a caregiver. */
  patientSystemId: string;
  /** Shown in the upload dialog of a claim attachment (a caregiver sees whose record receives the file). */
  patientName?:    string | null;
  viewer:          'patient' | 'proxy';
  /** Start payments (patient; caregiver with both view and manage billing). */
  canPay:          boolean;
  /** Submit claims and appeal decisions (same people as `canPay`). */
  canClaim:        boolean;
  /** Upload claim attachments (patient; caregiver who may also manage documents). */
  canAttach:       boolean;
  /** Download claim attachments (patient; caregiver who may view documents). */
  canViewDocuments: boolean;
  /** Shows the Payment methods tab with add, default and remove (the patient only). */
  canManageMethods: boolean;
  /** URL params of the section: `tab`, `status` (invoice filter), `id` (invoice id). */
  params:          Record<string, string | undefined>;
  setParams:       (patch: Record<string, string | number | undefined>) => void;
  /** Called when the server refuses any read or action with 403 (a caregiver whose access changed). */
  onForbidden?:    () => void;
}

const TAB_LABELS: Record<BillingTabKey, string> = {
  invoices: 'Invoices',
  payments: 'Payments',
  claims:   'Insurance claims',
  methods:  'Payment methods',
};

/** The invoice id of the `id` URL param, or null when absent. A value that is not an id opens "not available". */
export function parseInvoiceIdParam(value: string | null | undefined): number | null {
  const text = value?.trim();
  if (!text) return null;
  return /^\d{1,15}$/.test(text) ? Number(text) : 0;
}

interface PayTarget {
  invoice:  Invoice;
  reserved: number;
}

export function PatientBillingView({
  patientSystemId, patientName, viewer, canPay, canClaim, canAttach, canViewDocuments, canManageMethods,
  params, setParams, onForbidden,
}: PatientBillingViewProps) {
  const tabs: TabItem<BillingTabKey>[] = (['invoices', 'payments', 'claims', 'methods'] as const)
    .filter(key => key !== 'methods' || canManageMethods)
    .map(key => ({ key, label: TAB_LABELS[key] }));
  const requestedTab = params.tab as BillingTabKey | undefined;
  const tab: BillingTabKey = tabs.some(t => t.key === requestedTab) ? (requestedTab as BillingTabKey) : 'invoices';
  const filter = parseInvoiceFilter(params.status, viewer);
  const invoiceId = parseInvoiceIdParam(params.id);

  const { config } = useBillingConfig({ enabled: canPay });
  const onlineClosed = config !== null && config.onlineMethods.length === 0;

  // Lists and the summary reload when this changes (after a payment, a claim or an appeal).
  const [version, setVersion] = useState(0);
  const bump = useCallback(() => setVersion(v => v + 1), []);
  const [notice, setNotice] = useState<string | null>(null);

  // The open dialogs, and how to reload the invoice detail they were opened from.
  const [payTarget, setPayTarget] = useState<PayTarget | null>(null);
  const [claimTarget, setClaimTarget] = useState<Invoice | null>(null);
  const [appealTarget, setAppealTarget] = useState<InsuranceClaim | null>(null);
  const refreshDetail = useRef<(() => void) | null>(null);
  const changed = useCallback(() => {
    refreshDetail.current?.();
    bump();
  }, [bump]);

  const openInvoice = useCallback((id: number) => setParams({ id }), [setParams]);
  const closeInvoice = useCallback(() => setParams({ id: undefined }), [setParams]);

  const detailActions = (ctx: InvoiceDetailContext) => {
    const payable = isPayableInvoice(ctx.invoice);
    const showPay = canPay && payable && !onlineClosed;
    const showClaim = canClaim && canSubmitClaimFor(ctx.invoice, ctx.claimsKnown, ctx.openClaim);
    if (!showPay && !showClaim) return null;
    return (
      <>
        {showPay ? (
          <Button
            label="Pay"
            icon="card-outline"
            accessibilityLabel={`Pay invoice ${ctx.invoice.invoiceNo}, ${formatMoney(ctx.invoice.outstanding, ctx.invoice.currency)} due`}
            onPress={() => {
              setNotice(null);
              refreshDetail.current = ctx.refresh;
              setPayTarget({ invoice: ctx.invoice, reserved: reservedAmount(ctx.payments) });
            }}
          />
        ) : null}
        {showClaim ? (
          <Button
            label="Submit claim"
            icon="shield-checkmark-outline"
            variant="secondary"
            accessibilityLabel={`Submit an insurance claim for invoice ${ctx.invoice.invoiceNo}`}
            onPress={() => {
              setNotice(null);
              refreshDetail.current = ctx.refresh;
              setClaimTarget(ctx.invoice);
            }}
          />
        ) : null}
      </>
    );
  };

  const claimActions = (claim: InsuranceClaim, ctx: InvoiceDetailContext) => (
    canClaim && isAppealableClaim(claim) ? (
      <Button
        label="Appeal"
        icon="chatbox-ellipses-outline"
        variant="secondary"
        size="sm"
        accessibilityLabel={`Appeal claim ${claim.claimNo}`}
        onPress={() => {
          setNotice(null);
          refreshDetail.current = ctx.refresh;
          setAppealTarget(claim);
        }}
      />
    ) : null
  );

  let content;
  if (invoiceId !== null) {
    content = (
      <InvoiceDetail
        key={invoiceId}
        invoiceId={invoiceId}
        patientSystemId={patientSystemId}
        viewer={viewer}
        actions={detailActions}
        claimActions={claimActions}
        onBack={closeInvoice}
        backLabel={tab === 'invoices' ? 'Back to invoices' : `Back to ${TAB_LABELS[tab].toLowerCase()}`}
        onForbidden={onForbidden}
      />
    );
  } else if (tab === 'payments') {
    content = (
      <PaymentHistory patientSystemId={patientSystemId} onOpenInvoice={openInvoice} refreshKey={version} onForbidden={onForbidden} />
    );
  } else if (tab === 'claims') {
    content = (
      <ClaimTracker
        patientSystemId={patientSystemId}
        canClaim={canClaim}
        canViewDocuments={canViewDocuments}
        onOpenInvoice={openInvoice}
        refreshKey={version}
        onChanged={bump}
        onForbidden={onForbidden}
      />
    );
  } else if (tab === 'methods') {
    content = (
      <PaymentMethodsManager
        patientSystemId={patientSystemId}
        canManage={canManageMethods}
        refreshKey={version}
        onForbidden={onForbidden}
      />
    );
  } else {
    content = (
      <View style={styles.stack}>
        <AmountDueCard
          patientSystemId={patientSystemId}
          title="Outstanding balance"
          refreshKey={version}
          onForbidden={onForbidden}
        />
        <InvoiceList
          patientSystemId={patientSystemId}
          viewer={viewer}
          filter={filter}
          onFilterChange={(next: InvoiceFilterKey) => setParams({ status: next === 'all' ? undefined : next })}
          onSelect={invoice => openInvoice(invoice.invoiceId)}
          refreshKey={version}
          onForbidden={onForbidden}
        />
      </View>
    );
  }

  return (
    <View style={styles.stack}>
      <Tabs
        tabs={tabs}
        active={tab}
        label="Billing views"
        onChange={key => setParams({ tab: key === 'invoices' ? undefined : key, id: undefined })}
      />
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      {content}

      {payTarget ? (
        <PayDialog
          open
          invoice={payTarget.invoice}
          reservedAmount={payTarget.reserved}
          patientSystemId={patientSystemId}
          viewer={viewer}
          canPay={canPay}
          onClose={() => setPayTarget(null)}
          onChanged={changed}
          onForbidden={onForbidden}
        />
      ) : null}
      {claimTarget ? (
        <ClaimForm
          open
          invoice={claimTarget}
          patientSystemId={patientSystemId}
          patientName={patientName}
          canClaim={canClaim}
          canAttach={canAttach}
          onClose={() => setClaimTarget(null)}
          onSubmitted={claim => {
            setClaimTarget(null);
            setNotice(`Claim ${claim.claimNo} was submitted to ${claim.insuranceProvider}. You can follow it under Insurance claims.`);
            changed();
          }}
          onChanged={changed}
          onForbidden={onForbidden}
        />
      ) : null}
      <AppealClaimDialog
        claim={appealTarget}
        onClose={() => setAppealTarget(null)}
        onAppealed={claim => {
          setAppealTarget(null);
          setNotice(`Your appeal of claim ${claim.claimNo} was sent. The clinic reviews the claim again.`);
          changed();
        }}
        onChanged={changed}
        onForbidden={onForbidden}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space.lg, minWidth: 0 },
});
