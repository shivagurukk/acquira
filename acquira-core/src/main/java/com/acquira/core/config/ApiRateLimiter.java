package com.acquira.core.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-API-key sliding-window rate limiter (in-memory).
 *
 * Safe as an in-process map ONLY because acquira-core runs single-replica by
 * design (replicas: 1 — the batch/scheduler architecture requires it). If the
 * app is ever horizontally scaled this must move to a shared store (Redis).
 *
 * A simple fixed-window-per-minute counter: cheap, allocation-light, and precise
 * enough for abuse protection. Each key gets `limitPerMinute` requests per wall-clock
 * minute bucket; the bucket resets when the minute rolls over.
 */
@Component
public class ApiRateLimiter {

    private static final class Window {
        long minuteEpoch;   // System.currentTimeMillis() / 60000
        int count;
    }

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    /**
     * @return true if the request is allowed, false if the key has exceeded its per-minute budget.
     */
    public boolean allow(Long keyId, int limitPerMinute) {
        if (keyId == null) return true;
        if (limitPerMinute <= 0) limitPerMinute = 120; // defensive default

        long nowMinute = System.currentTimeMillis() / 60_000L;
        final int limit = limitPerMinute;

        Window w = windows.computeIfAbsent(keyId, k -> new Window());
        synchronized (w) {
            if (w.minuteEpoch != nowMinute) {
                w.minuteEpoch = nowMinute;
                w.count = 0;
            }
            if (w.count >= limit) {
                return false;
            }
            w.count++;
            return true;
        }
    }

    /** Remaining requests in the current minute window (for X-RateLimit-Remaining). */
    public int remaining(Long keyId, int limitPerMinute) {
        if (keyId == null) return limitPerMinute;
        Window w = windows.get(keyId);
        if (w == null) return limitPerMinute;
        long nowMinute = System.currentTimeMillis() / 60_000L;
        synchronized (w) {
            if (w.minuteEpoch != nowMinute) return limitPerMinute;
            return Math.max(0, limitPerMinute - w.count);
        }
    }

    /** Evict a key's window on revoke, to keep the map bounded over a long uptime. */
    public void evict(Long keyId) {
        if (keyId != null) windows.remove(keyId);
    }
}
