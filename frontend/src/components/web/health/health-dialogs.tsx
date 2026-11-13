import { useCallback, useRef, useState } from 'react';
import { type BannerTone, Modal } from '@/components/web/ui';
import { entryView, metricSeverityBadge, toReadingEntries } from '@/lib/health/catalog';
import type { RecordHealthMetricsResponse, SymptomLog } from '@/lib/health/types';
import { confirmAction } from '@/lib/ui/dialogs';
import { RecordVitalsForm } from './record-vitals-form';
import { SymptomForm } from './symptom-form';

// The two entry forms as dialogs. A dialog cannot be closed while its save is running, a click outside it does
// nothing, and closing it with something typed asks first.

/** What to tell the person after a save: a sentence and the tone of the banner that carries it. */
export interface HealthNotice { tone: BannerTone; text: string }

/** "2 readings saved.", or which of the saved readings the server flagged as abnormal or critical. */
export function recordedNotice(result: RecordHealthMetricsResponse): HealthNotice {
  const views = toReadingEntries(result.metrics).map(entryView);
  const flagged = views.flatMap(v => {
    const badge = metricSeverityBadge(v.severity);
    return badge ? [`${v.label} ${v.valueText} ${v.unitLabel} (${badge.label})`.replace(/\s+/g, ' ')] : [];
  });
  if (flagged.length === 0) {
    return { tone: 'success', text: views.length === 1 ? 'Reading saved.' : `${views.length} readings saved.` };
  }
  return { tone: 'warning', text: `Saved. Outside the reference range: ${flagged.join(', ')}.` };
}

/** "Symptoms saved.", with a reminder of who is told when they are severe or critical. */
export function loggedNotice(log: SymptomLog): HealthNotice {
  if (log.severity === 'SEVERE' || log.severity === 'CRITICAL') {
    return { tone: 'warning', text: 'Symptoms saved. Doctors on the care team are notified about severe and critical symptoms.' };
  }
  return { tone: 'success', text: 'Symptoms saved.' };
}

interface EntryDialogProps {
  open:    boolean;
  /** Called when the dialog should close: cancelled, dismissed, or saved. */
  onClose: () => void;
  /** The patient the entry is for. Required for a doctor or caregiver; a patient may leave it out. */
  patientSystemId?: string | null;
  /** Shown in the title when the entry is for someone else ("Record vitals for Mary Davis"). */
  patientName?:     string | null;
}

/** Busy and dirty state reported by the form inside, and a close handler that respects both. */
function useEntryDialog(onClose: () => void, discardTitle: string) {
  const [busy, setBusy] = useState(false);
  const dirty = useRef(false);
  const onDirtyChange = useCallback((value: boolean) => { dirty.current = value; }, []);
  const requestClose = useCallback(async () => {
    if (dirty.current) {
      const discard = await confirmAction(discardTitle, 'What you entered has not been saved.', 'Discard', true);
      if (!discard) return;
    }
    dirty.current = false;
    onClose();
  }, [discardTitle, onClose]);
  const finish = useCallback(() => { dirty.current = false; onClose(); }, [onClose]);
  return { busy, setBusy, onDirtyChange, requestClose, finish };
}

export function RecordVitalsDialog({ open, onClose, patientSystemId, patientName, onRecorded }: EntryDialogProps & {
  onRecorded?: (result: RecordHealthMetricsResponse) => void;
}) {
  const dialog = useEntryDialog(onClose, 'Discard these readings?');
  return (
    <Modal
      open={open}
      title={patientName ? `Record vitals for ${patientName}` : 'Record vitals'}
      onClose={() => { void dialog.requestClose(); }}
      size="lg"
      dismissOnBackdrop={false}
      busy={dialog.busy}
    >
      <RecordVitalsForm
        patientSystemId={patientSystemId}
        onBusyChange={dialog.setBusy}
        onDirtyChange={dialog.onDirtyChange}
        onCancel={() => { void dialog.requestClose(); }}
        onRecorded={result => { dialog.finish(); onRecorded?.(result); }}
      />
    </Modal>
  );
}

export function SymptomDialog({ open, onClose, patientSystemId, patientName, onLogged }: EntryDialogProps & {
  onLogged?: (log: SymptomLog) => void;
}) {
  const dialog = useEntryDialog(onClose, 'Discard this symptom log?');
  return (
    <Modal
      open={open}
      title={patientName ? `Log symptoms for ${patientName}` : 'Log symptoms'}
      onClose={() => { void dialog.requestClose(); }}
      size="lg"
      dismissOnBackdrop={false}
      busy={dialog.busy}
    >
      <SymptomForm
        patientSystemId={patientSystemId}
        onBusyChange={dialog.setBusy}
        onDirtyChange={dialog.onDirtyChange}
        onCancel={() => { void dialog.requestClose(); }}
        onLogged={log => { dialog.finish(); onLogged?.(log); }}
      />
    </Modal>
  );
}
