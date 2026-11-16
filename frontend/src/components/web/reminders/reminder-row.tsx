import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, colors, fontSize, Icon, type IoniconsName, radius, space, StatusBadge, type StatusTone,
} from '@/components/web/ui';
import type { ReminderRowAction } from '@/hooks/use-reminders';
import {
  formatReminderDate, formatReminderTime, reminderFrequencyLabel, reminderNextDueLabel, reminderSourceLabel,
  reminderStatusLabel, reminderTypeLabel,
} from '@/lib/reminders/format';
import type { Reminder, ReminderStatus, ReminderType } from '@/lib/reminders/types';
import { formatClinicDateTime } from '@/lib/time/clinic';

// One reminder of the list. Which controls appear follows the contract (api/be-reminders.md 3.6): Pause for an
// active reminder, Resume for a paused one (never for an ended one), Edit for manual reminders, Delete when the
// server says the caller may delete it. The server enforces the same rules.

const TYPE_ICON: Record<ReminderType, IoniconsName> = {
  MEDICATION:   'medkit-outline',
  APPOINTMENT:  'calendar-outline',
  LAB_FOLLOWUP: 'flask-outline',
  CARE_PLAN:    'clipboard-outline',
  CHECK_IN:     'chatbubble-ellipses-outline',
  VITAL_SIGN:   'pulse-outline',
};

const STATUS_TONE: Record<ReminderStatus, { tone: StatusTone; icon: IoniconsName }> = {
  ACTIVE: { tone: 'success', icon: 'notifications-outline' },
  PAUSED: { tone: 'warning', icon: 'pause-circle-outline' },
  ENDED:  { tone: 'neutral', icon: 'checkmark-done-outline' },
};

export interface ReminderRowProps {
  reminder:  Reminder;
  /** The viewer may pause, resume, edit and delete (the panel's `canManage`). */
  canManage: boolean;
  /** This row's action in flight, or null. */
  busyAction: ReminderRowAction | null;
  /** Another row's action is in flight: this row's buttons wait. */
  disabled:  boolean;
  onPause:   (reminder: Reminder) => void;
  onResume:  (reminder: Reminder) => void;
  onEdit:    (reminder: Reminder) => void;
  onDelete:  (reminder: Reminder) => void;
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <Text style={styles.meta}>
      <Text style={styles.metaLabel}>{label}: </Text>
      {value}
    </Text>
  );
}

export function ReminderRow({
  reminder, canManage, busyAction, disabled, onPause, onResume, onEdit, onDelete,
}: ReminderRowProps) {
  const status = STATUS_TONE[reminder.status] ?? STATUS_TONE.ENDED;
  const manual = reminder.sourceType === 'MANUAL';
  const once = reminder.frequency === 'ONCE';
  const title = reminder.title?.trim() || reminderTypeLabel(reminder.type);
  const waiting = disabled || busyAction !== null;

  const canPause  = canManage && reminder.status === 'ACTIVE';
  const canResume = canManage && reminder.status === 'PAUSED';
  const canEdit   = canManage && manual;
  const canDelete = canManage && reminder.canDelete;
  const hasActions = canPause || canResume || canEdit || canDelete;

  return (
    <View style={styles.row}>
      <View style={styles.head}>
        <View style={styles.titleWrap}>
          <Icon name={TYPE_ICON[reminder.type] ?? 'notifications-outline'} size={18} color={colors.textMuted} style={styles.typeIcon} />
          <View style={styles.titles}>
            <Text style={styles.title} accessibilityRole="header" {...ariaLevel(3)}>{title}</Text>
            <Text style={styles.kind}>{reminderTypeLabel(reminder.type)} · {reminderSourceLabel(reminder)}</Text>
          </View>
        </View>
        <StatusBadge label={reminderStatusLabel(reminder.status)} tone={status.tone} icon={status.icon} />
      </View>

      {reminder.message?.trim() ? <Text style={styles.message}>{reminder.message}</Text> : null}

      <View style={styles.metaList}>
        <Meta label="Time" value={formatReminderTime(reminder.reminderTime) || 'Not set'} />
        <Meta label="Repeats" value={reminderFrequencyLabel(reminder)} />
        {once ? (
          <Meta label="Date" value={formatReminderDate(reminder.startDate, 'Not set')} />
        ) : (
          <>
            <Meta label="Starts" value={formatReminderDate(reminder.startDate, 'Not set')} />
            <Meta label="Ends" value={reminder.endDate ? formatReminderDate(reminder.endDate, 'Not set') : 'No end date'} />
          </>
        )}
        <Meta label="Next due" value={reminderNextDueLabel(reminder)} />
        <Meta label="Last sent" value={formatClinicDateTime(reminder.lastSentAt, 'Not sent yet')} />
      </View>

      {canManage && !manual ? (
        <Text style={styles.hint}>Set automatically. It can be paused, but not edited or deleted.</Text>
      ) : null}
      {canEdit && reminder.status === 'ENDED' ? (
        <Text style={styles.hint}>To use this reminder again, edit it and choose a new date or time.</Text>
      ) : null}

      {hasActions ? (
        <View style={styles.actions}>
          {canPause ? (
            <Button
              label="Pause" icon="pause-outline" variant="secondary" size="sm"
              accessibilityLabel={`Pause reminder ${title}`}
              loading={busyAction === 'pause'} disabled={waiting && busyAction !== 'pause'}
              onPress={() => onPause(reminder)}
            />
          ) : null}
          {canResume ? (
            <Button
              label="Resume" icon="play-outline" variant="secondary" size="sm"
              accessibilityLabel={`Resume reminder ${title}`}
              loading={busyAction === 'resume'} disabled={waiting && busyAction !== 'resume'}
              onPress={() => onResume(reminder)}
            />
          ) : null}
          {canEdit ? (
            <Button
              label="Edit" icon="create-outline" variant="ghost" size="sm"
              accessibilityLabel={`Edit reminder ${title}`}
              disabled={waiting}
              onPress={() => onEdit(reminder)}
            />
          ) : null}
          {canDelete ? (
            <Button
              label="Delete" icon="trash-outline" variant="ghost" size="sm"
              accessibilityLabel={`Delete reminder ${title}`}
              loading={busyAction === 'delete'} disabled={waiting && busyAction !== 'delete'}
              onPress={() => onDelete(reminder)}
            />
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    gap: space.sm, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  head:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titleWrap: { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
  typeIcon:  { flexShrink: 0, marginTop: 2 },
  titles:    { flex: 1, minWidth: 0, gap: 2 },
  title:     { fontSize: fontSize.md, fontWeight: '700', color: colors.text, lineHeight: 20 },
  kind:      { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  message:   { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  metaList:  { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.xs },
  meta:      { fontSize: fontSize.sm, color: colors.text, lineHeight: 19, maxWidth: '100%' },
  metaLabel: { color: colors.textMuted },
  hint:      { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  actions:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginTop: space.xs },
});
