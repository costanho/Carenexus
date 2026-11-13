import { isReferralStatus, type ReferralStatus } from './types';

// Reading the URL params of the referral screens (CONTRACTS 12.1). A value that is missing or malformed is
// treated as absent, so a mistyped link opens the plain list instead of an error.

/** A positive integer id from a URL param (`id`, `consultationId`), or null. */
export function parseReferralId(value: string | null | undefined): number | null {
  const text = (value ?? '').trim();
  if (!/^\d{1,9}$/.test(text)) return null;
  const id = Number(text);
  return id > 0 ? id : null;
}

/** The `patient` URL param when it is a patient system id (PAT-...), otherwise ''. */
export function parsePatientParam(value: string | null | undefined): string {
  const text = (value ?? '').trim().toUpperCase();
  return /^PAT-\d{5,}$/.test(text) ? text : '';
}

/** The `status` URL param as a referral status, or '' for "all statuses". */
export function parseStatusParam(value: string | null | undefined): ReferralStatus | '' {
  const text = (value ?? '').trim().toUpperCase();
  return isReferralStatus(text) ? text : '';
}

export type DoctorReferralTab = 'sent' | 'received';

/** The `tab` URL param of the doctor screen, or null when it is absent or unknown. */
export function parseTabParam(value: string | null | undefined): DoctorReferralTab | null {
  const text = (value ?? '').trim().toLowerCase();
  return text === 'sent' || text === 'received' ? text : null;
}
