package com.acquira.core.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skips the per-request BCrypt on the external API hot path.
 *
 * BCrypt is deliberately slow (~100ms class); paying it on EVERY request made
 * the hash the throughput ceiling and an asymmetric-DoS lever (audit P1). This
 * cache maps SHA-256(raw key) -> (key_id, key_hash-at-verification-time) after
 * ONE successful BCrypt match, so subsequent requests do a cheap digest lookup.
 *
 * SAFETY:
 *  - Only ever populated after a full BCrypt verification succeeded.
 *  - The stored key_hash is compared against the CURRENT db row on every hit;
 *    a rotated key changes key_hash, the comparison fails, the entry is
 *    dropped and the request falls back to BCrypt (and fails). Revocation is
 *    enforced by the caller's is_active/expiry checks on the fresh row, so a
 *    revoked key dies on the next request regardless of the cache.
 *  - Raw keys are never stored — only their SHA-256.
 *
 * In-memory and single-replica by design, same as ApiRateLimiter.
 */
@Component
public class ApiKeyVerificationCache {

    /** Hard cap; the tenant count makes real cardinality tiny, this guards abuse. */
    private static final int MAX_ENTRIES = 10_000;
    private static final long TTL_MS = 5 * 60_000L;

    public record Entry(long keyId, String keyHash, long cachedAt) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public Entry get(String rawKey) {
        String digest = sha256(rawKey);
        Entry e = cache.get(digest);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.cachedAt() > TTL_MS) {
            cache.remove(digest);
            return null;
        }
        return e;
    }

    public void put(String rawKey, long keyId, String keyHash) {
        if (cache.size() >= MAX_ENTRIES) return; // shed rather than grow unbounded
        cache.put(sha256(rawKey), new Entry(keyId, keyHash, System.currentTimeMillis()));
    }

    public void invalidate(String rawKey) {
        cache.remove(sha256(rawKey));
    }

    /** Drop every entry for a key id — on revoke/rotate from the admin API. */
    public void evictByKeyId(long keyId) {
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().keyId() == keyId) it.remove();
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
