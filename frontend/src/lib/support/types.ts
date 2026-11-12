import type { LastMessage } from '@/lib/communications/types';

// Types of the support conversation endpoints (POST /api/communications/support-thread,
// GET /api/admin/support/threads, POST /api/admin/support/threads/{id}/close) and of
// GET /api/settings/public. Instants are ISO-8601 UTC.

/**
 * Wire value of `Thread.type` for a support conversation. The shared `ConversationType` union does not list it
 * yet, so it is compared as a string through this constant.
 */
export const SUPPORT_CONVERSATION_TYPE = 'SUPPORT';

/** Wire value of `ThreadMember.role` / `Message.senderRole` for an administrator in a support conversation. */
export const SUPPORT_ADMIN_ROLE = 'ADMIN';

/** Exact server text (409) when a message is sent into a closed support conversation. */
export const SUPPORT_CLOSED_TEXT = 'This support conversation is closed';

/** Fields a support conversation adds to `Thread`; `closed` is always sent by the server. */
export interface SupportThreadState {
  closed?:   boolean;
  closedAt?: string | null;
}

/** Who opened a support conversation. Administrators never open one. */
export type SupportOpenerRole = 'PATIENT' | 'DOCTOR' | 'CAREGIVER';

/** One row of the administrator inbox (`SupportThreadResponse`). */
export interface SupportThread {
  /** Use it with the normal conversation endpoints (details, messages, send, read). */
  threadId:       number;
  subject:        string | null;
  openerSystemId: string;
  openerName:     string;
  openerRole:     SupportOpenerRole;
  open:           boolean;
  closedAt:       string | null;
  lastMessage:    LastMessage | null;
  lastMessageAt:  string | null;
  createdAt:      string;
  /** The thread has a message from someone else that this administrator has not read. */
  unread:         boolean;
}

/** `GET /api/settings/public`: clinic identity, support contact and clinic hours. */
export interface PublicSettings {
  clinicName:     string;
  supportEmail:   string | null;
  supportPhone:   string | null;
  /** IANA zone id of the clinic, for example Africa/Harare. */
  clinicTimeZone: string;
  /** Hour of day the clinic opens, 0 to 23 (clinic wall clock). */
  openHour:       number;
  /** Hour of day the clinic closes, 1 to 24 (24 = midnight). */
  closeHour:      number;
  /** False when the clinic is closed on Saturday and Sunday. */
  allowWeekends:  boolean;
}

export const MAX_SUPPORT_SUBJECT_LENGTH = 200;
