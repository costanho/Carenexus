package com.carenexus.api.common.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code carenexus.rate-limit.*}. Every predefined bucket has a default in code ({@link #DEFAULTS}); application.yml
 * only overrides. A bucket configured with just one of {@code limit} / {@code window-seconds} keeps the code default
 * for the other. An unknown bucket name uses the {@code default} bucket.
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.rate-limit")
public class RateLimitProperties {

    public static final String DEFAULT_BUCKET = "default";

    /** Code defaults: bucket name to (limit, window in seconds). */
    static final Map<String, Limit> DEFAULTS;

    static {
        Map<String, Limit> d = new LinkedHashMap<>();
        d.put(DEFAULT_BUCKET, new Limit(120, 60));
        d.put("auth-login-email-ip", new Limit(5, 900));
        d.put("auth-login-ip", new Limit(30, 900));
        d.put("auth-login-account", new Limit(20, 3600));
        d.put("auth-register-ip", new Limit(10, 3600));
        d.put("auth-refresh-ip", new Limit(120, 900));
        d.put("auth-forgot-email", new Limit(3, 3600));
        d.put("auth-forgot-ip", new Limit(10, 3600));
        d.put("auth-verify-ip", new Limit(20, 3600));
        d.put("auth-mfa", new Limit(5, 300));
        d.put("password-change", new Limit(5, 900));
        d.put("appointment-book", new Limit(20, 3600));
        d.put("caregiver-request", new Limit(10, 86400));
        d.put("reminder-manual", new Limit(10, 3600));
        d.put("document-upload", new Limit(60, 3600));
        d.put("payment-initiate", new Limit(30, 3600));
        d.put("data-export", new Limit(3, 86400));
        d.put("correction-request", new Limit(10, 86400));
        d.put("message-send", new Limit(60, 60));
        d.put("thread-create", new Limit(20, 3600));
        DEFAULTS = Map.copyOf(d);
    }

    /** Master switch. When false, {@link RateLimitGuard#check} never refuses. */
    private boolean enabled = true;

    /** Counter store: {@code memory} (per instance). */
    private String store = "memory";

    /** Upper bound on keys the in-memory store tracks at once; expired, then oldest, windows are evicted beyond it. */
    private int maxTrackedKeys = 100_000;

    /** Overrides per bucket name. */
    private Map<String, Bucket> buckets = new LinkedHashMap<>();

    /** One configured bucket; a null field falls back to the code default. */
    @Getter
    @Setter
    public static class Bucket {
        private Integer limit;
        private Long windowSeconds;
    }

    /** Effective limit of a bucket. */
    public record Limit(int limit, long windowSeconds) {
    }

    /**
     * Resolves a bucket: its configured values first, then its code default, then the {@code default} bucket
     * (configured or code). A name that is neither predefined nor configured uses the {@code default} bucket.
     */
    public Limit resolve(String bucket) {
        String name = bucket == null || bucket.isBlank() ? DEFAULT_BUCKET : bucket;
        Limit base = merge(DEFAULTS.get(DEFAULT_BUCKET), buckets.get(DEFAULT_BUCKET));
        if (DEFAULT_BUCKET.equals(name)) {
            return base;
        }
        Limit codeDefault = DEFAULTS.get(name);
        Bucket configured = buckets.get(name);
        if (codeDefault == null && configured == null) {
            return base;
        }
        return merge(codeDefault != null ? codeDefault : base, configured);
    }

    private static Limit merge(Limit fallback, Bucket configured) {
        if (configured == null) {
            return fallback;
        }
        int limit = configured.getLimit() != null && configured.getLimit() > 0 ? configured.getLimit() : fallback.limit();
        long window = configured.getWindowSeconds() != null && configured.getWindowSeconds() > 0
                ? configured.getWindowSeconds() : fallback.windowSeconds();
        return new Limit(limit, window);
    }
}
