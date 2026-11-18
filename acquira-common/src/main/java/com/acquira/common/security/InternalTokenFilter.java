package com.acquira.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Single enforcement point for the pod-to-pod shared secret on {@code /internal/**}.
 *
 * SecurityConfig marks {@code /internal/**} as permitAll because those calls carry
 * no user JWT (they run on background threads: core asking the pdf pod to render
 * a statement). Until now each /internal controller checked
 * {@link InternalAuth#isValid} itself, which meant a new internal endpoint that
 * forgot the check was silently open to anyone who could reach the pod. This
 * filter rejects every /internal request without a valid {@link InternalAuth#HEADER}
 * before it reaches a controller, so the per-controller checks become
 * defence-in-depth rather than the only guard.
 *
 * Runs just after {@link com.acquira.common.config.CorrelationIdFilter}
 * (HIGHEST_PRECEDENCE) so rejections still carry a correlation id in the log,
 * and before the security chain and rate limiter (which already skips /internal/
 * because all pod-to-pod traffic arrives from one caller IP).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String REJECT_BODY =
            "{\"error\":\"internal token required\",\"code\":\"INTERNAL_AUTH_REQUIRED\"}";

    private final InternalAuth internalAuth;

    public InternalTokenFilter(InternalAuth internalAuth) {
        this.internalAuth = internalAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Everything outside /internal/ is the user-facing API and is guarded by the JWT chain.
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!internalAuth.isValid(request.getHeader(InternalAuth.HEADER))) {
            // Caller IP is sanitised by ClientIp, so it is safe to log.
            log.warn("[INTERNAL-AUTH] rejected {} {} from {} (missing or invalid {})",
                    request.getMethod(), request.getRequestURI(), ClientIp.of(request), InternalAuth.HEADER);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(REJECT_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
