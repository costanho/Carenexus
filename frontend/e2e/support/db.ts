import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

// Direct SQL against the LOCAL dev Postgres container, for test setup the API deliberately refuses
// (for example an appointment whose start time has already passed). Never used against anything
// but the local docker container. Configure with E2E_PG_CONTAINER / E2E_PG_USER / E2E_PG_DB.

const run = promisify(execFile);
const CONTAINER = process.env.E2E_PG_CONTAINER ?? 'carenexus-postgres';
const USER = process.env.E2E_PG_USER ?? 'carenexus';
const DB = process.env.E2E_PG_DB ?? 'carenexus_db';

/** Runs one SQL statement; returns the unaligned, tuples-only output. */
export async function sql(statement: string): Promise<string> {
  const { stdout } = await run('docker', ['exec', CONTAINER, 'psql', '-U', USER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-Atc', statement], {
    env: { ...process.env, DOCKER_HOST: process.env.DOCKER_HOST ?? `unix://${process.env.HOME}/.docker/run/docker.sock` },
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
