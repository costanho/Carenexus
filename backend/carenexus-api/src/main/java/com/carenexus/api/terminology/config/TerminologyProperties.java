package com.carenexus.api.terminology.config;

import com.carenexus.api.terminology.provider.BundledIcd10Provider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Clinical terminology settings ({@code carenexus.terminology.*}). Defaults live here; application.yml only
 * overrides.
 *
 * <pre>
 * carenexus.terminology.provider         = bundled                         ICD-10 subset shipped with the API
 * carenexus.terminology.bundled-resource = terminology/icd10-subset.tsv    classpath location of that subset
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.terminology")
public class TerminologyProperties {

    public static final String BUNDLED = BundledIcd10Provider.PROVIDER_NAME;

    /** Terminology provider id. */
    private String provider = BUNDLED;

    /** Classpath resource of the bundled ICD-10 catalogue. */
    private String bundledResource = BundledIcd10Provider.DEFAULT_RESOURCE;

    public String providerId() {
        String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? BUNDLED : id;
    }

    public String bundledResourceOrDefault() {
        return bundledResource == null || bundledResource.isBlank()
                ? BundledIcd10Provider.DEFAULT_RESOURCE : bundledResource.trim();
    }
}
