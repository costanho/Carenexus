package com.carenexus.api.storage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * File storage and scanning settings ({@code carenexus.storage.*}). Defaults live here; application.yml only
 * overrides.
 *
 * <pre>
 * carenexus.storage.provider  = local    files under local-dir
 * carenexus.storage.local-dir = ${java.io.tmpdir}/carenexus-storage
 * carenexus.storage.scanner   = local    file-signature type detection + EICAR test string
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.storage")
public class StorageProperties {

    public static final String LOCAL = "local";

    /** Storage provider id. */
    private String provider = LOCAL;

    /** Directory of the local storage. */
    private String localDir = defaultLocalDir();

    /** Scanner provider id. */
    private String scanner = LOCAL;

    public String providerId() {
        return normalize(provider);
    }

    public String scannerId() {
        return normalize(scanner);
    }

    /** The configured directory, or the default when the value is blank. */
    public String localDirOrDefault() {
        return localDir == null || localDir.isBlank() ? defaultLocalDir() : localDir.trim();
    }

    private static String normalize(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? LOCAL : id;
    }

    private static String defaultLocalDir() {
        String tmp = System.getProperty("java.io.tmpdir", ".");
        return (tmp.endsWith("/") || tmp.endsWith("\\") ? tmp : tmp + "/") + "carenexus-storage";
    }
}
