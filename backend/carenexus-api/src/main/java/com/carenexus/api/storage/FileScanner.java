package com.carenexus.api.storage;

import java.io.InputStream;

/**
 * Provider seam for upload scanning. Selected by {@code carenexus.storage.scanner}: {@code local} (default: content
 * type detection from the file's first bytes plus the EICAR anti-virus test string) or a real scanner (ClamAV, a
 * cloud scanning service) once an adapter is added to {@code StorageConfig}.
 *
 * Every upload is scanned before it is stored or becomes visible. The caller owns the allow-list: it accepts a
 * file only when {@link ScanResult#clean()} is true and {@link ScanResult#detectedContentType()} is a type it
 * allows. The declared content type and the file name come from the client and are hints only.
 */
public interface FileScanner {

    /** Lower-case provider id, for example {@code local}, {@code clamav}. Used in startup checks and logs. */
    default String providerName() {
        return "custom";
    }

    /**
     * Reads the content to its end (the stream is not closed) and reports what it is.
     *
     * @param content             the uploaded bytes
     * @param declaredContentType content type sent by the client; never trusted
     * @param filename            file name sent by the client; never trusted
     * @throws StorageException when the content cannot be read or the scanner is unreachable (the upload must then
     *                          be refused)
     */
    ScanResult scan(InputStream content, String declaredContentType, String filename);
}
