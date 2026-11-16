import * as Clipboard from 'expo-clipboard';

// The temporary password a caregiver hands to a loved one who will sign in themselves, and the clipboard
// handling around it. The password is never stored or logged: it lives in the open form only.

const LETTERS = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
const DIGITS  = '23456789';

/** A uniformly random index below `bound` (rejection sampling on Web Crypto; Math.random on old runtimes). */
function randomIndex(bound: number): number {
  const cryptoApi = (globalThis as { crypto?: { getRandomValues?: (a: Uint32Array) => Uint32Array } }).crypto;
  if (!cryptoApi?.getRandomValues) return Math.floor(Math.random() * bound);
  const limit = 0x100000000 - (0x100000000 % bound);
  const buffer = new Uint32Array(1);
  do { cryptoApi.getRandomValues(buffer); } while (buffer[0] >= limit);
  return buffer[0] % bound;
}

/**
 * Twelve random characters with at least one letter and one digit (the backend rule is 8 to 72 characters with
 * a letter and a number). Look-alike characters (0/O, 1/l/I) are left out because the password is read aloud
 * or copied by hand.
 */
export function generateTemporaryPassword(): string {
  const all = LETTERS + DIGITS;
  const chars = [LETTERS[randomIndex(LETTERS.length)], DIGITS[randomIndex(DIGITS.length)]];
  while (chars.length < 12) chars.push(all[randomIndex(all.length)]);
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

/** Copies text to the clipboard. Resolves to false when the browser or device refused; never rejects. */
export async function copyToClipboard(value: string): Promise<boolean> {
  try {
    return (await Clipboard.setStringAsync(value)) !== false;
  } catch {
    return false;
  }
}

/**
 * Empties the clipboard after a password was copied to it, so it does not stay there once the form is closed.
 * Best effort: a browser may refuse a write that is not the direct result of a click.
 */
export async function clearClipboard(): Promise<void> {
  try {
    await Clipboard.setStringAsync('');
  } catch {
    // Nothing more can be done from the page.
  }
}
