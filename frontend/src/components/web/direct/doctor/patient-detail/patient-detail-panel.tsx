import { type ReactNode, useCallback, useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, decorative, ErrorView, fontSize, LoadingView, NoAccessView, SidePanel, space, StatusBadge,
  statusTone, Tabs, UiThemeProvider, useUiTheme,
} from '@/components/web/ui';
import { useDoctorMessagePatient } from '@/hooks/use-doctor-message-patient';
import { type HealthStatusResult, useDoctorPatientDetail } from '@/hooks/use-doctor-patient-detail';
import { type DoctorPatientDto, patientFullName } from '@/hooks/use-doctor-patients';
import { useSectionNav } from '@/hooks/use-section-nav';
import { ageText, genderLabel, healthStatusLabel, initialsOf } from './format';
import { PatientOverview } from './patient-overview';

/** Key of the built-in tab. It is the tab shown when the `tab` URL param is missing or names no tab. */
export const PATIENT_OVERVIEW_TAB = 'overview';

/** A tab added to the panel by the screen that mounts it (care team, vitals, alerts, documents, ...). */
export interface PatientDetailTab {
  /** Value of the `tab` URL param that selects this tab. */
  key:    string;
  label:  string;
  /** A count shown after the label (open alerts, pending items). */
  badge?: number;
  /** Renders the tab for the open patient (its id and loaded detail). Called only while the tab is selected. */
  render: (patientSystemId: string, patient: DoctorPatientDto) => ReactNode;
}

/** What the panel hands to its `actions`. */
export interface PatientPanelHelpers {
  /** Reloads the patient's detail (Overview counts and recent appointments). */
  refetch: () => void;
}

export interface PatientDetailPanelProps {
  /** PAT id of the patient to show. Null, undefined or blank keeps the panel closed. */
  patientSystemId: string | null | undefined;
  /** Close button, Escape or a click outside the panel. The caller clears its selection (the `patient` URL param). */
  onClose: () => void;
  /** Tabs shown after Overview. */
  extraTabs?: readonly PatientDetailTab[];
  /**
   * The selected tab. Leave undefined to let the panel read and write the `tab` URL param of the current
   * section; pass a value (with `onTabChange`) to keep the tab somewhere else.
   */
  tab?: string | null;
  onTabChange?: (key: string) => void;
  /** Opens Messages on a conversation. Default: the dashboard's own `openThread`. */
  onOpenThread?: (threadId: number) => void;
  /** Called after a health-status save. `result.changed` tells whether the status itself changed (lists refetch then). */
  onHealthStatusChanged?: (result: HealthStatusResult) => void;
  /**
   * Extra buttons for the action row under the patient's summary (Book appointment, Prescribe, ...). `refetch`
   * reloads the panel's detail, for example after an appointment was booked from one of these buttons.
   */
  actions?: (patient: DoctorPatientDto, helpers: PatientPanelHelpers) => ReactNode;
  /** Panel title until the patient has loaded. Default "Patient details". */
  fallbackTitle?: string;
}

/**
 * Side panel with everything a doctor needs about one of their patients. The built-in Overview tab shows the
 * health-status control, allergies and chronic conditions, the appointments with this doctor, demographics and
 * emergency contacts; `extraTabs` adds more. It loads GET /api/doctors/patients/{id}/detail itself, so it can be
 * opened from any doctor screen with just the PAT id.
 *
 * ```tsx
 * <PatientDetailPanel
 *   patientSystemId={params.patient}
 *   onClose={() => setParams({ patient: undefined, tab: undefined })}
 *   extraTabs={[{ key: 'care-team', label: 'Care team', render: id => <CareTeamPanel patientSystemId={id} /> }]}
 * />
 * ```
 */
export function PatientDetailPanel({
  patientSystemId, onClose, extraTabs, tab, onTabChange, onOpenThread, onHealthStatusChanged, actions,
  fallbackTitle = 'Patient details',
}: PatientDetailPanelProps) {
  const { params, setParams, navigate, hasSection, openThread } = useSectionNav();
  const id = (patientSystemId ?? '').trim() || null;
  const detail = useDoctorPatientDetail(id);
  const { patient, applyHealthStatus } = detail;

  const tabs = useMemo(
    () => [
      { key: PATIENT_OVERVIEW_TAB, label: 'Overview' },
      ...(extraTabs ?? []).filter(t => t.key !== PATIENT_OVERVIEW_TAB).map(t => ({ key: t.key, label: t.label, badge: t.badge })),
    ],
    [extraTabs],
  );
  const requested = tab !== undefined ? tab : params.tab;
  const activeKey = tabs.some(t => t.key === requested) ? (requested as string) : PATIENT_OVERVIEW_TAB;
  const activeExtra = activeKey === PATIENT_OVERVIEW_TAB ? undefined : extraTabs?.find(t => t.key === activeKey);

  const changeTab = useCallback((key: string) => {
    if (onTabChange) onTabChange(key);
    // `id` in the URL points at a row of the tab a link opened; it does not carry over to another tab.
    else setParams({ tab: key === PATIENT_OVERVIEW_TAB ? undefined : key, id: undefined });
  }, [onTabChange, setParams]);

  const healthStatusUpdated = useCallback((result: HealthStatusResult) => {
    applyHealthStatus(result);
    onHealthStatusChanged?.(result);
  }, [applyHealthStatus, onHealthStatusChanged]);

  const viewAppointments = useMemo(
    () => (id && hasSection('appointments') ? () => navigate('appointments', { patient: id }) : undefined),
    [id, hasSection, navigate],
  );
  const refer = useMemo(
    () => (id && hasSection('referrals') ? () => navigate('referrals', { mode: 'new', patient: id }) : undefined),
    [id, hasSection, navigate],
  );

  let body: ReactNode = null;
  if (id === null) {
    body = null;
  } else if (detail.loading) {
    body = <LoadingView label="Loading patient…" />;
  } else if (!patient) {
    body = detail.errorStatus === 403
      ? (
        <NoAccessView
          message={detail.error ?? "You don't have access to this patient."}
          sub="You can open patients you care for through a care team, an appointment or a consultation."
        />
      )
      : <ErrorView message={detail.error ?? 'Failed to load this patient.'} onRetry={detail.refetch} />;
  } else {
    body = (
      <>
        {/* A reload that failed keeps the patient on screen and says so. */}
        {detail.error ? <Banner tone="danger" text={detail.error} action={{ label: 'Retry', onPress: detail.refetch }} /> : null}
        <PatientSummary
          patient={patient}
          onOpenThread={onOpenThread ?? openThread}
          onViewAppointments={viewAppointments}
          onRefer={refer}
          extraActions={actions?.(patient, { refetch: detail.refetch })}
        />
        {tabs.length > 1 ? <Tabs tabs={tabs} active={activeKey} onChange={changeTab} label="Patient information" /> : null}
        {tabs.length > 1 ? (
          <View role="tabpanel" aria-label={tabs.find(t => t.key === activeKey)?.label} style={styles.tabPanel}>
            {activeExtra
              ? activeExtra.render(patient.patientSystemId, patient)
              : <PatientOverview patient={patient} onHealthStatusUpdated={healthStatusUpdated} onViewAppointments={viewAppointments} />}
          </View>
        ) : (
          <PatientOverview patient={patient} onHealthStatusUpdated={healthStatusUpdated} onViewAppointments={viewAppointments} />
        )}
      </>
    );
  }

  return (
    // The panel belongs to the doctor dashboard wherever it is mounted, so it always draws with the doctor accent.
    <UiThemeProvider role="doctor">
      <SidePanel
        open={id !== null}
        title={patient ? patientFullName(patient) : fallbackTitle}
        onClose={() => onClose()}
        size="lg"
      >
        {body}
      </SidePanel>
    </UiThemeProvider>
  );
}

// ─── Summary and actions above the tabs ───────────────────────────────────────

function PatientSummary({ patient, onOpenThread, onViewAppointments, onRefer, extraActions }: {
  patient:             DoctorPatientDto;
  onOpenThread:        (threadId: number) => void;
  onViewAppointments?: () => void;
  onRefer?:            () => void;
  extraActions?:       ReactNode;
}) {
  const { accent } = useUiTheme();
  const name = patientFullName(patient);
  const { messagePatient, pendingPatient } = useDoctorMessagePatient(onOpenThread);
  const facts = [ageText(patient.dateOfBirth), genderLabel(patient.gender), patient.bloodType ? `Blood type ${patient.bloodType}` : null]
    .filter(Boolean)
    .join(' · ');

  return (
    <View style={styles.summary}>
      <View style={styles.identity}>
        <View style={[styles.avatar, { backgroundColor: accent }]} {...decorative}>
          <Text style={styles.avatarText}>{initialsOf(name)}</Text>
        </View>
        <View style={styles.identityText}>
          <Text style={styles.patientId} selectable>{patient.patientSystemId}</Text>
          {facts ? <Text style={styles.facts}>{facts}</Text> : null}
          <StatusBadge label={healthStatusLabel(patient.healthStatus)} tone={statusTone('health', patient.healthStatus)} />
        </View>
      </View>
      <View style={styles.actions}>
        <Button
          label="Message"
          icon="chatbubble-ellipses-outline"
          variant="secondary"
          size="sm"
          onPress={() => { void messagePatient(patient.patientSystemId); }}
          loading={pendingPatient === patient.patientSystemId}
          accessibilityLabel={`Message ${name}`}
        />
        {onViewAppointments ? (
          <Button
            label="Appointments"
            icon="calendar-outline"
            variant="secondary"
            size="sm"
            onPress={onViewAppointments}
            accessibilityLabel={`Appointments with ${name}`}
          />
        ) : null}
        {onRefer ? (
          <Button
            label="Refer"
            icon="arrow-redo-outline"
            variant="secondary"
            size="sm"
            onPress={onRefer}
            accessibilityLabel={`Refer ${name} to a specialist`}
          />
        ) : null}
        {extraActions}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  summary:      { gap: space.md, minWidth: 0 },
  identity:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.md, minWidth: 0 },
  avatar:       { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:   { color: '#FFFFFF', fontSize: fontSize.lg, fontWeight: '700' },
  identityText: { flex: 1, minWidth: 0, gap: space.xs },
  patientId:    { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
  facts:        { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  tabPanel:     { minWidth: 0 },
});
