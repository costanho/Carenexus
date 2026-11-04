import { useCallback, useState } from 'react';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { createThread, fetchRecipients, fetchThreads, newClientId } from '@/lib/communications/api';
import type { Thread } from '@/lib/communications/types';
import type { CaregiverDependent } from './use-caregiver-dependents';

// ─── Messaging state for one loved one ────────────────────────────────────────

export type CaregiverMessagingStatus = 'ALLOWED' | 'WAITING' | 'NOT_ALLOWED';

export interface CaregiverMessagingState {
  status: CaregiverMessagingStatus;
  /** Why messaging is unavailable (null when allowed). Shown next to a disabled "Message Care Team". */
  reason: string | null;
}

function firstName(dep: Pick<CaregiverDependent, 'patientName'>): string {
  return (dep.patientName ?? '').trim().split(/\s+/)[0] || 'the patient';
}

/**
 * Messaging for the caregiver, from the server's effective values: `messagingAllowed` (approved by the patient
 * and the grant is active) and `pendingApproval` (a request is waiting for the patient). `canMessageCareTeam`
 * alone is only the request flag; an expired or revoked grant with that flag is "not allowed", not "waiting".
 */
export function caregiverMessagingState(
  dep: Pick<CaregiverDependent, 'patientName' | 'canMessageCareTeam' | 'messagingAllowed' | 'pendingApproval' | 'accessStatus'>,
): CaregiverMessagingState {
  if (dep.messagingAllowed) return { status: 'ALLOWED', reason: null };
  if (dep.pendingApproval && dep.canMessageCareTeam) {
    return { status: 'WAITING', reason: `Waiting for ${firstName(dep)} to approve messaging` };
  }
  if (dep.accessStatus && dep.accessStatus !== 'ACTIVE') {
    return { status: 'NOT_ALLOWED', reason: 'Your access for this person is no longer active' };
  }
  return { status: 'NOT_ALLOWED', reason: 'Messaging is not allowed for your access' };
}

/**
 * Same rule for "join video calls": `joinCallsAllowed` is effective now; the request is waiting only while
 * `pendingApproval` is set.
 */
export function caregiverCallsStatus(
  dep: Pick<CaregiverDependent, 'canJoinCalls' | 'joinCallsAllowed' | 'pendingApproval'>,
): CaregiverMessagingStatus {
  if (dep.joinCallsAllowed) return 'ALLOWED';
  if (dep.pendingApproval && dep.canJoinCalls) return 'WAITING';
  return 'NOT_ALLOWED';
}

// ─── Open the care-team conversation ──────────────────────────────────────────

/** Most recent care-team conversation about this patient that the caregiver is in (inbox first, then archived). */
async function findCareTeamThread(patientSystemId: string): Promise<Thread | null> {
  for (const archived of [false, true]) {
    const page = await fetchThreads({ patientSystemId, archived, size: 50 });
    // The list is ordered by last activity, newest first.
    const found = (page?.content ?? []).find(t => t.type === 'CARE_TEAM' && t.patientSystemId === patientSystemId);
    if (found) return found;
  }
  return null;
}

/** Starts a care-team conversation with every doctor the caregiver may add (the patient is added by the server). */
async function startCareTeamThread(dep: CaregiverDependent): Promise<Thread> {
  const recipients = await fetchRecipients(dep.patientSystemId);
  const doctors = (recipients?.recipients ?? []).filter(r => r.role === 'DOCTOR' && r.canAddToCareTeam);
  if (doctors.length === 0) {
    throw new NoCareTeamError(`${firstName(dep)} has no doctor you can message yet.`);
  }
  return createThread({
    patientSystemId: dep.patientSystemId,
    type:            'CARE_TEAM',
    memberSystemIds: doctors.map(d => d.systemId),
    clientThreadId:  newClientId(),
  });
}

class NoCareTeamError extends Error {}

interface CareTeamConversation {
  /** accessId of the loved one whose conversation is being opened, or null. */
  openingFor: number | null;
  error:      string | null;
  /** Opens the existing care-team conversation for `dep`, or starts one. No-op unless messaging is allowed. */
  open:       (dep: CaregiverDependent) => Promise<void>;
}

/**
 * "Message Care Team" for a loved one: opens Messages on their most recent care-team conversation, or starts a
 * new one with their doctors. `onOpenThread` is the dashboard's handler that switches to Messages on a thread.
 */
export function useCareTeamConversation(onOpenThread: ((threadId: number) => void) | undefined): CareTeamConversation {
  const [openingFor, setOpeningFor] = useState<number | null>(null);
  const [error,      setError]      = useState<string | null>(null);

  const open = useCallback(async (dep: CaregiverDependent) => {
    if (!onOpenThread || openingFor != null) return;
    if (caregiverMessagingState(dep).status !== 'ALLOWED') return;
    setOpeningFor(dep.accessId);
    setError(null);
    try {
      const thread = (await findCareTeamThread(dep.patientSystemId)) ?? (await startCareTeamThread(dep));
      onOpenThread(thread.threadId);
    } catch (e) {
      setError(e instanceof NoCareTeamError ? e.message : apiErrorMessage(e, 'Could not open the conversation. Please try again.'));
    } finally {
      setOpeningFor(null);
    }
  }, [onOpenThread, openingFor]);

  return { openingFor, error, open };
}
