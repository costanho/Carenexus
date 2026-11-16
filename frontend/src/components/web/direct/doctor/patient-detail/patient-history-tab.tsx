import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, colors, ErrorView, fontSize, LoadingView, NoAccessView, radius, space, StatusBadge,
  type StatusTone,
} from '@/components/web/ui';
import { listConsultations } from '@/hooks/use-doctor-consultations';
import { listImagingResults } from '@/hooks/use-doctor-imaging-results';
import { listLabResults } from '@/hooks/use-doctor-lab-results';
import { listMedicalRecords } from '@/hooks/use-doctor-medical-records';
import { toPrescriptionDto } from '@/hooks/use-doctor-prescriptions';
import { useResource } from '@/hooks/use-resource';
import { useSectionNav } from '@/hooks/use-section-nav';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type { SectionParamsInput } from '@/lib/nav/sections';
import { formatClinicDate } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import { consultationDateLabel, consultationStatusBadge } from '../consultation/consultation-format';
import { statusBadge as prescriptionStatusBadge } from '../prescription/labels';
import { recordTypeMeta } from '../records/record-meta';
import { imagingTitle } from '../results/imaging-result-views';
import { resultStatusLabel } from '../results/shared';
import { PanelCard } from './panel-card';

// Patient detail > History: how many consultations, prescriptions, lab results, imaging results and medical
// records the patient has, with the newest three of each. Every block reads the canonical paged list with only
// `patientSystemId` (size 3), so it covers the patient's history from every doctor (the server checks the care
// relationship) and its `totalElements` is the count, so every count says "from all doctors". The button opens
// the list section filtered to the patient (CONTRACTS 12.1). The lab and imaging lists show every doctor's
// results of a patient ("View all"); the consultation, prescription and medical-record lists show only the
// signed-in doctor's own rows, so their button reads "View yours" and the count difference is expected. A row
// opens that item in its section. Each block is a level-3 card inside the side panel (level 2); row titles are
// level 4.

const LATEST = 3;

interface HistoryRow {
  key:    string;
  title:  string;
  detail: string;
  badge:  { label: string; tone: StatusTone } | null;
  /** The section and params that open this item; null when the section cannot open a single item. */
  open:   { section: string; params: SectionParamsInput } | null;
}

interface HistoryPage { total: number; rows: HistoryRow[] }

function toHistory<T>(page: PageResponse<T>, map: (row: T) => HistoryRow): HistoryPage {
  return { total: page.totalElements, rows: page.content.slice(0, LATEST).map(map) };
}

function resultTone(status: string | null | undefined): StatusTone {
  switch (status) {
    case 'CRITICAL':        return 'critical';
    case 'REQUIRES_REVIEW': return 'warning';
    case 'COMPLETED':       return 'success';
    case 'CANCELLED':       return 'neutral';
    default:                return 'info';
  }
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

// ─── Loaders (GET /api/<list>?patientSystemId=<PAT>&size=3) ──────────────────

function loadConsultations(patientSystemId: string): Promise<HistoryPage> {
  // No doctor filter (an empty value is not sent): the patient's consultations by any doctor.
  return listConsultations({ doctorSystemId: '', patientSystemId }, 0, LATEST).then(page => toHistory(page, c => {
    const badge = consultationStatusBadge(c.status);
    return {
      key:    `consultation-${c.consultationId}`,
      title:  c.diagnosis?.trim() || c.chiefComplaint?.trim() || 'Consultation',
      detail: consultationDateLabel(c),
      badge:  { label: badge.label, tone: badge.tone },
      open:   { section: 'consultations', params: { id: c.consultationId, mode: 'edit' } },
    };
  }));
}

function loadPrescriptions(patientSystemId: string): Promise<HistoryPage> {
  return fetchPage<unknown>('/api/prescriptions', 0, LATEST, { patientSystemId }).then(page => toHistory(page, raw => {
    const p = toPrescriptionDto(raw);
    const badge = prescriptionStatusBadge(p.effectiveStatus);
    return {
      key:    `prescription-${p.prescriptionId}`,
      title:  [p.medicationName, p.dosage].filter(Boolean).join(' '),
      detail: [`Prescribed ${formatDateOnly(p.prescribedDate)}`, p.frequency, p.doctorName].filter(Boolean).join(' · '),
      badge:  { label: badge.label, tone: badge.tone },
      open:   { section: 'prescriptions', params: { patient: p.patientSystemId, id: p.prescriptionId } },
    };
  }));
}

function loadLabResults(patientSystemId: string): Promise<HistoryPage> {
  return listLabResults({ patientSystemId, size: LATEST }).then(page => toHistory(page, r => ({
    key:    `lab-${r.resultId}`,
    title:  r.testName,
    detail: [`Tested ${formatDateOnly(r.testDate)}`, r.doctorName].filter(Boolean).join(' · '),
    badge:  { label: r.isCritical && r.status !== 'CRITICAL' ? `${resultStatusLabel(r.status)}, critical` : resultStatusLabel(r.status), tone: r.isCritical ? 'critical' : resultTone(r.status) },
    open:   { section: 'labs', params: { patient: r.patientSystemId, id: r.resultId } },
  })));
}

function loadImagingResults(patientSystemId: string): Promise<HistoryPage> {
  return listImagingResults({ patientSystemId, size: LATEST }).then(page => toHistory(page, r => ({
    key:    `imaging-${r.imagingId}`,
    title:  imagingTitle(r),
    detail: [`Imaged ${formatDateOnly(r.imageDate)}`, r.doctorName].filter(Boolean).join(' · '),
    badge:  { label: resultStatusLabel(r.status), tone: resultTone(r.status) },
    open:   { section: 'imaging', params: { patient: r.patientSystemId, id: r.imagingId } },
  })));
}

function loadMedicalRecords(patientSystemId: string): Promise<HistoryPage> {
  return listMedicalRecords({ patientSystemId }, 0, LATEST).then(page => toHistory(page, r => ({
    key:    `record-${r.recordId}`,
    title:  r.title,
    detail: [formatClinicDate(r.createdAt, ''), r.doctorName].filter(Boolean).join(' · '),
    badge:  { label: recordTypeMeta(r.recordType).label, tone: 'neutral' },
    open:   { section: 'medical-records', params: { patient: r.patientSystemId, id: r.recordId } },
  })));
}

// ─── Blocks ───────────────────────────────────────────────────────────────────

interface BlockProps {
  title:           string;
  /** Section key of the full list; "View all" and the row buttons appear only when the dashboard has it. */
  section:         string;
  noun:            [one: string, many: string];
  /**
   * What the target list shows for a patient: 'all' = every doctor's items (labs, imaging), 'own' = only the
   * signed-in doctor's items (consultations, prescriptions, medical records).
   */
  listScope:       'all' | 'own';
  emptyText:       string;
  patientSystemId: string;
  patientName:     string;
  load:            (patientSystemId: string) => Promise<HistoryPage>;
}

function HistoryBlock({ title, section, noun, listScope, emptyText, patientSystemId, patientName, load }: BlockProps) {
  const { navigate, hasSection } = useSectionNav();
  const state = useResource(() => load(patientSystemId), [patientSystemId], {
    errorMessage: `${title} could not be loaded.`,
  });
  const linked = hasSection(section);
  const data = state.data;

  let body;
  if (state.loading && !data) {
    body = <LoadingView label={`Loading ${title.toLowerCase()}…`} compact />;
  } else if (!data) {
    body = state.errorStatus === 403
      ? <NoAccessView message={`${title} of this patient are not available to you.`} compact />
      : <ErrorView message={state.error ?? `${title} could not be loaded.`} onRetry={state.refetch} compact />;
  } else if (data.rows.length === 0) {
    body = <Text style={styles.empty}>{emptyText}</Text>;
  } else {
    body = (
      <View role="list" aria-label={`Latest ${title.toLowerCase()} of ${patientName}`} style={styles.rows}>
        {data.rows.map(row => (
          <View key={row.key} role="listitem" style={styles.row}>
            <View style={styles.rowText}>
              <Text style={styles.rowTitle} accessibilityRole="header" {...ariaLevel(4)} numberOfLines={2}>{row.title}</Text>
              {row.detail ? <Text style={styles.rowDetail}>{row.detail}</Text> : null}
            </View>
            <View style={styles.rowSide}>
              {row.badge ? <StatusBadge label={row.badge.label} tone={row.badge.tone} /> : null}
              {row.open && hasSection(row.open.section) ? (
                <Button
                  label="Open"
                  variant="ghost"
                  size="sm"
                  onPress={() => {
                    const open = row.open;
                    if (!open) return;
                    navigate(open.section, open.params);
                  }}
                  accessibilityLabel={`Open ${row.title}`}
                />
              ) : null}
            </View>
          </View>
        ))}
      </View>
    );
  }

  const total = data ? plural(data.total, noun[0], noun[1]) : undefined;
  const own = listScope === 'own';

  return (
    <PanelCard
      title={title}
      subtitle={total ? `${total} in total, from all doctors` : undefined}
      action={linked ? (
        <Button
          label={own ? 'View yours' : 'View all'}
          variant="secondary"
          size="sm"
          icon="arrow-forward"
          onPress={() => navigate(section, { patient: patientSystemId })}
          accessibilityLabel={own
            ? `View yours: the ${title.toLowerCase()} you wrote for ${patientName}`
            : `View all ${title.toLowerCase()} of ${patientName}`}
        />
      ) : undefined}
    >
      {state.error && data ? (
        <Text style={styles.stale}>{`Showing the last loaded list. ${state.error}`}</Text>
      ) : null}
      {body}
    </PanelCard>
  );
}

export interface PatientHistoryTabProps {
  patientSystemId: string;
  patientName:     string;
}

/** The History tab of the doctor's patient detail panel. */
export function PatientHistoryTab({ patientSystemId, patientName }: PatientHistoryTabProps) {
  const common = { patientSystemId, patientName };
  return (
    <View style={styles.tab}>
      <Text style={styles.intro}>
        {`The newest items in ${patientName}'s record from every doctor in their care. "View all" opens a list with `
          + 'every doctor\'s items; "View yours" opens your own list for this patient, which shows only the items you wrote.'}
      </Text>
      <HistoryBlock {...common} title="Consultations" section="consultations"
        noun={['consultation', 'consultations']} listScope="own"
        emptyText="No consultations yet." load={loadConsultations} />
      <HistoryBlock {...common} title="Prescriptions" section="prescriptions"
        noun={['prescription', 'prescriptions']} listScope="own"
        emptyText="No prescriptions yet." load={loadPrescriptions} />
      <HistoryBlock {...common} title="Lab results" section="labs"
        noun={['lab result', 'lab results']} listScope="all"
        emptyText="No lab results yet." load={loadLabResults} />
      <HistoryBlock {...common} title="Imaging results" section="imaging"
        noun={['imaging result', 'imaging results']} listScope="all"
        emptyText="No imaging results yet." load={loadImagingResults} />
      <HistoryBlock {...common} title="Medical records" section="medical-records"
        noun={['medical record', 'medical records']} listScope="own"
        emptyText="No medical records yet." load={loadMedicalRecords} />
    </View>
  );
}

const styles = StyleSheet.create({
  tab:       { gap: space.lg, minWidth: 0 },
  intro:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rows:      { gap: space.sm },
  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm,
    paddingVertical: space.sm, paddingHorizontal: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, minWidth: 0,
  },
  rowText:   { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:  { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  rowDetail: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowSide:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  empty:     { fontSize: fontSize.sm, color: colors.textMuted },
  stale:     { fontSize: fontSize.sm, color: colors.textMuted },
});
