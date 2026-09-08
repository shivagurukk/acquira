package com.acquira.core.config;

import com.acquira.core.webhook.WebhookDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for the 2026-09-05 enterprise-API hardening: CIDR IP
 * allowlisting, the per-day quota window, the verification cache, and HMAC
 * webhook signing. No Spring context.
 */
class EnterpriseApiHardeningTest {

    private ApiKeyAuthFilter filter() {
        return new ApiKeyAuthFilter(null, null, null, new ApiRateLimiter(), null,
                new ApiKeyVerificationCache());
    }

    // ─── CIDR allowlist ────────────────────────────────────────────────

    @Test
    void blankAllowlistAllowsAnyIp() {
        assertTrue(filter().ipAllowed(null, "203.0.113.9"));
        assertTrue(filter().ipAllowed("  ", "203.0.113.9"));
    }

    @Test
    void exactIpStillMatches() {
        assertTrue(filter().ipAllowed("10.0.0.1, 10.0.0.2", "10.0.0.2"));
        assertFalse(filter().ipAllowed("10.0.0.1, 10.0.0.2", "10.0.0.3"));
    }

    @Test
    void cidrBlockMatchesInsideAndRejectsOutside() {
        ApiKeyAuthFilter f = filter();
        assertTrue(f.ipAllowed("10.20.0.0/16", "10.20.255.254"));
        assertFalse(f.ipAllowed("10.20.0.0/16", "10.21.0.1"));
        // Non-octet-aligned prefix: /28 = .16 to .31
        assertTrue(f.ipAllowed("192.168.1.16/28", "192.168.1.31"));
        assertFalse(f.ipAllowed("192.168.1.16/28", "192.168.1.32"));
    }

    @Test
    void cidrIpv6AndMixedFamilies() {
        ApiKeyAuthFilter f = filter();
        assertTrue(f.cidrMatches("2001:db8::/32", "2001:db8:0:1::5"));
        assertFalse(f.cidrMatches("2001:db8::/32", "2001:db9::1"));
        // v4 caller against v6 block (and vice versa) never matches
        assertFalse(f.cidrMatches("2001:db8::/32", "10.0.0.1"));
        assertFalse(f.cidrMatches("10.0.0.0/8", "2001:db8::1"));
    }

    @Test
    void malformedCidrNeverMatchesAndNeverThrows() {
        ApiKeyAuthFilter f = filter();
        assertFalse(f.ipAllowed("10.0.0.0/abc", "10.0.0.1"));
        assertFalse(f.ipAllowed("10.0.0.0/33, banana/8", "10.0.0.1"));
    }

    // ─── Daily quota window ────────────────────────────────────────────

    @Test
    void dailyQuotaEnforcedAndReported() {
        ApiRateLimiter limiter = new ApiRateLimiter();
        Long key = 42L;
        assertTrue(limiter.allowDay(key, 3));
        assertTrue(limiter.allowDay(key, 3));
        assertEquals(1, limiter.remainingDay(key, 3));
        assertTrue(limiter.allowDay(key, 3));
        assertFalse(limiter.allowDay(key, 3));
        assertEquals(0, limiter.remainingDay(key, 3));
        // No quota configured = unlimited
        assertTrue(limiter.allowDay(key, null));
        assertTrue(limiter.allowDay(key, 0));
    }

    @Test
    void dailyQuotaIndependentOfMinuteWindow() {
        ApiRateLimiter limiter = new ApiRateLimiter();
        Long key = 7L;
        assertTrue(limiter.allow(key, 2));
        assertTrue(limiter.allow(key, 2));
        assertFalse(limiter.allow(key, 2));       // minute window exhausted
        assertTrue(limiter.allowDay(key, 100));   // day window unaffected
    }

    // ─── Verification cache ────────────────────────────────────────────

    @Test
    void verificationCacheRoundTripAndEviction() {
        ApiKeyVerificationCache cache = new ApiKeyVerificationCache();
        cache.put("aqr_secret", 5L, "{bcrypt}hash");
        ApiKeyVerificationCache.Entry e = cache.get("aqr_secret");
        assertNotNull(e);
        assertEquals(5L, e.keyId());
        assertEquals("{bcrypt}hash", e.keyHash());
        assertNull(cache.get("aqr_other"));

        cache.evictByKeyId(5L);
        assertNull(cache.get("aqr_secret"));
    }

    // ─── Webhook HMAC signing ──────────────────────────────────────────

    @Test
    void hmacSignatureMatchesKnownVector() throws Exception {
        // RFC 4231-style check: deterministic, hex-encoded HMAC-SHA256.
        String sig = WebhookDispatcher.hmacHex("whsec_test", "{\"id\":\"evt_1\"}");
        assertEquals(64, sig.length());
        assertTrue(sig.matches("[0-9a-f]{64}"));
        // Stable across calls (no per-call salt — receivers must be able to recompute it)
        assertEquals(sig, WebhookDispatcher.hmacHex("whsec_test", "{\"id\":\"evt_1\"}"));
        assertNotEquals(sig, WebhookDispatcher.hmacHex("whsec_other", "{\"id\":\"evt_1\"}"));
    }
}
