// Demo accounts from the DEV-ONLY seed (backend/carenexus-api/src/main/resources/db/seed, V2 and V21).
// These passwords are published development placeholders, never real credentials.

export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:9000';

export type LoginRole = 'patient' | 'doctor' | 'proxy';

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
// Sarah's system id depends on insert order, so it is looked up at runtime (see api.ts `me`).
export const SARAH: DemoUser = {
  email: 'sarah.dube@carenexus.com', password: 'proxy@sarah', role: 'proxy', systemId: '', name: 'Sarah Dube',
};

export const LABELS = {
  doctor:          'Dr. James Banda',
  patientInCall:   'Thomas Dube (Patient)',
  caregiverInCall: 'Sarah Dube (Caregiver for Thomas Dube)',
  caregiverMsg:    'Sarah Dube (Caregiver for Thomas Dube)',
};

export const ROLE_LOGIN_LABEL: Record<LoginRole, string> = {
  patient: 'Patient',
  doctor:  'Doctor',
  proxy:   'Proxy / Carer',
};

export const ROLE_HOME: Record<LoginRole, string> = {
  patient: '/dashboard/patient',
  doctor:  '/dashboard/doctor',
  proxy:   '/dashboard/proxy',
};

/** Unique tag for test data so reruns never collide with earlier runs. */
export function runTag(): string {
  return `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}
