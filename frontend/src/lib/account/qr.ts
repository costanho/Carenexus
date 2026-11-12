import qrcode from 'qrcode-generator';

// Turns the otpauth:// URI of a two-factor setup into a QR code. The code is drawn in the page from this matrix;
// the secret is never sent to an image service.

export interface QrMatrix {
  /** Modules per side, without the quiet zone. */
  size: number;
  /** SVG path data of every dark module in module units (one unit = one module), origin at the top-left module. */
  path: string;
}

/** The QR matrix of `text` (error correction M, smallest version that fits), or null when it cannot be encoded. */
export function buildQrMatrix(text: string): QrMatrix | null {
  if (!text) return null;
  try {
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    const size = qr.getModuleCount();
    const parts: string[] = [];
    for (let row = 0; row < size; row += 1) {
      // Merge each horizontal run of dark modules into one rectangle to keep the path short.
      let col = 0;
      while (col < size) {
        if (!qr.isDark(row, col)) { col += 1; continue; }
        let end = col;
        while (end + 1 < size && qr.isDark(row, end + 1)) end += 1;
        parts.push(`M${col} ${row}h${end - col + 1}v1h-${end - col + 1}z`);
        col = end + 1;
      }
    }
    return { size, path: parts.join('') };
  } catch {
    return null;
  }
}
