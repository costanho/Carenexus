import { expect } from '@playwright/test';
import { Api, withQuery } from './api';
import { sqlLiteral, sqlRows } from './db';
import { ADMIN } from './env';

// Reader of the server's outbox (emails, SMS and push messages it queued), for the links a person would get by
// email: password reset, account invitation, email verification and email change confirmation.
//
// It asks the development endpoint GET /api/admin/dev/outbound-messages as the seeded administrator. That
// endpoint exists only on the local, docker and test profiles (404 elsewhere). When it is not available (404,
// 403, the slice is not deployed, or the API cannot be reached) the reader falls back to SQL on the local dev
// database (db.ts). Either way a message is readable as soon as the request that queued it has committed:
// readers never wait for status SENT (be-foundation-delivery-storage, section 4).

export type OutboxChannel = 'EMAIL' | 'SMS' | 'PUSH';

export interface OutboxMessage {
  messageId:         number;
  channel:           OutboxChannel;
  recipientSystemId: string | null;
  recipientAddress:  string | null;
  templateId:        string | null;
  subject:           string | null;
  body:              string;
  status:            string;
  createdAt:         string;
}

export interface OutboxQuery {
  /** Case-insensitive "contains" match on the recipient address or the recipient system id. */
  to?:       string;
  /** Exact template id, for example `account-password-reset`. */
  template?: string;
  channel?:  OutboxChannel;
  /** Only messages queued at or after this moment (allowing two seconds of clock skew). */
  since?:    Date;
}

export interface OutboxLink {
  /** The absolute link as written in the message. */
  url:     string;
  /** Path and query of the link, to open against the web app under test (`page.goto(link.path)`). */
  path:    string;
  /** The `token` query parameter. */
  token:   string;
  message: OutboxMessage;
}

/** Link patterns from the account slices' contracts; the token is the first capture group. */
export const OUTBOX_LINKS = {
  passwordReset:      { template: 'account-password-reset',        pattern: /\/auth\/reset-password\?token=([A-Za-z0-9_-]+)/ },
  passwordInvite:     { template: 'account-password-invite',       pattern: /\/auth\/reset-password\?token=([A-Za-z0-9_-]+)/ },
  emailVerification:  { template: 'account-email-verify',          pattern: /\/auth\/verify-email\?token=([A-Za-z0-9_-]+)/ },
  emailChangeConfirm: { template: 'account-email-change-confirm',  pattern: /\/auth\/confirm-email-change\?token=([A-Za-z0-9_-]+)/ },
} as const;
export type OutboxLinkKind = keyof typeof OUTBOX_LINKS;

const CLOCK_SKEW_MS = 2_000;
const READ_SIZE = 10;

let adminSession: Promise<Api | null> | null = null;
let endpointAvailable: boolean | null = null;

async function admin(): Promise<Api | null> {
  if (!adminSession) {
    adminSession = Api.login(ADMIN).then(api => api, () => null);
  }
  return adminSession;
}

/** Signs the reader's administrator session out of this process (call from afterAll when you used the reader). */
export async function closeOutboxReader(): Promise<void> {
  const pending = adminSession;
  adminSession = null;
  endpointAvailable = null;
  const api = pending ? await pending : null;
  await api?.dispose();
}

function matchesSince(m: OutboxMessage, since: Date | undefined): boolean {
  return !since || new Date(m.createdAt).getTime() >= since.getTime() - CLOCK_SKEW_MS;
}

async function readThroughApi(q: OutboxQuery): Promise<OutboxMessage[] | null> {
  if (endpointAvailable === false) return null;
  const api = await admin();
  if (!api) return null;
  const r = await api.get(withQuery('/api/admin/dev/outbound-messages', {
    to: q.to, template: q.template, channel: q.channel, page: 0, size: READ_SIZE,
  })).catch(() => null);
  if (!r || r.status === 404 || r.status === 403 || r.status === 401) {
    if (r) endpointAvailable = false;
    return null;
  }
  expect(r.status, 'GET /api/admin/dev/outbound-messages').toBe(200);
  endpointAvailable = true;
  return r.body.content as OutboxMessage[];
}

async function readThroughSql(q: OutboxQuery): Promise<OutboxMessage[]> {
  if (q.to !== undefined && q.to.length > 254) throw new Error('outbox: `to` is longer than 254 characters');
  if (q.template !== undefined && !/^[A-Za-z0-9._-]{1,80}$/.test(q.template)) throw new Error('outbox: bad template id');
  const where: string[] = ['TRUE'];
  if (q.to) {
    const to = sqlLiteral(q.to.trim().toLowerCase());
    where.push(`(strpos(lower(coalesce(recipient_address, '')), ${to}) > 0 OR strpos(lower(coalesce(recipient_system_id, '')), ${to}) > 0)`);
  }
  if (q.template) where.push(`lower(template_id) = ${sqlLiteral(q.template.toLowerCase())}`);
  if (q.channel) where.push(`channel = ${sqlLiteral(q.channel)}`);
  if (q.since) where.push(`created_at >= ${sqlLiteral(new Date(q.since.getTime() - CLOCK_SKEW_MS).toISOString())}::timestamptz`);
  return sqlRows<OutboxMessage>(`
    SELECT message_id AS "messageId", channel, recipient_system_id AS "recipientSystemId",
           recipient_address AS "recipientAddress", template_id AS "templateId", subject, body, status,
           to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt"
      FROM outbound_messages
     WHERE ${where.join(' AND ')}
     ORDER BY created_at DESC, message_id DESC
     LIMIT ${READ_SIZE}`);
}

/** The newest outbox message matching the query, or null when there is none yet. */
export async function latestOutboxMessage(q: OutboxQuery): Promise<OutboxMessage | null> {
  const viaApi = await readThroughApi(q);
  const rows = viaApi ?? await readThroughSql(q);
  return rows.find(m => matchesSince(m, q.since)) ?? null;
}

/** Waits (polling) until a matching message has been queued and returns the newest one. */
export async function waitForOutboxMessage(q: OutboxQuery, timeoutMs = 15_000): Promise<OutboxMessage> {
  let found: OutboxMessage | null = null;
  await expect.poll(async () => {
    found = await latestOutboxMessage(q);
    return found !== null;
  }, { message: `outbox message ${JSON.stringify({ ...q, since: q.since?.toISOString() })}`, timeout: timeoutMs, intervals: [250, 500, 1000] })
    .toBe(true);
  return found!;
}

/** The first link in `message` that matches `pattern` (whose first group is the token). */
export function linkIn(message: OutboxMessage, pattern: RegExp): OutboxLink | null {
  for (const candidate of message.body.match(/https?:\/\/\S+/g) ?? []) {
    const m = pattern.exec(candidate);
    if (!m) continue;
    const parsed = new URL(candidate);
    return { url: candidate, path: `${parsed.pathname}${parsed.search}`, token: m[1], message };
  }
  return null;
}

/**
 * Waits for the newest message of a link kind sent to `to` (an email address or system id) and returns its link.
 * Pass `since` (taken before the action that sends the mail) so an older link is never picked up.
 */
export async function waitForOutboxLink(kind: OutboxLinkKind, to: string, since?: Date, timeoutMs = 15_000): Promise<OutboxLink> {
  const { template, pattern } = OUTBOX_LINKS[kind];
  const message = await waitForOutboxMessage({ to, template, channel: 'EMAIL', since }, timeoutMs);
  const link = linkIn(message, pattern);
  expect(link, `${template} message ${message.messageId} contains a ${kind} link`).not.toBeNull();
  return link!;
}

export const passwordResetLink      = (email: string, since?: Date) => waitForOutboxLink('passwordReset', email, since);
export const passwordInviteLink     = (email: string, since?: Date) => waitForOutboxLink('passwordInvite', email, since);
export const emailVerificationLink  = (email: string, since?: Date) => waitForOutboxLink('emailVerification', email, since);
export const emailChangeConfirmLink = (newEmail: string, since?: Date) => waitForOutboxLink('emailChangeConfirm', newEmail, since);
