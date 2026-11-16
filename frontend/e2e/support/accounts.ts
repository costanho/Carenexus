import { expect, request } from '@playwright/test';
import { acceptPendingLegal, Api, currentLegalVersions } from './api';
import { ADMIN, API_URL, type DemoUser, DEMO_USERS, runTag, throwawayEmail } from './env';
import { emailVerificationLink } from './outbox';

// Accounts for the suites: the seeded administrator, preparing the demo accounts, and throwaway accounts
// registered the way the web form does it (current legal versions, clinician fields). Throwaway accounts are
// e2e-…@carenexus.test and are removed by scripts/dev-reset-e2e-data.sh; passwords live only in memory.

/** Registration role as the API accepts it (`proxy` is stored as CAREGIVER). */
export type RegisterRole = 'patient' | 'doctor' | 'proxy';

export interface ClinicianFields {
  licenseNo:      string;
  specialization: string;
  licensingBody?: string;
  /** ISO 3166-1 alpha-2 */
  licenseCountry?: string;
}

export interface RegisterOptions {
  role:        RegisterRole;
  /** Starts with "e2e-" (default: a new runTag()); the email is `<tag>@carenexus.test`. */
  tag?:        string;
  firstName?:  string;
  lastName?:   string;
  phone?:      string;
  /** Sends the current Terms and Privacy versions, as the web form does (default true). */
  acceptLegal?: boolean;
  /** Doctor only: overrides of the default clinician fields the web form requires. */
  clinician?:  Partial<ClinicianFields>;
  /** Patient only: links an existing caregiver at registration. */
  caregiverSystemId?: string;
  relationship?:      string;
}

export interface RegisteredUser extends DemoUser {
  tag:       string;
  firstName: string;
  lastName:  string;
  /** The server's confirmation text (the doctor variant says the account is pending verification). */
  message:   string;
}

/** A password that meets the registration policy (letters and digits, at most 72 bytes). */
export function throwawayPassword(): string {
  return `E2e-${crypto.randomUUID()}`;
}

/** The request body the web registration form sends for these options (legal versions resolved by the caller). */
export function registrationBody(opts: RegisterOptions & { tag: string; password: string }, legal: { terms: string; privacy: string } | null): Record<string, unknown> {
  const body: Record<string, unknown> = {
    firstName: opts.firstName ?? 'E2e',
    lastName:  opts.lastName ?? roleWord(opts.role),
    email:     throwawayEmail(opts.tag),
    password:  opts.password,
    role:      opts.role,
  };
  if (opts.phone) body.phone = opts.phone;
  if (legal) {
    body.acceptedTermsVersion = legal.terms;
    body.acceptedPrivacyVersion = legal.privacy;
  }
  if (opts.role === 'doctor') {
    const suffix = opts.tag.replace(/^e2e-/i, '').toUpperCase().slice(0, 40);
    body.licenseNo = opts.clinician?.licenseNo ?? `E2E-${suffix}`;
    body.specialization = opts.clinician?.specialization ?? 'General Practice';
    body.licensingBody = opts.clinician?.licensingBody ?? 'Medical and Dental Practitioners Council';
    body.licenseCountry = opts.clinician?.licenseCountry ?? 'ZW';
  }
  if (opts.role === 'patient' && opts.caregiverSystemId) {
    body.caregiverSystemId = opts.caregiverSystemId;
    if (opts.relationship) body.relationship = opts.relationship;
  }
  return body;
}

function roleWord(role: RegisterRole): string {
  return role === 'patient' ? 'Patient' : role === 'doctor' ? 'Doctor' : 'Carer';
}

/**
 * Registers a throwaway account through POST /api/auth/register like the web form: current legal versions
 * (unless acceptLegal is false) and, for a doctor, licence number and specialization. A doctor starts PENDING
 * and cannot sign in until approveClinician(); patients and caregivers can sign in at once on dev profiles
 * (email verification is not enforced there; see verifyEmail()).
 */
export async function registerAccount(opts: RegisterOptions): Promise<RegisteredUser> {
  const tag = opts.tag ?? runTag();
  const password = throwawayPassword();
  const legal = opts.acceptLegal === false ? null : await currentLegalVersions();
  const body = registrationBody({ ...opts, tag, password }, legal);
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/register', { data: body });
    const text = await res.text();
    expect(res.status(), `register ${opts.role} ${body.email}: ${text.slice(0, 300)}`).toBe(201);
    const json = JSON.parse(text);
    return {
      email:     body.email as string,
      password,
      role:      opts.role,
      systemId:  json.user?.system_id ?? '',
      name:      `${body.firstName} ${body.lastName}`,
      tag,
      firstName: body.firstName as string,
      lastName:  body.lastName as string,
      message:   json.message ?? '',
    };
  } finally {
    await ctx.dispose();
  }
}

/** Confirms a throwaway account's email address with the link from its verification email. */
export async function verifyEmail(user: Pick<DemoUser, 'email'>, since?: Date): Promise<void> {
  const link = await emailVerificationLink(user.email, since);
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/email/verify', { data: { token: link.token } });
    expect(res.status(), `verify ${user.email}`).toBe(200);
  } finally {
    await ctx.dispose();
  }
}

/**
 * Signs the seeded administrator in through the API and records its system id on ADMIN. Fails at once when the
 * server signed in an account that is not an administrator (for example E2E_ADMIN_EMAIL pointing elsewhere),
 * instead of later as unrelated 403s.
 */
export async function loginAdmin(): Promise<Api> {
  const api = await Api.login(ADMIN);
  if (api.role !== 'admin') await api.dispose();
  expect(api.role, `server role of ADMIN (${ADMIN.email})`).toBe('admin');
  ADMIN.systemId = api.systemId;
  return api;
}

/** An administrator approves a registered clinician (POST /api/admin/doctors/{id}/verify). */
export async function approveClinician(admin: Api, doctorSystemId: string): Promise<void> {
  await admin.ok(admin.post(`/api/admin/doctors/${encodeURIComponent(doctorSystemId)}/verify`), `approve clinician ${doctorSystemId}`);
}

/** Registers a throwaway doctor and has the administrator approve it, so it can sign in. */
export async function registerApprovedDoctor(admin: Api, opts: Omit<RegisterOptions, 'role'> = {}): Promise<RegisteredUser> {
  const doctor = await registerAccount({ ...opts, role: 'doctor' });
  await approveClinician(admin, doctor.systemId);
  return doctor;
}

export interface PreparedAccount { email: string; ok: boolean; accepted: number; message: string }

/**
 * Prepares the demo accounts for UI sign-in: signs each in through the API and accepts the current Terms and
 * Privacy versions where they are still pending, so sign-in lands on the role home instead of
 * /auth/accept-terms (every account that predates the legal documents has no acceptance rows). Never throws.
 */
export async function prepareDemoAccounts(users: readonly DemoUser[] = DEMO_USERS): Promise<PreparedAccount[]> {
  const results: PreparedAccount[] = [];
  for (const user of users) {
    try {
      const api = await Api.login(user);
      try {
        if (user === ADMIN) ADMIN.systemId = api.systemId;
        const accepted = await acceptPendingLegal(api);
        results.push({ email: user.email, ok: true, accepted, message: accepted ? `accepted ${accepted} document(s)` : 'nothing pending' });
      } finally {
        await api.dispose();
      }
    } catch (e: any) {
      results.push({ email: user.email, ok: false, accepted: 0, message: String(e?.message ?? e).split('\n')[0] });
    }
  }
  return results;
}
