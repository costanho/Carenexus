import type { Thread, ThreadMember } from './types';

// Times are shown in the clinic zone (CONTRACT.md 0.6).
const ZONE = 'Africa/Harare';

const timeFmt = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, hour: '2-digit', minute: '2-digit' });
const dayFmt  = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, day: 'numeric', month: 'short' });
const keyFmt  = new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' });
const fullFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: ZONE, weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
});
const dateHeadingFmt = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

function toDate(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "Today" style key (YYYY-MM-DD in the clinic zone) for grouping by day. */
export function dayKey(iso: string | null | undefined): string {
  const d = toDate(iso);
  return d ? keyFmt.format(d) : '';
}

/** Short time for lists: HH:mm today, otherwise "3 Nov". */
export function shortTime(iso: string | null | undefined): string {
  const d = toDate(iso);
  if (!d) return '';
  return dayKey(iso) === keyFmt.format(new Date()) ? timeFmt.format(d) : dayFmt.format(d);
}

export function clockTime(iso: string | null | undefined): string {
  const d = toDate(iso);
  return d ? timeFmt.format(d) : '';
}

export function fullTime(iso: string | null | undefined): string {
  const d = toDate(iso);
  return d ? fullFmt.format(d) : '';
}

export function dayHeading(iso: string | null | undefined): string {
  const d = toDate(iso);
  if (!d) return '';
  const k = keyFmt.format(d);
  const today = keyFmt.format(new Date());
  const yesterday = keyFmt.format(new Date(Date.now() - 86_400_000));
  if (k === today) return 'Today';
  if (k === yesterday) return 'Yesterday';
  return dateHeadingFmt.format(d);
}

/** Members other than the current user. */
export function otherMembers(thread: Thread, mySystemId: string | null | undefined): ThreadMember[] {
  return thread.members.filter(m => m.systemId !== mySystemId);
}

export function threadTitle(thread: Thread, mySystemId: string | null | undefined): string {
  if (thread.subject && thread.subject.trim()) return thread.subject.trim();
  const others = otherMembers(thread, mySystemId);
  if (others.length === 0) return thread.type === 'CARE_TEAM' ? 'Care team conversation' : 'Conversation';
  if (others.length <= 2) return others.map(m => m.label).join(', ');
  return `${others[0].label} and ${others.length - 1} others`;
}

export function initials(name: string | null | undefined): string {
  return (name ?? '?').replace(/^Dr\.\s+/, '').split(/\s+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase() || '?';
}

export function roleLabel(role: string): string {
  switch (role) {
    case 'DOCTOR':    return 'Doctor';
    case 'PATIENT':   return 'Patient';
    case 'CAREGIVER': return 'Caregiver';
    default:          return role;
  }
}
