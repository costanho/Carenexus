import { useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, EmptyView, ErrorView, fontSize, IconButton, PagedList, radius, ROLE_THEME, type RoleTheme,
  Screen, SelectField, space, Tabs, type TabItem, useUiTheme,
} from '@/components/web/ui';
import {
  type ConsultationStatus, type ConsultationTarget, patientNameFallback, useConsultationStats,
  useDoctorConsultations, useDoctorPatientOptions,
} from '@/hooks/use-doctor-consultations';
import { useSectionNav } from '@/hooks/use-section-nav';
import { noteSectionNavigation } from '@/lib/nav/nav-focus';
import type { SectionParams, SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { ConsultationCard } from './consultation/consultation-card';
import { ConsultationEditor } from './consultation/consultation-editor';
import { idParam } from './consultation/consultation-format';
import type { ConsultationNav } from './consultation/consultation-view';
import { NewConsultationPanel } from './consultation/new-consultation-panel';

// Doctor dashboard, Consultations section.
//
// URL params (CONTRACTS 12.1):
//   id=<consultationId> or appointmentId=<appointmentId> (with mode=edit)  opens the editor on that consultation,
//                                                         or on the appointment's consultation (starting one
//                                                         when it has none);
//   patient=<PAT id>                                      lists only that patient's consultations (server-side);
//   tab=draft|in-progress|completed|cancelled             the status filter of the list;
//   panel=<tab>                                           (with the editor) opens the patient detail panel over
//                                                         the editor on that tab (overview, history, vitals, ...).

type StatusTab = 'all' | 'draft' | 'in-progress' | 'completed' | 'cancelled';

const TAB_STATUS: Record<StatusTab, ConsultationStatus | null> = {
  'all':         null,
  'draft':       'DRAFT',
  'in-progress': 'IN_PROGRESS',
  'completed':   'COMPLETED',
  'cancelled':   'CANCELLED',
};

function tabOf(value: string | undefined): StatusTab {
  return value && value in TAB_STATUS ? (value as StatusTab) : 'all';
}

/** The `panel` URL param: the tab of the patient panel opened over the editor, or null when it is closed. */
function panelParam(value: string | undefined): string | null {
  const tab = (value ?? '').trim();
  return tab && /^[a-z-]{1,30}$/.test(tab) ? tab : null;
}

/** A PAT id from the URL, or null. Anything that cannot be a system id is ignored rather than sent. */
function patientParam(value: string | undefined): string | null {
  const id = (value ?? '').trim();
  return id && id.length <= 20 ? id : null;
}

/** The list filters carried by the URL, as params for opening the list again. */
function listFilters(params: SectionParams): SectionParamsInput {
  const tab = tabOf(params.tab);
  return { patient: patientParam(params.patient) ?? undefined, tab: tab === 'all' ? undefined : tab };
}

interface InnerProps {
  params:    SectionParams;
  nav:       ConsultationNav;
  setParams: (params: SectionParamsInput) => void;
  /** Opens a section in place of the current history entry. */
  replace:   (section: string, params?: SectionParamsInput) => void;
  theme:     RoleTheme;
}

// ─── Back to the list ─────────────────────────────────────────────────────────
//
// The list opens the editor as a history entry of its own. The editor's Back button then goes back through
// history, so the in-app button and the browser's Back button leave the same trail. An editor reached any other
// way (a link, another section, a reload) replaces its own entry with the list instead of stacking a copy of it.
//
// Every history move mounts a fresh copy of the dashboard, so what the list opened is kept here, per editor URL.

/** Editors (see `editorKey`) whose history entry was added by the list. */
const openedFromList = new Set<string>();
/** When the list last opened an editor, and when the browser last moved through history (Back or Forward). */
let listOpenedAt = 0;
let historyMovedAt = 0;
let historyListening = false;
/** An editor that mounts this soon after either event belongs to it. */
const MOUNT_WINDOW_MS = 1500;

/** Identifies the editor a URL opens: one consultation, or the consultation of one appointment. */
function editorKey(params: SectionParams): string | null {
  if (params.id !== undefined) return `id:${params.id}`;
  if (params.appointmentId !== undefined) return `appointment:${params.appointmentId}`;
  return null;
}

function listenForHistoryMoves(): void {
  if (historyListening || Platform.OS !== 'web' || typeof window === 'undefined') return;
  historyListening = true;
  window.addEventListener('popstate', () => { historyMovedAt = Date.now(); });
}

/** Called by the list just before it opens an editor with a new history entry. */
function noteOpenedFromList(key: string): void {
  listenForHistoryMoves();
  openedFromList.add(key);
  listOpenedAt = Date.now();
}

/** Called when an editor mounts: true when the entry it is shown in was added by the list. Safe to call twice. */
function entryAddedByList(key: string | null): boolean {
  if (key === null || !openedFromList.has(key)) return false;
  const now = Date.now();
  // Just opened from the list, or returned to with Back or Forward: the entry before it is still the list.
  if (now - listOpenedAt < MOUNT_WINDOW_MS || now - historyMovedAt < MOUNT_WINDOW_MS) return true;
  // Opened some other way (a link, another section): this entry was not added by the list.
  openedFromList.delete(key);
  return false;
}

// ─── Editor ───────────────────────────────────────────────────────────────────

function ConsultationEditorScreen({ params, nav, setParams, replace, theme }: InnerProps) {
  const consultationId = idParam(params.id);
  const appointmentId = idParam(params.appointmentId);
  const target = useMemo<ConsultationTarget | null>(() => {
    if (consultationId !== null) return { consultationId };
    if (params.id === undefined && appointmentId !== null) return { appointmentId };
    return null;
  }, [consultationId, appointmentId, params.id]);

  const router = useRouter();
  const [fromList] = useState(() => entryAddedByList(editorKey(params)));
  // The editor can switch consultation in place (an appointment's consultation, a cancelled one reopened): the
  // entry is still the one the list added, so Back from it after a Forward still returns to the list.
  const key = editorKey(params);
  useEffect(() => {
    if (fromList && key !== null) openedFromList.add(key);
  }, [fromList, key]);

  // Back to the list this editor was opened from: the patient and status filters stay in the URL.
  const onBack = useCallback(() => {
    if (fromList && Platform.OS === 'web' && router.canGoBack()) {
      // The list's copy of the dashboard mounts again and its section takes keyboard focus.
      noteSectionNavigation();
      router.back();
      return;
    }
    replace('consultations', listFilters(params));
  }, [fromList, router, replace, params]);

  return (
    <Screen
      title="Consultation"
      subtitle="Record the visit, then complete it to add it to the patient's records."
      theme={theme}
      actions={<Button label="Back to consultations" icon="arrow-back" variant="secondary" onPress={onBack} />}
    >
      {target ? (
        <ConsultationEditor
          target={target}
          nav={nav}
          setParams={setParams}
          onBack={onBack}
          panel={panelParam(params.panel)}
          onPanelChange={tab => setParams({ panel: tab ?? undefined })}
        />
      ) : (
        <EmptyView
          icon="document-text-outline"
          title="This link does not point to a consultation"
          sub="Open the consultation from the list instead."
          action={{ label: 'Back to consultations', onPress: onBack, icon: 'arrow-back' }}
        />
      )}
    </Screen>
  );
}

// ─── List ─────────────────────────────────────────────────────────────────────

function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <Card style={styles.stat}>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </Card>
  );
}

function PatientChip({ name, onRemove }: { name: string; onRemove: () => void }) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={[styles.chip, { backgroundColor: accentBg, borderColor: accent }]}>
      <Text style={styles.chipText} numberOfLines={1}>Patient: {name}</Text>
      <IconButton label={`Remove patient filter, ${name}`} icon="close" size="sm" onPress={onRemove} />
    </View>
  );
}

function ConsultationListScreen({ params, nav, setParams, theme }: InnerProps) {
  const tab = tabOf(params.tab);
  const status = TAB_STATUS[tab];
  const patient = patientParam(params.patient);

  const list = useDoctorConsultations({ status, patientSystemId: patient, pageSize: 20 });
  const stats = useConsultationStats();
  const patients = useDoctorPatientOptions();
  const [pickerOpen, setPickerOpen] = useState(false);

  const refreshList = list.refetch;
  const refreshStats = stats.refetch;
  const refresh = useCallback(() => { refreshList(); refreshStats(); }, [refreshList, refreshStats]);

  // The editor is a history entry of its own, so Back (the browser's or the editor's) returns to this list with
  // its filters.
  const filters = useMemo<SectionParamsInput>(
    () => ({ patient: patient ?? undefined, tab: tab === 'all' ? undefined : tab }),
    [patient, tab],
  );
  const openConsultation = useCallback((consultationId: number) => {
    noteOpenedFromList(`id:${consultationId}`);
    nav.navigate('consultations', { ...filters, id: consultationId, mode: 'edit' });
  }, [nav, filters]);
  const openAppointment = useCallback((appointmentId: number) => {
    setPickerOpen(false);
    noteOpenedFromList(`appointment:${appointmentId}`);
    nav.navigate('consultations', { ...filters, appointmentId, mode: 'edit' });
  }, [nav, filters]);

  // The counts cover all of the doctor's consultations, so they are shown only while no patient is selected.
  const counts = patient ? null : stats.data;
  const tabs = useMemo<TabItem<StatusTab>[]>(() => [
    { key: 'all',         label: 'All' },
    { key: 'draft',       label: 'Draft', badge: counts?.draftCount },
    { key: 'in-progress', label: 'In progress', badge: counts?.inProgressCount },
    { key: 'completed',   label: 'Completed' },
    { key: 'cancelled',   label: 'Cancelled' },
  ], [counts]);

  const patientOptions = useMemo(
    () => patients.options.map(o => ({ value: o.patientSystemId, label: o.fullName || o.patientSystemId })),
    [patients.options],
  );
  const patientName = patient ? patients.nameOf(patient) ?? patientNameFallback(patient) : null;
  const filtered = status !== null || patient !== null;

  const header = (
    <View style={styles.filters}>
      <Tabs
        tabs={tabs}
        active={tab}
        onChange={key => setParams({ tab: key === 'all' ? undefined : key })}
        label="Consultation status"
      />
      {patient && patientName ? (
        <PatientChip name={patientName} onRemove={() => setParams({ patient: undefined })} />
      ) : patientOptions.length > 0 ? (
        <View style={styles.patientSelect}>
          <SelectField
            label="Patient"
            value=""
            onChange={value => setParams({ patient: value || undefined })}
            options={patientOptions}
            placeholder="All patients"
          />
        </View>
      ) : patients.error ? (
        <Button label="Retry loading the patient filter" icon="refresh-outline" variant="ghost" size="sm" onPress={patients.refetch} />
      ) : null}
    </View>
  );

  return (
    <Screen
      title="Consultations"
      subtitle="Your full consultation history and active sessions"
      theme={theme}
      actions={(
        <>
          <Button label="Refresh" icon="refresh-outline" variant="secondary" onPress={refresh} />
          <Button label="New consultation" icon="add" onPress={() => setPickerOpen(true)} />
        </>
      )}
    >
      {counts ? (
        <View style={styles.stats} role="group" aria-label="Your consultations by status">
          <StatTile label="Total" value={counts.totalConsultations} />
          <StatTile label="Completed" value={counts.completedCount} />
          <StatTile label="In progress" value={counts.inProgressCount} />
          <StatTile label="Draft" value={counts.draftCount} />
          <StatTile label="Cancelled" value={counts.cancelledCount} />
        </View>
      ) : !patient && stats.error && !stats.data ? (
        <View style={styles.statsError}>
          <ErrorView message={stats.error} onRetry={stats.refetch} compact />
        </View>
      ) : null}

      <PagedList
        state={list.paged}
        keyOf={c => c.consultation_id}
        renderItem={c => <ConsultationCard consultation={c} onOpen={openConsultation} />}
        header={header}
        label="Consultations"
        noAccessMessage="You don't have access to these consultations."
        empty={filtered
          ? {
              icon: 'document-text-outline',
              title: 'No consultations match these filters',
              sub: 'Choose another status or remove the patient filter.',
            }
          : {
              icon: 'document-text-outline',
              title: 'No consultations yet',
              sub: 'Open a consultation for an appointment once it has started.',
              action: { label: 'New consultation', onPress: () => setPickerOpen(true), icon: 'add' },
            }}
      />

      <NewConsultationPanel
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onSelect={openAppointment}
        patientSystemId={patient}
        nameOf={patients.nameOf}
      />
    </Screen>
  );
}

// ─── Section ──────────────────────────────────────────────────────────────────

export default function DoctorWebConsultation(props: Partial<SectionProps>) {
  // The registry may render this screen with or without SectionProps; the shell's navigation fills the gaps.
  const sectionNav = useSectionNav();
  const params = props.params ?? sectionNav.params;
  const navigate = props.navigate ?? sectionNav.navigate;
  const theme = props.theme ?? ROLE_THEME.doctor;
  const { hasSection, setParams, replace } = sectionNav;
  const nav = useMemo<ConsultationNav>(() => ({ navigate, hasSection }), [navigate, hasSection]);

  const editing = params.id !== undefined || params.appointmentId !== undefined;
  return editing
    ? <ConsultationEditorScreen params={params} nav={nav} setParams={setParams} replace={replace} theme={theme} />
    : <ConsultationListScreen params={params} nav={nav} setParams={setParams} replace={replace} theme={theme} />;
}

const styles = StyleSheet.create({
  stats:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  stat:      { flexGrow: 1, flexShrink: 1, flexBasis: 120, gap: 2, padding: space.md },
  statValue: { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.sm, color: colors.textMuted },
  statsError: { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md },

  filters:       { gap: space.md, minWidth: 0 },
  patientSelect: { maxWidth: 360, width: '100%' },
  chip: {
    flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: space.xs, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2, borderWidth: 1, borderRadius: radius.pill,
  },
  chipText: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
});
