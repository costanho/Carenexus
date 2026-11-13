package com.carenexus.api.storage;

/**
 * A file could not be stored, read, deleted or scanned. The message is technical and never contains file content;
 * callers translate it into their own client error (for example 503 PROVIDER_UNAVAILABLE) or let it surface as 500.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
