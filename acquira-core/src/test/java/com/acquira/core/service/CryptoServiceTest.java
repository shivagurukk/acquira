package com.acquira.core.service;

import com.acquira.common.service.CryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CryptoService} (AES-256-GCM at-rest encryption).
 *
 * Covers: round-trip correctness, the ENC_PREFIX self-describing token format,
 * idempotent re-encryption, plaintext pass-through on decrypt, null handling,
 * IV randomness (same plaintext -> different ciphertext), tamper detection,
 * key-length validation, and unicode/empty payloads.
 */
class CryptoServiceTest {

    /** Build a service with a known 32+ char key so tests are deterministic across runs. */
    private CryptoService svc() {
        return new CryptoService("UnitTestEncryptionKey_ThirtyTwoByte!!");
    }

    // ---- round trip ---------------------------------------------------------

    @Test
    @DisplayName("encrypt then decrypt returns the original plaintext")
    void roundTrip() {
        CryptoService c = svc();
        String secret = "super-secret-smtp-password";
        String token = c.encrypt(secret);
        assertEquals(secret, c.decrypt(token));
    }

    @Test
    @DisplayName("encrypted output carries the enc:v1: prefix")
    void encryptedHasPrefix() {
        String token = svc().encrypt("hello");
        assertTrue(token.startsWith(CryptoService.ENC_PREFIX));
    }

    @Test
    @DisplayName("ciphertext differs from plaintext")
    void ciphertextNotPlaintext() {
        String plain = "plain-value";
        String token = svc().encrypt(plain);
        assertNotEquals(plain, token);
        assertFalse(token.contains(plain));
    }

    @Test
    @DisplayName("empty string round-trips")
    void emptyStringRoundTrip() {
        CryptoService c = svc();
        assertEquals("", c.decrypt(c.encrypt("")));
    }

    @Test
    @DisplayName("unicode payload round-trips intact")
    void unicodeRoundTrip() {
        CryptoService c = svc();
        String secret = "pà$$wörd—密码—🔐";
        assertEquals(secret, c.decrypt(c.encrypt(secret)));
    }

    @Test
    @DisplayName("long payload round-trips intact")
    void longPayloadRoundTrip() {
        CryptoService c = svc();
        String secret = "x".repeat(10_000);
        assertEquals(secret, c.decrypt(c.encrypt(secret)));
    }

    // ---- IV randomness ------------------------------------------------------

    @Test
    @DisplayName("same plaintext encrypts to different ciphertext each time (random IV)")
    void randomIvProducesDistinctCiphertext() {
        CryptoService c = svc();
        String t1 = c.encrypt("repeat-me");
        String t2 = c.encrypt("repeat-me");
        assertNotEquals(t1, t2, "GCM with a random IV must not produce identical tokens");
        // ...yet both decrypt back to the same value
        assertEquals(c.decrypt(t1), c.decrypt(t2));
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    @DisplayName("encrypt is idempotent on an already-encrypted token (no double-encrypt)")
    void encryptIdempotent() {
        CryptoService c = svc();
        String once = c.encrypt("value");
        String twice = c.encrypt(once);
        assertEquals(once, twice, "re-encrypting an enc token must return it unchanged");
        assertEquals("value", c.decrypt(twice));
    }

    // ---- null handling ------------------------------------------------------

    @Test
    @DisplayName("encrypt(null) returns null")
    void encryptNull() {
        assertNull(svc().encrypt(null));
    }

    @Test
    @DisplayName("decrypt(null) returns null")
    void decryptNull() {
        assertNull(svc().decrypt(null));
    }

    // ---- plaintext pass-through on decrypt ----------------------------------

    @Test
    @DisplayName("decrypt of a non-prefixed (legacy plaintext) value returns it unchanged")
    void decryptLegacyPlaintext() {
        assertEquals("legacy-plain", svc().decrypt("legacy-plain"));
    }

    // ---- isEncrypted --------------------------------------------------------

    @Test
    @DisplayName("isEncrypted is true for an encrypt() token and false otherwise")
    void isEncryptedDetection() {
        CryptoService c = svc();
        assertTrue(c.isEncrypted(c.encrypt("x")));
        assertFalse(c.isEncrypted("x"));
        assertFalse(c.isEncrypted(null));
        assertFalse(c.isEncrypted(""));
    }

    // ---- tamper detection ---------------------------------------------------

    @Test
    @DisplayName("decrypting a tampered token throws (GCM auth tag fails)")
    void tamperedTokenThrows() {
        CryptoService c = svc();
        String token = c.encrypt("integrity-protected");

        // Tamper on the DECODED bytes, not on a base64 character.
        //
        // The previous version flipped a base64 char near the end of the string.
        // That is unreliable: Base64 packs 3 bytes into 4 chars, and the trailing
        // bits of the final quantum are ignored on decode — so flipping a char in
        // that tail region can decode to the SAME byte array, GCM then passes, and
        // decrypt() returns normally (the "nothing was thrown" failure). Decoding
        // the body, flipping a byte in the middle of the ciphertext, and re-encoding
        // guarantees the decoded bytes actually change, so the GCM auth tag MUST fail.
        String body = token.substring(CryptoService.ENC_PREFIX.length());
        byte[] raw = Base64.getDecoder().decode(body);

        // Flip a byte squarely inside the ciphertext (past the 12-byte IV,
        // before the trailing 16-byte tag) so the change is unambiguous.
        int idx = raw.length / 2;
        raw[idx] ^= 0x01;

        String tampered = CryptoService.ENC_PREFIX + Base64.getEncoder().encodeToString(raw);
        assertThrows(RuntimeException.class, () -> c.decrypt(tampered));
    }

    @Test
    @DisplayName("decrypting with a different key throws (key mismatch)")
    void wrongKeyThrows() {
        String token = new CryptoService("FirstEncryptionKey_ThirtyTwoBytes!!!").encrypt("secret");
        CryptoService other = new CryptoService("SecondEncryptionKey_ThirtyTwoByte!!!");
        assertThrows(RuntimeException.class, () -> other.decrypt(token));
    }

    // ---- key validation -----------------------------------------------------

    @Test
    @DisplayName("constructor rejects a key shorter than 32 bytes")
    void shortKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CryptoService("tooshort"));
    }

    @Test
    @DisplayName("constructor accepts a key of exactly 32 bytes")
    void exact32ByteKeyAccepted() {
        assertDoesNotThrow(() -> new CryptoService("0123456789012345678901234567890123"));
    }
}
