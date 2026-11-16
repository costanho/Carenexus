package com.carenexus.api.storage;

import com.carenexus.api.storage.provider.LocalFileScanner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalFileScanner: content types are detected from the file signature (never from the declared type or the file
 * name) and the EICAR anti-virus test string is rejected wherever it appears.
 */
class LocalFileScannerTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private final LocalFileScanner scanner = new LocalFileScanner();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] dicom() {
        byte[] file = new byte[400];
        System.arraycopy(bytes("DICM"), 0, file, 128, 4);
        return file;
    }

    private ScanResult scan(byte[] content) {
        return scanner.scan(new ByteArrayInputStream(content), null, null);
    }

    // ==================== DETECTION ====================

    @Test
    void detectsPdfJpegPngAndDicomFromTheirSignatures() {
        ScanResult pdf = scan(bytes("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF"));
        assertTrue(pdf.clean());
        assertEquals("application/pdf", pdf.detectedContentType());
        assertNull(pdf.reason());

        assertEquals("image/jpeg", scan(concat(JPEG_SIGNATURE, new byte[200])).detectedContentType());
        assertEquals("image/png", scan(concat(PNG_SIGNATURE, new byte[200])).detectedContentType());
        assertEquals("application/dicom", scan(dicom()).detectedContentType());
        assertTrue(scan(dicom()).clean());
    }

    @Test
    void theSmallestFilesThatCarryASignature_areDetected() {
        assertEquals("application/pdf", scan(bytes("%PDF-")).detectedContentType());
        assertEquals("image/jpeg", scan(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).detectedContentType());
        assertEquals("image/png", scan(PNG_SIGNATURE).detectedContentType());
        assertEquals("application/dicom", scan(Arrays.copyOf(dicom(), 132)).detectedContentType());
    }

    @Test
    void anythingElse_isOctetStream_andStillClean() {
        for (byte[] content : new byte[][]{
                new byte[0],
                bytes("just some text"),
                bytes("<html><script>alert(1)</script></html>"),
                bytes("MZ\u0090\u0000 this is an executable header"),
                bytes("PK\u0003\u0004 zip archive"),
                bytes(" %PDF-1.7 not at the start"),
                bytes("%PDF"),
                Arrays.copyOf(PNG_SIGNATURE, 7),
                new byte[]{(byte) 0xFF, (byte) 0xD8},
                Arrays.copyOf(dicom(), 131),
                concat(new byte[127], bytes("DICM"), new byte[50])}) {
            ScanResult result = scan(content);
            assertEquals("application/octet-stream", result.detectedContentType());
            assertTrue(result.clean(), "an unknown type is not malware: the caller's allow-list rejects it");
            assertNull(result.reason());
        }
    }

    @Test
    void theDeclaredTypeAndTheFileNameAreNeverTrusted() {
        ScanResult disguised = scanner.scan(new ByteArrayInputStream(bytes("MZ this is not a pdf")),
                "application/pdf", "report.pdf");
        assertEquals("application/octet-stream", disguised.detectedContentType());

        ScanResult renamed = scanner.scan(new ByteArrayInputStream(bytes("%PDF-1.4 real pdf")),
                "application/x-msdownload", "scan.exe");
        assertEquals("application/pdf", renamed.detectedContentType());
        assertTrue(renamed.clean());
    }

    // ==================== REJECTION ====================

    @Test
    void theEicarTestFile_isRejected() {
        byte[] eicar = LocalFileScanner.eicarTestBytes();
        assertEquals(68, eicar.length, "the standard test file is 68 bytes");

        ScanResult result = scan(eicar);

        assertFalse(result.clean());
        assertEquals("EICAR test signature found", result.reason());
        assertEquals("application/octet-stream", result.detectedContentType());
    }

    @Test
    void theEicarString_isRejectedInsideAnAllowedFileType_andAcrossReadBuffers() {
        byte[] eicar = LocalFileScanner.eicarTestBytes();

        ScanResult inPdf = scan(concat(bytes("%PDF-1.7\n"), new byte[500], eicar, bytes("\n%%EOF")));
        assertFalse(inPdf.clean());
        assertEquals("application/pdf", inPdf.detectedContentType(), "the type is still reported");
        assertEquals(LocalFileScanner.EICAR_REASON, inPdf.reason());

        // The signature straddles the scanner's 16 KB read buffer.
        ScanResult straddling = scan(concat(PNG_SIGNATURE, new byte[16 * 1024 - 8 - 30], eicar, new byte[5000]));
        assertFalse(straddling.clean());
        assertEquals("image/png", straddling.detectedContentType());

        // Deep inside a large file, and at its very end.
        assertFalse(scan(concat(JPEG_SIGNATURE, new byte[3_000_000], eicar)).clean());

        // A stream that hands out one byte at a time.
        InputStream trickle = new ByteArrayInputStream(concat(bytes("prefix "), eicar)) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        assertFalse(scanner.scan(trickle, "text/plain", "eicar.com").clean());
    }

    @Test
    void nearMisses_areClean() {
        byte[] eicar = LocalFileScanner.eicarTestBytes();
        byte[] truncated = Arrays.copyOf(eicar, eicar.length - 1);
        byte[] altered = eicar.clone();
        altered[30] = 'x';
        byte[] lower = new String(eicar, StandardCharsets.US_ASCII).toLowerCase().getBytes(StandardCharsets.US_ASCII);

        assertTrue(scan(truncated).clean());
        assertTrue(scan(altered).clean());
        assertTrue(scan(lower).clean());
        // A partial match followed by the full string is still found.
        assertFalse(scan(concat(Arrays.copyOf(eicar, 20), eicar)).clean());
        assertFalse(scan(concat(bytes("X5O!P%@AP[4\\PZX5"), bytes("X5O!"), eicar)).clean());
    }

    @Test
    void eicarTestBytes_isACopy() {
        byte[] first = LocalFileScanner.eicarTestBytes();
        first[0] = 'Y';

        assertEquals('X', LocalFileScanner.eicarTestBytes()[0]);
        assertFalse(scan(LocalFileScanner.eicarTestBytes()).clean());
    }

    // ==================== FAILURES ====================

    @Test
    void anUnreadableStream_failsClosed() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThrows(StorageException.class, () -> scanner.scan(broken, "application/pdf", "a.pdf"));
        assertThrows(StorageException.class, () -> scanner.scan(null, "application/pdf", "a.pdf"));
    }

    @Test
    void theStreamIsReadToItsEnd_andNotClosed() throws Exception {
        boolean[] closed = {false};
        ByteArrayInputStream content = new ByteArrayInputStream(concat(bytes("%PDF-1.7"), new byte[40_000])) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        scanner.scan(content, null, null);

        assertEquals(-1, content.read(), "read to the end");
        assertFalse(closed[0], "the caller owns the stream");
        assertEquals("local", scanner.providerName());
    }
}
