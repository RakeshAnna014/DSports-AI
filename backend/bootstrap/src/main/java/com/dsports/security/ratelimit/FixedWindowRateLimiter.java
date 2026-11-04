package com.dsports.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window limiter. It is a POC/single-instance control: with several backend replicas each one counts separately, so the real
 * limit is (replicas x limit). A production deployment needs a shared store (for example Redis) or an API gateway; see docs/SECURITY.md.
 */
public final class FixedWindowRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Window(long startMillis, int count) {}

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one request for {@code key}; allowed while at most {@code limit} requests were seen in the current {@code window}. */
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long span = window.toMillis();
        var result = new Decision[1];
        windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis() >= span) {
                result[0] = new Decision(limit > 0, 0);
                return new Window(now, 1);
            }
            if (current.count() >= limit) {
                result[0] = new Decision(false, Math.max(1, (current.startMillis() + span - now + 999) / 1000));
                return current;
            }
            result[0] = new Decision(true, 0);
            return new Window(current.startMillis(), current.count() + 1);
        });
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= span);
        }
        return result[0];
    }

    int trackedKeys() {
        return windows.size();
    }
}
