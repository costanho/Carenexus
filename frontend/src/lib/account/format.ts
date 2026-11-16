import type { AccountRequest, LegalDocType, SessionResponse } from './types';

// Display helpers of the account cards. The timestamps of these endpoints are operational instants (UTC with
// `Z`), not clinic schedule times, so they are shown in the viewer's own time zone.

/** "10 Nov 2026, 09:47" in the browser's zone and locale; an empty string for a missing or unreadable value. */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return '';
  return at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** "10 Nov 2026" in the browser's zone; an empty string for a missing or unreadable value. */
export function formatInstantDate(value: string | null | undefined): string {
  if (!value) return '';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return '';
  return at.toLocaleDateString(undefined, { dateStyle: 'medium' });
}

/** "JBSW Y3DP EHPK 3PXP ...": the Base32 secret grouped in fours so it can be typed without losing the place. */
export function groupSecret(secret: string): string {
  return (secret.match(/.{1,4}/g) ?? []).join(' ');
}

/** A session's device in words. Both fields are free text from the client and may be missing. */
export function sessionDeviceLabel(session: Pick<SessionResponse, 'deviceType' | 'deviceInfo'>): string {
  return session.deviceInfo?.trim() || session.deviceType?.trim() || 'Unknown device';
}

/** The client kind ("Web", "iOS", "Android") when it adds something to the device label, otherwise null. */
export function sessionDeviceType(session: Pick<SessionResponse, 'deviceType' | 'deviceInfo'>): string | null {
  const type = session.deviceType?.trim();
  const info = session.deviceInfo?.trim();
  return type && info && type.toLowerCase() !== info.toLowerCase() ? type : null;
}

export type ExportState = 'ready' | 'preparing' | 'expired';

/** What an export row shows. Whether it can still be downloaded is the server's decision (`downloadable`). */
export function exportState(row: Pick<AccountRequest, 'status' | 'downloadable'>): ExportState {
  if (row.downloadable) return 'ready';
  if (row.status === 'REQUESTED' || row.status === 'PROCESSING') return 'preparing';
  return 'expired';
}

export const LEGAL_DOC_LABEL: Record<LegalDocType, string> = {
  TERMS:   'Terms of Service',
  PRIVACY: 'Privacy Policy',
};

/** The document's name for a type the client may not know yet. */
export function legalDocLabel(docType: string): string {
  return LEGAL_DOC_LABEL[docType as LegalDocType] ?? docType;
}

/** The text saved when recovery codes are downloaded: one code per line under a short explanation. */
export function recoveryCodesFileText(codes: readonly string[], generatedAt: Date = new Date()): string {
  return [
    'CareNexus two-factor authentication recovery codes',
    `Generated: ${generatedAt.toISOString()}`,
    '',
    'Each code can be used once to sign in when you cannot use your authenticator app.',
    'Keep them somewhere safe and private. Generating new codes cancels these.',
    '',
    ...codes,
    '',
  ].join('\n');
}

export const RECOVERY_CODES_FILE_NAME = 'carenexus-recovery-codes.txt';

/** How long another device can keep working after its session was ended (the access-token lifetime). */
export const SIGN_OUT_DELAY_NOTE = 'A device that was signed out can keep working for up to 15 minutes before it is asked to sign in again.';
