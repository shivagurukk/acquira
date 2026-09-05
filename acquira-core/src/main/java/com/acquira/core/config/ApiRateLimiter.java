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
        long dayEpoch;      // System.currentTimeMillis() / 86_400_000
        long dayCount;
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

    /**
     * Daily quota check (UTC day fixed window). Unlike the minute limiter this
     * does NOT consume on rejection — call it BEFORE allow() so a 429 on the
     * daily ceiling doesn't also burn a minute-window slot.
     *
     * @param quotaPerDay null or <= 0 means no daily quota configured.
     * @return true if the request fits inside today's quota.
     */
    public boolean allowDay(Long keyId, Integer quotaPerDay) {
        if (keyId == null || quotaPerDay == null || quotaPerDay <= 0) return true;
        long today = System.currentTimeMillis() / 86_400_000L;
        Window w = windows.computeIfAbsent(keyId, k -> new Window());
        synchronized (w) {
            if (w.dayEpoch != today) {
                w.dayEpoch = today;
                w.dayCount = 0;
            }
            if (w.dayCount >= quotaPerDay) return false;
            w.dayCount++;
            return true;
        }
    }

    /** Remaining requests in today's quota window (for X-RateLimit-Remaining-Day). */
    public long remainingDay(Long keyId, Integer quotaPerDay) {
        if (keyId == null || quotaPerDay == null || quotaPerDay <= 0) return Long.MAX_VALUE;
        Window w = windows.get(keyId);
        if (w == null) return quotaPerDay;
        long today = System.currentTimeMillis() / 86_400_000L;
        synchronized (w) {
            if (w.dayEpoch != today) return quotaPerDay;
            return Math.max(0, quotaPerDay - w.dayCount);
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
