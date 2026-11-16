import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, type BannerTone, Button, colors, fontSize, IconButton, LoadingView, PagedList, radius, ROLE_THEME, Screen,
  SelectField, space, Tabs, TextField,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import {
  type DoctorPrescription, getPrescriptionSchedule, PRESCRIPTION_STATUSES, type PrescriptionDto,
  type PrescriptionSchedulePlan, type PrescriptionStatus, toDoctorPrescription, toPrescriptionDto,
  useDoctorPrescriptionCounts, useDoctorPrescriptions,
} from '@/hooks/use-doctor-prescriptions';
import { useResource } from '@/hooks/use-resource';
import { api } from '@/lib/auth/auth.interceptor';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { ApiError } from '@/lib/api/errors';
import type { SectionProps } from '@/lib/nav/sections';
import { CLINIC_ZONE_LABEL } from '@/lib/time/clinic';
import { PRESCRIPTION_LIMITS } from '@/lib/validation/prescription';
import { EditPrescriptionDialog } from './prescription/edit-prescription-dialog';
import { EditScheduleDialog } from './prescription/edit-schedule-dialog';
import { STATUS_LABEL, type StatusAction } from './prescription/labels';
import { PrescriptionCard } from './prescription/prescription-card';
import { PrescriptionForm, type PrescriptionSavedInfo } from './prescription/prescription-form';
import { StatusActionDialog } from './prescription/status-action-dialog';

// Doctor > Prescriptions: the prescriptions the signed-in doctor wrote, with issuing, editing and status changes.
//
// URL params (CONTRACTS 12.1):
//   patient         PAT id; the list is filtered to that patient on the server and a removable chip shows the name
//   status          one of the five statuses; absent = all
//   consultationId  opens "New prescription" linked to that consultation (with `patient` as its patient)
//   mode=new        opens "New prescription" (with `patient` prefilled when present)
//   id              a prescription to show first, above the list (a link from the patient history, a correction
//                   request or a clinical alert); one written by another doctor is shown read-only

type StatusTab = 'ALL' | PrescriptionStatus;

interface Notice {
  tone:    BannerTone;
  text:    string;
  action?: { label: string; onPress: () => void };
}

const SEARCH_DELAY_MS = 350;

function useDebounced<T>(value: T, delayMs: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return settled;
}

function statusFromParam(value: string | undefined): PrescriptionStatus | null {
  const s = (value ?? '').trim().toUpperCase().replace(/-/g, '_');
  return (PRESCRIPTION_STATUSES as readonly string[]).includes(s) ? (s as PrescriptionStatus) : null;
}

function positiveInt(value: string | undefined): number | null {
  return value && /^[1-9]\d{0,9}$/.test(value.trim()) ? Number(value.trim()) : null;
}

function timesSentence(times: readonly string[]): string {
  return times.length > 0
    ? `Dose times: ${times.join(', ')} (${CLINIC_ZONE_LABEL}).`
    : 'No doses are scheduled.';
}

export default function DoctorWebPrescription(props: Partial<SectionProps> = {}) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.doctor;
  const { setParams } = nav;

  // ── Filters ────────────────────────────────────────────────────────────────
  const patientFilter = params.patient?.trim() || null;
  const statusFilter = statusFromParam(params.status);
  const [searchText, setSearchText] = useState('');
  const search = useDebounced(searchText.trim(), SEARCH_DELAY_MS);

  const list = useDoctorPrescriptions(
    { status: statusFilter, patientSystemId: patientFilter, search },
    { pageSize: 20 },
  );
  const counts = useDoctorPrescriptionCounts({ patientSystemId: patientFilter, search });
  const { patients, applyUpdate, refetch: refetchList } = list;
  const { refetch: refetchCounts } = counts;

  const refreshAll = useCallback(() => { refetchList(); refetchCounts(); }, [refetchList, refetchCounts]);

  // ── The prescription a link points at (`id`) ──────────────────────────────
  const { user } = useCurrentUser();
  const linkedId = positiveInt(params.id);
  const linked = useResource<PrescriptionDto>(
    () => api.get<unknown>(`/api/prescriptions/${linkedId}`).then(r => toPrescriptionDto(r.data)),
    [linkedId],
    { enabled: linkedId !== null, errorMessage: 'That prescription could not be opened.' },
  );
  const closeLinked = useCallback(() => setParams({ id: undefined }), [setParams]);

  // While the linked prescription is shown above the list, its row is left out of the list, so it never appears
  // twice with two sets of actions.
  const shownAboveId = linked.data && linkedId !== null && linked.data.prescriptionId === linkedId ? linkedId : null;
  const listState = useMemo(() => {
    if (shownAboveId === null) return list.paged;
    const items = list.paged.items.filter(rx => rx.prescription_id !== shownAboveId);
    if (items.length === list.paged.items.length) return list.paged;
    return { ...list.paged, items, total: Math.max(items.length, list.paged.total - 1) };
  }, [list.paged, shownAboveId]);
  const onlyLinked = shownAboveId !== null && listState.items.length === 0 && list.paged.items.length > 0;

  // ── Messages about the last action ─────────────────────────────────────────
  const [notice, setNotice] = useState<Notice | null>(null);
  // Identifies the latest notice, so a late follow-up (the dose times of a new prescription) never replaces a newer one.
  const noticeSeq = useRef(0);
  const show = useCallback((next: Notice | null) => {
    noticeSeq.current += 1;
    setNotice(next);
    return noticeSeq.current;
  }, []);

  // ── Dialogs ────────────────────────────────────────────────────────────────
  const consultationParam = positiveInt(params.consultationId);
  const openedByUrl = consultationParam !== null || params.mode === 'new';
  const [formOpenedHere, setFormOpenedHere] = useState(false);
  const formOpen = formOpenedHere || openedByUrl;

  const [editing, setEditing] = useState<DoctorPrescription | null>(null);
  const [scheduling, setScheduling] = useState<DoctorPrescription | null>(null);
  const [statusRequest, setStatusRequest] = useState<{ prescription: DoctorPrescription; action: StatusAction } | null>(null);
  const [scheduleVersion, setScheduleVersion] = useState(0);

  const closeForm = useCallback(() => {
    setFormOpenedHere(false);
    // The request to open the form is used up: without this a refresh would open it again.
    if (openedByUrl) setParams({ consultationId: undefined, mode: undefined });
  }, [openedByUrl, setParams]);

  const viewOf = useCallback((dto: PrescriptionDto): DoctorPrescription => {
    const row = toDoctorPrescription(dto);
    return { ...row, patient_name: patients.nameOf(dto.patientSystemId) };
  }, [patients]);

  const onCreated = (created: PrescriptionDto, info: PrescriptionSavedInfo) => {
    closeForm();
    refreshAll();
    if (info.differsFromForm) {
      show({
        tone: 'warning',
        text: `${created.medicationName} had already been issued by an earlier attempt of this form and was not issued again. `
          + 'Changes made after that attempt were not applied: check the prescription in the list, and cancel it and '
          + 'issue a new one if it is not right.',
      });
      return;
    }
    const issued = `${created.medicationName} was prescribed for ${patients.nameOf(created.patientSystemId)}.`;
    if (info.scheduleTimes.length > 0) {
      show({ tone: 'success', text: `${issued} ${timesSentence(info.scheduleTimes)}` });
      return;
    }
    const seq = show({ tone: 'success', text: issued });
    // No times were given: the server works them out from the frequency when it can. Show what the patient gets.
    getPrescriptionSchedule(created.prescriptionId)
      .then(plan => {
        if (noticeSeq.current !== seq) return;
        setNotice(plan.times.length > 0
          ? { tone: 'success', text: `${issued} ${timesSentence(plan.times)}` }
          : {
              // Expected for "as needed" or weekly medication, so it is information, not a warning.
              tone: 'info',
              text: `${issued} No doses are scheduled yet: no dose times were given and none could be worked out from the frequency.`,
              action: { label: 'Edit schedule times', onPress: () => setScheduling(viewOf(created)) },
            });
      })
      .catch(() => { /* the prescription is issued; its dose times stay available under "Show details" */ });
  };

  const onEdited = (updated: PrescriptionDto) => {
    setEditing(null);
    applyUpdate(updated);
    linked.setData(prev => (prev && prev.prescriptionId === updated.prescriptionId ? updated : prev));
    refreshAll();
    show({ tone: 'success', text: `${updated.medicationName} was updated.` });
  };

  const onStatusChanged = (updated: PrescriptionDto, action: StatusAction) => {
    setStatusRequest(null);
    applyUpdate(updated);
    linked.setData(prev => (prev && prev.prescriptionId === updated.prescriptionId ? updated : prev));
    refreshAll();
    setScheduleVersion(v => v + 1);
    show({ tone: 'success', text: `${updated.medicationName} ${action.done}.` });
  };

  const onScheduleSaved = (plan: PrescriptionSchedulePlan) => {
    const name = scheduling?.medication_name ?? 'The prescription';
    setScheduling(null);
    setScheduleVersion(v => v + 1);
    show({ tone: 'success', text: `${name}: schedule times saved. ${timesSentence(plan.times)}` });
  };

  /** A write was refused because the prescription changed elsewhere (completed, cancelled, expired) or is gone. */
  const onStale = (error: ApiError) => {
    setEditing(null);
    setScheduling(null);
    setStatusRequest(null);
    refreshAll();
    show({ tone: 'warning', text: `${error.message.replace(/[.\s]+$/, '')}. The list has been refreshed.` });
  };

  // ── Filter controls ────────────────────────────────────────────────────────
  const tabs = useMemo(() => [
    { key: 'ALL' as StatusTab, label: 'All', badge: counts.total ?? undefined },
    ...PRESCRIPTION_STATUSES.map(s => ({ key: s as StatusTab, label: STATUS_LABEL[s], badge: counts.counts?.[s] })),
  ], [counts.total, counts.counts]);

  const patientOptions = useMemo(
    () => patients.options.map(o => ({ value: o.patientSystemId, label: o.fullName })),
    [patients.options],
  );
  const patientName = patientFilter ? patients.nameOf(patientFilter) : '';
  const filtered = !!statusFilter || !!patientFilter || !!search;

  const clearFilters = () => {
    setSearchText('');
    setParams({ status: undefined, patient: undefined });
  };

  const header = (
    <View style={styles.filters}>
      <Tabs<StatusTab>
        tabs={tabs}
        active={statusFilter ?? 'ALL'}
        onChange={key => setParams({ status: key === 'ALL' ? undefined : key })}
        label="Filter prescriptions by status"
      />
      <View style={styles.filterRow}>
        <View style={styles.filterCell}>
          <TextField
            label="Search by medication"
            value={searchText}
            onChange={setSearchText}
            placeholder="Medication or generic name"
            maxLength={PRESCRIPTION_LIMITS.search}
            autoCapitalize="none"
          />
        </View>
        <View style={styles.filterCell}>
          <SelectField
            label="Patient"
            value={patientFilter ?? ''}
            onChange={id => setParams({ patient: id || undefined })}
            options={patientOptions}
            placeholder={patients.loading ? 'Loading…' : patientOptions.length === 0 ? 'No patients to choose from' : 'All patients'}
            allowEmpty={patientOptions.length > 0}
            disabled={patients.loading || patientOptions.length === 0}
            hint={patients.error ?? undefined}
          />
        </View>
      </View>
      {patientFilter ? (
        <View style={styles.chipRow}>
          <View style={[styles.chip, { backgroundColor: theme.accentBg, borderColor: theme.accent }]}>
            <Text style={styles.chipText}>Patient: {patientName}</Text>
            <IconButton
              label={`Remove patient filter, ${patientName}`}
              icon="close"
              size="sm"
              onPress={() => setParams({ patient: undefined })}
              style={styles.chipRemove}
            />
          </View>
        </View>
      ) : null}
    </View>
  );

  return (
    <Screen
      title="Prescriptions"
      subtitle="Issue prescriptions and manage the ones you have written"
      role="doctor"
      theme={props.theme}
      actions={(
        <>
          <Button label="Refresh" icon="refresh-outline" variant="secondary" onPress={refreshAll} loading={list.refreshing} />
          <Button label="New prescription" icon="add" onPress={() => setFormOpenedHere(true)} />
        </>
      )}
    >
      {notice ? (
        <Banner tone={notice.tone} text={notice.text} action={notice.action} onDismiss={() => show(null)} />
      ) : null}

      {linkedId !== null ? (
        linked.loading ? (
          <LoadingView label="Opening the prescription…" compact />
        ) : linked.data ? (
          <View style={styles.linked} role="region" aria-label="Opened prescription">
            <View style={styles.linkedHead}>
              <Text style={styles.linkedNote}>Opened from a link</Text>
              <Button label="Close" icon="close" variant="ghost" size="sm" onPress={closeLinked} accessibilityLabel="Close the opened prescription" />
            </View>
            <PrescriptionCard
              rx={viewOf(linked.data)}
              scheduleVersion={scheduleVersion}
              onEdit={setEditing}
              onEditSchedule={setScheduling}
              onStatusAction={(prescription, action) => setStatusRequest({ prescription, action })}
              readOnly={!user || linked.data.doctorSystemId !== user.system_id}
            />
          </View>
        ) : (
          <Banner
            tone="warning"
            text={linked.errorStatus === 403 || linked.errorStatus === 404
              ? 'That prescription is not available to you. It may have been removed, or the patient is no longer in your care.'
              : linked.error ?? 'That prescription could not be opened.'}
            action={linked.errorStatus === 403 || linked.errorStatus === 404 ? undefined : { label: 'Retry', onPress: linked.refetch }}
            onDismiss={closeLinked}
          />
        )
      ) : null}

      <PagedList
        state={listState}
        label="Prescriptions"
        keyOf={rx => rx.prescription_id}
        header={header}
        renderItem={rx => (
          <PrescriptionCard
            rx={rx}
            scheduleVersion={scheduleVersion}
            onEdit={setEditing}
            onEditSchedule={setScheduling}
            onStatusAction={(prescription, action) => setStatusRequest({ prescription, action })}
          />
        )}
        empty={onlyLinked
          ? {
              icon: 'medkit-outline', title: 'No other prescriptions',
              sub: 'The opened prescription is the only one in this list.',
            }
          : filtered
          ? {
              icon: 'search-outline', title: 'No prescriptions match these filters',
              sub: 'Try another status, patient or search.',
              action: { label: 'Clear filters', onPress: clearFilters, icon: 'close-outline' },
            }
          : {
              icon: 'medkit-outline', title: 'No prescriptions yet',
              sub: 'Prescriptions you issue for your patients appear here.',
              action: { label: 'New prescription', onPress: () => setFormOpenedHere(true), icon: 'add' },
            }}
        noAccessMessage="You don't have access to prescriptions."
      />

      <PrescriptionForm
        open={formOpen}
        onClose={closeForm}
        onSaved={onCreated}
        patientSystemId={patientFilter}
        consultationId={consultationParam}
      />
      <EditPrescriptionDialog prescription={editing} onClose={() => setEditing(null)} onSaved={onEdited} onStale={onStale} />
      <EditScheduleDialog prescription={scheduling} onClose={() => setScheduling(null)} onSaved={onScheduleSaved} onStale={onStale} />
      <StatusActionDialog request={statusRequest} onClose={() => setStatusRequest(null)} onDone={onStatusChanged} onStale={onStale} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters:    { gap: space.md, minWidth: 0 },
  // The two filters share a row on a wide screen and stack on a phone.
  filterRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  filterCell: { flexGrow: 1, flexShrink: 1, flexBasis: 240, minWidth: 0 },

  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: 2, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: 2, paddingVertical: 2, borderWidth: 1, borderRadius: radius.pill,
  },
  chipText:   { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  chipRemove: { borderRadius: radius.pill },

  linked:     { gap: space.sm, minWidth: 0 },
  linkedHead: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  linkedNote: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
});
