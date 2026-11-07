// Minimal static server for the exported web app (`npx expo export --platform web`), used by the
// e2e tests and for local demos. DEV ONLY. No dependencies.
//
//   node e2e/static-server.mjs [distDir] [port]     (defaults: dist, 8081)
//
// Resolves "/x" to x, x.html or x/index.html, maps dynamic routes such as
// /dashboard/call/12 to the exported "[appointmentId].html" page, and falls back to index.html
// so client-side routing still works for any other path.
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';

const root = resolve(process.argv[2] ?? 'dist');
const port = Number(process.argv[3] ?? process.env.PORT ?? 8081);

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.map': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon',
  '.webp': 'image/webp', '.ttf': 'font/ttf', '.otf': 'font/otf', '.woff': 'font/woff', '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

function isFile(p) {
  try { return statSync(p).isFile(); } catch { return false; }
}

/** Finds "[param].html" siblings so dynamic routes resolve to their exported page. */
function dynamicMatch(dir, segment) {
  if (!existsSync(dir)) return null;
  try {
    const page = readdirSync(dir).find(f => /^\[[^\]]+\]\.html$/.test(f));
    return page && segment ? join(dir, page) : null;
  } catch {
    return null;
  }
}

function resolvePath(urlPath) {
  let decoded;
  try { decoded = decodeURIComponent(urlPath); } catch { return null; }
  const rel = normalize(decoded).replace(/^([/\\])+/, '');
  const abs = join(root, rel);
  if (abs !== root && !abs.startsWith(root + sep)) return null; // path traversal
  const candidates = [abs, `${abs}.html`, join(abs, 'index.html')];
  for (const c of candidates) if (isFile(c)) return c;
  const parts = rel.split(/[/\\]/).filter(Boolean);
  if (parts.length > 0) {
    const dyn = dynamicMatch(join(root, ...parts.slice(0, -1)), parts[parts.length - 1]);
    if (dyn) return dyn;
  }
  return null;
}

const server = createServer((req, res) => {
  const urlPath = (req.url ?? '/').split('?')[0];
  let file = resolvePath(urlPath);
  let status = 200;
  if (!file) {
    // Assets that do not exist are real 404s; anything else is a client route.
    if (extname(urlPath)) status = 404;
    file = join(root, 'index.html');
  }
  if (status === 404) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
    return;
  }
  res.writeHead(200, {
    'Content-Type': TYPES[extname(file).toLowerCase()] ?? 'application/octet-stream',
    'Cache-Control': file.endsWith('.html') ? 'no-cache' : 'public, max-age=3600',
  });
  createReadStream(file).pipe(res);
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Serving ${root} at http://localhost:${port}`);
});
