import { useCallback, useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AssignSpecialistDialog } from '@/components/web/referrals/assign-specialist-dialog';
import { CancelReferralDialog } from '@/components/web/referrals/cancel-referral-dialog';
import { DoctorReferralCard } from '@/components/web/referrals/doctor-referral-card';
import { NewReferralPanel } from '@/components/web/referrals/new-referral-panel';
import type { ReferralDialogKind } from '@/components/web/referrals/referral-actions';
import { ReferralDetailPanel } from '@/components/web/referrals/referral-detail-panel';
import { ReferralOutcomeDialog } from '@/components/web/referrals/referral-outcome-dialog';
import {
  Banner, type BannerTone, Button, colors, fontSize, Icon, IconButton, LoadingView, PagedList, radius, ROLE_THEME,
  type RoleTheme, Screen, SelectField, space, Tabs,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  useDoctorPatientName, useIncomingReferrals, useOutgoingReferrals, usePendingIncomingCount, useReferral,
  useReferralRowUpdater,
} from '@/hooks/use-referrals';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { REFERRAL_STATUS_OPTIONS, specialistSummary } from '@/lib/referrals/format';
import {
  type DoctorReferralTab, parsePatientParam, parseReferralId, parseStatusParam, parseTabParam,
} from '@/lib/referrals/params';
import type { Referral, ReferralStatus } from '@/lib/referrals/types';

// Doctor dashboard section `referrals`.
//
//   Sent      referrals this doctor issued: assign or change the specialist and cancel while pending, and record
//             the outcome for a referral that has no specialist on the platform.
//   Received  referrals addressed to this doctor as specialist: accept, decline with a reason, complete.
//
// Which controls a referral shows comes from the server (`allowedActions`), never from rules repeated here.
// Everything that should survive a refresh or a shared link lives in the URL:
//   tab=sent|received   status=<STATUS>   patient=<PAT id> (filter chip)   id=<referralId> (opens the details)
//   mode=new [&patient=<PAT id>&consultationId=<id>] (opens the New referral form, prefilled)

const UNAVAILABLE = 'This referral is no longer available to you.';

interface Notice { tone: BannerTone; text: string }

function PatientChip({ name, theme, onRemove }: { name: string | null; theme: RoleTheme; onRemove: () => void }) {
  return (
    <View style={[styles.chip, { backgroundColor: theme.accentBg, borderColor: theme.accent }]}>
      <Icon name="person-outline" size={14} color={theme.accent} />
      <Text style={styles.chipText} numberOfLines={1}>
        {name ? `Patient: ${name}` : 'Filtered to one patient'}
      </Text>
      <IconButton label="Remove patient filter" icon="close" size="sm" onPress={onRemove} />
    </View>
  );
}

export default function DoctorWebReferrals(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams } = nav;
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.doctor;

  const { user, loading: userLoading } = useCurrentUser();
  const me = user?.system_id ?? null;

  const tabParam       = parseTabParam(params.tab);
  const status         = parseStatusParam(params.status);
  const patientFilter  = parsePatientParam(params.patient);
  const openId         = parseReferralId(params.id);
  const formOpen       = params.mode === 'new';
  const consultationId = parseReferralId(params.consultationId);

  const detail = useReferral(openId);

  // A link that carries only `id` (a notification) does not say which list the referral is in. It is the
  // Received list when the signed-in doctor is the named specialist, otherwise the Sent list; the lists wait
  // until that is known so the wrong one is never requested.
  const [linkPending, setLinkPending] = useState(() => openId != null && tabParam == null);
  const [linkTab, setLinkTab] = useState<DoctorReferralTab | null>(null);
  const resolving = linkPending && (detail.loading || userLoading);
  const loadedSpecialist = detail.data ? detail.data.specialistSystemId : undefined;
  useEffect(() => {
    if (!linkPending || detail.loading || userLoading) return;
    setLinkPending(false);
    if (tabParam || loadedSpecialist === undefined || !me) return;
    const resolved: DoctorReferralTab = loadedSpecialist === me ? 'received' : 'sent';
    setLinkTab(resolved);
    setParams({ tab: resolved });
  }, [linkPending, detail.loading, userLoading, tabParam, loadedSpecialist, me, setParams]);

  const tab: DoctorReferralTab = tabParam ?? linkTab ?? 'sent';

  const filters = useMemo(() => ({ status, patientSystemId: patientFilter }), [status, patientFilter]);
  const sent     = useOutgoingReferrals(filters, { enabled: tab === 'sent' && !resolving });
  const received = useIncomingReferrals(filters, { enabled: tab === 'received' && !resolving });
  const list = tab === 'sent' ? sent : received;
  const updateRow = useReferralRowUpdater(list.setItems);
  const pendingIncoming = usePendingIncomingCount();

  // ── Opened referral ─────────────────────────────────────────────────────────
  const fromList = openId != null ? list.items.find(r => r.referralId === openId) ?? null : null;
  const gone = openId != null && !detail.loading && !detail.data
    && (detail.errorStatus === 403 || detail.errorStatus === 404);
  const opened = detail.data ?? fromList;
  const staleRow = gone && fromList != null;
  const refetchList = list.refetch;
  useEffect(() => {
    // The row is still listed although the server no longer shows the referral to this doctor: reload the list.
    if (staleRow) refetchList();
  }, [staleRow, refetchList]);

  // ── Patient filter chip ─────────────────────────────────────────────────────
  const rowName = !patientFilter ? null
    : list.items.find(r => r.patientSystemId === patientFilter)?.patientName
      ?? (detail.data?.patientSystemId === patientFilter ? detail.data.patientName : null);
  const [knownPatient, setKnownPatient] = useState<{ id: string; name: string } | null>(null);
  const rememberedName = knownPatient?.id === patientFilter ? knownPatient.name : null;
  const lookedUpName = useDoctorPatientName(patientFilter, {
    enabled: !!patientFilter && !rowName && !rememberedName && !list.loading && !resolving,
  });
  const patientName = rowName ?? rememberedName ?? lookedUpName;
  useEffect(() => {
    const name = rowName ?? lookedUpName;
    if (patientFilter && name) setKnownPatient({ id: patientFilter, name });
  }, [patientFilter, rowName, lookedUpName]);

  // ── Actions ─────────────────────────────────────────────────────────────────
  const [notice, setNotice] = useState<Notice | null>(null);
  const [dialog, setDialog] = useState<{ kind: ReferralDialogKind; referral: Referral } | null>(null);

  const openDetails  = useCallback((referral: Referral) => setParams({ id: referral.referralId }), [setParams]);
  const closeDetails = useCallback(() => setParams({ id: undefined }), [setParams]);
  const openForm     = useCallback(() => { setNotice(null); setParams({ mode: 'new' }); }, [setParams]);
  const closeForm    = useCallback(() => setParams({ mode: undefined, consultationId: undefined }), [setParams]);
  const onAction     = useCallback((kind: ReferralDialogKind, referral: Referral) => {
    setNotice(null);
    setDialog({ kind, referral });
  }, []);

  const refetchPending = pendingIncoming.refetch;
  const refetchDetail = detail.refetch;
  const setDetail = detail.setData;

  const onDone = (updated: Referral, message: string) => {
    setDialog(null);
    // Show the change at once, then reload: a row that no longer matches the status filter leaves the list.
    updateRow(updated);
    if (openId === updated.referralId) setDetail(updated);
    refetchList();
    refetchPending();
    setNotice({ tone: 'success', text: message });
  };

  const onStale = () => {
    refetchList();
    refetchPending();
    if (openId != null) refetchDetail();
  };

  const onCreated = (created: Referral) => {
    const patch: SectionParamsInput = { mode: undefined, consultationId: undefined, tab: 'sent' };
    // Leave filters that would hide the new (pending) referral.
    if (status && status !== 'PENDING') patch.status = undefined;
    if (patientFilter && patientFilter !== created.patientSystemId) patch.patient = undefined;
    const sameQuery = tab === 'sent' && !('status' in patch) && !('patient' in patch);
    setParams(patch);
    if (sameQuery) sent.refetch();
    setNotice({
      tone: 'success',
      text: created.specialistType === 'UNASSIGNED'
        ? `Referral for ${created.patientName} created without a specialist. Assign one while it is pending.`
        : `Referral for ${created.patientName} created. Specialist: ${specialistSummary(created)}.`,
    });
  };

  // ── Rendering ───────────────────────────────────────────────────────────────
  const renderDialog = () => {
    if (!dialog) return null;
    const { kind, referral } = dialog;
    const close = () => setDialog(null);
    if (kind === 'assign') return <AssignSpecialistDialog referral={referral} onClose={close} onDone={onDone} onStale={onStale} />;
    if (kind === 'cancel') return <CancelReferralDialog referral={referral} onClose={close} onDone={onDone} onStale={onStale} />;
    return <ReferralOutcomeDialog referral={referral} mode={kind} onClose={close} onDone={onDone} onStale={onStale} />;
  };

  const filtered = !!status || !!patientFilter;
  const empty = tab === 'sent'
    ? filtered
      ? { icon: 'funnel-outline' as const, title: 'No sent referrals match', sub: 'Change the status or remove the patient filter to see more.' }
      : {
          icon: 'swap-horizontal-outline' as const,
          title: 'No referrals sent yet',
          sub: 'Refer a patient to a specialist on CareNexus or outside it. You can follow each referral here.',
          action: { label: 'New referral', icon: 'add' as const, onPress: openForm },
        }
    : filtered
      ? { icon: 'funnel-outline' as const, title: 'No received referrals match', sub: 'Change the status or remove the patient filter to see more.' }
      : {
          icon: 'swap-horizontal-outline' as const,
          title: 'No referrals received',
          sub: 'Referrals that other doctors address to you appear here for you to accept or decline.',
        };

  const header = (
    <View style={styles.filters}>
      <View style={styles.statusFilter}>
        <SelectField<ReferralStatus>
          label="Status"
          value={status}
          onChange={value => setParams({ status: value || undefined })}
          options={REFERRAL_STATUS_OPTIONS}
          placeholder="All statuses"
          allowEmpty
        />
      </View>
      {patientFilter ? (
        <PatientChip name={patientName} theme={theme} onRemove={() => setParams({ patient: undefined })} />
      ) : null}
    </View>
  );

  return (
    <Screen
      title="Referrals"
      subtitle="Refer patients to specialists and respond to referrals addressed to you."
      theme={theme}
      actions={<Button label="New referral" icon="add" onPress={openForm} />}
    >
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
      {gone ? <Banner tone="info" text={UNAVAILABLE} onDismiss={closeDetails} /> : null}

      <Tabs<DoctorReferralTab>
        label="Referral lists"
        tabs={[
          { key: 'sent', label: 'Sent' },
          { key: 'received', label: 'Received', badge: pendingIncoming.count },
        ]}
        active={tab}
        onChange={key => setParams({ tab: key })}
      />

      {resolving ? (
        <LoadingView label="Opening referral…" />
      ) : (
        <PagedList<Referral>
          state={list}
          keyOf={r => r.referralId}
          label={tab === 'sent' ? 'Referrals you sent' : 'Referrals you received'}
          header={header}
          empty={empty}
          renderItem={referral => (
            <DoctorReferralCard
              referral={referral}
              perspective={tab}
              selected={referral.referralId === openId}
              onOpen={openDetails}
              onAction={onAction}
            />
          )}
        />
      )}

      <ReferralDetailPanel
        open={openId != null && !gone && !formOpen}
        referral={opened}
        loading={detail.loading}
        error={detail.error}
        onRetry={refetchDetail}
        onClose={closeDetails}
        onAction={onAction}
      />

      <NewReferralPanel
        open={formOpen}
        initialPatientId={patientFilter}
        consultationId={consultationId}
        selfSystemId={me}
        onClose={closeForm}
        onCreated={onCreated}
      />

      {renderDialog()}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.md, minWidth: 0 },
  statusFilter: { width: 220, maxWidth: '100%' },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%', minWidth: 0,
    paddingLeft: space.md, paddingRight: space.xs, borderWidth: 1, borderRadius: radius.pill,
  },
  chipText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
});
