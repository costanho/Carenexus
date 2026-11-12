import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import {
  Banner, DateField, type DialogCloseReason, FormActions, FormError, Modal, SelectField, type SelectOption, space,
  TextField, TimeField,
} from '@/components/web/ui';
import { useCreateReminder, useUpdateReminder } from '@/hooks/use-reminders';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import {
  isReminderType, REMINDER_FREQUENCY_LABELS, REMINDER_TYPE_DELIVERY_HINTS, REMINDER_TYPE_LABELS,
} from '@/lib/reminders/format';
import { REMINDER_FREQUENCIES, REMINDER_TYPES, type Reminder } from '@/lib/reminders/types';
import { CLINIC_ZONE_LABEL } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  changesReminderSchedule, emptyReminderForm, isEmptyReminderPatch, REMINDER_FORM_FIELDS, REMINDER_MESSAGE_MAX,
  REMINDER_TITLE_MAX, reminderFormOf, type ReminderFormValues, reminderSchema, reminderUpcomingIssue,
  toCreateReminderInput, toUpdateReminderInput,
} from '@/lib/validation/reminder';

// The add / edit dialog for a manual reminder. Mount it only while it is open (the panel does): each mount is
// one "form open" with its own Idempotency-Key, repeated when the same submit is retried.

const TYPE_OPTIONS: readonly SelectOption[] = REMINDER_TYPES.map(value => ({ value, label: REMINDER_TYPE_LABELS[value] }));
const FREQUENCY_OPTIONS: readonly SelectOption[] = REMINDER_FREQUENCIES.map(value => ({ value, label: REMINDER_FREQUENCY_LABELS[value] }));

export type ReminderSaveKind = 'created' | 'updated' | 'unchanged';

export interface ReminderFormDialogProps {
  /** The patient the reminder is for (and who is notified). */
  patientSystemId: string;
  /** The manual reminder being edited; absent or null for a new reminder. */
  reminder?: Reminder | null;
  /** The dialog was dismissed without saving. */
  onClose:   () => void;
  /** The reminder as the server stored it. `unchanged` = the edit form was saved without a change (no request). */
  onSaved:   (reminder: Reminder, kind: ReminderSaveKind) => void;
}

export function ReminderFormDialog({ patientSystemId, reminder = null, onClose, onSaved }: ReminderFormDialogProps) {
  const editing = reminder !== null;
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<ReminderFormValues>({
    resolver:      zodResolver(reminderSchema),
    defaultValues: reminder ? reminderFormOf(reminder) : emptyReminderForm(),
  });
  const type = useWatch({ control, name: 'type' });
  const frequency = useWatch({ control, name: 'frequency' });
  const startDate = useWatch({ control, name: 'startDate' });
  const once = frequency === 'ONCE';

  const create = useCreateReminder();
  const update = useUpdateReminder();
  const [formError, setFormError] = useState<string | null>(null);
  const submitting = create.pending || update.pending;

  /** Shows the "no upcoming time" pre-check on its field. True when the form may be sent. */
  const upcomingOk = (values: ReminderFormValues): boolean => {
    const issue = reminderUpcomingIssue(values);
    if (!issue) return true;
    setError(issue.field, { type: 'validate', message: issue.message }, { shouldFocus: true });
    return false;
  };

  /** Puts a failed save on the fields the server named, otherwise above the form. */
  const showFailure = (error: ApiError) => {
    if (error.code === 'IDEMPOTENCY_IN_PROGRESS') {
      setFormError('This reminder is still being saved. Wait a moment, then try again.');
      return;
    }
    setFormError(applyFieldErrors(error, setError, REMINDER_FORM_FIELDS));
  };

  const submit = handleSubmit(async values => {
    setFormError(null);

    if (!reminder) {
      if (!upcomingOk(values)) return;
      const result = await create.run(toCreateReminderInput(patientSystemId, values));
      if (result.ok) onSaved(result.data, 'created');
      else if (result.error) showFailure(result.error);
      return;
    }

    const patch = toUpdateReminderInput(reminder, values);
    if (isEmptyReminderPatch(patch)) {
      onSaved(reminder, 'unchanged');
      return;
    }
    if (changesReminderSchedule(patch)) {
      // A paused reminder stays paused, so the server does not ask for an upcoming time; an active one keeps
      // running and an ended one is started again with the new schedule.
      if (reminder.status !== 'PAUSED' && !upcomingOk(values)) return;
      if (reminder.status === 'ENDED') patch.isActive = true;
    }
    const result = await update.run(reminder.reminderId, patch);
    if (result.ok) onSaved(result.data, 'updated');
    else if (result.error) showFailure(result.error);
  });

  const close = (reason: DialogCloseReason) => {
    // Escape also arrives when it was pressed to dismiss a date or select popup, so typed input is not
    // thrown away without asking. The Close and Cancel buttons are deliberate.
    if (reason === 'escape' && isDirty) {
      void confirmAction(
        editing ? 'Discard changes?' : 'Discard this reminder?',
        'What you entered will be lost.',
        'Discard',
        true,
      ).then(ok => { if (ok) onClose(); });
      return;
    }
    onClose();
  };

  return (
    <Modal
      open
      title={editing ? 'Edit reminder' : 'Add reminder'}
      onClose={close}
      dismissOnBackdrop={false}
      busy={submitting}
      footer={(
        <FormActions
          submitLabel={editing ? 'Save changes' : 'Add reminder'}
          onSubmit={() => { void submit(); }}
          onCancel={onClose}
          submitting={submitting}
        />
      )}
    >
      {reminder?.status === 'ENDED' ? (
        <Banner tone="info" text="This reminder has ended. Choose a new date or time and save to start it again." />
      ) : null}
      {reminder?.status === 'PAUSED' ? (
        <Banner tone="info" text="This reminder is paused. Saving does not resume it; use Resume in the list." />
      ) : null}
      <FormError message={formError} />

      <SelectField
        label="Reminder type" control={control} name="type" options={TYPE_OPTIONS}
        placeholder="Choose a type" required disabled={submitting}
        hint={isReminderType(type) ? REMINDER_TYPE_DELIVERY_HINTS[type] : undefined}
      />
      <TextField
        label="Title" control={control} name="title" required disabled={submitting}
        maxLength={REMINDER_TITLE_MAX} showCount
        hint="Shown as the title of the notification."
      />
      <TextField
        label="Message" control={control} name="message" required disabled={submitting}
        multiline rows={3} maxLength={REMINDER_MESSAGE_MAX} showCount
        hint="Shown in the notification and, when email or text-message notifications are switched on, sent there too. Leave out anything that should not appear in an email or a text message."
      />

      <View style={styles.pair}>
        <View style={styles.cell}>
          <TimeField
            label={`Time (clinic time, ${CLINIC_ZONE_LABEL})`} control={control} name="reminderTime" required disabled={submitting}
            hint="24-hour clinic time, not the time zone of this device."
          />
        </View>
        <View style={styles.cell}>
          <SelectField
            label="Repeats" control={control} name="frequency" options={FREQUENCY_OPTIONS} required disabled={submitting}
          />
        </View>
      </View>

      <View style={styles.pair}>
        <View style={styles.cell}>
          <DateField
            label={once ? 'Date' : 'Start date'} control={control} name="startDate" required disabled={submitting}
            hint={once ? undefined : 'Weekly and monthly reminders repeat from this date.'}
          />
        </View>
        {once ? null : (
          <View style={styles.cell}>
            <DateField
              label="End date" control={control} name="endDate" disabled={submitting}
              min={startDate || undefined}
              hint="Optional. The last day the reminder is sent; leave empty for no end date."
            />
          </View>
        )}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  // Two fields side by side where they fit, stacked on a phone.
  pair: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  cell: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
});
