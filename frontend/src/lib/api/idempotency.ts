import { useCallback, useRef } from 'react';

// Idempotency-Key header for create requests (CONTRACTS 3.4): 8 to 64 characters of [A-Za-z0-9_-].
// One key per form open, reused when the same submit is retried, replaced after a success.

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

function randomChars(length: number): string {
  const cryptoApi = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }).crypto;
  let out = '';
  if (cryptoApi?.getRandomValues) {
    // Reject bytes past the last full multiple of the alphabet so every character is equally likely.
    const limit = 256 - (256 % ALPHABET.length);
    while (out.length < length) {
      const bytes = cryptoApi.getRandomValues(new Uint8Array(length * 2));
      for (let i = 0; i < bytes.length && out.length < length; i++) {
        if (bytes[i] < limit) out += ALPHABET[bytes[i] % ALPHABET.length];
      }
    }
    return out;
  }
  // Old runtimes without Web Crypto: uniqueness comes from the timestamp plus Math.random.
  out = Date.now().toString(36);
  while (out.length < length) out += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  return out.slice(0, length);
}

/** A new random key, valid for the `Idempotency-Key` header. */
export function newIdempotencyKey(): string {
  const cryptoApi = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
  return randomChars(32);
}

/** Axios request config fragment carrying the key: `api.post(url, body, withIdempotencyKey(key))`. */
export function withIdempotencyKey(key: string): { headers: Record<string, string> } {
  return { headers: { [IDEMPOTENCY_HEADER]: key } };
}

/**
 * The key for one open form. `current()` returns the same key until `renew()` is called, so a retried submit
 * repeats the key and the server answers with the stored result instead of creating a second row.
 * Call `renew()` after a successful submit (the next submit is a new request).
 */
export function useIdempotencyKey(): { current: () => string; renew: () => void } {
  const ref = useRef<string | null>(null);
  const current = useCallback(() => {
    if (!ref.current) ref.current = newIdempotencyKey();
    return ref.current;
  }, []);
  const renew = useCallback(() => { ref.current = null; }, []);
  return { current, renew };
}
