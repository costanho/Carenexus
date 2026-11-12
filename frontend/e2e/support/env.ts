// Demo accounts from the DEV-ONLY seed (backend/carenexus-api/src/main/resources/db/seed, V2 and V21).
// These passwords are published development placeholders, never real credentials.

export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:9000';

/** The dashboard a session lands on. `admin` has no tab on the sign-in page: the server role decides. */
export type LoginRole = 'patient' | 'doctor' | 'proxy' | 'admin';

export interface DemoUser {
  email:     string;
  password:  string;
  role:      LoginRole;
  systemId:  string;
  name:      string;
}

export const THOMAS: DemoUser = {
  email: 'thomas.dube@carenexus.com', password: 'patient@thomas', role: 'patient', systemId: 'PAT-00021', name: 'Thomas Dube',
};
export const JAMES: DemoUser = {
  email: 'james.banda@carenexus.com', password: 'doctor@banda', role: 'doctor', systemId: 'DOC-00002', name: 'James Banda',
};
// Seed V2 patient with medical records and lab results (Thomas has none in the dev seed).
export const LINDA: DemoUser = {
  email: 'linda.moyo@carenexus.com', password: 'patient@123', role: 'patient', systemId: 'PAT-00001', name: 'Linda Moyo',
};
// Sarah's system id depends on insert order, so it is looked up at runtime (see api.ts `me`).
export const SARAH: DemoUser = {
  email: 'sarah.dube@carenexus.com', password: 'proxy@sarah', role: 'proxy', systemId: '', name: 'Sarah Dube',
};
// The seeded administrator (V2). Its system id (ADM-…) depends on insert order and is filled in at sign-in
// (accounts.ts loginAdmin). E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD point the suites at another local admin.
export const ADMIN: DemoUser = {
  email:    process.env.E2E_ADMIN_EMAIL ?? 'admin@carenexus.com',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'admin@nexus',
  role:     'admin',
  systemId: '',
  name:     'Admin User',
};

/** Every demo account the suites sign in with (global setup prepares them once per run). */
export const DEMO_USERS: readonly DemoUser[] = [THOMAS, JAMES, LINDA, SARAH, ADMIN];

export const LABELS = {
  doctor:          'Dr. James Banda',
  patientInCall:   'Thomas Dube (Patient)',
  caregiverInCall: 'Sarah Dube (Caregiver for Thomas Dube)',
  caregiverMsg:    'Sarah Dube (Caregiver for Thomas Dube)',
};

/** The role tab chosen on the sign-in page. Administrators may use any tab; the server role wins. */
export const ROLE_LOGIN_LABEL: Record<LoginRole, string> = {
  patient: 'Patient',
  doctor:  'Doctor',
  proxy:   'Proxy / Carer',
  admin:   'Patient',
};

export const ROLE_HOME: Record<LoginRole, string> = {
  patient: '/dashboard/patient',
  doctor:  '/dashboard/doctor',
  proxy:   '/dashboard/proxy',
  admin:   '/dashboard/admin',
};

/** Unique tag for test data so reruns never collide with earlier runs. */
export function runTag(): string {
  return `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Test data markers (CONTRACTS section 13). The cleanup script (scripts/dev-reset-e2e-data.sh) recognises
 * free text that starts with E2E_MARKER and throwaway accounts on THROWAWAY_DOMAIN, nothing else.
 */
export const E2E_MARKER = 'e2e';
export const THROWAWAY_DOMAIN = 'carenexus.test';

/** Pattern a throwaway account's email must match to be removed by the cleanup script. */
export const THROWAWAY_EMAIL = /^e2e-[a-z0-9._+-]+@carenexus\.test$/i;

/** Email of a throwaway account for a tag from runTag() (or any tag starting with "e2e-"). */
export function throwawayEmail(tag: string): string {
  const email = `${tag.toLowerCase()}@${THROWAWAY_DOMAIN}`;
  if (!THROWAWAY_EMAIL.test(email)) {
    throw new Error(`"${tag}" does not make a throwaway address the e2e cleanup removes (it must start with "e2e-")`);
  }
  return email;
}
