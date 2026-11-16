import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { FilterChips } from '@/components/web/billing';
import {
  Banner, Button, colors, fontSize, Icon, PagedList, radius, space, STATUS_TONES, StatusBadge, statusTone,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useInsuranceClaims } from '@/hooks/use-insurance-claims';
import { getDocument } from '@/lib/documents/api';
import { saveDocument } from '@/lib/documents/download';
import {
  claimStatusLabel, claimStatusMeaning, formatBillingDateTime, isAppealableClaim, OPEN_CLAIM_STATUSES,
} from '@/lib/billing/format';
import type { ClaimDocument, ClaimStatus, InsuranceClaim } from '@/lib/billing/types';
import { formatMoney } from '@/lib/format/money';
import { AppealClaimDialog } from './appeal-claim-dialog';

// Every insurance claim of one patient, newest first, with where each one stands, its attachments and an Appeal
// action for a rejected or partly approved decision (api/be-billing-claims.md 5.3, 5.4).

export type ClaimFilterKey = 'all' | 'open' | 'decided';

const CLAIM_FILTERS: readonly { key: ClaimFilterKey; label: string; statuses: readonly ClaimStatus[] }[] = [
  { key: 'all',     label: 'All',          statuses: [] },
  { key: 'open',    label: 'In progress',  statuses: OPEN_CLAIM_STATUSES },
  { key: 'decided', label: 'Decided',      statuses: ['APPROVED', 'PARTIAL', 'REJECTED'] },
];

export interface ClaimTrackerProps {
  /** Whose claims: the patient's own id, or the loved one's for a caregiver. Nothing loads until it is known. */
  patientSystemId: string | null | undefined;
  /** Shows Appeal on rejected and partly approved claims (patient; caregiver with view and manage billing). */
  canClaim:        boolean;
  /** Lets the attachments be downloaded (patient; caregiver who may view documents). */
  canViewDocuments?: boolean;
  /** Makes each claim's invoice number a button that opens the invoice. */
  onOpenInvoice?:  (invoiceId: number) => void;
  /** Change the value to make the list load again. */
  refreshKey?:     string | number;
  /** Called after an appeal changed a claim (so other views can reload). */
  onChanged?:      () => void;
  /** Called when the server refuses the list or an appeal with 403. */
  onForbidden?:    () => void;
}

type StepState = 'done' | 'current' | 'upcoming';

/** Where a claim stands as three steps: submitted, reviewed, decided (or appealed and waiting again). */
function claimSteps(claim: InsuranceClaim): { label: string; detail?: string; state: StepState }[] {
  const decided = claim.status === 'APPROVED' || claim.status === 'PARTIAL' || claim.status === 'REJECTED';
  return [
    { label: 'Submitted', detail: formatBillingDateTime(claim.submittedAt), state: 'done' },
    {
      label: claim.status === 'APPEALED' ? 'Appealed, waiting for a new review' : 'Under review',
      state: claim.status === 'SUBMITTED' ? 'upcoming' : claim.status === 'PENDING' || claim.status === 'APPEALED' ? 'current' : 'done',
    },
    {
      label:  decided ? `Decision: ${claimStatusLabel(claim.status)}` : 'Decision',
      detail: decided && claim.resolvedAt ? formatBillingDateTime(claim.resolvedAt) : undefined,
      state:  decided ? 'done' : 'upcoming',
    },
  ];
}

const STEP_WORD: Record<StepState, string> = { done: 'done', current: 'in progress', upcoming: 'not yet' };

function ClaimProgress({ claim }: { claim: InsuranceClaim }) {
  const steps = claimSteps(claim);
  return (
    <View role="list" aria-label={`Progress of claim ${claim.claimNo}`} style={styles.steps}>
      {steps.map(step => {
        const color = step.state === 'done'
          ? STATUS_TONES.success.fg
          : step.state === 'current' ? STATUS_TONES.info.fg : colors.textSubtle;
        return (
          <View
            key={step.label}
            role="listitem"
            accessibilityLabel={`${step.label}${step.detail ? `, ${step.detail}` : ''}, ${STEP_WORD[step.state]}`}
            style={styles.step}
          >
            <Icon
              name={step.state === 'done' ? 'checkmark-circle' : step.state === 'current' ? 'time-outline' : 'ellipse-outline'}
              size={16}
              color={color}
            />
            <Text style={[styles.stepText, step.state === 'upcoming' && styles.stepUpcoming]}>
              {step.label}{step.detail ? ` · ${step.detail}` : ''}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

function ClaimCard({ claim, canClaim, canViewDocuments, onOpenInvoice, onAppeal, onDownload, downloading }: {
  claim:            InsuranceClaim;
  canClaim:         boolean;
  canViewDocuments: boolean;
  onOpenInvoice?:   (invoiceId: number) => void;
  onAppeal:         (claim: InsuranceClaim) => void;
  onDownload:       (doc: ClaimDocument) => void;
  downloading:      number | null;
}) {
  const invoiceLabel = claim.invoiceNo ?? `Invoice #${claim.invoiceId}`;
  const meaning = claimStatusMeaning(claim);
  return (
    <View style={styles.card}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title}>{claim.insuranceProvider}</Text>
          <Text style={styles.meta} selectable>Claim {claim.claimNo} · Policy {claim.policyNumber}{claim.memberNumber ? ` · Member ${claim.memberNumber}` : ''}</Text>
        </View>
        <View style={styles.side}>
          <Text style={styles.amount}>{formatMoney(claim.claimAmount, claim.currency)}</Text>
          <StatusBadge label={claimStatusLabel(claim.status)} tone={statusTone('claim', claim.status)} />
        </View>
      </View>

      {meaning ? <Text style={styles.body}>{meaning}</Text> : null}
      <ClaimProgress claim={claim} />

      {claim.approvedAmount !== null && claim.status !== 'PARTIAL' ? (
        <Text style={styles.meta}>Approved amount: {formatMoney(claim.approvedAmount, claim.currency)}</Text>
      ) : null}
      {claim.rejectionReason ? (
        <Text style={styles.meta}>
          {claim.status === 'PARTIAL' ? "Reviewer's note" : 'Reason for the decision'}: {claim.rejectionReason}
        </Text>
      ) : null}
      {claim.appealReason ? <Text style={styles.meta}>Appeal: {claim.appealReason}</Text> : null}
      {claim.invoiceOutstanding > 0 ? (
        <Text style={styles.meta}>Still owed on the invoice: {formatMoney(claim.invoiceOutstanding, claim.currency)}</Text>
      ) : null}

      {claim.documents.length > 0 ? (
        <View style={styles.docs}>
          <Text style={styles.metaStrong}>Attachments</Text>
          {canViewDocuments ? (
            <View style={styles.row}>
              {claim.documents.map(doc => (
                <Button
                  key={doc.documentId}
                  label={doc.title}
                  icon="download-outline"
                  variant="ghost"
                  size="sm"
                  loading={downloading === doc.documentId}
                  accessibilityLabel={`Download ${doc.title}`}
                  onPress={() => onDownload(doc)}
                />
              ))}
            </View>
          ) : (
            <Text style={styles.meta}>{claim.documents.map(d => d.title).join(', ')}</Text>
          )}
        </View>
      ) : null}

      <View style={styles.row}>
        {onOpenInvoice ? (
          <Button
            label={`View invoice ${invoiceLabel}`}
            icon="receipt-outline"
            variant="secondary"
            size="sm"
            onPress={() => onOpenInvoice(claim.invoiceId)}
          />
        ) : <Text style={styles.meta}>{invoiceLabel}</Text>}
        {canClaim && isAppealableClaim(claim) ? (
          <Button
            label="Appeal"
            icon="chatbox-ellipses-outline"
            variant="secondary"
            size="sm"
            accessibilityLabel={`Appeal claim ${claim.claimNo}`}
            onPress={() => onAppeal(claim)}
          />
        ) : null}
      </View>
    </View>
  );
}

/**
 * The patient's insurance claims with filter chips (All, In progress, Decided), a three-step progress line per
 * claim, attachments and Appeal. Renders its own loading, empty, failed (with Retry) and no-access states.
 */
export function ClaimTracker({
  patientSystemId, canClaim, canViewDocuments = false, onOpenInvoice, refreshKey, onChanged, onForbidden,
}: ClaimTrackerProps) {
  const [filter, setFilter] = useState<ClaimFilterKey>('all');
  const statuses = CLAIM_FILTERS.find(f => f.key === filter)?.statuses ?? [];
  const claims = useInsuranceClaims(patientSystemId, { status: statuses });

  const { refetch, setItems } = claims;
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return;
    lastRefreshKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const onForbiddenRef = useRef(onForbidden);
  onForbiddenRef.current = onForbidden;
  const forbidden = claims.errorStatus === 403;
  useEffect(() => {
    if (forbidden) onForbiddenRef.current?.();
  }, [forbidden]);

  const [appealing, setAppealing] = useState<InsuranceClaim | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const download = useAction(async (doc: ClaimDocument) => {
    const meta = await getDocument(doc.documentId);
    await saveDocument(meta);
  }, { errorMessage: 'The file could not be downloaded. Please try again.' });
  const [downloading, setDownloading] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const runDownload = async (doc: ClaimDocument) => {
    setDownloadError(null);
    setDownloading(doc.documentId);
    const result = await download.run(doc);
    setDownloading(null);
    if (result.ok || !result.error) return;
    setDownloadError(result.error.status === 404
      ? `${doc.title} is no longer available. It may have been deleted from the documents.`
      : result.error.status === 403
        ? `You don't have access to ${doc.title}.`
        : result.error.message);
  };

  const filterLabel = CLAIM_FILTERS.find(f => f.key === filter)?.label ?? 'All';
  const empty = filter === 'all'
    ? {
        icon:  'shield-checkmark-outline' as const,
        title: 'No insurance claims yet',
        sub:   canClaim
          ? 'Open an unpaid invoice and choose Submit claim to ask an insurer to pay it.'
          : 'Claims submitted for invoices appear here.',
      }
    : {
        icon:   'funnel-outline' as const,
        title:  `No claims ${filterLabel.toLowerCase()}`,
        sub:    'Choose another filter to see other claims.',
        action: { label: 'Show all claims', onPress: () => setFilter('all') },
      };

  return (
    <View style={styles.wrap}>
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      {downloadError ? <Banner tone="danger" text={downloadError} onDismiss={() => setDownloadError(null)} /> : null}
      <PagedList
        state={claims}
        keyOf={claim => claim.claimId}
        label="Insurance claims"
        noAccessMessage="You don't have access to these insurance claims."
        empty={empty}
        header={(
          <FilterChips
            label="Filter claims"
            options={CLAIM_FILTERS}
            value={filter}
            onChange={setFilter}
          />
        )}
        renderItem={claim => (
          <ClaimCard
            claim={claim}
            canClaim={canClaim}
            canViewDocuments={canViewDocuments}
            onOpenInvoice={onOpenInvoice}
            onAppeal={c => { setNotice(null); setAppealing(c); }}
            onDownload={doc => { void runDownload(doc); }}
            downloading={downloading}
          />
        )}
      />
      <AppealClaimDialog
        claim={appealing}
        onClose={() => setAppealing(null)}
        onAppealed={updated => {
          setAppealing(null);
          setItems(items => items.map(c => (c.claimId === updated.claimId ? updated : c)));
          setNotice(`Your appeal of claim ${updated.claimNo} was sent. The clinic reviews the claim again.`);
          onChanged?.();
        }}
        onChanged={() => { refetch(); onChanged?.(); }}
        onForbidden={onForbidden}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },
  card: {
    gap: space.sm, padding: space.lg, minWidth: 0,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
  },
  head:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', columnGap: space.lg, rowGap: space.xs },
  titles: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  side:   { gap: space.xs, minWidth: 0 },
  title:  { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  amount: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  body:   { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  meta:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  metaStrong: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  row:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  docs:   { gap: space.xs },

  steps:        { gap: 4, paddingVertical: space.xs },
  step:         { flexDirection: 'row', alignItems: 'center', gap: space.sm, minWidth: 0 },
  stepText:     { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.text },
  stepUpcoming: { color: colors.textMuted },
});
