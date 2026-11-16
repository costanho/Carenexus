import { useState } from 'react';
import { apiErrorMessage } from './use-appointments';
import { createThread } from '@/lib/communications/api';
import { showMessage } from '@/lib/ui/dialogs';

/**
 * "Message patient" from a patient or appointment card: opens the doctor's private conversation
 * with that patient. POST /api/communications/threads returns the existing PRIVATE thread for the
 * same two members and patient (200) or creates it (201), so pressing it twice never duplicates.
 * `onOpenThread` switches the dashboard to Messages with the thread selected.
 */
export function useDoctorMessagePatient(onOpenThread?: (threadId: number) => void) {
  const [pendingPatient, setPendingPatient] = useState<string | null>(null);

  async function messagePatient(patientSystemId: string) {
    if (!onOpenThread || !patientSystemId || pendingPatient !== null) return;
    setPendingPatient(patientSystemId);
    try {
      const thread = await createThread({
        patientSystemId,
        type:            'PRIVATE',
        memberSystemIds: [patientSystemId],
      });
      onOpenThread(thread.threadId);
    } catch (e) {
      showMessage('Could not open the conversation', apiErrorMessage(e, 'Please try again.'));
    } finally {
      setPendingPatient(null);
    }
  }

  return { messagePatient, pendingPatient, enabled: !!onOpenThread };
}
