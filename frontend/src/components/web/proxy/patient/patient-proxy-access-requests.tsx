import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, FormActions, FormError, Modal, PagedList, ROLE_THEME, Screen,
  space, StatusBadge, statusTone, Tabs, type TabItem, useUiTheme,
} from '@/components/web/ui';
import {
  useCancelAccessRequest, useCaregiverAccessRequests,
  type AccessRequestStatus, type CaregiverAccessRequest,
} from '@/hooks/use-caregiver-access-requests';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  accessLevelLabel, COMM_FLAGS, COMM_LABELS, dataFlagsOf, normaliseDataFlags, PERMISSION_OPTIONS, toAccessLevel,
} from '@/lib/caregiver/access';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';

// The caregiver's requests for access to patients who already have an account (US-14): the ones still waiting
// for an answer and the ones that were approved, declined, cancelled or expired. A waiting request can be
// cancelled. What a patient granted on approval is shown on that patient's profile, not here: a request row
// only ever holds what was asked for.

export type AccessRequestTab = 'pending' | 'approved' | 'declined' | 'all';

const TABS: readonly TabItem<AccessRequestTab>[] = [
  { key: 'pending',  label: 'Waiting'  },
  { key: 'approved', label: 'Approved' },
  { key: 'declined', label: 'Declined' },
  { key: 'all',      label: 'All requests' },
];

const TAB_STATUS: Record<AccessRequestTab, AccessRequestStatus | undefined> = {
  pending: 'PENDING', approved: 'APPROVED', declined: 'DECLINED', all: undefined,
};

const EMPTY: Record<AccessRequestTab, { title: string; sub: string }> = {
  pending:  { title: 'No requests are waiting',  sub: 'A request you send to a patient stays here until they answer it.' },
  approved: { title: 'No approved requests',     sub: 'Requests a patient approved are listed here.' },
  declined: { title: 'No declined requests',     sub: 'Requests a patient declined are listed here.' },
  all:      { title: 'You have not sent any requests', sub: 'Ask a patient who already has an account to share their care with you.' },
};

const STATUS_TEXT: Record<AccessRequestStatus, string> = {
  PENDING:   'Waiting for an answer',
  APPROVED:  'Approved',
  DECLINED:  'Declined',
  CANCELLED: 'Cancelled',
  EXPIRED:   'Expired',
};

export function isAccessRequestTab(value: unknown): value is AccessRequestTab {
  return value === 'pending' || value === 'approved' || value === 'declined' || value === 'all';
}

function statusText(status: string): string {
  return STATUS_TEXT[status as AccessRequestStatus] ?? 'Unknown';
}

/** "Answered", "Cancelled" or "Expired" with the date, for a request that is no longer waiting. */
function outcomeLine(request: CaregiverAccessRequest): string | null {
  if (request.status === 'PENDING') {
    return `Expires on ${formatClinicDate(request.requestExpiresAt, 'a later date')} if it is not answered`;
  }
  const at = formatClinicDateTime(request.respondedAt);
  if (!at) return null;
  if (request.status === 'CANCELLED') return `Cancelled ${at}`;
  if (request.status === 'EXPIRED')   return `Expired ${at}`;
  return `Answered ${at}`;
}

/** What was asked for, in words: the data permissions the level stores plus any communication request. */
function askedFor(request: CaregiverAccessRequest): string[] {
  const flags = normaliseDataFlags(toAccessLevel(request.accessLevel), dataFlagsOf(request));
  const out = PERMISSION_OPTIONS.filter(o => flags[o.flag]).map(o => o.label);
  for (const flag of COMM_FLAGS) if (request[flag]) out.push(COMM_LABELS[flag]);
  return out;
}

function RequestCard({ request, highlighted, onCancel, onOpenProfile }: {
  request:        CaregiverAccessRequest;
  highlighted:    boolean;
  onCancel:       (request: CaregiverAccessRequest) => void;
  onOpenProfile?: (patientSystemId: string) => void;
}) {
  const { accent } = useUiTheme();
  const who = request.patientName?.trim() || request.patientEmail;
  const asked = askedFor(request);
  const outcome = outcomeLine(request);
  return (
    <Card style={[styles.card, highlighted && { borderColor: accent, borderWidth: 2 }]}>
      <View style={styles.cardHead}>
        <View style={styles.cardTitles}>
          {/* Level 2: the cards sit directly under the page heading (Access Requests or Add Loved One). */}
          <Text style={styles.cardTitle} accessibilityRole="header" {...ariaLevel(2)}>{who}</Text>
          {request.patientName?.trim() ? <Text style={styles.meta}>{request.patientEmail}</Text> : null}
          {/* The highlight is said in words too, not by the border colour alone. */}
          {highlighted ? <Text style={[styles.linked, { color: accent }]}>From your notification</Text> : null}
        </View>
        <StatusBadge label={statusText(request.status)} tone={statusTone('accessRequest', request.status)} />
      </View>

      <Text style={styles.meta}>
        {`Relationship: ${request.relationship} · Asked for: ${accessLevelLabel(request.accessLevel)}`}
      </Text>
      <Text style={styles.meta}>{`Sent ${formatClinicDateTime(request.createdAt, 'on an unknown date')}`}</Text>
      {outcome ? <Text style={styles.meta}>{outcome}</Text> : null}

      <Text style={styles.body}>
        {asked.length > 0 ? `You asked to: ${asked.join(', ')}.` : 'You did not ask for any specific permission.'}
      </Text>
      {request.message?.trim() ? <Text style={styles.body}>{`Your note: ${request.message.trim()}`}</Text> : null}
      {request.status === 'DECLINED' && request.declineReason?.trim() ? (
        <Text style={styles.body}>{`Reason given: ${request.declineReason.trim()}`}</Text>
      ) : null}
      {request.status === 'APPROVED' ? (
        <Text style={styles.body}>
          The patient chose what to share when they approved. Their profile shows what you can see and do.
        </Text>
      ) : null}

      {request.status === 'PENDING' || (request.status === 'APPROVED' && request.patientSystemId && onOpenProfile) ? (
        <View style={styles.actions}>
          {request.status === 'APPROVED' && request.patientSystemId && onOpenProfile ? (
            <Button
              label="Open profile"
              icon="person-outline"
              variant="secondary"
              size="sm"
              accessibilityLabel={`Open profile of ${who}`}
              onPress={() => onOpenProfile(request.patientSystemId as string)}
            />
          ) : null}
          {request.status === 'PENDING' ? (
            <Button
              label="Cancel request"
              icon="close-circle-outline"
              variant="secondary"
              size="sm"
              accessibilityLabel={`Cancel request to ${who}`}
              onPress={() => onCancel(request)}
            />
          ) : null}
        </View>
      ) : null}
    </Card>
  );
}

export interface AccessRequestsPanelProps {
  tab:            AccessRequestTab;
  onTabChange:    (tab: AccessRequestTab) => void;
  /** A request to mark, for example the one a notification is about. */
  highlightId?:   number | null;
  /** Opens the profile of the patient of an approved request. The button is absent without it. */
  onOpenProfile?: (patientSystemId: string) => void;
}

/** The tabbed, paged list of the caregiver's access requests with Cancel on the waiting ones. */
export function AccessRequestsPanel({ tab, onTabChange, highlightId = null, onOpenProfile }: AccessRequestsPanelProps) {
  const requests = useCaregiverAccessRequests(TAB_STATUS[tab]);
  const cancel = useCancelAccessRequest();
  const [target, setTarget] = useState<CaregiverAccessRequest | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const closeDialog = () => { setTarget(null); cancel.reset(); };

  const confirmCancel = async () => {
    if (!target) return;
    const result = await cancel.run(target.requestId);
    if (result.ok) {
      setNotice(`Your request to ${target.patientEmail} was cancelled.`);
      setTarget(null);
      requests.refetch();
    } else if (result.error?.status === 409 || result.error?.status === 404) {
      // The request was answered, or expired, while this page was open: show its current state.
      requests.refetch();
    }
  };

  return (
    <View style={styles.panel}>
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      <PagedList
        state={requests}
        keyOf={request => request.requestId}
        label="Access requests"
        header={(
          <View style={styles.header}>
            <Tabs tabs={TABS} active={tab} onChange={next => { setNotice(null); onTabChange(next); }} label="Access requests by status" />
            {tab === 'pending' ? (
              <Text style={styles.meta}>
                A request waits until the patient answers. If no patient account uses the email you entered, it
                stays unanswered and expires after 30 days.
              </Text>
            ) : null}
          </View>
        )}
        empty={{ icon: 'mail-open-outline', title: EMPTY[tab].title, sub: EMPTY[tab].sub }}
        renderItem={request => (
          <RequestCard
            request={request}
            highlighted={highlightId !== null && request.requestId === highlightId}
            onCancel={setTarget}
            onOpenProfile={onOpenProfile}
          />
        )}
      />

      <Modal
        open={target !== null}
        title="Cancel this request?"
        onClose={closeDialog}
        size="sm"
        busy={cancel.pending}
        footer={(
          <FormActions
            submitLabel="Cancel request"
            destructive
            onSubmit={confirmCancel}
            submitting={cancel.pending}
            cancelLabel="Keep request"
            onCancel={closeDialog}
          />
        )}
      >
        <Text style={styles.body}>
          {`Your request to ${target?.patientEmail ?? 'this patient'} will be withdrawn. The patient is not told. You can send a new request at any time.`}
        </Text>
        <FormError message={cancel.error} />
      </Modal>
    </View>
  );
}

/**
 * The hidden proxy section `access-requests`. URL params: `tab` (pending, approved, declined, all) and `id`
 * (a request to mark; with it the list opens on All requests so an answered request is visible).
 */
export default function PatientProxyAccessRequests(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const highlightId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null;
  const tab: AccessRequestTab = isAccessRequestTab(params.tab) ? params.tab : highlightId !== null ? 'all' : 'pending';

  return (
    <Screen
      title="Access Requests"
      subtitle="Requests you sent to patients who already have their own account"
      theme={props.theme ?? ROLE_THEME.proxy}
      actions={(
        <Button label="Ask for access" icon="person-add-outline" onPress={() => navigate('add-dependent', { mode: 'link' })} />
      )}
    >
      <AccessRequestsPanel
        tab={tab}
        onTabChange={next => nav.setParams({ tab: next })}
        highlightId={highlightId}
        onOpenProfile={patientSystemId => navigate('patient-profile', { patient: patientSystemId })}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  panel:  { gap: space.md, minWidth: 0 },
  header: { gap: space.sm, minWidth: 0 },

  card:       { gap: space.sm },
  cardHead:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  cardTitles: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  cardTitle:  { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  linked:     { fontSize: fontSize.xs, fontWeight: '700' },
  meta:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  body:       { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.xs },
});
