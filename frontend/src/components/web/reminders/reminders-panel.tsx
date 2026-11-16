import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, type BannerTone, Button, colors, fontSize, Icon, type IoniconsName, LoadingView, PagedList, radius,
  type RoleTheme, SectionCard, SelectField, type SelectOption, space, STATUS_TONES, UiThemeProvider,
} from '@/components/web/ui';
import { useReminderRowActions, useReminders } from '@/hooks/use-reminders';
import type { ApiError } from '@/lib/api/errors';
import type { Reminder } from '@/lib/reminders/types';
import { CLINIC_ZONE_LABEL } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';
import { ReminderFormDialog, type ReminderSaveKind } from './reminder-form';
import { ReminderRow } from './reminder-row';

// The reminders of one patient: the list (manual, follow-up and care-plan review reminders), pause and resume,
// and add, edit and delete for manual ones. Mounted by the patient (prescriptions, tab reminders), the caregiver
// (medications, tab reminders) and the doctor (patient detail). Appointment and medication-dose reminders are
// sent by their own jobs and are not rows of this list; the note above the list says so.

export interface RemindersPanelProps {
  /** The patient whose reminders are shown (PAT-...). While it is not known yet the panel shows a spinner. */
  patientSystemId: string | null | undefined;
  /**
   * The viewer may add, pause, resume, edit and delete: the patient on their own reminders, a doctor in the
   * patient detail panel, a caregiver whose active grant allows managing the care plan. Otherwise read-only.
   */
  canManage: boolean;
  /** The dashboard's accent colours (`SectionProps.theme`). */
  theme?:    RoleTheme;
  /** Opens the viewer's notification settings; when given, the note about automatic reminders links to it. */
  onOpenNotificationSettings?: () => void;
}

type FilterKey = 'all' | 'active' | 'inactive';

const FILTER_OPTIONS: readonly SelectOption<FilterKey>[] = [
  { value: 'all',      label: 'All reminders' },
  { value: 'active',   label: 'Active' },
  { value: 'inactive', label: 'Paused or ended' },
];

/** The `active` query parameter of each filter. */
const FILTER_ACTIVE: Record<FilterKey, boolean | null> = { all: null, active: true, inactive: false };

const NO_ACCESS = "You don't have access to these reminders.";

interface Notice { tone: BannerTone; text: string }

type DialogState = { mode: 'create' } | { mode: 'edit'; reminder: Reminder } | null;

export function RemindersPanel({ patientSystemId, canManage, theme, onOpenNotificationSettings }: RemindersPanelProps) {
  const id = (patientSystemId ?? '').trim();
  return (
    <UiThemeProvider theme={theme}>
      {id ? (
        // Keyed by patient: the filter, an open form and any message belong to one patient's list.
        <PatientReminders
          key={id}
          patientSystemId={id}
          canManage={canManage}
          onOpenNotificationSettings={onOpenNotificationSettings}
        />
      ) : (
        <SectionCard title="Reminders">
          <LoadingView compact label="Loading reminders…" />
        </SectionCard>
      )}
    </UiThemeProvider>
  );
}

function AutomaticRemindersNote({ onOpenSettings }: { onOpenSettings?: () => void }) {
  const tone = STATUS_TONES.info;
  return (
    <View style={[styles.note, { backgroundColor: tone.bg, borderColor: tone.border }]}>
      <Icon name="information-circle-outline" size={18} color={tone.fg} style={styles.noteIcon} />
      <Text style={[styles.noteText, { color: tone.fg }]}>
        Appointment and medication reminders are automatic and are not listed here. They follow the booked
        appointments and the medication schedule, and are switched on or off in notification settings.
      </Text>
      {onOpenSettings ? (
        <Button label="Notification settings" variant="secondary" size="sm" icon="settings-outline" onPress={onOpenSettings} />
      ) : null}
    </View>
  );
}

function emptyState(filter: FilterKey, canManage: boolean): { icon: IoniconsName; title: string; sub?: string } {
  if (filter === 'active') {
    return { icon: 'notifications-off-outline', title: 'No active reminders', sub: 'Paused and ended reminders are under "Paused or ended".' };
  }
  if (filter === 'inactive') {
    return { icon: 'notifications-off-outline', title: 'No paused or ended reminders' };
  }
  return {
    icon:  'notifications-outline',
    title: 'No reminders yet',
    sub:   canManage
      ? 'Add a reminder for something that should not be forgotten, such as a daily reading or booking a check-up.'
      : 'Reminders for follow-up visits and care-plan reviews appear here when they are set.',
  };
}

function PatientReminders({ patientSystemId, canManage, onOpenNotificationSettings }: {
  patientSystemId: string;
  canManage: boolean;
  onOpenNotificationSettings?: () => void;
}) {
  const [filter, setFilter] = useState<FilterKey>('all');
  const list = useReminders(patientSystemId, { active: FILTER_ACTIVE[filter] });
  const rowActions = useReminderRowActions();
  const [dialog, setDialog] = useState<DialogState>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  // The server refused the list: nothing can be managed either, whatever the caller was told.
  const refused = list.errorStatus === 403;
  const noAccess = refused && list.items.length === 0;
  const manage = canManage && !refused;
  const { refetch, setItems } = list;

  /** Shows the stored reminder at once, then reloads: a write changes the order (active first, soonest due). */
  const applySaved = (saved: Reminder) => {
    setItems(prev => prev.map(r => (r.reminderId === saved.reminderId ? saved : r)));
    refetch();
  };

  const showFailure = (error: ApiError) => {
    if (error.status === 404) {
      // Deleted meanwhile (by someone else, or an automatic reminder its source no longer needs).
      setNotice({ tone: 'warning', text: 'This reminder no longer exists. The list has been refreshed.' });
      refetch();
      return;
    }
    setNotice({ tone: 'danger', text: error.message });
    // The reminder or the caller's access is not what the row showed (ended meanwhile, access changed).
    if (error.status === 403 || error.status === 409) refetch();
  };

  const changeFilter = (next: FilterKey) => {
    setNotice(null);
    setFilter(next);
  };

  const onPause = async (reminder: Reminder) => {
    setNotice(null);
    const result = await rowActions.pause(reminder.reminderId);
    if (result.ok) {
      applySaved(result.data);
      setNotice({ tone: 'success', text: `"${reminder.title}" is paused. It sends no notifications until it is resumed.` });
    } else if (result.error) {
      showFailure(result.error);
    }
  };

  const onResume = async (reminder: Reminder) => {
    setNotice(null);
    const result = await rowActions.resume(reminder.reminderId);
    if (result.ok) {
      applySaved(result.data);
      setNotice({ tone: 'success', text: `"${reminder.title}" is active again.` });
    } else if (result.error) {
      showFailure(result.error);
    }
  };

  const onDelete = async (reminder: Reminder) => {
    const confirmed = await confirmAction(
      'Delete reminder',
      `Delete "${reminder.title}"? It stops sending notifications and cannot be restored. Notifications already sent are kept.`,
      'Delete',
      true,
    );
    if (!confirmed) return;
    setNotice(null);
    const result = await rowActions.remove(reminder.reminderId);
    if (result.ok) {
      setItems(prev => prev.filter(r => r.reminderId !== reminder.reminderId));
      refetch();
      setNotice({ tone: 'success', text: `"${reminder.title}" was deleted.` });
    } else if (result.error) {
      showFailure(result.error);
    }
  };

  const onSaved = (saved: Reminder, kind: ReminderSaveKind) => {
    const before = dialog?.mode === 'edit' ? dialog.reminder : null;
    setDialog(null);
    if (kind === 'unchanged') return;
    if (kind === 'created') {
      setNotice({ tone: 'success', text: `"${saved.title}" was added.` });
      // A new reminder is active: under "Paused or ended" it would not be visible.
      if (filter === 'inactive') setFilter('all');
      else refetch();
      return;
    }
    applySaved(saved);
    const restarted = before?.status === 'ENDED' && saved.status === 'ACTIVE';
    setNotice({ tone: 'success', text: restarted ? `"${saved.title}" was saved and is active again.` : `"${saved.title}" was saved.` });
  };

  const openCreate = () => {
    setNotice(null);
    setDialog({ mode: 'create' });
  };

  const openEdit = (reminder: Reminder) => {
    setNotice(null);
    setDialog({ mode: 'edit', reminder });
  };

  const busy = rowActions.busy;

  return (
    <SectionCard
      title="Reminders"
      subtitle={`Times are clinic time (${CLINIC_ZONE_LABEL}).`}
      action={manage ? <Button label="Add reminder" icon="add" onPress={openCreate} /> : undefined}
    >
      {noAccess ? null : <AutomaticRemindersNote onOpenSettings={onOpenNotificationSettings} />}

      {!canManage && !refused ? (
        <Text style={styles.readOnly}>You can see these reminders but not change them.</Text>
      ) : null}

      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      <PagedList<Reminder>
        state={list}
        label="Reminders"
        keyOf={reminder => reminder.reminderId}
        noAccessMessage={NO_ACCESS}
        empty={emptyState(filter, manage)}
        header={noAccess ? undefined : (
          <View style={styles.filter}>
            <SelectField<FilterKey> label="Show" value={filter} onChange={changeFilter} options={FILTER_OPTIONS} />
          </View>
        )}
        renderItem={reminder => (
          <ReminderRow
            reminder={reminder}
            canManage={manage}
            busyAction={busy?.reminderId === reminder.reminderId ? busy.action : null}
            disabled={busy !== null}
            onPause={r => { void onPause(r); }}
            onResume={r => { void onResume(r); }}
            onEdit={openEdit}
            onDelete={r => { void onDelete(r); }}
          />
        )}
      />

      {dialog ? (
        <ReminderFormDialog
          patientSystemId={patientSystemId}
          reminder={dialog.mode === 'edit' ? dialog.reminder : null}
          onClose={() => setDialog(null)}
          onSaved={onSaved}
        />
      ) : null}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  note: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.sm, borderWidth: 1, borderRadius: radius.md,
  },
  noteIcon: { flexShrink: 0 },
  // flexBasis lets the text keep a readable width before the button wraps under it on a phone.
  noteText: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, fontSize: fontSize.sm, lineHeight: 19 },
  readOnly: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  filter:   { width: '100%', maxWidth: 260 },
});
