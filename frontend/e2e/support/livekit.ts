/// <reference types="node" />
import { createHmac } from 'node:crypto';

// Direct LiveKit server API access, used only to check from the provider's side who is in a room.
// The local stack runs `livekit-server --dev`, whose public dev key pair is devkey/secret
// (docker-compose.yml). These are published development placeholders, never real credentials.

const LIVEKIT_HTTP_URL = process.env.E2E_LIVEKIT_URL ?? 'http://localhost:7880';
const LIVEKIT_API_KEY = process.env.E2E_LIVEKIT_API_KEY ?? 'devkey';
const LIVEKIT_API_SECRET = process.env.E2E_LIVEKIT_API_SECRET ?? 'secret';

const b64url = (s: string | Buffer) => Buffer.from(s).toString('base64url');

function adminToken(room: string): string {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    iss: LIVEKIT_API_KEY, sub: 'e2e-observer', nbf: now - 5, exp: now + 120,
    video: { roomAdmin: true, room },
  }));
  const sig = createHmac('sha256', LIVEKIT_API_SECRET).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${sig}`;
}

/** Room name and identity carried in a LiveKit access token issued by the API (payload only, never logged). */
export function tokenClaims(jwt: string): { room: string; identity: string } {
  const payload = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8'));
  return { room: payload.video?.room, identity: payload.sub };
}

/** Identities currently connected to `room`, as reported by the LiveKit server itself. */
export async function roomIdentities(room: string): Promise<string[]> {
  const res = await fetch(`${LIVEKIT_HTTP_URL}/twirp/livekit.RoomService/ListParticipants`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken(room)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ room }),
  });
  if (res.status === 404) return []; // room gone
  if (!res.ok) throw new Error(`LiveKit ListParticipants: HTTP ${res.status}`);
  const body = await res.json() as { participants?: { identity: string }[] };
  return (body.participants ?? []).map(p => p.identity);
}
