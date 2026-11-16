import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { promisify } from 'node:util';

// Direct SQL against the LOCAL dev Postgres, for test setup the API deliberately refuses (for example an
// appointment whose start time has already passed) and as the fallback reader of the outbox. Never used against
// anything but the local docker container or a Postgres on localhost (the same targets as
// scripts/dev-reset-e2e-data.sh). Configure with E2E_PG_CONTAINER / E2E_PG_USER / E2E_PG_DB, or E2E_PG_HOST
// (localhost, 127.0.0.1 or ::1) with E2E_PG_PORT to use the host psql instead of the container.

const run = promisify(execFile);
const CONTAINER = process.env.E2E_PG_CONTAINER ?? 'carenexus-postgres';
const USER = process.env.E2E_PG_USER ?? 'carenexus';
const DB = process.env.E2E_PG_DB ?? 'carenexus_db';
const HOST = process.env.E2E_PG_HOST;
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1']);

// Docker Desktop on macOS puts its socket under ~/.docker; Linux (CI) uses docker's own default.
const DESKTOP_SOCKET = `${process.env.HOME}/.docker/run/docker.sock`;
const DOCKER_ENV = process.env.DOCKER_HOST || !existsSync(DESKTOP_SOCKET)
  ? process.env
  : { ...process.env, DOCKER_HOST: `unix://${DESKTOP_SOCKET}` };

function psqlCommand(statement: string): { file: string; args: string[] } {
  const common = ['-X', '-U', USER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-Atc', statement];
  if (HOST !== undefined && HOST !== '') {
    if (!LOCAL_HOSTS.has(HOST)) throw new Error(`E2E_PG_HOST=${HOST} is not localhost; e2e SQL only runs against a local database`);
    return { file: 'psql', args: ['-h', HOST, '-p', process.env.E2E_PG_PORT ?? '5432', ...common] };
  }
  return { file: 'docker', args: ['exec', CONTAINER, 'psql', ...common] };
}

/** Runs one SQL statement; returns the unaligned, tuples-only output. */
export async function sql(statement: string): Promise<string> {
  const { file, args } = psqlCommand(statement);
  const { stdout } = await run(file, args, { env: DOCKER_ENV, maxBuffer: 16 * 1024 * 1024 });
  return stdout.trim();
}

/**
 * A SQL string literal for `value` (standard_conforming_strings is on, so only quotes need doubling).
 * Use it for every value that is not a hard-coded constant.
 */
export function sqlLiteral(value: string | number | boolean | null): string {
  if (value === null) return 'NULL';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('sqlLiteral: not a finite number');
    return String(value);
  }
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE';
  if (value.includes('\0')) throw new Error('sqlLiteral: NUL character');
  return `'${value.replace(/'/g, "''")}'`;
}

/** Runs a SELECT and returns its rows as objects (column names as written in the query). */
export async function sqlRows<T = Record<string, unknown>>(select: string): Promise<T[]> {
  const out = await sql(`SELECT coalesce(json_agg(q), '[]'::json) FROM (${select}) q`);
  return JSON.parse(out || '[]') as T[];
}

/** Whether the local database can be reached (Docker running, container up, or host psql answering). */
export async function databaseReachable(): Promise<boolean> {
  try {
    return (await sql('SELECT 1')) === '1';
  } catch {
    return false;
  }
}

/** Moves an appointment's start (clinic wall-clock LocalDateTime "YYYY-MM-DDTHH:MM:SS"). */
export async function setAppointmentStart(appointmentId: number, localDateTime: string): Promise<void> {
  if (!Number.isInteger(appointmentId) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(localDateTime)) {
    throw new Error('setAppointmentStart: bad arguments');
  }
  const out = await sql(`UPDATE appointments SET scheduled_at = '${localDateTime}' WHERE appointment_id = ${appointmentId} RETURNING appointment_id`);
  if (!out.includes(String(appointmentId))) throw new Error(`appointment ${appointmentId} not updated`);
}
