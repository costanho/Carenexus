package com.carenexus.api.storage.config;

import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.provider.LocalFileScanner;
import com.carenexus.api.storage.provider.LocalFileStorage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Applies the provider-seam startup rule ({@link LocalProviderGuard}) to file storage and file scanning: the local
 * implementations are fine on developer profiles, log a warning on staging and refuse to start in production
 * unless {@code carenexus.allow-local-providers=true}. With the local storage it also makes sure the directory
 * exists and is writable, so a wrong path is found at startup and not on the first upload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageStartupChecks {

    private final LocalProviderGuard localProviderGuard;
    private final FileStorage fileStorage;
    private final FileScanner fileScanner;

    @PostConstruct
    void check() {
        localProviderGuard.check("file storage", fileStorage.providerName(), fileStorage instanceof LocalFileStorage);
        localProviderGuard.check("file scanning", fileScanner.providerName(), fileScanner instanceof LocalFileScanner);
        if (fileStorage instanceof LocalFileStorage local) {
            local.ensureWritable();
            log.info("File storage: local directory {}", local.root());
        }
        log.info("File storage provider: {}, scanner: {}", fileStorage.providerName(), fileScanner.providerName());
    }
}
