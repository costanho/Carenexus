import { type ReactNode, useCallback, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, EmptyView, ErrorView, fontSize, IconButton, type IoniconsName, LoadingView,
  NoAccessView, radius, space, StatusBadge, useUiTheme,
} from '@/components/web/ui';
import { useMedicationAdherence, useMedicationSchedule } from '@/hooks/use-medication-schedule';
import { usePolling } from '@/hooks/use-page-active';
import { relativeDayName, scheduleHeading } from '@/lib/medication/dates';
import {
  countDoses, DOSE_ACTION_LABEL, DOSE_STATUS_LABEL, doseControls, doseCountsSentence, doseDetail, doseName,
  doseTone, groupDosesByTime,
} from '@/lib/medication/dose';
import type { DoseAction, MedicationDose } from '@/lib/medication/types';
import { CLINIC_ZONE_LABEL, clinicNowLocal, clinicToday } from '@/lib/time/clinic';
import { AdherenceSummary } from './adherence-summary';
import { DateSwitcher } from './date-switcher';
import { REVEAL_TARGET_PROPS, useReveal } from './use-reveal';

/** How often the day on screen is re-read while it can still change (the server marks overdue doses missed). */
const REFRESH_MS = 5 * 60_000;

export interface DoseListProps {
  /**
   * The patient whose schedule is shown. With `self` it is only needed for the adherence summary (a patient
   * reads their own numbers by their system id).
   */
  patientSystemId: string | null | undefined;
  /** True when the signed-in person is the patient. */
  self?:    boolean;
  /**
   * Whether the viewer may mark doses: always true for the patient; for a caregiver only when the grant gives
   * both viewing medications and managing the care plan. The server still checks every update.
   */
  canMark:  boolean;
  /** The clinic day shown, "yyyy-MM-dd". Keep it in the URL (`date`) so a refresh and links keep the day. */
  date:     string;
  onDateChange: (date: string) => void;
  /** Name of the patient when the viewer is someone else ("Thomas Dube"); used in headings and control names. */
  personName?: string | null;
  /** System id of the signed-in person, to say "by you" on doses they marked. */
  viewerSystemId?: string | null;
  /** Shown instead of the default sentence when the viewer may see but not mark doses. */
  readOnlyNote?: string;
  /** A dose to point out (the `id` of a notification link). Ignored when the day does not contain it. */
  highlightScheduleId?: number | null;
  /** Hide the date switcher when the screen draws one switcher for several lists. Default true. */
  showDateSwitcher?: boolean;
  /** Show the 30-day adherence summary. Default true. */
  showAdherence?: boolean;
  /** Called when the server refuses a dose update (403), so the screen can re-read the viewer's access. */
  onMarkDenied?: () => void;
  /** Heading level of the title. Default 2. */
  headingLevel?: 2 | 3;
}

const ACTION_ICON: Record<DoseAction, IoniconsName> = {
  taken:  'checkmark-circle-outline',
  missed: 'close-circle-outline',
  undo:   'arrow-undo-outline',
};

function DoseRow({ dose, controls, busy, pendingAction, highlighted, detail, now, context, onAction }: {
  dose:        MedicationDose;
  controls:    DoseAction[];
  /** Some dose of the list is being updated: every control waits. */
  busy:        boolean;
  /** The update running on this dose, or null. */
  pendingAction: DoseAction | null;
  highlighted: boolean;
  detail:      string | null;
  now:         string;
  /** Appended to control names: "Metformin 850mg at 08:00" (and the person's name for a caregiver). */
  context:     string;
  onAction:    (dose: MedicationDose, action: DoseAction) => void;
}) {
  const { accent, accentBg } = useUiTheme();
  // The dose a notification link named may sit below the date switcher, the summary and other people's
  // schedules: bring it into view and give it focus once, when it first appears.
  const rowRef = useRef<View>(null);
  useReveal(rowRef, highlighted ? dose.scheduleId : null);
  return (
    <View
      ref={rowRef}
      role="listitem"
      style={[styles.dose, highlighted && { borderColor: accent, backgroundColor: accentBg }]}
      {...(highlighted ? REVEAL_TARGET_PROPS : {})}
    >
      <View style={styles.doseText}>
        <Text style={styles.doseName}>{dose.medicationName || 'Medication'}</Text>
        {dose.dosage ? <Text style={styles.doseDosage}>{dose.dosage}</Text> : null}
        {detail ? <Text style={styles.doseDetail}>{detail}</Text> : null}
      </View>
      <View style={styles.doseSide}>
        <StatusBadge label={DOSE_STATUS_LABEL[dose.status]} tone={doseTone(dose, now)} />
        {controls.length > 0 ? (
          <View style={styles.controls}>
            {controls.map(action => (
              <Button
                key={action}
                label={DOSE_ACTION_LABEL[action]}
                accessibilityLabel={`${DOSE_ACTION_LABEL[action]}, ${context}`}
                icon={ACTION_ICON[action]}
                variant={action === 'taken' ? 'primary' : 'secondary'}
                size="sm"
                // One update at a time: the button that was pressed shows the spinner, the others wait.
                loading={pendingAction === action}
                disabled={busy && pendingAction !== action}
                onPress={() => onAction(dose, action)}
              />
            ))}
          </View>
        ) : null}
      </View>
    </View>
  );
}

/**
 * A patient's medication schedule for one day: the doses grouped by time with a status chip each (Due, Taken,
 * Missed), the Mark taken / Mark missed / Undo controls for doses of today and yesterday, a date switcher and
 * the 30-day adherence summary. Read-only when `canMark` is false.
 */
export function DoseList({
  patientSystemId, self = false, canMark, date, onDateChange, personName, viewerSystemId, readOnlyNote,
  highlightScheduleId, showDateSwitcher = true, showAdherence = true, onMarkDenied, headingLevel = 2,
}: DoseListProps) {
  const name = personName?.trim() || null;
  const adherence = useMedicationAdherence(patientSystemId, 30, { enabled: showAdherence });
  const reloadAdherence = adherence.refetch;
  const schedule = useMedicationSchedule(patientSystemId, date, {
    self,
    onChanged: reloadAdherence,
    onMarkDenied,
  });
  const {
    doses, loading, refreshing, error, noAccess, refetch, mark, pendingId, pendingAction, actionError, markDenied,
    announcement,
  } = schedule;

  const today = clinicToday();
  const now = clinicNowLocal();
  const dayName = relativeDayName(date, today);
  const mayMark = canMark && !markDenied;
  // Doses of yesterday, today and tomorrow still change on the server (overdue doses become missed, a doctor
  // edits the times), so those days are re-read now and then while the page is in view.
  usePolling(refetch, REFRESH_MS, dayName !== null && !loading && !error);

  const refresh = useCallback(() => { refetch(); reloadAdherence(); }, [refetch, reloadAdherence]);
  const onAction = useCallback((dose: MedicationDose, action: DoseAction) => { void mark(dose, action); }, [mark]);

  const slots = groupDosesByTime(doses);
  const counts = countDoses(doses);
  const anyMarkable = doses.some(d => d.markable);
  const heading = name ? `${name}: ${scheduleHeading(date, today).replace(/^./, c => c.toLowerCase())}` : scheduleHeading(date, today);

  let body: ReactNode;
  if (loading) {
    body = <LoadingView compact label={name ? `Loading the schedule of ${name}` : 'Loading your schedule'} />;
  } else if (noAccess) {
    body = (
      <NoAccessView
        compact
        message={name ? `You don't have access to the medication schedule of ${name}.` : undefined}
        sub={self ? undefined : 'Ask your loved one to update the permissions on your caregiver access.'}
      />
    );
  } else if (error && doses.length === 0) {
    body = <ErrorView compact message={error} onRetry={refetch} />;
  } else if (doses.length === 0) {
    body = (
      <EmptyView
        compact
        icon="calendar-clear-outline"
        title={dayName ? `No doses scheduled for ${dayName.toLowerCase()}` : 'No doses scheduled for this day'}
        sub={self
          ? 'Doses appear here when one of your active prescriptions has daily times.'
          : 'Doses appear here when an active prescription has daily times.'}
      />
    );
  } else {
    body = (
      <>
        {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        <Text style={styles.summary}>{doseCountsSentence(counts)}. Times are clinic time ({CLINIC_ZONE_LABEL}).</Text>
        <View style={styles.slots} aria-busy={refreshing || pendingId !== null}>
          {slots.map(slot => (
            <View key={slot.time} style={styles.slot} role="group" aria-label={`${slot.time}, ${slot.doses.length} ${slot.doses.length === 1 ? 'dose' : 'doses'}`}>
              <Text style={styles.slotTime} accessibilityRole="header" {...ariaLevel(headingLevel === 2 ? 3 : 4)}>{slot.time}</Text>
              <View style={styles.slotDoses} role="list">
                {slot.doses.map(dose => (
                  <DoseRow
                    key={dose.scheduleId}
                    dose={dose}
                    controls={doseControls(dose, mayMark)}
                    busy={pendingId !== null}
                    pendingAction={pendingId === dose.scheduleId ? pendingAction : null}
                    highlighted={highlightScheduleId != null && highlightScheduleId === dose.scheduleId}
                    detail={doseDetail(dose, { systemId: viewerSystemId, patientName: self ? null : name }, now)}
                    now={now}
                    context={[`${doseName(dose)} at ${dose.scheduledTime}`, name].filter(Boolean).join(', ')}
                    onAction={onAction}
                  />
                ))}
              </View>
            </View>
          ))}
        </View>
        {mayMark && !anyMarkable ? (
          <Text style={styles.note}>
            {dayName === 'Tomorrow'
              ? 'Doses can be marked once their day has started.'
              : 'Doses can be marked on their day and the day after. Older days are shown for reference.'}
          </Text>
        ) : null}
        {!mayMark && anyMarkable ? (
          <Text style={styles.note}>
            {readOnlyNote ?? 'You can see this schedule but not mark doses.'}
          </Text>
        ) : null}
      </>
    );
  }

  return (
    <Card style={styles.card}>
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{heading}</Text>
        <IconButton
          label={name ? `Refresh the schedule of ${name}` : 'Refresh the schedule'}
          icon="refresh-outline"
          variant="secondary"
          size="sm"
          loading={refreshing}
          onPress={refresh}
        />
      </View>

      {showDateSwitcher ? <DateSwitcher date={date} onChange={onDateChange} context={name ?? undefined} /> : null}
      {showAdherence && !noAccess ? <AdherenceSummary state={adherence} /> : null}

      {actionError ? (
        <Banner tone={markDenied ? 'warning' : 'danger'} text={actionError} onDismiss={schedule.clearActionError} />
      ) : null}
      {/* Announces the result of an update without moving focus away from the list. */}
      <Text style={styles.live} role="status" aria-live="polite">{announcement ?? ''}</Text>

      {body}
    </Card>
  );
}

const styles = StyleSheet.create({
  card:    { gap: space.md },
  header:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  title:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  summary: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  note:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  // Visually empty until there is something to announce; kept in the tree so the live region is registered.
  live:    { position: 'absolute', width: 1, height: 1, overflow: 'hidden', opacity: 0 },

  slots:     { gap: space.md, minWidth: 0 },
  slot:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.md, minWidth: 0 },
  slotTime:  { width: 56, paddingTop: space.md, fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  slotDoses: { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, gap: space.sm },

  dose: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.md,
    padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
    minWidth: 0,
  },
  doseText:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, gap: 2 },
  doseName:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  doseDosage: { fontSize: fontSize.sm, color: colors.textMuted },
  doseDetail: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  doseSide:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
  controls:   { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, maxWidth: '100%' },
});
