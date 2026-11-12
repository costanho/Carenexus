package com.carenexus.api.common.web;

import com.carenexus.api.admin.settings.SettingKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * application.yml is the operator's reference: every setting the code binds must be listed in its default document
 * (the part before the profile sections) with its environment override and default. This test fails when
 * <ul>
 *   <li>a {@code @ConfigurationProperties} class under com.carenexus.api has a prefix with no section in the file;</li>
 *   <li>a {@code carenexus.*} key read through {@code @Value}, {@code @Scheduled} or {@code @ConditionalOnProperty}
 *       is not in the file;</li>
 *   <li>the first fallback property of a system setting ({@link SettingKey}) is not in the file.</li>
 * </ul>
 * Add the key to application.yml (with a ${ENV_NAME:default} value and a comment) when it fails.
 */
class ApplicationYmlCoverageTest {

    private static final String BASE_PACKAGE = "com.carenexus.api";
    private static final Pattern PLACEHOLDER_KEY = Pattern.compile("\\$\\{(carenexus\\.[A-Za-z0-9.\\-]+)");

    private static EnumerablePropertySource<?> baseDocument;
    private static Set<String> documentedKeys;

    @BeforeAll
    static void loadApplicationYml() throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertFalse(documents.isEmpty(), "application.yml is on the classpath");
        // The first document is the shared configuration; the later ones are the developer profiles.
        baseDocument = (EnumerablePropertySource<?>) documents.get(0);
        documentedKeys = new TreeSet<>(Arrays.asList(baseDocument.getPropertyNames()));
    }

    private static boolean documented(String key) {
        String normalized = key.replaceAll("\\[\\d+]$", "");
        return documentedKeys.stream().anyMatch(k -> k.equals(normalized) || k.startsWith(normalized + ".")
                || k.startsWith(normalized + "["));
    }

    @Test
    void everyConfigurationPropertiesPrefix_hasASectionInApplicationYml() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        TreeMap<String, String> missing = new TreeMap<>();
        int found = 0;
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
            ConfigurationProperties annotation = AnnotatedElementUtils.findMergedAnnotation(type, ConfigurationProperties.class);
            assertNotNull(annotation, type.getName());
            String prefix = !annotation.prefix().isEmpty() ? annotation.prefix() : annotation.value();
            found++;
            if (!documented(prefix)) {
                missing.put(prefix, type.getSimpleName());
            }
        }
        assertTrue(found >= 10, "the scan found the properties classes (" + found + ")");
        assertTrue(missing.isEmpty(), "application.yml has no section for: " + missing);
    }

    @Test
    void everyCarenexusKeyReadByTheCode_isInApplicationYml() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        // Application classes only (target/classes), not the test sources on the same classpath.
        scanner.addIncludeFilter((reader, factory) -> !reader.getResource().getURL().toString().contains("/test-classes/"));

        TreeMap<String, String> missing = new TreeMap<>();
        Set<String> seen = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
            for (String expression : placeholderExpressions(type)) {
                Matcher matcher = PLACEHOLDER_KEY.matcher(expression);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    seen.add(key);
                    if (!documented(key)) {
                        missing.put(key, type.getSimpleName());
                    }
                }
            }
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            if (condition != null) {
                for (String name : condition.name().length > 0 ? condition.name() : condition.value()) {
                    String key = condition.prefix().isEmpty() ? name : condition.prefix() + "." + name;
                    if (key.startsWith("carenexus.")) {
                        seen.add(key);
                        if (!documented(key)) {
                            missing.put(key, type.getSimpleName());
                        }
                    }
                }
            }
        }
        assertTrue(seen.contains("carenexus.jobs.enabled"), "the scan found the @Value keys: " + seen);
        assertTrue(seen.contains("carenexus.jobs.outbound.interval-ms"), "the scan found the @Scheduled keys: " + seen);
        assertTrue(missing.isEmpty(), "carenexus.* keys read by the code but not listed in application.yml: " + missing);
    }

    @Test
    void theFallbackPropertyOfEverySystemSetting_isInApplicationYml() {
        List<String> missing = new ArrayList<>();
        for (SettingKey key : SettingKey.values()) {
            String property = key.properties().get(0);
            if (!documented(property)) {
                missing.add(key.key() + " -> " + property);
            }
        }
        assertTrue(missing.isEmpty(), "system settings without their fallback property in application.yml: " + missing);
    }

    @Test
    void theKeysNamedInTheConfigurationBrief_areListed() {
        for (String key : List.of(
                "carenexus.rate-limit.buckets.message-send.limit",
                "carenexus.rate-limit.buckets.thread-create.limit",
                "carenexus.auth.licence-registry",
                "carenexus.auth.human-verification",
                "carenexus.ai.anomaly-deviation-percent",
                "carenexus.billing.auto-invoice",
                "carenexus.billing.overdue-grace-days",
                "carenexus.retention.export-days",
                "carenexus.jobs.payment-reconcile.interval-ms",
                "server.shutdown",
                "server.tomcat.max-http-form-post-size",
                "spring.servlet.multipart.max-file-size",
                "spring.servlet.multipart.max-request-size",
                "spring.datasource.hikari.maximum-pool-size",
                "spring.lifecycle.timeout-per-shutdown-phase")) {
            assertTrue(documentedKeys.contains(key), key + " is missing from application.yml");
        }
        assertFalse(documentedKeys.contains("carenexus.reminders.appointment-lead-minutes"),
                "the unused reminder lead-minutes key was replaced by reminders.appointment-lead-hours");
        assertEquals("health, info, prometheus",
                String.valueOf(baseDocument.getProperty("management.endpoints.web.exposure.include")));
    }

    /** Every placeholder string in {@code @Value} (fields, constructor and method parameters) and {@code @Scheduled}. */
    private static List<String> placeholderExpressions(Class<?> type) {
        List<String> expressions = new ArrayList<>();
        List<AnnotatedElement> elements = new ArrayList<>();
        List<Executable> executables = new ArrayList<>();
        try {
            elements.addAll(Arrays.asList(type.getDeclaredFields()));
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            Method[] methods = type.getDeclaredMethods();
            executables.addAll(Arrays.asList(constructors));
            executables.addAll(Arrays.asList(methods));
        } catch (NoClassDefFoundError optionalDependencyMissing) {
            return expressions;
        }
        elements.addAll(executables);
        for (Executable executable : executables) {
            elements.addAll(Arrays.asList(executable.getParameters()));
        }
        for (AnnotatedElement element : elements) {
            for (Annotation annotation : element.getDeclaredAnnotations()) {
                if (annotation instanceof Value value) {
                    expressions.add(value.value());
                } else if (annotation instanceof Scheduled scheduled) {
                    expressions.addAll(List.of(scheduled.fixedDelayString(), scheduled.fixedRateString(),
                            scheduled.initialDelayString(), scheduled.cron()));
                }
            }
        }
        return expressions;
    }
}
