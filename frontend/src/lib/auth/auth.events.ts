// Tiny event hook so the API layer can tell the UI that the session is gone
// (refresh token missing, expired, rotated or revoked). The root layout
// registers a handler that sends the user back to the login screen.

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
