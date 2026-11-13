package com.carenexus.api.storage;

/**
 * What {@link FileStorage#put} stored.
 *
 * @param key    the storage key
 * @param size   number of bytes stored
 * @param sha256 SHA-256 of the stored bytes, 64 lower-case hex characters
 */
public record StoredFile(String key, long size, String sha256) {
}
