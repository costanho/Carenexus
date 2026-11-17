// Turns the Expo static web export (dist/) into Vercel Build Output API v3 (.vercel/output/), with the same routing
// and security headers as the nginx web container (docker/default.conf.template, docker/security-headers.inc.template).
//
// Run by `npm run vercel-build` (frontend/vercel.json) after `expo export`. Environment, set in the Vercel project:
//   EXPO_PUBLIC_API_URL   required: the public HTTPS API origin, inlined into the bundle by `expo export`
//   CSP_CONNECT_SRC       optional: extra origins for connect-src, space-separated, e.g. the LiveKit project's
//                         "https://<project>.livekit.cloud wss://<project>.livekit.cloud". The API origin is always added.
//
// The security headers are read from docker/security-headers.inc.template, so nginx and Vercel cannot drift apart.

import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const output = join(root, '.vercel', 'output');
const fail = (message) => {
  console.error(`vercel-build-output: ${message}`);
  process.exit(1);
};

// ---- Inputs ------------------------------------------------------------------------------------------------------
const apiUrl = process.env.EXPO_PUBLIC_API_URL;
if (!apiUrl) fail('EXPO_PUBLIC_API_URL is not set. Set it in the Vercel project to the API origin, e.g. https://<app>.fly.dev');
let apiOrigin;
try {
  apiOrigin = new URL(apiUrl).origin;
} catch {
  fail(`EXPO_PUBLIC_API_URL is not a URL: ${apiUrl}`);
}
if (process.env.VERCEL && !apiOrigin.startsWith('https://')) fail(`EXPO_PUBLIC_API_URL must be https:// on Vercel, got ${apiOrigin}`);
if (!existsSync(join(dist, 'index.html'))) fail('dist/index.html is missing: run `expo export --platform web --output-dir dist` first');

const extra = (process.env.CSP_CONNECT_SRC ?? '').split(/\s+/).filter(Boolean);
if (!extra.some((o) => o.startsWith('wss://'))) {
  console.warn('vercel-build-output: CSP_CONNECT_SRC has no wss:// origin, so video calls (LiveKit) will be blocked by the CSP.');
}
const connectSrc = [...new Set([apiOrigin, ...extra])].join(' ');

// ---- Security headers from the nginx template --------------------------------------------------------------------
const template = readFileSync(join(root, 'docker', 'security-headers.inc.template'), 'utf8');
const security = {};
for (const match of template.matchAll(/^add_header\s+(\S+)\s+"([^"]*)"\s+always;\s*$/gm)) {
  security[match[1]] = match[2].replaceAll('${CSP_CONNECT_SRC}', connectSrc);
}
if (!security['Content-Security-Policy']) fail('no Content-Security-Policy found in docker/security-headers.inc.template');
if (/\$\{/.test(JSON.stringify(security))) fail('an unsubstituted ${...} variable is left in the security headers');

// ---- Static files ------------------------------------------------------------------------------------------------
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
cpSync(dist, join(output, 'static'), { recursive: true });

// Copies with plain names, so routes never have to reference "[...]" or "+" in a destination path.
const staticDir = join(output, 'static');
const callPage = join(staticDir, 'dashboard', 'call', '[appointmentId].html');
if (!existsSync(callPage)) fail('dashboard/call/[appointmentId].html is missing from the export');
renameSync(callPage, join(staticDir, 'dashboard', 'call', '_call-room.html'));
const notFound = join(staticDir, '+not-found.html');
cpSync(existsSync(notFound) ? notFound : join(staticDir, 'index.html'), join(staticDir, '_404.html'));

// ---- Routes (mirror of docker/default.conf.template) -------------------------------------------------------------
const withCache = (value) => ({ ...security, 'Cache-Control': value });
const routes = [
  // Headers on every response; `continue` lets the request fall through to the routes below.
  { src: '^/_expo/(.*)$', headers: withCache('public, max-age=31536000, immutable'), continue: true },
  { src: '^/assets/(.*)$', headers: withCache('public, max-age=604800'), continue: true },
  { src: '^/(?!_expo/|assets/)(.*)$', headers: withCache('no-cache'), continue: true },
  // The renamed internal copies are never served under their own names.
  { src: '^/(dashboard/call/_call-room|_404)\\.html$', dest: '/_404.html', status: 404 },
  // Existing files: bundles, assets, exported pages (index.html for /).
  { handle: 'filesystem' },
  // The one dynamic route: /dashboard/call/<appointmentId>.
  { src: '^/dashboard/call/[^/.]+/?$', dest: '/dashboard/call/_call-room.html' },
  // Missing bundles, assets and any other path with a file extension: 404, never the app shell.
  { src: '^/(_expo|assets)/.*$', dest: '/_404.html', status: 404 },
  { src: '^.*\\.[A-Za-z0-9]+$', dest: '/_404.html', status: 404 },
  // Clean URLs: /dashboard/doctor -> dashboard/doctor.html, then <path>/index.html, else the app shell.
  { src: '^/(.+?)/?$', dest: '/$1.html', check: true },
  { src: '^/(.+?)/?$', dest: '/$1/index.html', check: true },
  { src: '^/.*$', dest: '/index.html' },
];

writeFileSync(join(output, 'config.json'), `${JSON.stringify({ version: 3, routes }, null, 2)}\n`);
console.log(`vercel-build-output: wrote .vercel/output (connect-src 'self' ${connectSrc})`);
