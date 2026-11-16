import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { prepareDemoAccounts } from './support/accounts';
import { API_URL } from './support/env';

// Runs scripts/dev-reset-e2e-data.sh before the run (globalSetup) and after it (global-teardown.ts),
// so every run starts from the demo seed and leaves no e2e rows behind in the shared dev database.
// E2E_CLEANUP=0 keeps the data (for example to inspect it after a failure). The script only ever
// targets the local docker container or localhost; when Docker is not reachable the cleanup is
// skipped with a warning and the run goes on (specs that need the DB fail on their own).
//
// Before the run the demo accounts are also prepared (support/accounts.ts prepareDemoAccounts): each one
// accepts the current Terms and Privacy versions it has not accepted yet, so a UI sign-in lands on the
// dashboard instead of /auth/accept-terms. E2E_PREPARE=0 skips this; it never fails the run.

const run = promisify(execFile);
const SCRIPT = path.resolve(__dirname, '../../scripts/dev-reset-e2e-data.sh');
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

export interface CleanupResult {
  ok:      boolean;
  /** Rows deleted per kind, as printed by the script ("appointments", "threads", ...). */
  removed: Record<string, number>;
  message: string;
}

export async function resetE2EData(when: string, env: NodeJS.ProcessEnv = process.env): Promise<CleanupResult> {
  const skip = (message: string): CleanupResult => {
    console.warn(`[e2e cleanup] ${when}: ${message}`);
    return { ok: false, removed: {}, message };
  };
  if (env.E2E_CLEANUP === '0') return skip('skipped (E2E_CLEANUP=0)');
  if (!LOCAL_HOSTS.has(new URL(API_URL).hostname)) return skip(`skipped, the API ${API_URL} is not local`);

  try {
    const { stdout } = await run('bash', [SCRIPT], { env, timeout: 60_000 });
    const removed: Record<string, number> = {};
    for (const m of stdout.matchAll(/^\s*(\d+)\s{2}(.+)$/gm)) removed[m[2].trim()] = Number(m[1]);
    const summary = Object.entries(removed).filter(([, n]) => n > 0).map(([what, n]) => `${n} ${what}`).join(', ');
    console.log(`[e2e cleanup] ${when}: ${summary ? `deleted ${summary}` : 'nothing to delete'}`);
    return { ok: true, removed, message: summary };
  } catch (e: any) {
    const detail = String(e?.stderr || e?.message || e).trim().split('\n').pop();
    return skip(`skipped, ${detail}. Remove the test data later with \`npm run e2e:clean\`.`);
  }
}

/** Accepts pending legal documents for the demo accounts (local API only). Logs, never throws. */
export async function prepareAccounts(env: NodeJS.ProcessEnv = process.env): Promise<void> {
  if (env.E2E_PREPARE === '0') {
    console.warn('[e2e setup] demo accounts not prepared (E2E_PREPARE=0)');
    return;
  }
  if (!LOCAL_HOSTS.has(new URL(API_URL).hostname)) {
    console.warn(`[e2e setup] demo accounts not prepared: the API ${API_URL} is not local`);
    return;
  }
  const results = await prepareDemoAccounts();
  const failed = results.filter(r => !r.ok);
  const accepted = results.filter(r => r.accepted > 0).map(r => r.email);
  if (accepted.length) console.log(`[e2e setup] accepted the current terms for ${accepted.join(', ')}`);
  for (const f of failed) console.warn(`[e2e setup] could not prepare ${f.email}: ${f.message}`);
}

export default async function globalSetup(): Promise<void> {
  await resetE2EData('before the run');
  await prepareAccounts();
}
