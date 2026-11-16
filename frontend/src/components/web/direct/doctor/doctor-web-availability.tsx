import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  availabilityServerIssues, blocksOfDay, DEFAULT_SLOT_MINUTES, draftsOf, hourLabel, isWholeDays, lastWholeDay,
  MAX_BLOCKS_PER_DAY, MAX_REASON_LENGTH, MAX_SLOT_MINUTES, MIN_SLOT_MINUTES, newBlockDraft, NO_ISSUES, suggestBlock,
  TIME_STEP_MINUTES, timeOffSchema, toAvailabilityRequest, toTimeOffRequest, useDoctorAvailability, useDoctorTimeOff,
  validateAvailability, WEEKDAYS,
  type AvailabilityIssues, type BlockDraft, type ClinicHours, type DoctorAvailability, type DoctorAvailabilityState,
  type TimeOff, type TimeOffFormValues,
} from '@/hooks/use-doctor-availability';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  ariaLevel, Banner, Button, colors, DateField, ErrorView, fontSize, FormActions, FormError, IconButton, LoadingView,
  Modal, NoAccessView, PagedList, radius, Screen, SectionCard, SelectField, type SelectOption, space, StatusBadge,
  SwitchField, TextField, TimeField,
} from '@/components/web/ui';
import type { ApiError } from '@/lib/api/errors';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicNowLocal, clinicToday, formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function slotLabel(minutes: number): string {
  if (minutes < 60) return `${minutes} minutes`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  const h = `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  return rest === 0 ? h : `${h} ${rest} minutes`;
}

const SLOT_OPTIONS: SelectOption[] = Array.from(
  { length: (MAX_SLOT_MINUTES - MIN_SLOT_MINUTES) / TIME_STEP_MINUTES + 1 },
  (_, i) => {
    const minutes = MIN_SLOT_MINUTES + i * TIME_STEP_MINUTES;
    return { value: String(minutes), label: slotLabel(minutes) };
  },
);

function clinicHoursText(clinic: ClinicHours | null): string {
  if (!clinic) return 'clinic hours';
  const close = clinic.closeHour >= 24 ? 'midnight' : hourLabel(clinic.closeHour);
  return `clinic hours (${hourLabel(clinic.openHour)} to ${close}${clinic.allowWeekends ? '' : ', Monday to Friday'})`;
}

/** Identifies what a save would store. Without hours there is no template, so the slot length alone changes nothing. */
function requestKey(slotMinutes: number, blocks: readonly BlockDraft[]): string {
  return blocks.length === 0 ? 'clinic-hours' : JSON.stringify(toAvailabilityRequest(slotMinutes, blocks).body);
}

/** Field messages of the editor: the server's answer to the last save wins over the local check. */
function mergeIssues(local: AvailabilityIssues, server: AvailabilityIssues | null): AvailabilityIssues {
  if (!server) return local;
  const blocks: AvailabilityIssues['blocks'] = { ...local.blocks };
  for (const [id, entry] of Object.entries(server.blocks)) blocks[id] = { ...blocks[id], ...entry };
  return {
    slotMinutes: server.slotMinutes ?? local.slotMinutes,
    blocks,
    days: { ...local.days, ...server.days },
    valid: local.valid,
  };
}

function timeOffText(timeOff: TimeOff): string {
  if (isWholeDays(timeOff)) {
    const first = formatClinicDate(timeOff.startsAt, timeOff.startsAt);
    const lastDay = lastWholeDay(timeOff.endsAt);
    return lastDay === timeOff.startsAt.slice(0, 10)
      ? `${first} (all day)`
      : `${first} to ${formatClinicDate(`${lastDay}T00:00:00`, lastDay)} (all day)`;
  }
  return `${formatClinicDateTime(timeOff.startsAt, timeOff.startsAt)} to ${formatClinicDateTime(timeOff.endsAt, timeOff.endsAt)}`;
}

// ─── Weekly hours ─────────────────────────────────────────────────────────────

function BlockRow({ day, index, block, issue, clinic, disabled, onChange, onRemove }: {
  day:      string;
  index:    number;
  block:    BlockDraft;
  issue?:   { startTime?: string; endTime?: string };
  clinic:   ClinicHours | null;
  disabled: boolean;
  onChange: (patch: Partial<BlockDraft>) => void;
  onRemove: () => void;
}) {
  const min = clinic ? hourLabel(clinic.openHour) : undefined;
  const max = clinic ? (clinic.closeHour >= 24 ? '23:55' : hourLabel(clinic.closeHour)) : undefined;
  return (
    <View style={styles.blockRow} role="group" aria-label={`${day} hours ${index + 1}`}>
      <View style={styles.timeCell}>
        <TimeField
          label="Start" value={block.startTime} onChange={value => onChange({ startTime: value })}
          stepMinutes={TIME_STEP_MINUTES} min={min} max={max} error={issue?.startTime} required disabled={disabled}
        />
      </View>
      <View style={styles.timeCell}>
        <TimeField
          label="End" value={block.endTime} onChange={value => onChange({ endTime: value })}
          stepMinutes={TIME_STEP_MINUTES} min={min} max={max} error={issue?.endTime} required disabled={disabled}
        />
      </View>
      <View style={styles.switchCell}>
        <SwitchField
          label="Bookable" value={block.isActive} onChange={value => onChange({ isActive: value })}
          description={block.isActive ? 'Patients can book these hours' : 'Kept, but not offered to patients'}
          disabled={disabled}
        />
      </View>
      <IconButton
        label={`Remove ${day} hours ${index + 1}`} icon="trash-outline" variant="secondary"
        onPress={onRemove} disabled={disabled} style={styles.removeBtn}
      />
    </View>
  );
}

function WeeklyHoursEditor({ availability, clinic, save, saving }: {
  availability: DoctorAvailability;
  clinic:       ClinicHours | null;
  save:         DoctorAvailabilityState['save'];
  saving:       boolean;
}) {
  const [slotMinutes, setSlotMinutes] = useState(availability.slotMinutes || DEFAULT_SLOT_MINUTES);
  const [blocks, setBlocks] = useState<BlockDraft[]>(() => draftsOf(availability));
  // The stored template these drafts started from, to tell whether anything changed.
  const [baseline, setBaseline] = useState(() => requestKey(availability.slotMinutes || DEFAULT_SLOT_MINUTES, draftsOf(availability)));
  const [showIssues, setShowIssues] = useState(false);
  const [serverIssues, setServerIssues] = useState<AvailabilityIssues | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // A saved (or reloaded) template replaces the drafts.
  useEffect(() => {
    const drafts = draftsOf(availability);
    const slot = availability.slotMinutes || DEFAULT_SLOT_MINUTES;
    setSlotMinutes(slot);
    setBlocks(drafts);
    setBaseline(requestKey(slot, drafts));
    setShowIssues(false);
    setServerIssues(null);
    setFormError(null);
  }, [availability]);

  const localIssues = useMemo(() => validateAvailability(slotMinutes, blocks, clinic), [slotMinutes, blocks, clinic]);
  const issues = mergeIssues(showIssues ? localIssues : NO_ISSUES, serverIssues);
  const dirty = requestKey(slotMinutes, blocks) !== baseline;
  const hadTemplate = availability.source === 'TEMPLATE';
  const noneBookable = blocks.length > 0 && blocks.every(b => !b.isActive);

  function edited() {
    setServerIssues(null);
    setFormError(null);
    setSaved(false);
  }

  function changeBlock(id: string, patch: Partial<BlockDraft>) {
    edited();
    setBlocks(prev => prev.map(b => (b.id === id ? { ...b, ...patch } : b)));
  }

  function removeBlock(id: string) {
    edited();
    setBlocks(prev => prev.filter(b => b.id !== id));
  }

  function addBlock(day: number) {
    edited();
    setBlocks(prev => prev.concat(suggestBlock(prev, day, slotMinutes, clinic) ?? newBlockDraft(day, '', '')));
  }

  function discard() {
    const drafts = draftsOf(availability);
    setSlotMinutes(availability.slotMinutes || DEFAULT_SLOT_MINUTES);
    setBlocks(drafts);
    setShowIssues(false);
    edited();
  }

  async function submit() {
    setSaved(false);
    setServerIssues(null);
    setShowIssues(true);
    if (!localIssues.valid) {
      setFormError('Some hours need attention. Fix the highlighted fields, then save again.');
      return;
    }
    setFormError(null);
    const { body, order } = toAvailabilityRequest(slotMinutes, blocks);
    const result = await save(body);
    if (result.ok) {
      setSaved(true);
    } else if (result.error) {
      const mapped = availabilityServerIssues(result.error, order);
      setServerIssues(mapped.issues);
      setFormError(mapped.formMessage ?? 'These hours were not accepted. Fix the highlighted fields, then save again.');
    }
  }

  return (
    <SectionCard
      title="Weekly hours"
      subtitle="The hours patients and caregivers can book you in, in clinic time (CAT). You can still book appointments yourself outside them."
    >
      {!hadTemplate && blocks.length === 0 && (
        <Banner tone="info" text={`You have not set weekly hours, so patients can book you during ${clinicHoursText(clinic)}.`} />
      )}
      {hadTemplate && blocks.length === 0 && (
        <Banner tone="warning" text={`Saving with no hours removes your weekly hours: patients will be able to book you during ${clinicHoursText(clinic)} again.`} />
      )}
      {noneBookable && (
        <Banner tone="warning" text="None of these hours are bookable, so patients and caregivers cannot book you on any day." />
      )}
      {!clinic && (
        <Banner tone="info" text="Clinic opening hours could not be loaded. Hours outside them are refused when you save." />
      )}

      <View style={styles.slotField}>
        <SelectField
          label="Appointment slot length"
          hint="How far apart bookable start times are. One length applies to all your hours."
          value={String(slotMinutes)}
          onChange={value => { edited(); setSlotMinutes(Number(value)); }}
          options={SLOT_OPTIONS}
          error={issues.slotMinutes}
          disabled={saving}
          required
        />
      </View>

      <View style={styles.days}>
        {WEEKDAYS.map(({ day, label }) => {
          const ofDay = blocksOfDay(blocks, day);
          const closed = !!clinic && !clinic.allowWeekends && day >= 6;
          const full = ofDay.length >= MAX_BLOCKS_PER_DAY;
          return (
            <View key={day} style={styles.day} role="group" aria-label={`${label} hours`}>
              <View style={styles.dayHeader}>
                <Text style={styles.dayName} accessibilityRole="header" {...ariaLevel(3)}>{label}</Text>
                {ofDay.length === 0 && (
                  <Text style={styles.dayNote}>
                    {closed
                      ? 'The clinic is closed on weekends'
                      : blocks.length === 0 ? 'Clinic hours apply' : 'No hours: patients cannot book you on this day'}
                  </Text>
                )}
              </View>
              {ofDay.map((block, index) => (
                <BlockRow
                  key={block.id}
                  day={label}
                  index={index}
                  block={block}
                  issue={issues.blocks[block.id]}
                  clinic={clinic}
                  disabled={saving}
                  onChange={patch => changeBlock(block.id, patch)}
                  onRemove={() => removeBlock(block.id)}
                />
              ))}
              {!!issues.days[day] && (
                <Text style={styles.dayError} accessibilityRole="alert">{issues.days[day]}</Text>
              )}
              {!closed && !full && (
                <Button
                  label="Add hours"
                  accessibilityLabel={`Add hours on ${label}`}
                  icon="add"
                  variant="ghost"
                  size="sm"
                  disabled={saving}
                  onPress={() => addBlock(day)}
                />
              )}
              {full && <Text style={styles.dayNote}>A day can have at most {MAX_BLOCKS_PER_DAY} sets of hours.</Text>}
            </View>
          );
        })}
      </View>

      <FormError message={formError} />
      {saved && !dirty && <Banner tone="success" text="Weekly hours saved." onDismiss={() => setSaved(false)} />}

      <Text style={styles.note}>
        Changing your hours never changes or cancels appointments that are already booked.
      </Text>
      <View style={styles.actions}>
        {blocks.length > 0 && (
          <Button label="Remove all hours" icon="trash-outline" variant="ghost" onPress={() => { edited(); setBlocks([]); }} disabled={saving} />
        )}
        <Button label="Discard changes" variant="secondary" onPress={discard} disabled={!dirty || saving} />
        <Button label="Save weekly hours" icon="save-outline" onPress={submit} loading={saving} disabled={!dirty} />
      </View>
    </SectionCard>
  );
}

// ─── Time off ─────────────────────────────────────────────────────────────────

const SERVER_FIELD: Record<string, keyof TimeOffFormValues> = { startsAt: 'startDate', endsAt: 'endDate', reason: 'reason' };

function AddTimeOffDialog({ open, submitting, onClose, onSubmit }: {
  open:       boolean;
  submitting: boolean;
  onClose:    () => void;
  /** Resolves with the error of a refused request, or null when the range was added. */
  onSubmit:   (values: TimeOffFormValues) => Promise<ApiError | null>;
}) {
  const today = clinicToday();
  const { control, handleSubmit, watch, setError } = useForm<TimeOffFormValues>({
    resolver: zodResolver(timeOffSchema),
    defaultValues: { allDay: true, startDate: today, startTime: '09:00', endDate: today, endTime: '17:00', reason: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const allDay = watch('allDay');

  const submit = handleSubmit(async values => {
    setFormError(null);
    const error = await onSubmit(values);
    if (!error) return;
    // Rules checked in the service arrive as "<field>: <text>"; Bean Validation also fills fieldErrors.
    const fromList = Object.entries(error.fieldErrors ?? {}).find(([field]) => SERVER_FIELD[field]);
    const fromMessage = /^(startsAt|endsAt|reason):\s*(.+)$/.exec(error.message);
    const field = fromList ? fromList[0] : fromMessage?.[1];
    const text = fromList ? fromList[1] : fromMessage?.[2];
    if (field && text && SERVER_FIELD[field]) {
      setError(SERVER_FIELD[field], { type: 'server', message: text.charAt(0).toUpperCase() + text.slice(1) }, { shouldFocus: true });
    } else {
      setFormError(error.message);
    }
  });

  return (
    <Modal
      open={open}
      title="Add time off"
      onClose={onClose}
      dismissOnBackdrop={false}
      busy={submitting}
      footer={<FormActions submitLabel="Add time off" onSubmit={submit} onCancel={onClose} submitting={submitting} />}
    >
      <View style={styles.form}>
        <Text style={styles.note}>
          Patients and caregivers cannot book you during time off. Appointments that are already booked stay booked.
        </Text>
        <SwitchField control={control} name="allDay" label="Whole days" description="From the start of the first day to the end of the last day" />
        <View style={styles.formRow}>
          <View style={styles.formCell}>
            <DateField control={control} name="startDate" label="First day" required />
          </View>
          {!allDay && (
            <View style={styles.formCell}>
              <TimeField control={control} name="startTime" label="From" required />
            </View>
          )}
        </View>
        <View style={styles.formRow}>
          <View style={styles.formCell}>
            <DateField control={control} name="endDate" label="Last day" min={today} required />
          </View>
          {!allDay && (
            <View style={styles.formCell}>
              <TimeField control={control} name="endTime" label="Until" required />
            </View>
          )}
        </View>
        <TextField
          control={control} name="reason" label="Reason"
          hint="Only you can see this note." maxLength={MAX_REASON_LENGTH} showCount multiline rows={2}
        />
        <FormError message={formError} />
      </View>
    </Modal>
  );
}

function TimeOffRow({ timeOff, removing, onRemove, onViewSchedule }: {
  timeOff:         TimeOff;
  removing:        boolean;
  onRemove:        () => void;
  onViewSchedule?: () => void;
}) {
  const now = clinicNowLocal();
  const text = timeOffText(timeOff);
  const state = timeOff.endsAt <= now
    ? { label: 'Past', tone: 'neutral' as const }
    : timeOff.startsAt <= now ? { label: 'Now', tone: 'info' as const } : { label: 'Upcoming', tone: 'success' as const };
  const conflicts = timeOff.conflictingAppointments;
  return (
    <View style={styles.timeOffRow}>
      <View style={styles.timeOffTop}>
        <View style={styles.timeOffText}>
          <Text style={styles.timeOffRange}>{text}</Text>
          {!!timeOff.reason && <Text style={styles.timeOffReason}>{timeOff.reason}</Text>}
          <StatusBadge label={state.label} tone={state.tone} />
        </View>
        <Button
          label="Remove" accessibilityLabel={`Remove time off ${text}`} icon="trash-outline"
          variant="secondary" size="sm" loading={removing} onPress={onRemove}
        />
      </View>
      {conflicts > 0 && timeOff.endsAt > now && (
        <Banner
          tone="warning"
          text={`${conflicts} booked appointment${conflicts === 1 ? ' falls' : 's fall'} inside this time off. ${conflicts === 1 ? 'It is' : 'They are'} not cancelled: reschedule or cancel ${conflicts === 1 ? 'it' : 'them'} from your schedule.`}
          action={onViewSchedule ? { label: 'View schedule', onPress: onViewSchedule } : undefined}
        />
      )}
    </View>
  );
}

function TimeOffSection({ navigate }: { navigate?: SectionProps['navigate'] }) {
  const [includePast, setIncludePast] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  // Remounts the dialog on every open, so it starts from fresh values.
  const [formKey, setFormKey] = useState(0);
  const [notice, setNotice] = useState<{ tone: 'success' | 'warning' | 'danger'; text: string } | null>(null);
  const timeOff = useDoctorTimeOff(includePast);

  function openForm() {
    timeOff.beginForm();
    setNotice(null);
    setFormKey(k => k + 1);
    setFormOpen(true);
  }

  async function submit(values: TimeOffFormValues): Promise<ApiError | null> {
    const body = toTimeOffRequest(values);
    if (!body) return { status: 0, message: 'Choose the first and the last day.' };
    const result = await timeOff.add(body);
    if (!result.ok) return result.error ?? { status: 0, message: 'The time off is still being added. Please wait a moment.' };
    setFormOpen(false);
    const n = result.data.conflictingAppointments;
    setNotice(n > 0
      ? { tone: 'warning', text: `Time off added. ${n} booked appointment${n === 1 ? ' falls' : 's fall'} inside it and ${n === 1 ? 'stays' : 'stay'} booked.` }
      : { tone: 'success', text: 'Time off added.' });
    return null;
  }

  async function remove(row: TimeOff) {
    const text = timeOffText(row);
    const ok = await confirmAction('Remove time off', `${text}\n\nPatients will be able to book you in this period again.`, 'Remove', true);
    if (!ok) return;
    const result = await timeOff.remove(row.timeOffId);
    if (result.ok) setNotice({ tone: 'success', text: 'Time off removed.' });
    else if (result.error) setNotice({ tone: 'danger', text: result.error.message });
  }

  return (
    <SectionCard
      title="Time off"
      subtitle="Leave, conferences and other periods when you are away, soonest first."
      action={<Button label="Add time off" icon="add" size="sm" onPress={openForm} />}
    >
      {!!notice && <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} />}
      <PagedList
        state={timeOff.list}
        label="Time off"
        keyOf={row => row.timeOffId}
        header={(
          <SwitchField
            label="Show past time off" value={includePast} onChange={setIncludePast}
            description="Include periods that have already ended"
          />
        )}
        empty={{
          icon: 'airplane-outline',
          title: includePast ? 'No time off recorded' : 'No time off planned',
          sub: 'Add time off to stop patients booking you while you are away.',
        }}
        renderItem={row => (
          <TimeOffRow
            timeOff={row}
            removing={timeOff.removingId === row.timeOffId}
            onRemove={() => { void remove(row); }}
            onViewSchedule={navigate ? () => navigate('schedule', {
              date: row.startsAt.slice(0, 10) < clinicToday() ? clinicToday() : row.startsAt.slice(0, 10),
              tab: isWholeDays(row) && lastWholeDay(row.endsAt) !== row.startsAt.slice(0, 10) ? 'week' : undefined,
            }) : undefined}
          />
        )}
      />
      <AddTimeOffDialog key={formKey} open={formOpen} submitting={timeOff.adding} onClose={() => setFormOpen(false)} onSubmit={submit} />
    </SectionCard>
  );
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Availability: the doctor's weekly hours (one slot length, up to six sets of hours per weekday, inside clinic
 * hours) and their time off. Patients and caregivers can only book inside the hours and outside time off;
 * neither ever changes an appointment that is already booked.
 */
export default function DoctorWebAvailability(props: Partial<SectionProps> = {}) {
  const nav = useSectionNav();
  const navigate = props.navigate ?? nav.navigate;
  const state = useDoctorAvailability();

  let body;
  if (state.loading) {
    body = <LoadingView label="Loading your availability…" />;
  } else if (state.errorStatus === 403) {
    body = <NoAccessView message="Availability is set by doctors for their own calendar." />;
  } else if (!state.availability) {
    body = <ErrorView message={state.error ?? 'Could not load your availability. Please try again.'} onRetry={state.refetch} />;
  } else {
    body = (
      <>
        {!!state.error && <Banner tone="danger" text={state.error} action={{ label: 'Retry', onPress: state.refetch }} />}
        <WeeklyHoursEditor availability={state.availability} clinic={state.clinic} save={state.save} saving={state.saving} />
        <TimeOffSection navigate={navigate} />
      </>
    );
  }

  return (
    <Screen
      title="Availability"
      subtitle="Set the hours patients can book you in and block time when you are away"
      theme={props.theme}
      role="doctor"
    >
      {body}
    </Screen>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  note:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  slotField: { maxWidth: 360, width: '100%' },

  days:      { gap: space.md },
  day: {
    gap: space.sm, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
    backgroundColor: colors.bg, minWidth: 0,
  },
  dayHeader: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', columnGap: space.md, rowGap: 2 },
  dayName:   { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  dayNote:   { fontSize: fontSize.sm, color: colors.textMuted },
  dayError:  { fontSize: fontSize.sm, color: colors.danger, fontWeight: '500' },

  blockRow: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md,
    padding: space.md, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
  },
  timeCell:   { flexGrow: 1, flexShrink: 1, flexBasis: 130, minWidth: 120, maxWidth: 220 },
  switchCell: { flexGrow: 2, flexShrink: 1, flexBasis: 200, minWidth: 0 },
  removeBtn:  { marginTop: 22 },

  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm },

  form:     { gap: space.md },
  formRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  formCell: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },

  timeOffRow: {
    gap: space.sm, padding: space.md, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md,
    backgroundColor: colors.surface, minWidth: 0,
  },
  timeOffTop:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  timeOffText:   { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 4 },
  timeOffRange:  { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  timeOffReason: { fontSize: fontSize.sm, color: colors.textMuted },
});
