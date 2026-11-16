import { fetchInvoiceReceipt } from './api';
import type { Invoice } from './types';

// The printable invoice / receipt (api/be-billing-notify-receipt.md, section 4.7). The server renders one
// self-contained, script-free HTML document. It is fetched with the bearer token as a Blob and shown to the
// browser's print dialog from a hidden, sandboxed frame: the API path is never opened directly and the HTML is
// never injected into the app's own page.

/** Text of the print control: the document is a receipt once the invoice is fully paid. */
export function receiptActionLabel(status: Invoice['status']): string {
  return status === 'PAID' ? 'Print receipt' : 'Print invoice';
}

/** File name for saving the document, built from the invoice on screen (the response header is not read). */
export function receiptFileName(invoice: Pick<Invoice, 'invoiceNo' | 'status'>): string {
  const safeNo = invoice.invoiceNo.replace(/[^A-Za-z0-9-]/g, '_');
  return `${invoice.status === 'PAID' ? 'receipt' : 'invoice'}-${safeNo}.html`;
}

/** True where a document can be printed: a browser. Native apps have no print dialog here. */
export function canPrintReceipt(): boolean {
  return typeof document !== 'undefined' && typeof window !== 'undefined' && typeof URL !== 'undefined'
    && typeof URL.createObjectURL === 'function';
}

/** How long the hidden frame is kept when the browser never reports that printing ended. */
const CLEANUP_AFTER_MS = 5 * 60 * 1000;

/**
 * Opens the browser's print dialog on an HTML document held in a Blob. Resolves once the dialog has been asked
 * to open; rejects when the document could not be loaded into the frame. The frame is sandboxed without
 * `allow-scripts`, so nothing inside the document can run.
 */
export function printHtmlBlob(blob: Blob, title = 'Invoice'): Promise<void> {
  if (!canPrintReceipt()) return Promise.reject(new Error('Printing is only available in a web browser.'));

  return new Promise<void>((resolve, reject) => {
    const url = URL.createObjectURL(new Blob([blob], { type: 'text/html;charset=utf-8' }));
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-same-origin allow-modals');
    frame.setAttribute('aria-hidden', 'true');
    frame.setAttribute('tabindex', '-1');
    frame.title = title;
    // Off screen rather than display:none, which some browsers refuse to print.
    frame.style.position = 'fixed';
    frame.style.right = '0';
    frame.style.bottom = '0';
    frame.style.width = '0';
    frame.style.height = '0';
    frame.style.border = '0';
    frame.style.visibility = 'hidden';

    let done = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const cleanup = () => {
      if (done) return;
      done = true;
      if (timer) clearTimeout(timer);
      URL.revokeObjectURL(url);
      frame.remove();
    };

    frame.onload = () => {
      const target = frame.contentWindow;
      if (!target) {
        cleanup();
        reject(new Error('The document could not be opened for printing.'));
        return;
      }
      // Browsers that block until the dialog closes fire afterprint straight away; the others fire it when the
      // dialog is dismissed. The timer covers a browser that never fires it.
      target.addEventListener('afterprint', cleanup, { once: true });
      timer = setTimeout(cleanup, CLEANUP_AFTER_MS);
      try {
        target.focus();
        target.print();
        resolve();
      } catch {
        cleanup();
        reject(new Error('The document could not be opened for printing.'));
      }
    };
    frame.onerror = () => {
      cleanup();
      reject(new Error('The document could not be opened for printing.'));
    };

    frame.src = url;
    document.body.appendChild(frame);
  });
}

/**
 * Fetches the current invoice / receipt document and opens the print dialog on it. The document is fetched on
 * every call, never cached: it changes when a payment arrives. Rejects with the request's error (show it with
 * `apiError`; a 404 means the invoice is not available to the caller).
 */
export async function printInvoiceReceipt(invoice: Pick<Invoice, 'invoiceId' | 'invoiceNo' | 'status'>): Promise<void> {
  const blob = await fetchInvoiceReceipt(invoice.invoiceId);
  await printHtmlBlob(blob, `${invoice.status === 'PAID' ? 'Receipt' : 'Invoice'} ${invoice.invoiceNo}`);
}
