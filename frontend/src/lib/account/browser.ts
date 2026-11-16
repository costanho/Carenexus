import * as Clipboard from 'expo-clipboard';

// The two things the account cards do outside React: put text on the clipboard and save a file.

/** Copies text to the clipboard. Resolves false when the browser refused (no permission, insecure page). */
export async function copyText(text: string): Promise<boolean> {
  try {
    return (await Clipboard.setStringAsync(text)) !== false;
  } catch {
    return false;
  }
}

/** True where a file can be saved from memory (a browser page). */
export function canSaveFiles(): boolean {
  return typeof document !== 'undefined' && typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function';
}

/**
 * Saves a Blob under `fileName` through a temporary download link. Used for files that need the bearer token
 * (a plain link cannot carry it) and for text generated in the page.
 */
export function saveBlob(blob: Blob, fileName: string): void {
  if (!canSaveFiles()) throw new Error('Files can only be saved from a web browser.');
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.rel = 'noopener';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Give the browser a moment to start the download before the object URL is released.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Saves text as a plain-text file. */
export function saveTextFile(text: string, fileName: string): void {
  saveBlob(new Blob([text], { type: 'text/plain;charset=utf-8' }), fileName);
}
