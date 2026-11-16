import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type { Thread } from '@/lib/communications/types';
import { MAX_SUPPORT_SUBJECT_LENGTH, type PublicSettings, type SupportThread } from './types';

// One function per support endpoint. Messages of a support conversation use the normal conversation
// endpoints in lib/communications/api.ts with the thread id returned here.

/**
 * The signed-in user's open support conversation; one is created when there is none (201 and 200 are the same
 * to the caller). No message is sent and nobody is notified until the user writes in it.
 * Patients, caregivers and doctors only: an administrator gets 403.
 */
export async function openSupportThread(subject?: string | null): Promise<Thread> {
  const trimmed = (subject ?? '').trim().slice(0, MAX_SUPPORT_SUBJECT_LENGTH);
  const { data } = await api.post<Thread>('/api/communications/support-thread', trimmed ? { subject: trimmed } : {});
  return data;
}

/**
 * Administrator inbox: support conversations with at least one message, newest activity first.
 * `open` true lists only open ones, false only closed ones; leave it out for both.
 */
export function fetchSupportThreads(opts: { open?: boolean | null; page?: number; size?: number } = {}): Promise<PageResponse<SupportThread>> {
  return fetchPage<SupportThread>('/api/admin/support/threads', opts.page ?? 0, opts.size ?? 20, { open: opts.open });
}

/** Closes a support conversation (administrators). Closing one that is already closed returns it unchanged. */
export async function closeSupportThread(threadId: number): Promise<SupportThread> {
  const { data } = await api.post<SupportThread>(`/api/admin/support/threads/${threadId}/close`);
  return data;
}

/** Clinic name, support contact and clinic hours. Needs a signed-in user. */
export async function fetchPublicSettings(): Promise<PublicSettings> {
  const { data } = await api.get<PublicSettings>('/api/settings/public');
  return data;
}
