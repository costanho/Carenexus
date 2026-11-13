package com.carenexus.api.common.ratelimit;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counter kept in this JVM (the default store, {@code carenexus.rate-limit.store=memory}).
 *
 * Each (bucket, key) gets a window that starts with its first call and lasts the bucket's window; calls beyond the
 * bucket's limit inside that window are denied until it ends. The map is bounded: beyond
 * {@code max-tracked-keys}, expired windows are dropped first, then the oldest ones.
 *
 * Per instance only: with several API replicas each one counts separately, so the effective limit is multiplied
 * by the number of replicas. Use a shared store behind {@link RateLimiter} when the API is scaled out.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private record Window(long startMillis, long windowMillis, int count) {
        boolean expired(long now) {
            return now - startMillis >= windowMillis;
        }
    }

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RateDecision tryAcquire(String bucket, String key) {
        RateLimitProperties.Limit limit = properties.resolve(bucket);
        long windowMillis = limit.windowSeconds() * 1000L;
        long now = clock.millis();
        String mapKey = mapKey(bucket, key);

        if (windows.size() >= Math.max(properties.getMaxTrackedKeys(), 1) && !windows.containsKey(mapKey)) {
            evict(now);
        }

        Window window = windows.compute(mapKey, (k, current) -> {
            if (current == null || current.expired(now)) {
                return new Window(now, windowMillis, 1);
            }
            // Cap the counter: a blocked caller hammering the endpoint must not overflow it.
            return new Window(current.startMillis(), current.windowMillis(), Math.min(current.count() + 1, limit.limit() + 1));
        });

        if (window.count() <= limit.limit()) {
            return RateDecision.allow();
        }
        long remainingMillis = window.startMillis() + window.windowMillis() - now;
        return RateDecision.deny((remainingMillis + 999) / 1000);
    }

    @Override
    public void reset(String bucket, String key) {
        windows.remove(mapKey(bucket, key));
    }

    /** Number of keys currently tracked (diagnostics and tests). */
    public int trackedKeys() {
        return windows.size();
    }

    private void evict(long now) {
        windows.values().removeIf(w -> w.expired(now));
        int max = Math.max(properties.getMaxTrackedKeys(), 1);
        if (windows.size() < max) {
            return;
        }
        // Still full of live windows: drop the oldest tenth so one flood of distinct keys cannot grow the map.
        int toDrop = Math.max(windows.size() - max + Math.max(max / 10, 1), 1);
        List<String> oldest = windows.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().startMillis()))
                .limit(toDrop)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(windows::remove);
    }

    private static String mapKey(String bucket, String key) {
        return (bucket == null ? "" : bucket) + '\u0000' + (key == null ? "" : key);
    }
}
