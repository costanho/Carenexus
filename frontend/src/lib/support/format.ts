import { roleLabel } from '@/lib/communications/format';
import type { Thread, ThreadMember } from '@/lib/communications/types';
import {
  SUPPORT_ADMIN_ROLE, SUPPORT_CONVERSATION_TYPE,
  type PublicSettings, type SupportThread, type SupportThreadState,
} from './types';

// Display rules for support conversations and the clinic details on Help & Support.

export function isSupportThread(thread: Pick<Thread, 'type'> | null | undefined): boolean {
  return !!thread && (thread.type as string) === SUPPORT_CONVERSATION_TYPE;
}

/** True for a support conversation an administrator has closed. Clinical conversations are never closed. */
export function isClosedSupportThread(thread: Thread | null | undefined): boolean {
  return isSupportThread(thread) && (thread as SupportThreadState).closed === true;
}

/** The user who opened a support conversation: its only member who is not an administrator. */
export function supportOpener(thread: Thread): ThreadMember | null {
  return thread.members.find(m => (m.role as string) !== SUPPORT_ADMIN_ROLE) ?? null;
}

/** Role name for a members list; administrators in a support conversation are shown as Support. */
export function memberRoleLabel(role: string): string {
  return role === SUPPORT_ADMIN_ROLE ? 'Support' : roleLabel(role);
}

/**
 * Title of a support conversation: its subject when it has one; otherwise the person who opened it for an
 * administrator, and "Support conversation" for that person.
 */
export function supportThreadTitle(thread: Thread, mySystemId: string | null | undefined): string {
  const subject = thread.subject?.trim();
  if (subject) return subject;
  const opener = supportOpener(thread);
  if (opener && opener.systemId !== mySystemId) return opener.label;
  return 'Support conversation';
}

/** "Sarah Dube (Caregiver)": the opener of an inbox row with their role. */
export function supportOpenerLabel(row: Pick<SupportThread, 'openerName' | 'openerRole' | 'openerSystemId'>): string {
  const name = row.openerName?.trim() || row.openerSystemId;
  return `${name} (${roleLabel(row.openerRole)})`;
}

/** Title of an administrator inbox row: the subject, or the person who opened it. */
export function supportRowTitle(row: SupportThread): string {
  return row.subject?.trim() || supportOpenerLabel(row);
}

/** Applies the closed state an inbox row reports to a loaded conversation with the same id. */
export function withSupportRow(thread: Thread, row: SupportThread | null | undefined): Thread {
  if (!row || row.threadId !== thread.threadId) return thread;
  const state: SupportThreadState = { closed: !row.open, closedAt: row.closedAt };
  return { ...thread, ...state };
}

/**
 * A closed support conversation is never reopened, so a copy that still says "open" (a response that was
 * already on its way when the conversation was closed) must not undo the closed state already known.
 */
export function keepClosed(previous: Thread | null | undefined, next: Thread): Thread {
  if (!previous || previous.threadId !== next.threadId) return next;
  if (!isClosedSupportThread(previous) || isClosedSupportThread(next)) return next;
  const state: SupportThreadState = { closed: true, closedAt: (previous as SupportThreadState).closedAt ?? null };
  return { ...next, ...state };
}

/** The label the server gives a member with this role in a support conversation. */
function openerMemberLabel(row: Pick<SupportThread, 'openerName' | 'openerRole' | 'openerSystemId'>): string {
  const name = row.openerName?.trim() || row.openerSystemId;
  if (row.openerRole === 'DOCTOR') return `Dr. ${name}`;
  if (row.openerRole === 'CAREGIVER') return `${name} (Caregiver)`;
  return name;
}

/**
 * What an inbox row says about its conversation, in the shape of a `Thread`, so the conversation can show its
 * title and state while its details are still loading. The details replace it as soon as they arrive.
 */
export function supportRowAsThread(row: SupportThread): Thread {
  const provisional = {
    threadId:         row.threadId,
    type:             SUPPORT_CONVERSATION_TYPE,
    subject:          row.subject,
    patientSystemId:  null,
    patientName:      null,
    members:          [{ systemId: row.openerSystemId, name: row.openerName, role: row.openerRole, label: openerMemberLabel(row) }],
    lastMessage:      row.lastMessage,
    unreadCount:      0,
    hasUrgentUnread:  false,
    archived:         false,
    lastMessageAt:    row.lastMessageAt,
    createdAt:        row.createdAt,
    canManageMembers: false,
    closed:           !row.open,
    closedAt:         row.closedAt,
  };
  // `Thread` does not describe support conversations yet (no patient, closed state): see SUPPORT_CONVERSATION_TYPE.
  return provisional as unknown as Thread;
}

function twoDigits(hour: number): string {
  return String(Math.trunc(hour)).padStart(2, '0');
}

function validHour(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

/** "08:00 to 18:00" in clinic time, or null when the server sent no usable hours. */
export function clinicHoursText(settings: Pick<PublicSettings, 'openHour' | 'closeHour'> | null | undefined): string | null {
  if (!settings || !validHour(settings.openHour, 0, 23) || !validHour(settings.closeHour, 1, 24)) return null;
  return `${twoDigits(settings.openHour)}:00 to ${twoDigits(settings.closeHour)}:00`;
}

/** "Africa/Harare" for display; null when the zone is missing. */
export function clinicTimeZoneText(zone: string | null | undefined): string | null {
  const value = (zone ?? '').trim();
  return value ? value.replace(/_/g, ' ') : null;
}
