package com.acquira.core.config;

import com.acquira.common.security.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JwtUtil} — HS256 token mint/parse used for access &
 * refresh tokens.
 *
 * Covers: subject round-trip, expiry presence + future-dating, access vs
 * refresh discrimination, custom TTL (and the &lt;=0 fallback to default),
 * validateToken happy/again-mismatch paths, expired-token and bad-signature
 * exceptions, and isRefreshToken on malformed input.
 */
class JwtUtilTest {

    private static final String SECRET = "UnitTestJwtSecretKeyAtLeastThirtyTwoChars!!";

    private JwtUtil jwt() {
        return new JwtUtil(SECRET);
    }

    private UserDetails user(String name) {
        return User.withUsername(name).password("n/a").authorities("ROLE_USER").build();
    }

    // ---- subject ------------------------------------------------------------

    @Test
    @DisplayName("generateToken then extractUsername returns the subject")
    void extractUsernameRoundTrip() {
        JwtUtil j = jwt();
        String token = j.generateToken(user("alice"));
        assertEquals("alice", j.extractUsername(token));
    }

    @Test
    @DisplayName("token for one user does not validate against another user")
    void validateRejectsOtherUser() {
        JwtUtil j = jwt();
        String token = j.generateToken(user("alice"));
        assertFalse(j.validateToken(token, user("bob")));
    }

    @Test
    @DisplayName("validateToken returns true for the matching, unexpired user")
    void validateAcceptsMatchingUser() {
        JwtUtil j = jwt();
        String token = j.generateToken(user("carol"));
        assertTrue(j.validateToken(token, user("carol")));
    }

    // ---- expiration ---------------------------------------------------------

    @Test
    @DisplayName("access token expiry is in the future")
    void accessTokenExpiryFuture() {
        JwtUtil j = jwt();
        Date exp = j.extractExpiration(j.generateToken(user("dan")));
        assertTrue(exp.after(new Date()));
    }

    @Test
    @DisplayName("refresh token expiry is further out than access token expiry")
    void refreshExpiryAfterAccess() {
        JwtUtil j = jwt();
        Date access = j.extractExpiration(j.generateToken(user("erin")));
        Date refresh = j.extractExpiration(j.generateRefreshToken("erin"));
        assertTrue(refresh.after(access));
    }

    @Test
    @DisplayName("an already-expired token raises ExpiredJwtException on parse")
    void expiredTokenThrows() throws InterruptedException {
        JwtUtil j = jwt();
        String token = j.generateToken(user("frank"), 1L); // 1ms TTL
        Thread.sleep(20);
        assertThrows(ExpiredJwtException.class, () -> j.extractUsername(token));
    }

    // ---- custom TTL ---------------------------------------------------------

    @Test
    @DisplayName("custom positive TTL is honoured (longer token expires later)")
    void customTtlHonoured() {
        JwtUtil j = jwt();
        Date shortExp = j.extractExpiration(j.generateToken(user("gita"), 60_000L));
        Date longExp = j.extractExpiration(j.generateToken(user("gita"), 3_600_000L));
        assertTrue(longExp.after(shortExp));
    }

    @Test
    @DisplayName("TTL <= 0 falls back to the default access-token lifetime")
    void nonPositiveTtlFallsBack() {
        JwtUtil j = jwt();
        Date fallback = j.extractExpiration(j.generateToken(user("hugo"), 0L));
        // default is 30 min; assert it is comfortably in the future (> 20 min)
        long deltaMs = fallback.getTime() - System.currentTimeMillis();
        assertTrue(deltaMs > 20L * 60 * 1000, "0 TTL should fall back to the 30-min default");
    }

    // ---- refresh discrimination --------------------------------------------

    @Test
    @DisplayName("isRefreshToken is true for a refresh token and false for an access token")
    void refreshDiscrimination() {
        JwtUtil j = jwt();
        assertTrue(j.isRefreshToken(j.generateRefreshToken("ivan")));
        assertFalse(j.isRefreshToken(j.generateToken(user("ivan"))));
    }

    @Test
    @DisplayName("isRefreshToken returns false for a malformed token (no throw)")
    void refreshOnGarbage() {
        assertFalse(jwt().isRefreshToken("not-a-jwt"));
    }

    @Test
    @DisplayName("refresh token with custom TTL still flagged as refresh")
    void refreshCustomTtlStillRefresh() {
        JwtUtil j = jwt();
        assertTrue(j.isRefreshToken(j.generateRefreshToken("jane", 120_000L)));
    }

    // ---- multi-tenant claims overload --------------------------------------

    @Test
    @DisplayName("tenant-claims token still resolves the subject")
    void tenantClaimsTokenSubject() {
        JwtUtil j = jwt();
        String token = j.generateToken(user("kim"), List.of(1L, 2L), 1L);
        assertEquals("kim", j.extractUsername(token));
    }

    // ---- signature integrity ------------------------------------------------

    @Test
    @DisplayName("a token signed with a different secret fails signature verification")
    void wrongSecretFailsSignature() {
        String token = new JwtUtil("FirstJwtSecretKeyThatIsAtLeastThirtyTwoChars!!").generateToken(user("leo"));
        JwtUtil other = new JwtUtil("SecondJwtSecretKeyThatIsAtLeastThirtyTwoChars!!");
        assertThrows(SignatureException.class, () -> other.extractUsername(token));
    }
}
