package com.carenexus.api.storage;

/** Nothing is stored under the requested key. */
public class StoredFileNotFoundException extends StorageException {

    public StoredFileNotFoundException(String message) {
        super(message);
    }
}
