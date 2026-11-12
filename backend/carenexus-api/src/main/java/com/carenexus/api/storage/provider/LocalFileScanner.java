package com.carenexus.api.storage.provider;

import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.ScanResult;
import com.carenexus.api.storage.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Local scanner ({@code carenexus.storage.scanner=local}, the default). It does two things, both from the bytes
 * and never from the declared type or the file name:
 * <ul>
 *   <li>detects the content type from the file signature: PDF ({@code %PDF-}), JPEG (start-of-image marker), PNG
 *       (8-byte signature) and DICOM ({@code DICM} at offset 128); anything else is
 *       {@code application/octet-stream};</li>
 *   <li>rejects content that contains the EICAR anti-virus test string, the industry-standard harmless sample
 *       every real scanner reports, so the rejection path can be exercised end to end.</li>
 * </ul>
 * It is not a malware scanner: a real one (ClamAV, a cloud service) plugs in behind {@link FileScanner}.
 */
public class LocalFileScanner implements FileScanner {

    public static final String PROVIDER = "local";
    public static final String EICAR_REASON = "EICAR test signature found";

    // Assembled from two halves so this source file is not itself reported by anti-virus software.
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);
    private static final int[] EICAR_FAILURE = failureTable(EICAR);

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] DICOM_MAGIC = "DICM".getBytes(StandardCharsets.US_ASCII);
    private static final int DICOM_OFFSET = 128;
    private static final int HEADER_SIZE = DICOM_OFFSET + DICOM_MAGIC.length;
    private static final int BUFFER_SIZE = 16 * 1024;

    /** The 68-byte EICAR test file, for tests of the rejection path. Harmless by design. */
    public static byte[] eicarTestBytes() {
        return EICAR.clone();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public ScanResult scan(InputStream content, String declaredContentType, String filename) {
        if (content == null) {
            throw new StorageException("No content to scan");
        }
        byte[] header = new byte[HEADER_SIZE];
        int headerLength = 0;
        boolean eicar = false;
        int matched = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = content.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (headerLength < HEADER_SIZE) {
                        header[headerLength++] = b;
                    }
                    if (!eicar) {
                        // Knuth-Morris-Pratt: one pass, no buffering of the whole file.
                        while (matched > 0 && b != EICAR[matched]) {
                            matched = EICAR_FAILURE[matched - 1];
                        }
                        if (b == EICAR[matched]) {
                            matched++;
                        }
                        if (matched == EICAR.length) {
                            eicar = true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException("The file could not be read for scanning", e);
        }
        String detected = detect(header, headerLength);
        return eicar ? new ScanResult(false, detected, EICAR_REASON) : new ScanResult(true, detected, null);
    }

    private static String detect(byte[] header, int length) {
        if (startsWith(header, length, 0, PDF_MAGIC)) {
            return ScanResult.PDF;
        }
        if (startsWith(header, length, 0, JPEG_MAGIC)) {
            return ScanResult.JPEG;
        }
        if (startsWith(header, length, 0, PNG_MAGIC)) {
            return ScanResult.PNG;
        }
        if (startsWith(header, length, DICOM_OFFSET, DICOM_MAGIC)) {
            return ScanResult.DICOM;
        }
        return ScanResult.UNKNOWN;
    }

    private static boolean startsWith(byte[] data, int length, int offset, byte[] magic) {
        if (length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static int[] failureTable(byte[] pattern) {
        int[] table = new int[pattern.length];
        int k = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (k > 0 && pattern[i] != pattern[k]) {
                k = table[k - 1];
            }
            if (pattern[i] == pattern[k]) {
                k++;
            }
            table[i] = k;
        }
        return table;
    }
}
