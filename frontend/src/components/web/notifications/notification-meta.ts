import type { Ionicons } from '@expo/vector-icons';
import type { AppNotification, NotificationType } from '@/lib/communications/types';

// How a notification looks and what activating it does. Shared by the full-page list and the bell preview
// (and by the native notifications screen, which renders the same list), so both always agree.

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

export interface NotificationTypeMeta {
  /** Label used by the type filter ("Appointments"). */
  label: string;
  /** The type as it reads in front of "notifications" ("No appointment notifications"). */
  kind:  string;
  icon:  IoniconsName;
  /** Icon colour; at least 4.5:1 against `bg` and against white. */
  color: string;
  /** Background of the icon circle. */
  bg:    string;
}

/**
 * Icon and colour per notification type. Keyed on `type` only: the server does not tie a category to a type,
 * so the category is never used to pick an icon.
 */
export const NOTIFICATION_TYPE_META: Record<NotificationType, NotificationTypeMeta> = {
  APPOINTMENT: { label: 'Appointments',  kind: 'appointment',  icon: 'calendar-outline',        color: '#1D4ED8', bg: '#DBEAFE' },
  MEDICATION:  { label: 'Medication',    kind: 'medication',   icon: 'medkit-outline',          color: '#6D28D9', bg: '#EDE9FE' },
  LAB_RESULT:  { label: 'Results',       kind: 'result',       icon: 'flask-outline',           color: '#0E7490', bg: '#CFFAFE' },
  MESSAGE:     { label: 'Messages',      kind: 'message',      icon: 'chatbubble-outline',      color: '#4338CA', bg: '#E0E7FF' },
  AI_ALERT:    { label: 'Health alerts', kind: 'health alert', icon: 'pulse-outline',           color: '#B45309', bg: '#FEF3C7' },
  PAYMENT:     { label: 'Billing',       kind: 'billing',      icon: 'card-outline',            color: '#047857', bg: '#D1FAE5' },
  REFERRAL:    { label: 'Referrals',     kind: 'referral',     icon: 'swap-horizontal-outline', color: '#BE185D', bg: '#FCE7F3' },
  SYSTEM:      { label: 'General',       kind: 'general',      icon: 'notifications-outline',   color: '#374151', bg: '#F3F4F6' },
};

function isNotificationType(value: string): value is NotificationType {
  return Object.prototype.hasOwnProperty.call(NOTIFICATION_TYPE_META, value);
}

/** A type this build does not know (a newer server) is shown like a general notification. */
export function notificationTypeMeta(type: string | null | undefined): NotificationTypeMeta {
  return typeof type === 'string' && isNotificationType(type) ? NOTIFICATION_TYPE_META[type] : NOTIFICATION_TYPE_META.SYSTEM;
}

/**
 * A notification type from an untyped source (a URL parameter such as `?type=PAYMENT`); null when the value is
 * missing or not one of the types, so an unknown value is never sent to the server as a filter.
 */
export function parseNotificationType(value: string | null | undefined): NotificationType | null {
  const key = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return isNotificationType(key) ? key : null;
}

/** CRITICAL gets the critical treatment, HIGH the urgent one; LOW and NORMAL (and unknown values) are plain. */
export type NotificationEmphasis = 'critical' | 'urgent' | null;

export function notificationEmphasis(priority: string | null | undefined): NotificationEmphasis {
  if (priority === 'CRITICAL') return 'critical';
  if (priority === 'HIGH') return 'urgent';
  return null;
}

/** The word shown on the row and read out with it ("Critical", "Urgent"). */
export function emphasisLabel(emphasis: NotificationEmphasis): string | null {
  if (emphasis === 'critical') return 'Critical';
  if (emphasis === 'urgent') return 'Urgent';
  return null;
}

/** What activating a notification opens; null when it is informational (activating it only marks it read). */
export type NotificationDestination = 'thread' | 'caregiver-access' | 'link' | null;

/** Which of the host's handlers are available to the list. */
export interface NotificationHandlers {
  /** The legacy caregiver-access callback is set (patient dashboard, native screens). */
  caregiverAccess: boolean;
  /** Something follows `actionUrl` (the dashboards' `followActionUrl`). */
  link: boolean;
}

/**
 * The activation rule, in order: a MESSAGE notification with a conversation opens that conversation; a caregiver
 * access notification uses the legacy callback where the host still passes it; anything else follows its
 * `actionUrl`; a notification without one is informational. `target` is never used to pick a destination
 * (apart from the legacy callback): links are the only routing.
 */
export function notificationDestination(item: AppNotification, handlers: NotificationHandlers): NotificationDestination {
  if (item.type === 'MESSAGE' && item.threadId != null) return 'thread';
  if (item.target === 'CAREGIVER_ACCESS' && handlers.caregiverAccess) return 'caregiver-access';
  if (item.actionUrl && handlers.link) return 'link';
  return null;
}

/** Screen-reader hint for a row: where it goes, or that activating it marks it read. */
export function destinationHint(destination: NotificationDestination, read: boolean): string | undefined {
  switch (destination) {
    case 'thread':           return 'Opens the conversation';
    case 'caregiver-access': return 'Opens your caregiver access settings';
    case 'link':             return 'Opens the related page';
    default:                 return read ? undefined : 'Marks this notification as read';
  }
}
