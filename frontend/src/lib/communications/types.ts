// Types mirroring the messaging + notifications API (docs/communications/CONTRACT.md, section 7).
// All time fields are ISO-8601 UTC instants.

export type MemberRole       = 'PATIENT' | 'DOCTOR' | 'CAREGIVER';
/** The conversation types a user can start with `POST /api/communications/threads`. */
export type ConversationType = 'PRIVATE' | 'CARE_TEAM';
export type MessagingMode    = MemberRole;

/**
 * Every type a conversation returned by the API can have. SUPPORT conversations (a user and the administrators)
 * are opened with `POST /api/communications/support-thread`, never through the new-conversation form.
 */
export type ThreadType = ConversationType | 'SUPPORT';

/** Role of a member or sender as it arrives from the API. ADMIN appears in SUPPORT conversations only. */
export type ParticipantRole = MemberRole | 'ADMIN';

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
  /** May be included when the caller starts a care-team conversation. */
  canAddToCareTeam: boolean;
  /** The caller may add this person to an existing care-team conversation (patient or doctor only). */
  canAddToExistingCareTeam: boolean;
}

export interface RecipientsResponse {
  patientSystemId: string;
  patientName:     string;
  recipients:      Recipient[];
}

export interface ThreadMember {
  systemId: string;
  name:     string;
  role:     ParticipantRole;
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
  type:             ThreadType;
  subject:          string | null;
  /** The patient the conversation is about. Null for SUPPORT conversations: check `type` before using it. */
  patientSystemId:  string | null;
  /** Null for SUPPORT conversations. */
  patientName:      string | null;
  members:          ThreadMember[];
  lastMessage:      LastMessage | null;
  unreadCount:      number;
  hasUrgentUnread:  boolean;
  archived:         boolean;
  lastMessageAt:    string | null;
  createdAt:        string;
  canManageMembers: boolean;
  /** SUPPORT conversations only: an administrator closed it and no more messages can be sent. The API always sends it. */
  closed?:          boolean;
  /** When the conversation was closed; null while it is open. */
  closedAt?:        string | null;
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
  senderRole:      ParticipantRole;
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

export type NotificationType =
  | 'APPOINTMENT' | 'MEDICATION' | 'LAB_RESULT' | 'MESSAGE' | 'AI_ALERT' | 'SYSTEM' | 'PAYMENT' | 'REFERRAL';

/** Every notification type, in the order the type filter lists them. */
export const NOTIFICATION_TYPES: readonly NotificationType[] = [
  'APPOINTMENT', 'MEDICATION', 'LAB_RESULT', 'MESSAGE', 'AI_ALERT', 'PAYMENT', 'REFERRAL', 'SYSTEM',
];

/** HIGH is shown as urgent, CRITICAL as critical (styling plus a text label). */
export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface AppNotification {
  notificationId:  number;
  type:            NotificationType;
  title:           string;
  message:         string;
  priority:        NotificationPriority;
  read:            boolean;
  readAt:          string | null;
  createdAt:       string;
  /** Conversation announced by a thread MESSAGE notification; null for every other notification. */
  threadId:        number | null;
  messageId:       number | null;
  /** The patient the notification is about; null for account-level notifications. */
  patientSystemId: string | null;
  /**
   * In-app link to open (`/dashboard/<role>?section=...`). Null means the notification is informational; it is
   * always null for thread MESSAGE notifications, which open by `threadId`.
   */
  actionUrl:       string | null;
  /**
   * What the notification is about (APPOINTMENTS, LAB_RESULTS, BILLING, CAREGIVER_ACCESS, ...). An open set:
   * navigate with `actionUrl`, never by this value. Null for thread MESSAGE and informational notifications.
   */
  target:          string | null;
  /** Preference category (APPOINTMENT_REMINDER, BILLING, CRITICAL, ...). An open set; icons key on `type`. */
  category:        string | null;
  /** Kind of the referenced record (APPOINTMENT, INVOICE, ACCESS_REQUEST, ...). For display or diagnostics only. */
  resourceType:    string | null;
  /** Id of the referenced record. */
  resourceId:      number | null;
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
