// Tiny event hooks so the API layer can tell the UI that the session is gone
// (refresh token missing, expired, rotated or revoked). The root layout
// registers a handler that sends the user back to the login screen.
// onSignedOut fires on every sign-out (explicit or session expiry) and accepts
// any number of listeners, e.g. messaging clearing drafts and unread counts.

type Listener = () => void;

let listener: Listener | null = null;

export function onSessionExpired(fn: Listener): () => void {
  listener = fn;
  return () => {
    if (listener === fn) listener = null;
  };
}

export function emitSessionExpired(): void {
  listener?.();
}

const signedOut = new Set<Listener>();

export function onSignedOut(fn: Listener): () => void {
  signedOut.add(fn);
  return () => { signedOut.delete(fn); };
}

export function emitSignedOut(): void {
  signedOut.forEach(fn => {
    try { fn(); } catch { /* one listener must not block the others */ }
  });
}
