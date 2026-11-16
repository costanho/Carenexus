import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ConsentHistoryPanel, Disclosure, GrantCard, GrantForm, RequestsSection, useOnline,
} from '@/components/web/caregiver-access';
import {
  ariaLevel, Banner, type BannerTone, Button, Card, colors, EmptyView, ErrorView, fontSize, LoadingView,
  type RoleTheme, Screen, space,
} from '@/components/web/ui';
import {
  useAccessRequests,
  useCaregiverAccess,
  type CaregiverAccess,
  type CaregiverAccessRequest,
} from '@/hooks/use-caregiver-access';
import { caregiverDisplayName } from '@/lib/caregiver-access/format';

const INTRO =
  'Decide who can help with your care and what they can see and do. Caregivers can message your care team or ' +
  'join your video calls only after you approve. Removing access also removes them from conversations and from ' +
  'any call in progress.';

export interface PatientWebCaregiverAccessProps {
  /** Returns to the dashboard home; shows the Back button when given. */
  onBack?: () => void;
  /**
   * URL params of the section (`SectionProps.params`). `tab=requests&id=<requestId>` is the link of the
   * "Caregiver access request" notification; the screen works the same without them.
   */
  params?: Record<string, string | undefined>;
  /** The dashboard's accent colours; patient teal when omitted (the native wrapper passes nothing). */
  theme?:  RoleTheme;
}

interface ScreenNotice { tone: BannerTone; text: string }

/** `id` of a request the page was opened for, or null. */
function requestIdFrom(params: Record<string, string | undefined> | undefined): number | null {
  if (!params || params.tab !== 'requests' || !params.id) return null;
  const id = Number(params.id);
  return Number.isInteger(id) && id > 0 ? id : null;
}

function withFullStop(message: string): string {
  const text = message.trim();
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

/**
 * My Proxies: the patient's caregivers.
 *   - Access requests: pending requests from caregivers, with a permission editor, Approve and Decline.
 *   - Caregivers with access: effective permissions, pending messaging and call requests (Approve or Decline),
 *     the permission editor with billing, removal, and per caregiver an Activity and a Consent history panel.
 *   - Consent history: the whole ledger, including caregivers whose access was removed.
 * The native caregiver-access screen wraps this component, so it is built from the shared primitives only.
 */
export default function PatientWebCaregiverAccess({ onBack, params, theme }: PatientWebCaregiverAccessProps) {
  const access   = useCaregiverAccess();
  const requests = useAccessRequests('PENDING');
  const online   = useOnline();

  const [granting, setGranting] = useState(false);
  const [notice,   setNotice]   = useState<ScreenNotice | null>(null);
  // The grant whose editor a request card asked to open; the token changes on every ask.
  const [editTarget, setEditTarget] = useState<{ accessId: number; token: number } | null>(null);
  const [showLedger, setShowLedger] = useState(false);
  // Bumped after every change so an open ledger shows the step that was just recorded.
  const [ledgerVersion, setLedgerVersion] = useState(0);

  const { grants } = access;
  const highlightId = requestIdFrom(params);
  const waitingCount = grants.filter(g => g.pendingApproval).length;

  function recorded() {
    setLedgerVersion(v => v + 1);
  }

  function changed(updated: CaregiverAccess) {
    access.setGrants(prev => prev.map(g => (g.accessId === updated.accessId ? { ...g, ...updated } : g)));
    recorded();
  }

  function removed(accessId: number, name: string) {
    access.setGrants(prev => prev.filter(g => g.accessId !== accessId));
    setNotice({ tone: 'success', text: `Access removed for ${name}.` });
    recorded();
  }

  function granted(grant: CaregiverAccess) {
    setGranting(false);
    access.setGrants(prev => [grant, ...prev.filter(g => g.accessId !== grant.accessId)]);
    setNotice({ tone: 'success', text: `Access granted to ${caregiverDisplayName(grant)}.` });
    recorded();
  }

  function forgetRequest(request: CaregiverAccessRequest) {
    requests.setItems(prev => prev.filter(r => r.requestId !== request.requestId));
    requests.refetch();
  }

  function approved(request: CaregiverAccessRequest) {
    forgetRequest(request);
    // The answer is the request row; the grant it created is read from the grant list.
    access.refetch();
    setNotice({
      tone: 'success',
      text: `${caregiverDisplayName(request)} now has access. You can change or remove it below at any time.`,
    });
    recorded();
  }

  function declined(request: CaregiverAccessRequest) {
    forgetRequest(request);
    setNotice({ tone: 'success', text: `Request from ${caregiverDisplayName(request)} declined.` });
  }

  /** A change was refused because a row on screen is out of date (revoked, already answered): load again. */
  function stale(message: string) {
    setNotice({ tone: 'warning', text: `${withFullStop(message)} The lists below have been updated.` });
    access.refetch();
    requests.refetch();
    recorded();
  }

  function editGrant(accessId: number) {
    setEditTarget(prev => ({ accessId, token: (prev?.token ?? 0) + 1 }));
  }

  const actions = (
    <>
      {onBack ? <Button label="Back to dashboard" variant="ghost" icon="arrow-back-outline" onPress={onBack} /> : null}
      {!granting ? (
        <Button
          label="Grant access" icon="person-add-outline"
          accessibilityLabel="Grant access to a caregiver"
          onPress={() => { setNotice(null); setGranting(true); }}
          disabled={!online}
        />
      ) : null}
    </>
  );

  return (
    <Screen title="My caregivers" subtitle={INTRO} actions={actions} theme={theme} role="patient">
      {!online ? (
        <Banner tone="warning" icon="cloud-offline-outline" text="You're offline. Changes can't be saved until you reconnect." />
      ) : null}
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      {granting ? <GrantForm online={online} onCancel={() => setGranting(false)} onGranted={granted} /> : null}

      <RequestsSection
        requests={requests}
        grants={grants}
        online={online}
        highlightId={highlightId}
        onApproved={approved}
        onDeclined={declined}
        onStale={stale}
        onEditGrant={editGrant}
      />

      <View style={styles.section}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>Caregivers with access</Text>
          <Text style={styles.sub}>
            Each card shows what the person can see and do right now. Full access includes everything; View only
            never allows booking, care plan changes or payments.
          </Text>
        </View>

        {access.loading ? (
          <LoadingView label="Loading your caregivers…" />
        ) : access.error && grants.length === 0 ? (
          <ErrorView message={access.error} onRetry={access.refetch} />
        ) : grants.length === 0 ? (
          <Card>
            <EmptyView
              icon="people-outline"
              title="No caregivers yet"
              sub="Grant access to someone who helps with your care, such as a family member."
              action={!granting && online
                ? { label: 'Grant access', icon: 'person-add-outline', onPress: () => { setNotice(null); setGranting(true); } }
                : undefined}
            />
          </Card>
        ) : (
          <>
            {access.error ? (
              <Banner tone="danger" text={access.error} action={{ label: 'Retry', onPress: access.refetch }} />
            ) : null}
            {waitingCount > 0 ? (
              <Banner
                tone="info"
                icon="hand-left-outline"
                text={`${waitingCount} ${waitingCount === 1 ? 'caregiver is' : 'caregivers are'} waiting for your answer about messaging or video calls.`}
              />
            ) : null}
            <View style={styles.list} role="list" aria-label="Caregivers with access" aria-busy={access.refreshing}>
              {grants.map(grant => (
                <View key={grant.accessId} role="listitem" style={styles.item}>
                  <GrantCard
                    grant={grant}
                    online={online}
                    editToken={editTarget?.accessId === grant.accessId ? editTarget.token : undefined}
                    onChanged={changed}
                    onRemoved={removed}
                    onStale={stale}
                  />
                </View>
              ))}
            </View>
          </>
        )}
      </View>

      <View style={styles.section}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>Consent history</Text>
          <Text style={styles.sub}>
            A permanent record of every time access to your information was granted, changed, removed or expired,
            including caregivers who no longer have access.
          </Text>
        </View>
        <Card style={styles.ledger}>
          <Disclosure
            label="All consent records" icon="document-text-outline" divider={false}
            open={showLedger} onToggle={() => setShowLedger(open => !open)}
          >
            <ConsentHistoryPanel accessId={null} refreshKey={ledgerVersion} />
          </Disclosure>
        </Card>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  section: { gap: space.md, minWidth: 0 },
  titles:  { gap: space.xs },
  title:   { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  sub:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  list:    { gap: space.md, minWidth: 0 },
  item:    { minWidth: 0 },
  ledger:  { paddingVertical: space.xs },
});
