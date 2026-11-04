import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { promisify } from 'node:util';

// Direct SQL against the LOCAL dev Postgres container, for test setup the API deliberately refuses
// (for example an appointment whose start time has already passed). Never used against anything
// but the local docker container. Configure with E2E_PG_CONTAINER / E2E_PG_USER / E2E_PG_DB.

const run = promisify(execFile);
const CONTAINER = process.env.E2E_PG_CONTAINER ?? 'carenexus-postgres';
const USER = process.env.E2E_PG_USER ?? 'carenexus';
const DB = process.env.E2E_PG_DB ?? 'carenexus_db';

// Docker Desktop on macOS puts its socket under ~/.docker; Linux (CI) uses docker's own default.
const DESKTOP_SOCKET = `${process.env.HOME}/.docker/run/docker.sock`;
const DOCKER_ENV = process.env.DOCKER_HOST || !existsSync(DESKTOP_SOCKET)
  ? process.env
  : { ...process.env, DOCKER_HOST: `unix://${DESKTOP_SOCKET}` };

/** Runs one SQL statement; returns the unaligned, tuples-only output. */
export async function sql(statement: string): Promise<string> {
  const { stdout } = await run('docker', ['exec', CONTAINER, 'psql', '-U', USER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-Atc', statement], {
    env: DOCKER_ENV,
  });
  return stdout.trim();
}

/** Moves an appointment's start (clinic wall-clock LocalDateTime "YYYY-MM-DDTHH:MM:SS"). */
export async function setAppointmentStart(appointmentId: number, localDateTime: string): Promise<void> {
  if (!Number.isInteger(appointmentId) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(localDateTime)) {
    throw new Error('setAppointmentStart: bad arguments');
  }
  const out = await sql(`UPDATE appointments SET scheduled_at = '${localDateTime}' WHERE appointment_id = ${appointmentId} RETURNING appointment_id`);
  if (!out.includes(String(appointmentId))) throw new Error(`appointment ${appointmentId} not updated`);
}
