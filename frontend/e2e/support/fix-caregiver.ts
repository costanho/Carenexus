import { expect, request } from '@playwright/test';
import { API_URL, type DemoUser } from './env';
import { Api } from './api';

// Helpers for fix-caregiver.spec.ts. Test data starts with FIX_PREFIX; throwaway accounts are
// e2e-fix-caregiver-…@carenexus.test, which the global e2e cleanup (scripts/dev-reset-e2e-data.sh) removes.

export const FIX_PREFIX = 'e2e-fix-caregiver-';

export function fixTag(): string {
  return `${FIX_PREFIX}${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** Registers a throwaway caregiver account (local dev API only); the password lives only in memory. */
export async function registerFixCaregiver(tag: string): Promise<DemoUser> {
  const user: DemoUser = {
    email: `${tag}@carenexus.test`,
    password: `Fix-${crypto.randomUUID()}`,
    role: 'proxy',
    systemId: '',
    name: 'Fix Carer',
  };
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/register', {
      data: { firstName: 'Fix', lastName: 'Carer', email: user.email, password: user.password, role: 'proxy' },
    });
    expect(res.status(), 'register fix caregiver').toBeLessThan(300);
    user.systemId = (await res.json()).user?.system_id ?? '';
  } finally {
    await ctx.dispose();
  }
  return user;
}

/** A dependent created through "Add Loved One": signs in as that new patient and revokes the caregiver's grant. */
export async function revokeDependentGrants(dependent: DemoUser): Promise<void> {
  const api = await Api.login(dependent);
  try {
    const grants = await api.ok(api.get<any[]>('/api/patients/me/caregiver-access'), 'dependent grants');
    for (const g of grants) await api.del(`/api/patients/me/caregiver-access/${g.accessId}`);
  } finally {
    await api.dispose();
  }
}

/** Removes the patient's grants to throwaway fix-caregiver accounts (this also removes them from conversations). */
export async function revokeFixCaregiverGrants(patient: Api): Promise<void> {
  const grants = await patient.get<any[]>('/api/patients/me/caregiver-access');
  for (const g of (grants.body ?? []) as any[]) {
    if (String(g.caregiverEmail ?? '').startsWith(FIX_PREFIX)) await patient.del(`/api/patients/me/caregiver-access/${g.accessId}`);
  }
}

/** The caller leaves each conversation (care team threads only; leaving is how a member removes themselves). */
export async function leaveThreads(api: Api, threadIds: number[]): Promise<void> {
  for (const id of threadIds) {
    await api.del(`/api/communications/threads/${id}/members/${encodeURIComponent(api.systemId)}`).catch(() => {});
  }
}
