package com.acquira.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Shared-secret check for pod-to-pod calls on {@code /internal/**} (e.g. core
 * asking the pdf pod to render a statement). Those calls run on background
 * threads with no user JWT, so they carry this token instead.
 *
 * Set {@code app.internal.token} (env APP_INTERNAL_TOKEN, same value on every
 * pod) to give these calls their own secret, rotated independently of user
 * sessions. Left unset, the token is derived from the JWT signing secret, which
 * every pod already shares. /internal/** is never routed by the ingress; the
 * token is the second line of defence.
 */
@Component
public class InternalAuth {

    public static final String HEADER = "X-Internal-Token";

    private final String token;

    public InternalAuth(@Value("${jwt.secret:AcquiraDefaultDevKeyAtLeast32Chars!!}") String jwtSecret,
                        @Value("${app.internal.token:}") String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank()) {
            this.token = configuredToken.trim();
            return;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.token = HexFormat.of().formatHex(mac.doFinal("acquira-internal-v1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive internal service token", e);
        }
    }

    /** Value to send in {@link #HEADER}. */
    public String token() {
        return token;
    }

    public boolean isValid(String presented) {
        return presented != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
