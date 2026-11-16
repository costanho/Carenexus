package com.carenexus.api.storage.config;

import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.provider.LocalFileScanner;
import com.carenexus.api.storage.provider.LocalFileStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * File storage wiring: the storage chosen by {@code carenexus.storage.provider} and the upload scanner chosen by
 * {@code carenexus.storage.scanner}.
 *
 * A real object store or scanner is added here: write an adapter that implements {@link FileStorage} or
 * {@link FileScanner} and return it for its provider id. An id nobody implements fails startup, so a typo never
 * silently falls back to the local implementation.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public FileStorage fileStorage(StorageProperties properties) {
        String provider = properties.providerId();
        if (StorageProperties.LOCAL.equals(provider)) {
            try {
                return new LocalFileStorage(Path.of(properties.localDirOrDefault()));
            } catch (InvalidPathException e) {
                throw new IllegalStateException("carenexus.storage.local-dir is not a valid path", e);
            }
        }
        throw new IllegalStateException("carenexus.storage.provider is '" + provider
                + "', which this build does not support. Use 'local', or add the provider adapter.");
    }

    @Bean
    public FileScanner fileScanner(StorageProperties properties) {
        String scanner = properties.scannerId();
        if (StorageProperties.LOCAL.equals(scanner)) {
            return new LocalFileScanner();
        }
        throw new IllegalStateException("carenexus.storage.scanner is '" + scanner
                + "', which this build does not support. Use 'local', or add the scanner adapter.");
    }
}
