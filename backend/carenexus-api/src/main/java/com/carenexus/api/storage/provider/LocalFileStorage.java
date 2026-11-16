package com.carenexus.api.storage.provider;

import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.StorageException;
import com.carenexus.api.storage.StoredFile;
import com.carenexus.api.storage.StoredFileNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local file storage ({@code carenexus.storage.provider=local}, the default): files live under one directory
 * ({@code carenexus.storage.local-dir}) on this machine, one file per key. Used on developer machines, in the
 * compose stack and in tests; a deployment with several API instances needs a shared object store instead.
 *
 * A file is written to a temporary name and moved into place, so a reader never sees a half-written file. Keys are
 * validated and resolved inside the root directory: a key can never address a path outside it.
 */
@Slf4j
public class LocalFileStorage implements FileStorage {

    public static final String PROVIDER = "local";

    static final int MAX_KEY_LENGTH = 300;
    private static final Pattern KEY_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final int BUFFER_SIZE = 16 * 1024;

    private final Path root;

    public LocalFileStorage(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("The local storage directory is required");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /** The directory files are stored under. */
    public Path root() {
        return root;
    }

    /**
     * Creates the root directory when it is missing (private to the service account where the file system supports
     * it) and checks that files can be written there.
     *
     * @throws StorageException when the directory cannot be created or is not writable
     */
    public void ensureWritable() {
        try {
            if (Files.notExists(root)) {
                Files.createDirectories(root);
                restrictToOwner(root);
            }
        } catch (IOException e) {
            throw new StorageException("The local storage directory cannot be created: " + root, e);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new StorageException("The local storage directory is not a writable directory: " + root);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public StoredFile put(String key, String contentType, InputStream content, long size) {
        Path target = resolve(key);
        if (content == null) {
            throw new StorageException("No content to store");
        }
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            MessageDigest digest = sha256();
            long written = 0;
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
            if (size >= 0 && written != size) {
                throw new StorageException("The stored content has " + written + " bytes, expected " + size);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            return new StoredFile(key, written, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new StorageException("The file could not be stored", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Temporary upload file could not be removed: {}", e.getClass().getSimpleName());
                }
            }
        }
    }

    @Override
    public InputStream open(String key) {
        Path target = resolve(key);
        try {
            if (!Files.isRegularFile(target)) {
                throw new StoredFileNotFoundException("No stored file for this key");
            }
            return Files.newInputStream(target);
        } catch (NoSuchFileException e) {
            throw new StoredFileNotFoundException("No stored file for this key");
        } catch (IOException e) {
            throw new StorageException("The stored file could not be opened", e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageException("The stored file could not be deleted", e);
        }
    }

    /** Local files have no direct URL: downloads stream through the API. */
    @Override
    public Optional<URI> signedUrl(String key, Duration ttl) {
        return Optional.empty();
    }

    /** Validates the key and resolves it inside the root directory. */
    private Path resolve(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new StorageException("Invalid storage key");
        }
        for (String segment : key.split("/", -1)) {
            if (!KEY_SEGMENT.matcher(segment).matches() || segment.endsWith(".tmp")) {
                throw new StorageException("Invalid storage key");
            }
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new StorageException("Invalid storage key");
        }
        return target;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 is not available", e);
        }
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system (or not permitted): the directory keeps the default permissions.
            log.debug("Could not restrict the local storage directory to its owner: {}", e.getClass().getSimpleName());
        }
    }
}
