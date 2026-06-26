package com.acquira.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    private final Key SECRET_KEY;

    // Access token: 30 minutes
    private static final long ACCESS_TOKEN_EXPIRY = 1000L * 60 * 30;

    // Refresh token: 7 days
    private static final long REFRESH_TOKEN_EXPIRY = 1000L * 60 * 60 * 24 * 7;

    public JwtUtil(@Value("${jwt.secret:AcquiraDefaultDevKeyAtLeast32Chars!!}") String secret) {
        this.SECRET_KEY = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(SECRET_KEY).build().parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername(), ACCESS_TOKEN_EXPIRY);
    }

    /** Access token with a caller-supplied lifetime (from the admin security policy). */
    public String generateToken(UserDetails userDetails, long ttlMillis) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername(),
                ttlMillis > 0 ? ttlMillis : ACCESS_TOKEN_EXPIRY);
    }

    public String generateToken(UserDetails userDetails, java.util.List<Long> allowedTenantIds, Long defaultTenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", userDetails.getAuthorities());
        claims.put("allowedTenantIds", allowedTenantIds);
        claims.put("defaultTenantId", defaultTenantId);
        return createToken(claims, userDetails.getUsername(), ACCESS_TOKEN_EXPIRY);
    }

    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return createToken(claims, username, REFRESH_TOKEN_EXPIRY);
    }

    /** Refresh token with a caller-supplied lifetime (from the admin security policy). */
    public String generateRefreshToken(String username, long ttlMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return createToken(claims, username,
                ttlMillis > 0 ? ttlMillis : REFRESH_TOKEN_EXPIRY);
    }

    public boolean isRefreshToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return "refresh".equals(claims.get("type"));
        } catch (Exception e) {
            return false;
        }
    }

    private String createToken(Map<String, Object> claims, String subject, long expiryMs) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(SECRET_KEY)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}
