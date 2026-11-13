import { z } from 'zod';
import type { StatusTone } from '@/components/web/ui';

// Types, labels and form schemas of the admin operations screens: facilities, system settings, account requests,
// legal documents and the development outbox. Shapes are the camelCase DTOs of the backend contracts
// (be-facilities, be-system-settings, be-account-data-rights, be-account-legal, be-foundation-delivery-storage).
// Correction requests live in ./corrections-api.

/** `ValidationPatterns.PHONE` of the backend: optional leading +, 7 to 15 digits, spaces, dots, dashes and brackets between them. */
export const PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;
/** The address shape the backend checks besides `@Email`. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export type AccountRole = 'PATIENT' | 'DOCTOR' | 'CAREGIVER' | 'ADMIN';

export const ACCOUNT_ROLE_LABEL: Record<AccountRole, string> = {
  PATIENT:   'Patient',
  DOCTOR:    'Doctor',
  CAREGIVER: 'Caregiver',
  ADMIN:     'Administrator',
};

export function accountRoleLabel(role: string | null | undefined): string {
  return ACCOUNT_ROLE_LABEL[(role ?? '').toUpperCase() as AccountRole] ?? 'Account';
}

/**
 * An instant (ISO-8601 with Z or an offset) as a date and time in the viewer's own locale and time zone, with the
 * zone named. For operational timestamps (requests, messages, legal versions); clinic wall-clock values use
 * `@/lib/time/clinic`.
 */
export function formatInstant(value: string | null | undefined, fallback = ''): string {
  if (!value) return fallback;
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return fallback;
  try {
    return at.toLocaleString(undefined, {
      day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
    });
  } catch {
    return at.toISOString();
  }
}

// ─── Facilities ───────────────────────────────────────────────────────────────

export interface Facility {
  facilityId: number;
  name: string;
  type: string | null;
  address: string | null;
  phone: string | null;
  email: string | null;
  isActive: boolean;
  createdAt: string;   // clinic wall-clock, no offset
  updatedAt: string;   // clinic wall-clock, no offset
}

export interface FacilityInput {
  name: string;
  type?: string | null;
  address?: string | null;
  phone?: string | null;
  email?: string | null;
}

/** The list filter of the admin facilities screen. */
export type FacilityActiveFilter = 'all' | 'active' | 'inactive';

/** Longest `query` the facility list accepts. */
export const FACILITY_QUERY_MAX = 100;

/** Mirrors `FacilityRequest`: name required (200); type 80, address 300, phone 20 + pattern, email 150 + pattern. */
export const facilitySchema = z.object({
  name: z.string().trim()
    .min(1, 'Enter the name of the facility')
    .max(200, 'Name must be at most 200 characters'),
  type: z.string().trim().max(80, 'Type must be at most 80 characters'),
  address: z.string().trim().max(300, 'Address must be at most 300 characters'),
  phone: z.string().trim()
    .max(20, 'Phone must be at most 20 characters')
    .refine(v => v === '' || PHONE_PATTERN.test(v), 'Enter a valid phone number (7 to 15 digits), or clear the field'),
  email: z.string().trim()
    .max(150, 'Email must be at most 150 characters')
    .refine(v => v === '' || EMAIL_PATTERN.test(v), 'Enter a valid email address, or clear the field'),
});

export type FacilityFormValues = z.infer<typeof facilitySchema>;

export const FACILITY_FORM_FIELDS = ['name', 'type', 'address', 'phone', 'email'] as const;

export const EMPTY_FACILITY_FORM: FacilityFormValues = { name: '', type: '', address: '', phone: '', email: '' };

export function facilityToForm(facility: Facility): FacilityFormValues {
  return {
    name:    facility.name ?? '',
    type:    facility.type ?? '',
    address: facility.address ?? '',
    phone:   facility.phone ?? '',
    email:   facility.email ?? '',
  };
}

function blankToNull(value: string): string | null {
  const text = value.trim();
  return text === '' ? null : text;
}

/** The request body: all five fields always (PUT is a full replacement), empty optional inputs as null. */
export function toFacilityInput(values: FacilityFormValues): FacilityInput {
  return {
    name:    values.name.trim(),
    type:    blankToNull(values.type),
    address: blankToNull(values.address),
    phone:   blankToNull(values.phone),
    email:   blankToNull(values.email),
  };
}

// ─── System settings ──────────────────────────────────────────────────────────

export type SettingType = 'STRING' | 'INT' | 'DECIMAL' | 'EMAIL' | 'PHONE' | 'CURRENCY';

export interface SystemSetting {
  key: string; value: string; defaultValue: string; overridden: boolean; type: SettingType; description: string;
  min: number | null; max: number | null; maxLength: number | null;
  updatedAt: string | null; updatedBySystemId: string | null;
}

export type SettingUnit = 'hours' | 'minutes' | 'days' | 'fraction';

export interface SettingMeta {
  /** Short name shown as the row heading. */
  label:   string;
  /** Heading of the card the setting is listed under. */
  group:   string;
  /** What changing the value does, in one or two sentences. */
  affects: string;
  unit?:   SettingUnit;
}

/** What each known setting is called and what it affects. A key the server adds later is still listed, without this text. */
export const SETTING_META: Record<string, SettingMeta> = {
  'clinic.name': {
    label: 'Clinic name', group: 'Help & Support contact',
    affects: 'Help & Support contact: shown as the clinic name on the Help & Support page of every role.',
  },
  'support.contact-email': {
    label: 'Support email', group: 'Help & Support contact',
    affects: 'Help & Support contact: the email address people are told to write to on the Help & Support page.',
  },
  'support.contact-phone': {
    label: 'Support phone', group: 'Help & Support contact',
    affects: 'Help & Support contact: the phone number shown on the Help & Support page. While it is not set, no phone number is shown.',
  },
  'reminders.appointment-lead-hours': {
    label: 'First appointment reminder', group: 'Reminders', unit: 'hours',
    affects: 'Reminder timing: the first reminder for an appointment is sent this long before it starts. Reminders already sent are not sent again.',
  },
  'reminders.second-lead-minutes': {
    label: 'Second appointment reminder', group: 'Reminders', unit: 'minutes',
    affects: 'Reminder timing: the second reminder for an appointment is sent this long before it starts.',
  },
  'appointments.needs-outcome-after-minutes': {
    label: 'Needs-outcome threshold', group: 'Appointments', unit: 'minutes',
    affects: 'Needs-outcome threshold: an appointment with no recorded outcome is listed for its doctor as needing one this long after its scheduled end.',
  },
  'billing.default-currency': {
    label: 'Invoice currency', group: 'Billing',
    affects: 'Invoice currency: invoices created after the change use this currency.',
  },
  'billing.payment-terms-days': {
    label: 'Invoice payment terms', group: 'Billing', unit: 'days',
    affects: 'Invoice terms: the due date of an invoice created after the change is this many days after it is issued.',
  },
  'billing.tax-rate': {
    label: 'Invoice tax rate', group: 'Billing', unit: 'fraction',
    affects: 'Invoice tax: applied to invoices created after the change. It is a fraction, so 0.16 means 16%.',
  },
};

export const OTHER_SETTINGS_GROUP = 'Other settings';

export function settingMeta(key: string): SettingMeta | null {
  return SETTING_META[key] ?? null;
}

/** "billing.tax-rate" becomes "Billing tax rate"; used for a key this screen has no name for. */
export function settingLabel(key: string): string {
  const known = SETTING_META[key]?.label;
  if (known) return known;
  const words = key.replace(/[._-]+/g, ' ').trim();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : key;
}

function plural(count: number, unit: string): string {
  return count === 1 ? unit.replace(/s$/, '') : unit;
}

/** The fraction as a percentage with at most two decimals: "0.16" becomes "16%". Null when it is not a number. */
export function fractionAsPercent(value: string): string | null {
  const text = value.trim();
  if (!/^-?\d+(\.\d+)?$/.test(text)) return null;
  const n = Number(text);
  if (!Number.isFinite(n)) return null;
  return `${parseFloat((n * 100).toFixed(2))}%`;
}

/** A setting value as a person reads it: "24 hours", "0.16 (16%)", "KES", or "Not set" for an empty support contact. */
export function formatSettingValue(setting: Pick<SystemSetting, 'key' | 'type'>, value: string): string {
  const text = (value ?? '').trim();
  if (text === '') return 'Not set';
  const unit = SETTING_META[setting.key]?.unit;
  if (unit === 'fraction') {
    const percent = fractionAsPercent(text);
    return percent ? `${text} (${percent})` : text;
  }
  if (unit && /^-?\d+$/.test(text)) return `${text} ${plural(Number(text), unit)}`;
  return text;
}

/** What the editor of a setting accepts, as a sentence under the input. */
export function settingInputHint(setting: SystemSetting): string {
  const unit = SETTING_META[setting.key]?.unit;
  switch (setting.type) {
    case 'INT': {
      const of = unit && unit !== 'fraction' ? ` of ${unit}` : '';
      return setting.min != null && setting.max != null
        ? `A whole number${of} from ${setting.min} to ${setting.max}.`
        : `A whole number${of}.`;
    }
    case 'DECIMAL':
      return setting.min != null && setting.max != null
        ? `A number from ${setting.min} to ${setting.max} with at most 4 decimal places.`
        : 'A number with at most 4 decimal places.';
    case 'CURRENCY':
      return 'A 3-letter ISO 4217 currency code, for example KES or USD.';
    case 'EMAIL':
      return 'An email address.';
    case 'PHONE':
      return 'A phone number with 7 to 15 digits. It may start with + and contain spaces, dots, dashes and brackets.';
    default:
      return setting.maxLength != null ? `At most ${setting.maxLength} characters.` : 'Text.';
  }
}

/**
 * Checks a value the way the server does for the setting's type (be-system-settings, section 1). Returns the
 * message to show under the editor, or null when the value can be sent.
 */
export function validateSettingValue(
  setting: Pick<SystemSetting, 'type' | 'min' | 'max' | 'maxLength'>,
  raw: string,
): string | null {
  const value = (raw ?? '').trim();
  if (value === '') return 'Enter a value. To remove a custom value, use Restore default.';
  if ((raw ?? '').length > 500) return 'The value must be at most 500 characters.';

  switch (setting.type) {
    case 'STRING': {
      const max = setting.maxLength ?? 120;
      return value.length > max ? `Use at most ${max} characters.` : null;
    }
    case 'EMAIL':
      return value.length > (setting.maxLength ?? 254) || !EMAIL_PATTERN.test(value)
        ? 'Enter a valid email address.' : null;
    case 'PHONE':
      return value.length > (setting.maxLength ?? 30) || !PHONE_PATTERN.test(value)
        ? 'Enter a valid phone number.' : null;
    case 'CURRENCY':
      return /^[A-Za-z]{3}$/.test(value) ? null : 'Enter a 3-letter ISO 4217 currency code, for example KES.';
    case 'INT': {
      if (!/^-?\d{1,9}$/.test(value)) return 'Enter a whole number.';
      const n = Number(value);
      if ((setting.min != null && n < setting.min) || (setting.max != null && n > setting.max)) {
        return `Enter a whole number between ${setting.min ?? 'the minimum'} and ${setting.max ?? 'the maximum'}.`;
      }
      return null;
    }
    case 'DECIMAL': {
      if (!/^-?\d{1,9}(\.\d+)?$/.test(value)) return 'Enter a number, for example 0.16.';
      const decimals = value.includes('.') ? value.split('.')[1].length : 0;
      if (decimals > 4) return 'Use at most 4 decimal places.';
      const n = Number(value);
      if ((setting.min != null && n < setting.min) || (setting.max != null && n > setting.max)) {
        return `Enter a number between ${setting.min ?? 'the minimum'} and ${setting.max ?? 'the maximum'}.`;
      }
      return null;
    }
    default:
      return null;
  }
}

/** The form schema of one setting's editor; the value is always sent as a string, whatever its type. */
export function settingFormSchema(setting: Pick<SystemSetting, 'type' | 'min' | 'max' | 'maxLength'>) {
  return z.object({
    value: z.string().superRefine((value, ctx) => {
      const message = validateSettingValue(setting, value);
      if (message) ctx.addIssue({ code: 'custom', message });
    }),
  });
}

export interface SettingFormValues { value: string }

// ─── Account requests ─────────────────────────────────────────────────────────

export type AccountRequestType = 'EXPORT' | 'CLOSURE' | 'DELETION';
export type AccountRequestStatus = 'REQUESTED' | 'PROCESSING' | 'READY' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';

export interface AccountRequest {
  requestId: number; type: AccountRequestType; status: AccountRequestStatus;
  reason: string | null; decisionReason: string | null;
  requestedAt: string; processedAt: string | null; expiresAt: string | null;
  downloadable: boolean; fileName: string | null;
}

export interface AdminAccountRequest extends AccountRequest {
  systemId: string; firstName: string; lastName: string; email: string | null;
  role: AccountRole; isActive: boolean; processedBySystemId: string | null;
}

export const ACCOUNT_REQUEST_TYPES: readonly AccountRequestType[] = ['EXPORT', 'CLOSURE', 'DELETION'];
export const ACCOUNT_REQUEST_STATUSES: readonly AccountRequestStatus[] =
  ['REQUESTED', 'PROCESSING', 'READY', 'COMPLETED', 'REJECTED', 'CANCELLED'];

export const ACCOUNT_REQUEST_TYPE_LABEL: Record<AccountRequestType, string> = {
  EXPORT:   'Data export',
  CLOSURE:  'Account closure',
  DELETION: 'Deletion request',
};

export const ACCOUNT_REQUEST_STATUS_LABEL: Record<AccountRequestStatus, string> = {
  REQUESTED:  'Requested',
  PROCESSING: 'Processing',
  READY:      'Ready',
  COMPLETED:  'Completed',
  REJECTED:   'Rejected',
  CANCELLED:  'Cancelled',
};

export function isAccountRequestType(value: string | undefined): value is AccountRequestType {
  return !!value && (ACCOUNT_REQUEST_TYPES as readonly string[]).includes(value);
}

export function isAccountRequestStatus(value: string | undefined): value is AccountRequestStatus {
  return !!value && (ACCOUNT_REQUEST_STATUSES as readonly string[]).includes(value);
}

/** Only a deletion request that is still waiting can be approved or rejected. */
export function isDecidable(request: Pick<AccountRequest, 'type' | 'status'>): boolean {
  return request.type === 'DELETION' && request.status === 'REQUESTED';
}

/** What a status means for its type of request, in words, with the tone of its badge. */
export function accountRequestState(request: Pick<AccountRequest, 'type' | 'status' | 'downloadable'>): { label: string; tone: StatusTone } {
  const { type, status } = request;
  if (type === 'DELETION') {
    if (status === 'REQUESTED') return { label: 'Awaiting decision', tone: 'warning' };
    if (status === 'COMPLETED') return { label: 'Approved, account anonymised', tone: 'success' };
    if (status === 'REJECTED')  return { label: 'Rejected', tone: 'danger' };
    if (status === 'CANCELLED') return { label: 'Cancelled by the user', tone: 'neutral' };
  }
  if (type === 'CLOSURE' && status === 'COMPLETED') return { label: 'Account closed', tone: 'neutral' };
  if (type === 'EXPORT') {
    if (status === 'REQUESTED' || status === 'PROCESSING') return { label: 'Preparing', tone: 'info' };
    if (status === 'READY') {
      return request.downloadable
        ? { label: 'Ready for the user to download', tone: 'success' }
        : { label: 'Expired', tone: 'neutral' };
    }
    if (status === 'COMPLETED') return { label: 'File removed (expired or replaced)', tone: 'neutral' };
  }
  const fallbackTone: StatusTone =
    status === 'REJECTED' ? 'danger' : status === 'CANCELLED' ? 'neutral' : status === 'COMPLETED' || status === 'READY' ? 'success' : 'info';
  return { label: ACCOUNT_REQUEST_STATUS_LABEL[status] ?? status, tone: fallbackTone };
}

/** "Tariro Moyo", or the system id when the row carries no name. */
export function accountRequestName(request: Pick<AdminAccountRequest, 'firstName' | 'lastName' | 'systemId'>): string {
  const name = `${request.firstName ?? ''} ${request.lastName ?? ''}`.trim();
  return name || request.systemId;
}

/** Mirrors `RejectAccountRequest`: reason required, at most 500 characters. */
export const rejectAccountRequestSchema = z.object({
  reason: z.string().trim()
    .min(1, 'Enter the reason. The user sees it.')
    .max(500, 'The reason must be at most 500 characters'),
});

export type RejectAccountRequestValues = z.infer<typeof rejectAccountRequestSchema>;

/** The statement shown wherever a deletion request can be approved. */
export const DELETION_STATEMENT =
  'Approving a deletion request anonymises the account: the name, email address, phone number and password are '
  + 'removed and the person can no longer sign in. Clinical records, messages and the audit history are kept and '
  + 'stay linked to the account ID. Nothing is deleted from the medical record.';

/** The confirmation text before approving (be-account-data-rights 8.3). */
export function approveDeletionConfirmText(role: string | null | undefined): string {
  const base = 'This anonymises the account (name, email, phone and password are removed and the user can no longer '
    + 'sign in). Clinical records are kept. This cannot be undone.';
  return (role ?? '').toUpperCase() === 'DOCTOR'
    ? `${base}\n\nRecords this clinician wrote will show the name Deleted User.`
    : base;
}

// ─── Legal documents ──────────────────────────────────────────────────────────

export type LegalDocType = 'TERMS' | 'PRIVACY';

export interface LegalDocumentSummary {
  docType: LegalDocType;
  version: string;
  title: string;
  effectiveAt: string;          // instant
  pendingLegalReview: boolean;
}

export interface LegalCurrent {
  terms: LegalDocumentSummary;
  privacy: LegalDocumentSummary;
}

export interface LegalDocument extends LegalDocumentSummary {
  contentMd: string;
}

export interface PublishLegalDocumentInput {
  docType: LegalDocType;
  version: string;
  title: string;
  contentMd: string;
  /** ISO instant; omitted for "effective now" (the server uses its own clock). */
  effectiveAt?: string;
}

export const LEGAL_DOC_LABEL: Record<LegalDocType, string> = {
  TERMS:   'Terms of Service',
  PRIVACY: 'Privacy Policy',
};

export const LEGAL_VERSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$/;
export const LEGAL_CONTENT_MAX = 100000;

/** The phrase that makes the server mark a text as placeholder copy (be-account-legal D7). */
export function containsPlaceholderPhrase(text: string): boolean {
  return /pending legal review/i.test(text ?? '');
}

/** The instant of a date ("YYYY-MM-DD") and time ("HH:mm") typed in the viewer's own time zone, or null. */
export function localDateTimeToDate(date: string, time: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  const at = new Date(`${date}T${time}:00`);
  return Number.isNaN(at.getTime()) ? null : at;
}

export type LegalEffectiveMode = 'now' | 'earlier';

/**
 * Mirrors `PublishLegalDocumentRequest`. `currentEffectiveAt` is the effective instant of the version in force:
 * an earlier effective date and time is refused by the server (409), so it is refused here first.
 */
export function publishLegalSchema(currentEffectiveAt: string | null) {
  return z.object({
    version: z.string().trim()
      .min(1, 'Enter a version label')
      .regex(LEGAL_VERSION_PATTERN, 'Use 1 to 20 letters, digits, dots, dashes or underscores, starting with a letter or digit'),
    title: z.string().trim()
      .min(1, 'Enter the title')
      .max(200, 'The title must be at most 200 characters'),
    contentMd: z.string()
      .refine(v => v.trim().length > 0, 'Enter the text of the document')
      .max(LEGAL_CONTENT_MAX, `The text must be at most ${LEGAL_CONTENT_MAX} characters`),
    effectiveMode: z.enum(['now', 'earlier']),
    effectiveDate: z.string(),
    effectiveTime: z.string(),
  }).superRefine((values, ctx) => {
    if (values.effectiveMode !== 'earlier') return;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(values.effectiveDate)) {
      ctx.addIssue({ code: 'custom', path: ['effectiveDate'], message: 'Choose the date' });
      return;
    }
    if (!/^\d{2}:\d{2}$/.test(values.effectiveTime)) {
      ctx.addIssue({ code: 'custom', path: ['effectiveTime'], message: 'Choose the time' });
      return;
    }
    const at = localDateTimeToDate(values.effectiveDate, values.effectiveTime);
    if (!at) {
      ctx.addIssue({ code: 'custom', path: ['effectiveDate'], message: 'Choose a valid date and time' });
      return;
    }
    if (at.getTime() > Date.now()) {
      ctx.addIssue({ code: 'custom', path: ['effectiveDate'], message: 'The effective date and time cannot be in the future' });
      return;
    }
    const current = currentEffectiveAt ? new Date(currentEffectiveAt).getTime() : NaN;
    if (!Number.isNaN(current) && at.getTime() < current) {
      ctx.addIssue({
        code: 'custom', path: ['effectiveDate'],
        message: `It cannot be earlier than the version in force (${formatInstant(currentEffectiveAt)})`,
      });
    }
  });
}

export interface PublishLegalFormValues {
  version: string;
  title: string;
  contentMd: string;
  effectiveMode: LegalEffectiveMode;
  effectiveDate: string;
  effectiveTime: string;
}

export const PUBLISH_LEGAL_FIELD_MAP: Record<string, keyof PublishLegalFormValues> = {
  version:     'version',
  title:       'title',
  contentMd:   'contentMd',
  effectiveAt: 'effectiveDate',
};

// ─── Development outbox ───────────────────────────────────────────────────────

export type DeliveryChannel = 'EMAIL' | 'SMS' | 'PUSH';
export type OutboundStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';

export interface OutboundMessage {
  messageId: number;
  channel: DeliveryChannel;
  recipientSystemId: string | null;
  recipientAddress: string | null;
  templateId: string | null;
  subject: string | null;
  body: string;
  data: Record<string, string> | null;
  notificationId: number | null;
  dedupeKey: string | null;
  status: OutboundStatus;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  provider: string | null;
  providerMessageId: string | null;
  createdAt: string;
  sentAt: string | null;
}

export interface OutboundMessageFilter {
  channel?: DeliveryChannel;
  /** Contains match on the recipient address or system id. */
  to?: string;
  /** Exact template id. */
  template?: string;
}

export const DELIVERY_CHANNELS: readonly DeliveryChannel[] = ['EMAIL', 'SMS', 'PUSH'];

export const DELIVERY_CHANNEL_LABEL: Record<DeliveryChannel, string> = {
  EMAIL: 'Email',
  SMS:   'SMS',
  PUSH:  'Push',
};

export const OUTBOUND_STATUS_LABEL: Record<OutboundStatus, string> = {
  PENDING: 'Pending',
  SENT:    'Sent',
  FAILED:  'Failed',
  SKIPPED: 'Skipped',
};

export const OUTBOX_TO_MAX = 254;
export const OUTBOX_TEMPLATE_MAX = 80;

export function isDeliveryChannel(value: string | undefined): value is DeliveryChannel {
  return !!value && (DELIVERY_CHANNELS as readonly string[]).includes(value);
}

/** The first http(s) link of a rendered message body (reset, verification and confirmation links), or null. */
export function firstLink(body: string | null | undefined): string | null {
  const match = /https?:\/\/\S+/.exec(body ?? '');
  return match ? match[0] : null;
}
