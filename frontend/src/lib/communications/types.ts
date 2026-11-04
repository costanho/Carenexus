// Types mirroring the messaging + notifications API (docs/communications/CONTRACT.md, section 7).
// All time fields are ISO-8601 UTC instants.

export type MemberRole       = 'PATIENT' | 'DOCTOR' | 'CAREGIVER';
export type ConversationType = 'PRIVATE' | 'CARE_TEAM';
export type MessagingMode    = MemberRole;

export interface PageResponse<T> {
  content:       T[];
  page:          number;
  size:          number;
  totalElements: number;
  totalPages:    number;
}

export interface Recipient {
  systemId:         string;
  name:             string;
  role:             MemberRole;
  label:            string;
  canStartPrivate:  boolean;
  canAddToCareTeam: boolean;
}

export interface RecipientsResponse {
  patientSystemId: string;
  patientName:     string;
  recipients:      Recipient[];
}

export interface ThreadMember {
  systemId: string;
  name:     string;
  role:     MemberRole;
  label:    string;
}

export interface LastMessage {
  messageId:      number;
  senderSystemId: string;
  senderLabel:    string;
  preview:        string;
  urgent:         boolean;
  sentAt:         string;
}

export interface Thread {
  threadId:         number;
  type:             ConversationType;
  subject:          string | null;
  patientSystemId:  string;
  patientName:      string;
  members:          ThreadMember[];
  lastMessage:      LastMessage | null;
  unreadCount:      number;
  hasUrgentUnread:  boolean;
  archived:         boolean;
  lastMessageAt:    string | null;
  createdAt:        string;
  canManageMembers: boolean;
}

export interface CreateThreadBody {
  patientSystemId: string;
  type:            ConversationType;
  subject?:        string;
  memberSystemIds: string[];
  clientThreadId?: string;
}

export interface Message {
  messageId:       number;
  threadId:        number;
  clientMessageId: string | null;
  senderSystemId:  string;
  senderName:      string;
  senderRole:      MemberRole;
  senderLabel:     string;
  body:            string;
  urgent:          boolean;
  sentAt:          string;
  mine:            boolean;
}

export interface MessagesResponse {
  threadId:          number;
  messages:          Message[];
  hasMore:           boolean;
  lastReadMessageId: number;
}

export interface SendMessageBody {
  clientMessageId: string;
  body:            string;
  urgent:          boolean;
}

export interface ReadResponse {
  lastReadMessageId: number;
  unreadCount:       number;
}

export interface AppNotification {
  notificationId:  number;
  type:            string;
  title:           string;
  message:         string;
  priority:        'NORMAL' | 'HIGH' | string;
  read:            boolean;
  readAt:          string | null;
  createdAt:       string;
  threadId:        number | null;
  messageId:       number | null;
  patientSystemId: string | null;
}

export interface UnreadCounts {
  unreadCount:        number;
  unreadMessageCount: number;
}

// ─── Shared copy ──────────────────────────────────────────────────────────────

/** Exact wording required by the contract (11.1). Urgent is a visual highlight only. */
export const URGENT_HELPER_TEXT =
  'Urgent highlights your message for the care team. It does not make a reply faster. For emergencies, call your local emergency number.';

export const NO_THREAD_ACCESS_TEXT = 'You no longer have access to this conversation.';
export const OFFLINE_SEND_TEXT     = "You're offline. Messages will be sent when you retry.";

export const MAX_MESSAGE_LENGTH = 4000;
export const MAX_SUBJECT_LENGTH = 200;
