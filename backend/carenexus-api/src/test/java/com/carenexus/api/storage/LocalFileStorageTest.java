package com.carenexus.api.storage;

import com.carenexus.api.storage.provider.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalFileStorage on a temporary directory: round trip with size and SHA-256, replace, delete, missing files and
 * the rule that a key can never address a path outside the storage directory.
 */
class LocalFileStorageTest {

    private static final String KEY = "documents/2026/11/3f2b9c1e-7a4d-4a55-9b0e-2f6c1d8e9a10";

    @TempDir
    Path tempDir;

    private Path root;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("storage");
        storage = new LocalFileStorage(root);
        storage.ensureWritable();
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private List<Path> filesOnDisk() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void roundTrip_returnsKeySizeAndSha256_andTheSameBytesComeBack() throws Exception {
        byte[] content = new byte[150_000];
        new Random(42).nextBytes(content);

        StoredFile stored = storage.put(KEY, "application/pdf", stream(content), content.length);

        assertEquals(KEY, stored.key());
        assertEquals(content.length, stored.size());
        assertEquals(sha256(content), stored.sha256());
        assertEquals(64, stored.sha256().length());
        assertEquals(stored.sha256(), stored.sha256().toLowerCase());
        try (InputStream in = storage.open(KEY)) {
            assertArrayEquals(content, in.readAllBytes());
        }
        assertEquals(List.of(root.resolve(KEY)), filesOnDisk(), "one file under the key, no temporary file left");
    }

    @Test
    void sha256_matchesAKnownVector() {
        StoredFile stored = storage.put("exports/2026/11/abc", "application/json",
                stream("abc".getBytes(StandardCharsets.UTF_8)), 3);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", stored.sha256());
    }

    @Test
    void anUnknownSize_isAccepted_andTheRealSizeIsReturned() {
        StoredFile stored = storage.put(KEY, "image/png", stream(new byte[1234]), -1);

        assertEquals(1234, stored.size());
    }

    @Test
    void anEmptyFile_canBeStored() throws Exception {
        StoredFile stored = storage.put(KEY, "application/octet-stream", stream(new byte[0]), 0);

        assertEquals(0, stored.size());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", stored.sha256());
        try (InputStream in = storage.open(KEY)) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void aSizeMismatch_storesNothing() throws Exception {
        byte[] content = "twelve bytes".getBytes(StandardCharsets.UTF_8);

        StorageException ex = assertThrows(StorageException.class,
                () -> storage.put(KEY, "application/pdf", stream(content), content.length + 1));
        assertTrue(ex.getMessage().contains("expected"));

        assertThrows(StoredFileNotFoundException.class, () -> storage.open(KEY));
        assertTrue(filesOnDisk().isEmpty(), "no file and no temporary file is left behind");
    }

    @Test
    void aFailingStream_storesNothing() throws Exception {
        InputStream broken = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 10) {
                    return 'x';
                }
                throw new IOException("connection reset");
            }
        };

        assertThrows(StorageException.class, () -> storage.put(KEY, "application/pdf", broken, -1));
        assertTrue(filesOnDisk().isEmpty());
    }

    @Test
    void put_replacesAnExistingFile() throws Exception {
        storage.put(KEY, "application/pdf", stream("first".getBytes(StandardCharsets.UTF_8)), 5);
        StoredFile second = storage.put(KEY, "application/pdf", stream("second!".getBytes(StandardCharsets.UTF_8)), 7);

        assertEquals(7, second.size());
        try (InputStream in = storage.open(KEY)) {
            assertEquals("second!", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, filesOnDisk().size());
    }

    @Test
    void delete_removesTheFile_andIsIdempotent() {
        storage.put(KEY, "application/pdf", stream(new byte[10]), 10);

        storage.delete(KEY);

        assertThrows(StoredFileNotFoundException.class, () -> storage.open(KEY));
        assertDoesNotThrow(() -> storage.delete(KEY), "deleting a missing key is not an error");
        assertDoesNotThrow(() -> storage.delete("documents/2026/11/never-stored"));
    }

    @Test
    void open_ofAMissingKeyOrADirectory_isNotFound() {
        storage.put(KEY, "application/pdf", stream(new byte[10]), 10);

        assertThrows(StoredFileNotFoundException.class, () -> storage.open("documents/2026/11/other"));
        assertThrows(StoredFileNotFoundException.class, () -> storage.open("documents/2026"), "a directory is not a file");
        StorageException notFound = assertThrows(StorageException.class, () -> storage.open("nothing/here"));
        assertInstanceOf(StoredFileNotFoundException.class, notFound);
    }

    @Test
    void aKeyNeverLeavesTheStorageDirectory() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        for (String key : new String[]{null, "", "../outside.txt", "documents/../../outside.txt", "/etc/passwd",
                "documents//file", "documents/", "/documents/file", "documents\\..\\file", "documents/./file",
                "documents/.hidden", "documents/file name", "documents/fi\u0000le", "documents/file.tmp",
                "C:/windows/file", "documents/%2e%2e/file", "a".repeat(301)}) {
            assertThrows(StorageException.class, () -> storage.put(key, "text/plain", stream(content), 1), String.valueOf(key));
            assertThrows(StorageException.class, () -> storage.open(key), String.valueOf(key));
            assertThrows(StorageException.class, () -> storage.delete(key), String.valueOf(key));
        }

        assertEquals("secret", Files.readString(outside), "a file outside the directory is never touched");
        assertTrue(filesOnDisk().isEmpty());
    }

    @Test
    void keysOfTheDocumentedShape_areAccepted() {
        for (String key : new String[]{"documents/2026/11/3f2b9c1e-7a4d-4a55-9b0e-2f6c1d8e9a10",
                "exports/2026/01/0a1b2c3d", "receipts/2026/12/INV-000123.pdf", "single", "a/b/c/d/e_f.g-h"}) {
            assertDoesNotThrow(() -> storage.put(key, "application/pdf", stream(new byte[1]), 1), key);
            assertDoesNotThrow(() -> storage.open(key).close(), key);
        }
    }

    @Test
    void localFiles_haveNoSignedUrl_andTheProviderIsLocal() {
        storage.put(KEY, "application/pdf", stream(new byte[10]), 10);

        assertTrue(storage.signedUrl(KEY, Duration.ofMinutes(5)).isEmpty(), "downloads stream through the API");
        assertEquals("local", storage.providerName());
        assertEquals(root.toAbsolutePath().normalize(), storage.root());
    }

    @Test
    void nullContent_isRefused() {
        assertThrows(StorageException.class, () -> storage.put(KEY, "application/pdf", null, 0));
    }
}
