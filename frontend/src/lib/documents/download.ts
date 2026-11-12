import { Platform } from 'react-native';
import { downloadDocument } from './api';
import type { DocumentDto } from './types';

// Saving a downloaded file. The bytes are fetched with the bearer token through the shared axios instance and
// handed to the browser from memory, so the download endpoint is never opened in a tab and no token is ever
// part of a URL (api/be-documents.md section 8.2).

/** How long the in-memory URL stays valid after the save prompt was triggered. */
const REVOKE_AFTER_MS = 60_000;

function safeFileName(name: string | null | undefined): string {
  const cleaned = (name ?? '').replace(/[\u0000-\u001F\u007F"<>:|?*\\/]+/g, ' ').replace(/\s+/g, ' ').trim();
  return cleaned || 'document';
}

/** Offers a blob to the browser as a file to save under `fileName`. Web only. */
export function saveBlob(blob: Blob, fileName: string): void {
  if (Platform.OS !== 'web' || typeof document === 'undefined' || typeof URL === 'undefined') {
    throw new Error('Saving files is available in the web app.');
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = safeFileName(fileName);
  link.rel = 'noopener';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // The browser reads the URL after the click returns; revoking at once would cancel the save in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), REVOKE_AFTER_MS);
}

/**
 * Downloads a document and opens the browser's save prompt with the name stored for it (`originalFilename`;
 * the Content-Disposition header is not parsed). Rejects with the request error when the download fails.
 */
export async function saveDocument(doc: Pick<DocumentDto, 'documentId' | 'originalFilename'>): Promise<void> {
  const blob = await downloadDocument(doc.documentId);
  saveBlob(blob, doc.originalFilename);
}
