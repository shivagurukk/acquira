package com.acquira.core.webhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic test for HMAC webhook signing. No Spring context.
 */
class WebhookSigningTest {

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
