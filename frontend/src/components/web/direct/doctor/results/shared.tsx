import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, ErrorView, fontSize, Icon, IconButton, type IoniconsName, LoadingView, radius,
  ROLE_THEME, type RoleTheme, SelectField, type SelectOption, SidePanel, space, StatusBadge, statusLabel,
  type StatusTone, statusTone, STATUS_TONES,
} from '@/components/web/ui';
import { useSectionNav } from '@/hooks/use-section-nav';
import { apiErrorMessage } from '@/lib/api/errors';
import { useDoctorName } from '@/lib/doctors/names';
import type { SectionParams, SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';
import {
  IMAGING_TYPE_LABELS, type ImagingType, isResultStatus, LAB_TEST_TYPE_LABELS, type LabTestType,
  RESULT_STATUS_LABELS, RESULT_STATUSES, type ResultStatus,
} from '@/lib/validation/results';

// Pieces shared by the doctor Labs & Results and Imaging sections: URL state, filters, the detail panel frame,
// badges and the attachment slot types.

// ─── Attachment slots (documents upload and download are wired by the dashboard integration) ─────────────────

export type ResultKind = 'LAB' | 'IMAGING';

/** Document type an attachment of each kind of result must be uploaded with (the server rejects any other). */
export const RESULT_DOCUMENT_TYPE: Record<ResultKind, 'LAB_REPORT' | 'IMAGING'> = {
  LAB:     'LAB_REPORT',
  IMAGING: 'IMAGING',
};

export interface ResultAttachmentSlotProps {
  kind:            ResultKind;
  /** The `documentType` the upload must use: LAB_REPORT for lab results, IMAGING for imaging results. */
  documentType:    'LAB_REPORT' | 'IMAGING';
  /** The patient the result is for; null until one is chosen (an upload needs it). */
  patientSystemId: string | null;
  /** The document attached in this form, or null. */
  documentId:      number | null;
  /**
   * Call with the id of the uploaded document. Null takes the new upload back; a document that was already
   * attached to a saved result cannot be detached, only replaced.
   */
  onChange:        (documentId: number | null) => void;
  disabled:        boolean;
}

/**
 * Renders the documents upload control inside the record form. Upload the file first (POST /api/documents with
 * `documentType` and the patient), then hand the document id to `onChange`; the form sends it as `documentId`.
 */
export type ResultAttachmentSlot = (props: ResultAttachmentSlotProps) => ReactNode;

export interface ResultAttachmentViewProps {
  kind:            ResultKind;
  documentId:      number;
  patientSystemId: string;
}

/**
 * Renders the detail panel's whole attachment block, its "Attachment" heading (use `DetailBlock`) included, with
 * the control that opens or downloads the result's document (GET /api/documents/{id}/download). Render nothing
 * when the document is gone, so no empty heading is left behind.
 */
export type ResultAttachmentViewSlot = (props: ResultAttachmentViewProps) => ReactNode;

/** Props of both sections: `SectionProps` (every prop optional) plus the attachment slots. */
export interface ResultSectionProps extends Partial<SectionProps> {
  attachmentSlot?:     ResultAttachmentSlot;
  attachmentViewSlot?: ResultAttachmentViewSlot;
}

// ─── URL state ────────────────────────────────────────────────────────────────

export type ResultTab = 'all' | 'review';

export interface ResultSectionState {
  params:    SectionParams;
  /** Updates params of the open section in place (no history entry). Undefined removes a param. */
  setParams: (params: SectionParamsInput) => void;
  theme:     RoleTheme;
  tab:       ResultTab;
  /** `patient`: the PAT id the lists are filtered to, or null. */
  patientSystemId: string | null;
  status:    ResultStatus | null;
  critical:  boolean | null;
  /** `id`: the result opened in the detail panel, or null. */
  openId:    number | null;
  /** `mode=new`: the record form is open. */
  creating:  boolean;
  /** `consultationId`: the consultation a new result is prefilled with, or null. */
  consultationId: number | null;
}

export function positiveIntParam(value: string | undefined): number | null {
  const text = (value ?? '').trim();
  if (!/^[1-9]\d{0,9}$/.test(text)) return null;
  return Number(text);
}

/**
 * Reads the section's state from the URL: `tab`, `patient`, `status`, `type`, `critical`, `id`, `mode` and
 * `consultationId`. The screen works with the props the shell passes and, when it is rendered without them,
 * with the dashboard's own navigation.
 */
export function useResultSection(props: Partial<SectionProps>): ResultSectionState {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const status = (params.status ?? '').trim().toUpperCase();
  const critical = (params.critical ?? '').trim().toLowerCase();
  return {
    params,
    setParams: nav.setParams,
    theme:     props.theme ?? ROLE_THEME.doctor,
    tab:       params.tab === 'review' ? 'review' : 'all',
    patientSystemId: params.patient?.trim() || null,
    status:    isResultStatus(status) ? status : null,
    critical:  critical === 'true' ? true : critical === 'false' ? false : null,
    openId:    positiveIntParam(params.id),
    creating:  params.mode === 'new',
    consultationId: positiveIntParam(params.consultationId),
  };
}

// ─── The opened result (deep link `id`) ───────────────────────────────────────

interface Opened<T> {
  id:      number | null;
  item:    T | null;
  loading: boolean;
  error:   string | null;
}

/**
 * The result named by the `id` URL param. A row that is already in a loaded list is used as is; otherwise it is
 * fetched by id (a notification link can point at a result that is not on the first page, or is cancelled).
 */
export function useOpenedResult<T>(
  openId: number | null,
  idOf: (item: T) => number,
  findLoaded: (id: number) => T | undefined,
  fetchOne: (id: number) => Promise<T>,
): { item: T | null; loading: boolean; error: string | null; retry: () => void; set: (item: T) => void } {
  const [state, setState] = useState<Opened<T>>({ id: null, item: null, loading: false, error: null });
  const [tick, setTick] = useState(0);
  const findRef = useRef(findLoaded);
  findRef.current = findLoaded;
  const fetchRef = useRef(fetchOne);
  fetchRef.current = fetchOne;
  const idOfRef = useRef(idOf);
  idOfRef.current = idOf;

  useEffect(() => {
    if (openId == null) {
      setState({ id: null, item: null, loading: false, error: null });
      return;
    }
    const known = findRef.current(openId);
    if (known) {
      setState({ id: openId, item: known, loading: false, error: null });
      return;
    }
    let cancelled = false;
    setState({ id: openId, item: null, loading: true, error: null });
    fetchRef.current(openId)
      .then(item => { if (!cancelled) setState({ id: openId, item, loading: false, error: null }); })
      .catch(e => {
        if (!cancelled) {
          setState({ id: openId, item: null, loading: false, error: apiErrorMessage(e, 'This result could not be opened.') });
        }
      });
    return () => { cancelled = true; };
  }, [openId, tick]);

  const set = useCallback((item: T) => {
    setState(prev => (prev.id != null && prev.id === idOfRef.current(item) ? { id: prev.id, item, loading: false, error: null } : prev));
  }, []);
  const retry = useCallback(() => setTick(t => t + 1), []);

  // Until the effect has caught up with a new id, the previous result is not shown under it.
  const current = state.id === openId ? state : { id: openId, item: null, loading: openId != null, error: null };
  return { item: current.item, loading: current.loading, error: current.error, retry, set };
}

// ─── Labels and badges ────────────────────────────────────────────────────────

export function resultStatusLabel(status: string | null | undefined): string {
  return RESULT_STATUS_LABELS[status as ResultStatus] ?? statusLabel(status);
}

export function resultStatusTone(status: string | null | undefined): StatusTone {
  // Pending is "not final yet", not a warning; everything else follows the shared table.
  return status === 'PENDING' ? 'info' : statusTone('lab', status);
}

export function ResultStatusBadge({ status }: { status: string | null | undefined }) {
  return <StatusBadge label={resultStatusLabel(status)} tone={resultStatusTone(status)} />;
}

export function labTestTypeLabel(type: string | null | undefined): string {
  return LAB_TEST_TYPE_LABELS[type as LabTestType] ?? statusLabel(type);
}

export function imagingTypeLabel(type: string | null | undefined): string {
  return IMAGING_TYPE_LABELS[type as ImagingType] ?? statusLabel(type);
}

/** "white_cell_count" becomes "White Cell Count"; a name typed with capitals ("HbA1c") is kept. */
export function labelify(key: string): string {
  return key.replace(/_/g, ' ').replace(/\s+/g, ' ').trim().replace(/(^|\s)([a-z])/g, (_, lead: string, c: string) => lead + c.toUpperCase());
}

interface FlagLook { label: string; tone: StatusTone; icon: IoniconsName }

function flagLook(flag: string): FlagLook {
  switch (flag) {
    case 'NORMAL':   return { label: 'Normal',   tone: 'success',  icon: 'checkmark-outline' };
    case 'LOW':      return { label: 'Low',      tone: 'warning',  icon: 'arrow-down-outline' };
    case 'HIGH':     return { label: 'High',     tone: 'danger',   icon: 'arrow-up-outline' };
    case 'CRITICAL': return { label: 'Critical', tone: 'critical', icon: 'warning' };
    default:         return { label: statusLabel(flag), tone: 'warning', icon: 'alert-circle-outline' };
  }
}

/**
 * The flag of a sub-test. No pill when the sub-test has no flag; green only for NORMAL; amber or red with the
 * flag's own words for anything else, so an unknown flag is never read as normal.
 */
export function FlagPill({ flag }: { flag: string | null | undefined }) {
  const value = (flag ?? '').trim().toUpperCase();
  if (!value) return null;
  const look = flagLook(value);
  return <StatusBadge label={look.label} tone={look.tone} icon={look.icon} />;
}

/** "Reviewed by Dr. Banda on Tue 10 Nov 2026, 09:41 CAT" with the reviewer's note, or "Not reviewed yet". */
export function ReviewStamp({ reviewedAt, reviewedBy, reviewerName, notes, compact = false }: {
  reviewedAt:   string | null;
  /** System id (DOC-...) of the reviewer; its name is looked up when `reviewerName` is missing. */
  reviewedBy:   string | null;
  reviewerName: string | null;
  notes?:       string | null;
  /** One line, without the note (list rows). */
  compact?:     boolean;
}) {
  const resolvedReviewer = useDoctorName(reviewedBy, reviewerName);
  if (!reviewedAt) {
    return (
      <View style={styles.stampRow}>
        <Icon name="time-outline" size={14} color={STATUS_TONES.warning.fg} />
        <Text style={[styles.stampText, { color: STATUS_TONES.warning.fg }]}>Not reviewed yet</Text>
      </View>
    );
  }
  const when = formatClinicDateTime(reviewedAt);
  const who = reviewedBy?.trim() || reviewerName?.trim() ? ` by ${resolvedReviewer}` : '';
  return (
    <View style={styles.stamp}>
      <View style={styles.stampRow}>
        <Icon name="checkmark-done-outline" size={14} color={STATUS_TONES.success.fg} />
        <Text style={styles.stampText}>{`Reviewed${who}${when ? ` on ${when}` : ''}`}</Text>
      </View>
      {!compact && notes ? <Text style={styles.stampNotes}>{notes}</Text> : null}
    </View>
  );
}

// ─── Filters ──────────────────────────────────────────────────────────────────

const STATUS_OPTIONS: SelectOption<ResultStatus>[] = RESULT_STATUSES.map(s => ({ value: s, label: RESULT_STATUS_LABELS[s] }));

type CriticalChoice = 'true' | 'false';
const CRITICAL_OPTIONS: SelectOption<CriticalChoice>[] = [
  { value: 'true',  label: 'Critical only' },
  { value: 'false', label: 'Not critical' },
];

export interface ResultFiltersProps<V extends string> {
  status:      ResultStatus | null;
  onStatus:    (status: ResultStatus | null) => void;
  /** Label of the type filter ("Test type", "Imaging type"). */
  typeLabel:   string;
  type:        V | null;
  typeOptions: readonly SelectOption<V>[];
  onType:      (type: V | null) => void;
  critical:    boolean | null;
  onCritical:  (critical: boolean | null) => void;
  /** The patient the list is filtered to (from the `patient` URL param), or null. */
  patient:     { name: string | null; loading: boolean } | null;
  onClearPatient: () => void;
  onReset:     () => void;
  /** One sentence saying whose results the list holds. */
  scopeNote?:  string;
}

/** Status, type and critical filters with the removable patient chip. All of them filter on the server. */
export function ResultFilters<V extends string>({
  status, onStatus, typeLabel, type, typeOptions, onType, critical, onCritical, patient, onClearPatient, onReset, scopeNote,
}: ResultFiltersProps<V>) {
  const filtered = status != null || type != null || critical != null;
  return (
    <Card style={styles.filters}>
      {patient ? (
        <View style={styles.chipRow}>
          <View style={styles.chip}>
            <Icon name="person-outline" size={14} color={colors.textMuted} />
            <Text style={styles.chipText}>
              {`Patient: ${patient.name ?? (patient.loading ? 'Loading…' : 'Selected patient')}`}
            </Text>
            <IconButton label="Remove patient filter" icon="close" size="sm" onPress={onClearPatient} />
          </View>
        </View>
      ) : null}
      <View style={styles.filterRow}>
        <View style={styles.filterField}>
          <SelectField<ResultStatus>
            label="Status"
            value={status}
            options={STATUS_OPTIONS}
            onChange={value => onStatus(value || null)}
            placeholder="All except cancelled"
            allowEmpty
          />
        </View>
        <View style={styles.filterField}>
          <SelectField<V>
            label={typeLabel}
            value={type}
            options={typeOptions}
            onChange={value => onType(value || null)}
            placeholder="All types"
            allowEmpty
          />
        </View>
        <View style={styles.filterField}>
          <SelectField<CriticalChoice>
            label="Critical"
            value={critical == null ? '' : critical ? 'true' : 'false'}
            options={CRITICAL_OPTIONS}
            onChange={value => onCritical(value === 'true' ? true : value === 'false' ? false : null)}
            placeholder="All results"
            allowEmpty
          />
        </View>
        {filtered ? (
          <View style={styles.filterReset}>
            <Button label="Clear filters" variant="ghost" size="sm" icon="close-circle-outline" onPress={onReset} />
          </View>
        ) : null}
      </View>
      {scopeNote ? <Text style={styles.scopeNote}>{scopeNote}</Text> : null}
    </Card>
  );
}

// ─── Summary ──────────────────────────────────────────────────────────────────

export interface ResultSummaryItem {
  label: string;
  /** Null while the number is not known (loading, or the request failed). */
  value: number | null;
  icon:  IoniconsName;
  tone?: StatusTone;
}

/** Counts above the lists (total, waiting for review, critical). Every number comes from the server. */
export function ResultSummary({ items }: { items: readonly ResultSummaryItem[] }) {
  return (
    <View style={styles.summary}>
      {items.map(item => {
        const c = STATUS_TONES[item.tone ?? 'neutral'];
        return (
          <View key={item.label} style={styles.stat} role="group" aria-label={`${item.label}: ${item.value ?? 'not available'}`}>
            <View style={[styles.statIcon, { backgroundColor: c.bg, borderColor: c.border }]}>
              <Icon name={item.icon} size={18} color={c.fg} />
            </View>
            <View style={styles.statText}>
              <Text style={styles.statValue}>{item.value ?? '–'}</Text>
              <Text style={styles.statLabel}>{item.label}</Text>
            </View>
          </View>
        );
      })}
    </View>
  );
}

// ─── Detail panel frame ───────────────────────────────────────────────────────

/** The side panel a result opens in (`id` URL param), with its loading and failed states. */
export function ResultDetailPanel({ open, title, loading, error, onRetry, onClose, footer, children }: {
  open:     boolean;
  title:    string;
  loading:  boolean;
  error:    string | null;
  onRetry:  () => void;
  onClose:  () => void;
  /** Action buttons, shown once the result is loaded. */
  footer?:  ReactNode;
  children: ReactNode;
}) {
  return (
    <SidePanel open={open} title={title} onClose={() => onClose()} size="lg" footer={!loading && !error ? footer : undefined}>
      {loading ? <LoadingView label="Loading result…" /> : error ? <ErrorView message={error} onRetry={onRetry} /> : children}
    </SidePanel>
  );
}

// ─── Actions ──────────────────────────────────────────────────────────────────

export interface ResultPermissions {
  /** "Mark reviewed": any result that is not cancelled and has no sign-off yet. */
  canReview:        boolean;
  /** Put the result (back) into the review queue. */
  canSendForReview: boolean;
  /** The result has a sign-off that sending it for review would clear. */
  clearsSignOff:    boolean;
  /** Edit and cancel: the doctor who recorded the result, while it is not cancelled. */
  canEdit:          boolean;
  canCancel:        boolean;
}

/**
 * What the signed-in doctor is offered on a result. The server decides in the end (a doctor without a care
 * relationship is refused); these rules only keep buttons that can never work off the screen.
 */
export function resultPermissions(
  row: { status: string; reviewedAt: string | null; doctorSystemId: string },
  currentDoctorId: string | null | undefined,
): ResultPermissions {
  const cancelled = row.status === 'CANCELLED';
  const reviewed = !!row.reviewedAt;
  const own = !!currentDoctorId && row.doctorSystemId === currentDoctorId;
  // An unreviewed critical result is already in the review queue.
  const queued = row.status === 'REQUIRES_REVIEW' || (row.status === 'CRITICAL' && !reviewed);
  return {
    canReview:        !cancelled && !reviewed,
    canSendForReview: !cancelled && !queued,
    clearsSignOff:    reviewed,
    canEdit:          own && !cancelled,
    canCancel:        own && !cancelled,
  };
}

/** The buttons under an opened result. A button is rendered only when its handler is given. */
export function ResultActions({ onReview, onSendForReview, sendForReviewLabel = 'Send for review', onEdit, onCancel, busy = false }: {
  onReview?:           () => void;
  onSendForReview?:    () => void;
  sendForReviewLabel?: string;
  onEdit?:             () => void;
  onCancel?:           () => void;
  /** An action of this result is in flight. */
  busy?:               boolean;
}) {
  if (!onReview && !onSendForReview && !onEdit && !onCancel) return null;
  return (
    <View style={styles.actions}>
      {onCancel ? <Button label="Cancel result" variant="danger" icon="close-circle-outline" onPress={onCancel} disabled={busy} /> : null}
      {onEdit ? <Button label="Edit" variant="secondary" icon="create-outline" onPress={onEdit} disabled={busy} /> : null}
      {onSendForReview ? (
        <Button label={sendForReviewLabel} variant="secondary" icon="eye-outline" onPress={onSendForReview} disabled={busy} />
      ) : null}
      {onReview ? <Button label="Mark reviewed" icon="checkmark-done-outline" onPress={onReview} disabled={busy} /> : null}
    </View>
  );
}

/** A feedback line above the list after an action (recorded, reviewed, cancelled, or why it failed). */
export interface ResultNotice { tone: 'success' | 'danger'; text: string }

export function ResultNoticeBanner({ notice, onDismiss }: { notice: ResultNotice | null; onDismiss: () => void }) {
  if (!notice) return null;
  return <Banner tone={notice.tone} text={notice.text} onDismiss={onDismiss} />;
}

/** A titled block of the detail panel (level-3 heading). */
export function DetailBlock({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.block}>
      <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
      {children}
    </View>
  );
}

/** Long clinical text of the detail panel; line breaks typed by the author are kept. */
export function DetailText({ children }: { children: string }) {
  return <Text style={styles.blockText}>{children}</Text>;
}

/**
 * What is shown for a result's attached document. With the integration's view slot, the slot renders the whole
 * block, its "Attachment" heading included, so it can leave both out when the document is gone. Without one, a
 * plain note under the heading.
 */
export function ResultAttachment({ kind, documentId, patientSystemId, viewSlot }: {
  kind:            ResultKind;
  documentId:      number | null;
  patientSystemId: string;
  viewSlot?:       ResultAttachmentViewSlot;
}) {
  if (documentId == null) return null;
  if (viewSlot) return <>{viewSlot({ kind, documentId, patientSystemId })}</>;
  return (
    <DetailBlock title="Attachment">
      <View style={styles.stampRow}>
        <Icon name="document-attach-outline" size={14} color={colors.textMuted} />
        <Text style={styles.stampText}>A report file is attached to this result. It is listed under the patient's documents.</Text>
      </View>
    </DetailBlock>
  );
}

const styles = StyleSheet.create({
  stamp:      { gap: space.xs, minWidth: 0 },
  stampRow:   { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs, minWidth: 0 },
  stampText:  { flex: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  stampNotes: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  filters:     { gap: space.md },
  chipRow:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: space.xs, paddingVertical: 2,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.pill, backgroundColor: colors.bgSubtle,
  },
  chipText:    { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  filterRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.md },
  filterField: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
  filterReset: { flexShrink: 0 },
  scopeNote:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  summary:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  stat: {
    flexGrow: 1, flexShrink: 1, flexBasis: 150, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: space.md,
    padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  statIcon:  { width: 36, height: 36, borderRadius: radius.md, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  statText:  { flexShrink: 1, minWidth: 0 },
  statValue: { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  statLabel: { fontSize: fontSize.xs, color: colors.textMuted },

  actions:    { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },

  block:      { gap: space.sm, minWidth: 0 },
  blockTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  blockText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
});
