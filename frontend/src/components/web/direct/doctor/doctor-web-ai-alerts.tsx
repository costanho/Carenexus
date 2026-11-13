import { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  AlertCard, DetectionPanel, RunAssessmentDialog, ScreeningNotice, settleReviewedDetection, useAlertSourceNav,
} from '@/components/web/ai';
import {
  Banner, Button, colors, fontSize, Icon, IconButton, PagedList, radius, Screen, SelectField, type SelectOption, space,
} from '@/components/web/ui';
import { refreshUnacknowledgedAlertCount, useAiAlerts, useUnacknowledgedAlertCount } from '@/hooks/use-ai-alerts';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  ALERT_DISCLAIMER, ALERT_PRIORITIES, ALERT_TYPES, alertTypeLabel, isAlertPriority, isAlertType, priorityWord,
} from '@/lib/ai/labels';
import type { AiAlertFilters, AiAlertPriority, AiAlertType } from '@/lib/ai/types';
import type { SectionProps } from '@/lib/nav/sections';

// Clinical Alerts: the doctor's inbox of automated-screening alerts (`/dashboard/doctor?section=ai-alerts`).
//
// URL params (all optional, so a refresh, Back and notification links keep the view):
//   status     'acknowledged' | 'all'; absent = unacknowledged alerts
//   priority   LOW | MEDIUM | HIGH | CRITICAL
//   type       one of the seven alert types
//   patient    PAT id: only this patient's alerts (set by notification links and the patient detail)
//   id         alert id from a notification link: that row is marked when it is among the loaded rows
//   detection  detection id whose screening-result panel is open

type AckFilter = 'open' | 'acknowledged' | 'all';

const STATUS_OPTIONS: readonly SelectOption<AckFilter>[] = [
  { value: 'open',         label: 'Unacknowledged' },
  { value: 'acknowledged', label: 'Acknowledged' },
  { value: 'all',          label: 'All alerts' },
];

const PRIORITY_OPTIONS: readonly SelectOption<AiAlertPriority>[] =
  ALERT_PRIORITIES.map(value => ({ value, label: priorityWord(value) }));

const TYPE_OPTIONS: readonly SelectOption<AiAlertType>[] =
  ALERT_TYPES.map(value => ({ value, label: alertTypeLabel(value) }));

const PATIENT_ID = /^PAT-\d{5,}$/;

function positiveInt(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value.trim())) return null;
  const parsed = Number(value.trim());
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function upper(value: string | undefined): string | undefined {
  const text = value?.trim().toUpperCase();
  return text || undefined;
}

export default function DoctorWebAiAlerts(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams } = nav;
  const params = props.params ?? nav.params;
  const role = props.role ?? nav.role;
  const isDoctor = role === 'doctor';

  // ── Filters, read from the URL ──
  const status: AckFilter = params.status === 'acknowledged' || params.status === 'all' ? params.status : 'open';
  const priorityParam = upper(params.priority);
  const priority = isAlertPriority(priorityParam) ? priorityParam : undefined;
  const typeParam = upper(params.type);
  const type = isAlertType(typeParam) ? typeParam : undefined;
  const patientParam = params.patient?.trim();
  const patient = patientParam && PATIENT_ID.test(patientParam) ? patientParam : undefined;
  const linkedAlertId = positiveInt(params.id);
  const detectionId = positiveInt(params.detection);

  const filters = useMemo<AiAlertFilters>(() => ({
    acknowledged:    status === 'all' ? undefined : status === 'acknowledged',
    priority,
    type,
    patientSystemId: patient,
  }), [status, priority, type, patient]);

  const alerts = useAiAlerts(filters);
  // The count endpoint is for doctors and administrators only.
  const countEnabled = isDoctor || role === 'admin';
  const count = useUnacknowledgedAlertCount(countEnabled);
  const { canOpenSource, openSource } = useAlertSourceNav();
  const [running, setRunning] = useState(false);

  // Keep the badge in step with the list the doctor is looking at (an answer of the last few seconds will do).
  const settled = !alerts.loading && !alerts.refreshing;
  useEffect(() => {
    if (settled && countEnabled) void refreshUnacknowledgedAlertCount({ maxAgeMs: 5_000 });
  }, [settled, countEnabled]);

  // The patient filter arrives as an id; its name comes from the rows and is kept while the list is empty.
  const [patientNames, setPatientNames] = useState<Record<string, string>>({});
  const rowPatientName = patient
    ? alerts.items.find(alert => alert.patientSystemId === patient)?.patientName?.trim() || null
    : null;
  useEffect(() => {
    if (!patient || !rowPatientName) return;
    setPatientNames(prev => (prev[patient] === rowPatientName ? prev : { ...prev, [patient]: rowPatientName }));
  }, [patient, rowPatientName]);
  const patientName = patient ? rowPatientName ?? patientNames[patient] ?? null : null;

  const filtered = status !== 'open' || !!priority || !!type || !!patient;
  const clearFilters = () => setParams({ status: undefined, priority: undefined, type: undefined, patient: undefined });

  // In the unfiltered view the list's total and the polled count measure the same thing, so a higher count
  // means alerts were raised since the list was loaded.
  const newerAlerts = !filtered && settled && !alerts.error && count.loaded && count.count > alerts.total;

  const subtitle = count.loaded
    ? `${count.count} unacknowledged ${count.count === 1 ? 'alert' : 'alerts'} ${isDoctor ? 'for your patients' : 'across all patients'}`
    : 'Alerts raised by automated screening of results, readings and medication';

  const refresh = () => {
    alerts.refetch();
    count.refetch();
  };

  return (
    <Screen
      title="Clinical Alerts"
      subtitle={subtitle}
      theme={props.theme}
      role={props.theme ? undefined : 'doctor'}
      actions={(
        <>
          <Button label="Refresh" icon="refresh-outline" variant="secondary" onPress={refresh} loading={alerts.refreshing} />
          {isDoctor ? <Button label="Run assessment" icon="play-outline" onPress={() => setRunning(true)} /> : null}
        </>
      )}
    >
      <PagedList
        state={alerts}
        label="Clinical alerts"
        keyOf={alert => alert.alertId}
        header={(
          <View style={styles.header}>
            <ScreeningNotice text={alerts.items[0]?.disclaimer || ALERT_DISCLAIMER} />

            <View style={styles.filters} role="group" aria-label="Filter alerts">
              <View style={styles.filter}>
                <SelectField<AckFilter>
                  label="Status"
                  value={status}
                  onChange={value => setParams({ status: value === 'open' || !value ? undefined : value })}
                  options={STATUS_OPTIONS}
                />
              </View>
              <View style={styles.filter}>
                <SelectField<AiAlertPriority>
                  label="Priority"
                  value={priority ?? ''}
                  onChange={value => setParams({ priority: value || undefined })}
                  options={PRIORITY_OPTIONS}
                  placeholder="All priorities"
                  allowEmpty
                />
              </View>
              <View style={styles.filter}>
                <SelectField<AiAlertType>
                  label="Type"
                  value={type ?? ''}
                  onChange={value => setParams({ type: value || undefined })}
                  options={TYPE_OPTIONS}
                  placeholder="All types"
                  allowEmpty
                />
              </View>
            </View>

            {patient ? (
              <View style={styles.chip}>
                <Icon name="person-outline" size={14} color={colors.textMuted} />
                <Text style={styles.chipText}>
                  {patientName ? `Patient: ${patientName}` : 'Showing one patient'}
                </Text>
                <IconButton
                  label={patientName ? `Remove patient filter ${patientName}` : 'Remove patient filter'}
                  icon="close"
                  size="sm"
                  onPress={() => setParams({ patient: undefined })}
                />
              </View>
            ) : null}

            {newerAlerts ? (
              <Banner
                tone="info"
                text="New alerts have been raised since this list was loaded."
                action={{ label: 'Refresh', onPress: refresh }}
              />
            ) : null}
            {alerts.acknowledgeError ? (
              <Banner tone="danger" text={alerts.acknowledgeError} onDismiss={alerts.clearAcknowledgeError} />
            ) : null}
          </View>
        )}
        empty={filtered
          ? {
              icon:   'funnel-outline',
              title:  'No alerts match these filters',
              sub:    'Change or clear the filters to see other alerts.',
              action: { label: 'Clear filters', onPress: clearFilters, icon: 'close-outline' },
            }
          : {
              icon:  'shield-checkmark-outline',
              title: 'No unacknowledged alerts',
              sub:   'New alerts appear here when screening flags a result, a reading, missed doses or a follow-up that is due.',
            }}
        noAccessMessage="Clinical alerts are available to doctors and administrators."
        renderItem={alert => (
          <AlertCard
            alert={alert}
            highlighted={linkedAlertId !== null && alert.alertId === linkedAlertId}
            acknowledging={alerts.isAcknowledging(alert.alertId)}
            onAcknowledge={row => { void alerts.acknowledge(row); }}
            onOpenDetection={row => setParams({ detection: row.detectionId ?? undefined })}
            onOpenSource={openSource}
            canOpenSource={canOpenSource}
          />
        )}
      />

      <DetectionPanel
        detectionId={detectionId}
        onClose={() => setParams({ detection: undefined })}
        onReviewed={detection => { void settleReviewedDetection(alerts, detection); }}
        onStale={() => alerts.refetch()}
      />
      {isDoctor ? (
        <RunAssessmentDialog
          open={running}
          onClose={() => setRunning(false)}
          patientSystemId={patient ?? null}
          patientName={patientName}
          onDetectionCreated={() => alerts.refetch()}
          onDetectionReviewed={detection => { void settleReviewedDetection(alerts, detection); }}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header:  { gap: space.md, minWidth: 0 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md, minWidth: 0 },
  // Three across on a desktop, stacked on a phone.
  filter:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, maxWidth: 320 },
  chip: {
    flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: space.xs, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.pill, backgroundColor: colors.surface,
  },
  chipText: { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
});
