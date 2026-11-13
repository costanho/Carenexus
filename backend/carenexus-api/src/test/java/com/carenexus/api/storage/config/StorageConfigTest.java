package com.carenexus.api.storage.config;

import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.ScanResult;
import com.carenexus.api.storage.StorageException;
import com.carenexus.api.storage.provider.LocalFileScanner;
import com.carenexus.api.storage.provider.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider selection and the startup rule of the storage seam, without a Spring context.
 */
class StorageConfigTest {

    private final StorageConfig config = new StorageConfig();

    @TempDir
    Path tempDir;

    private StorageProperties properties(Path dir) {
        StorageProperties properties = new StorageProperties();
        properties.setLocalDir(dir.toString());
        return properties;
    }

    private static LocalProviderGuard guard(String environment, boolean allowLocal, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        return new LocalProviderGuard(new EnvironmentInfo(spring, environment), allowLocal);
    }

    @Test
    void defaults_areTheLocalImplementations_underTheTempDirectory() {
        StorageProperties defaults = new StorageProperties();

        assertEquals("local", defaults.providerId());
        assertEquals("local", defaults.scannerId());
        assertTrue(Path.of(defaults.getLocalDir()).startsWith(Path.of(System.getProperty("java.io.tmpdir"))),
                defaults.getLocalDir());
        assertTrue(defaults.getLocalDir().endsWith("carenexus-storage"));

        defaults.setLocalDir("  ");
        assertTrue(defaults.localDirOrDefault().endsWith("carenexus-storage"), "a blank directory means the default");
    }

    @Test
    void localStorageAndScanner_areSelected() {
        StorageProperties properties = properties(tempDir.resolve("files"));
        properties.setProvider(" LOCAL ");
        properties.setScanner("");

        FileStorage storage = config.fileStorage(properties);
        FileScanner scanner = config.fileScanner(properties);

        assertInstanceOf(LocalFileStorage.class, storage);
        assertEquals("local", storage.providerName());
        assertEquals(tempDir.resolve("files").toAbsolutePath().normalize(), ((LocalFileStorage) storage).root());
        assertInstanceOf(LocalFileScanner.class, scanner);
        assertEquals("local", scanner.providerName());
    }

    @Test
    void anUnknownProviderOrScanner_failsStartup_insteadOfFallingBackToLocal() {
        StorageProperties s3 = properties(tempDir);
        s3.setProvider("s3");
        IllegalStateException storage = assertThrows(IllegalStateException.class, () -> config.fileStorage(s3));
        assertTrue(storage.getMessage().contains("carenexus.storage.provider"));
        assertTrue(storage.getMessage().contains("'s3'"));

        StorageProperties clamav = properties(tempDir);
        clamav.setScanner("clamav");
        IllegalStateException scanner = assertThrows(IllegalStateException.class, () -> config.fileScanner(clamav));
        assertTrue(scanner.getMessage().contains("carenexus.storage.scanner"));
    }

    @Test
    void startup_createsTheLocalDirectory() {
        Path dir = tempDir.resolve("not").resolve("there").resolve("yet");
        StorageProperties properties = properties(dir);
        StorageStartupChecks checks = new StorageStartupChecks(guard("development", false, "test"),
                config.fileStorage(properties), config.fileScanner(properties));

        checks.check();

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void startup_failsWhenTheLocalDirectoryCannotBeUsed() throws Exception {
        Path file = Files.writeString(tempDir.resolve("a-file"), "not a directory");
        StorageProperties properties = properties(file);
        StorageStartupChecks checks = new StorageStartupChecks(guard("development", false, "local"),
                config.fileStorage(properties), config.fileScanner(properties));

        StorageException ex = assertThrows(StorageException.class, checks::check);
        assertTrue(ex.getMessage().contains("not a writable directory"));
    }

    @Test
    void localImplementations_refuseToStartInProduction_unlessExplicitlyAllowed() {
        StorageProperties properties = properties(tempDir.resolve("prod"));
        FileStorage storage = config.fileStorage(properties);
        FileScanner scanner = config.fileScanner(properties);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new StorageStartupChecks(guard("production", false), storage, scanner).check());
        assertTrue(ex.getMessage().contains("file storage"));
        assertFalse(Files.exists(tempDir.resolve("prod")), "nothing is created when startup is refused");

        assertDoesNotThrow(() -> new StorageStartupChecks(guard("production", true), storage, scanner).check());
        assertDoesNotThrow(() -> new StorageStartupChecks(guard("staging", false), storage, scanner).check());
    }

    @Test
    void aRealStorageWithTheLocalScanner_isStillRefusedInProduction() {
        FileStorage realStorage = new FileStorage() {
            @Override
            public String providerName() {
                return "s3";
            }

            @Override
            public com.carenexus.api.storage.StoredFile put(String key, String contentType, java.io.InputStream content, long size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.io.InputStream open(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public java.util.Optional<java.net.URI> signedUrl(String key, java.time.Duration ttl) {
                return java.util.Optional.empty();
            }
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new StorageStartupChecks(guard("production", false), realStorage, new LocalFileScanner()).check());
        assertTrue(ex.getMessage().contains("file scanning"));

        // A scanner written as a lambda (the interface has one abstract method) is a real implementation.
        FileScanner realScanner = (content, declared, name) -> new ScanResult(true, ScanResult.PDF, null);
        assertEquals("custom", realScanner.providerName());
        assertDoesNotThrow(() -> new StorageStartupChecks(guard("production", false), realStorage, realScanner).check());
    }
}
