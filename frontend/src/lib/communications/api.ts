import { api } from '@/lib/auth/auth.interceptor';
import { installSignOutReset } from './session';
import type {
  AppNotification,
  CreateThreadBody,
  Message,
  MessagesResponse,
  PageResponse,
  ReadResponse,
  RecipientsResponse,
  SendMessageBody,
  Thread,
  UnreadCounts,
} from './types';

// One function per messaging / notification endpoint (CONTRACT.md 7.3, 7.4).

// Drafts, outbox and unread counts are cleared when the user signs out.
installSignOutReset();

export async function fetchRecipients(patientSystemId?: string | null): Promise<RecipientsResponse> {
  const params = patientSystemId ? { patientSystemId } : undefined;
  const { data } = await api.get<RecipientsResponse>('/api/communications/recipients', { params });
  return data;
}

export async function fetchThreads(opts: {
  archived?: boolean; patientSystemId?: string | null; page?: number; size?: number;
} = {}): Promise<PageResponse<Thread>> {
  const params: Record<string, string | number | boolean> = {
    archived: !!opts.archived,
    page:     opts.page ?? 0,
    size:     opts.size ?? 20,
  };
  if (opts.patientSystemId) params.patientSystemId = opts.patientSystemId;
  const { data } = await api.get<PageResponse<Thread>>('/api/communications/threads', { params });
  return data;
}

export async function fetchThread(threadId: number): Promise<Thread> {
  const { data } = await api.get<Thread>(`/api/communications/threads/${threadId}`);
  return data;
}

export async function createThread(body: CreateThreadBody): Promise<Thread> {
  const { data } = await api.post<Thread>('/api/communications/threads', body);
  return data;
}

export async function addThreadMembers(threadId: number, systemIds: string[]): Promise<Thread> {
  const { data } = await api.post<Thread>(`/api/communications/threads/${threadId}/members`, { systemIds });
  return data;
}

export async function removeThreadMember(threadId: number, systemId: string): Promise<void> {
  await api.delete(`/api/communications/threads/${threadId}/members/${encodeURIComponent(systemId)}`);
}

export async function setThreadArchived(threadId: number, archived: boolean): Promise<void> {
  await api.put(`/api/communications/threads/${threadId}/archive`, { archived });
}

export async function fetchMessages(threadId: number, opts: {
  before?: number; after?: number; limit?: number;
} = {}): Promise<MessagesResponse> {
  const params: Record<string, number> = { limit: opts.limit ?? 30 };
  if (opts.before != null) params.before = opts.before;
  else if (opts.after != null) params.after = opts.after;
  const { data } = await api.get<MessagesResponse>(`/api/communications/threads/${threadId}/messages`, { params });
  return data;
}

export async function sendMessage(threadId: number, body: SendMessageBody): Promise<Message> {
  const { data } = await api.post<Message>(`/api/communications/threads/${threadId}/messages`, body);
  return data;
}

export async function markThreadRead(threadId: number, lastReadMessageId: number): Promise<ReadResponse> {
  const { data } = await api.post<ReadResponse>(`/api/communications/threads/${threadId}/read`, { lastReadMessageId });
  return data;
}

export async function fetchNotifications(opts: {
  unreadOnly?: boolean; page?: number; size?: number;
} = {}): Promise<PageResponse<AppNotification>> {
  const { data } = await api.get<PageResponse<AppNotification>>('/api/notifications', {
    params: { unreadOnly: !!opts.unreadOnly, page: opts.page ?? 0, size: opts.size ?? 20 },
  });
  return data;
}

export async function fetchUnreadCounts(): Promise<UnreadCounts> {
  const { data } = await api.get<UnreadCounts>('/api/notifications/unread-count');
  return data;
}

export async function markNotificationRead(notificationId: number): Promise<void> {
  await api.post(`/api/notifications/${notificationId}/read`);
}

export async function markAllNotificationsRead(): Promise<void> {
  await api.post('/api/notifications/read-all');
}

// ─── Error helpers ────────────────────────────────────────────────────────────

export function httpStatus(e: unknown): number | null {
  const s = (e as any)?.response?.status;
  return typeof s === 'number' ? s : null;
}

/** True when the request never reached the server (offline, DNS, timeout). */
export function isNetworkError(e: unknown): boolean {
  return !!e && !(e as any).response;
}

/** Random id for clientMessageId / clientThreadId: 8-64 chars of [A-Za-z0-9_-]. */
export function newClientId(): string {
  const c: Crypto | undefined = typeof globalThis !== 'undefined' ? (globalThis as any).crypto : undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}
