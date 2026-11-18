package com.acquira.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Resolves the caller's IP for rate limiting, login lockout and audit rows.
 *
 * X-Forwarded-For is a list every proxy APPENDS to, so its left-hand entries are
 * whatever the client chose to send. Only the entries added by our own proxies
 * can be trusted, and those are on the right: with one proxy in front (the ALB,
 * ingress-nginx, or the compose/RHEL nginx) the real client is the LAST entry.
 * Taking the first entry let a caller pick its own rate-limit bucket and audit IP.
 *
 * {@code app.security.trusted-proxies} (env APP_SECURITY_TRUSTEDPROXIES) is the
 * number of our proxies that append to the header: 1 by default, 2 if e.g.
 * CloudFront sits in front of the ALB, 0 to ignore the header entirely (the app
 * is reached directly).
 */
@Component
public class ClientIp {

    private static final String HEADER = "X-Forwarded-For";

    private static volatile int trustedProxies = 1;

    public ClientIp(@Value("${app.security.trusted-proxies:1}") int trustedProxies) {
        ClientIp.trustedProxies = Math.max(0, trustedProxies);
    }

    public static String of(HttpServletRequest request) {
        return resolve(request.getHeader(HEADER), request.getRemoteAddr(), trustedProxies);
    }

    /**
     * Characters that can legitimately appear in an address hop: IPv4 digits and
     * dots, IPv6 hex and colons, a zone-id separator ({@code %}), and the
     * brackets/port some proxies add ({@code [::1]:8080}). Anything else is stripped.
     */
    private static final Pattern ILLEGAL = Pattern.compile("[^0-9a-fA-F.:%\\[\\]]");

    /** A bracketed IPv6 with zone and port is under 60 chars; cap well below a log line. */
    private static final int MAX_LEN = 64;

    static String resolve(String forwardedFor, String remoteAddr, int proxies) {
        if (proxies <= 0 || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwardedFor.split(",");
        String ip = hops[Math.max(0, hops.length - proxies)].trim();
        return ip.isEmpty() ? remoteAddr : sanitize(ip, remoteAddr);
    }

    /**
     * The chosen hop is still attacker-influenced text that ends up in audit rows
     * and WARN lines ([RATE-LIMIT], login lockout). A header value such as
     * {@code "1.2.3.4\r\n[AUDIT] admin login OK"} would forge a log entry, so
     * reduce it to the address alphabet, cap its length and, if nothing legal
     * remains, fall back to the socket address.
     */
    private static String sanitize(String hop, String remoteAddr) {
        String clean = ILLEGAL.matcher(hop).replaceAll("");
        if (clean.length() > MAX_LEN) clean = clean.substring(0, MAX_LEN);
        return clean.isEmpty() ? remoteAddr : clean;
    }
}
