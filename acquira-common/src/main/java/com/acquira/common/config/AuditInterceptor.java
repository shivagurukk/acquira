package com.acquira.common.config;

import com.acquira.common.model.AuditLog;
import com.acquira.common.service.AuditRequestRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Records one audit_log row per relevant user request.
 *
 * This is the piece that was missing: the audit_log table and the admin viewer
 * (AuditLogController) both existed, but nothing wrote a row per request, so the
 * Audit Logs page was effectively empty. CorrelationIdFilter only enriches SLF4J
 * MDC for the file log — it does not persist anything.
 *
 * Timing is captured in preHandle and the row is written in afterCompletion (so
 * the final HTTP status and any handler exception are known). The write goes
 * through AuditRequestRecorder on a separate transaction and is fully guarded —
 * auditing can never break or slow-fail the user's actual request.
 *
 * TenantContext and SecurityContext are still populated during afterCompletion
 * because interceptors run INSIDE the servlet dispatch, before JwtRequestFilter's
 * finally-block clears them.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "acquira.audit.start";

    private final AuditRequestRecorder recorder;

    public AuditInterceptor(AuditRequestRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception ex) {
        try {
            Object startObj0 = request.getAttribute(START_ATTR);
            Long elapsed = startObj0 instanceof Long start0
                    ? System.currentTimeMillis() - start0
                    : null;

            // If a controller already logged a DESCRIPTIVE row for this request
            // (e.g. "UNLOCK_USER"), that row is the audit record. Stamp the final
            // status/duration onto it and do NOT add a generic one — otherwise
            // every admin action produced two half-complete rows: this one with a
            // username but a meaningless action ("PUT"), and the controller's with
            // a real action but no HTTP outcome.
            java.util.List<Long> recorded = AuditRequestRecorder.recordedIds(request);
            if (!recorded.isEmpty()) {
                recorder.applyOutcome(recorded, response.getStatus(), elapsed);
                return;
            }

            if (!shouldAudit(request)) {
                return;
            }

            AuditLog entry = new AuditLog();
            entry.setHttpMethod(request.getMethod());
            entry.setEndpoint(request.getRequestURI());
            entry.setActionType(request.getMethod());
            entry.setStatusCode(response.getStatus());
            entry.setCategory(categorize(request.getRequestURI()));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 500));
            entry.setIpAddress(clientIp(request));
            entry.setUsername(currentUsername());
            entry.setTenantId(TenantContext.getCurrentTenant());

            entry.setDuration(elapsed);
            if (ex != null) {
                entry.setDetails(truncate("Exception: " + ex.getClass().getSimpleName()
                        + " - " + ex.getMessage(), 1000));
            }

            recorder.record(entry);
        } catch (Exception ignore) {
            // Auditing must never break the response — swallow everything.
        }
    }

    /**
     * Default policy: record state-changing requests (POST/PUT/PATCH/DELETE) plus
     * sensitive GETs (exports / downloads). Routine GET dashboard polling is skipped
     * so the table stays useful; CORS pre-flight is skipped; /api/auth is skipped
     * because AuthController already audits login; the audit viewer itself and infra
     * paths are skipped.
     *
     * To log EVERY request instead, replace the final return with `return true;`.
     */
    private boolean shouldAudit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (uri == null) return false;
        if (!uri.startsWith("/api/")) return false;                  // SPA / static assets
        if ("OPTIONS".equalsIgnoreCase(method)) return false;        // CORS preflight
        if (uri.startsWith("/api/auth/")) return false;              // AuthController audits these
        if (uri.startsWith("/api/admin/audit-logs")) return false;   // don't audit the audit viewer
        if (uri.contains("/swagger") || uri.contains("/v3/api-docs")
                || uri.startsWith("/api/actuator")) return false;

        boolean mutating = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
        boolean sensitiveRead = uri.contains("/export") || uri.contains("/download");
        return mutating || sensitiveRead;
    }

    /**
     * Canonical categories: AUTH, USER_MGMT, ADMINISTRATION, OPERATIONS,
     * REPORTING, API. Must stay in step with {@code AuditService.categoryFor()}
     * and the dropdown in AuditLogViewer.jsx.
     */
    private String categorize(String uri) {
        if (uri == null) return "API";
        if (uri.startsWith("/api/admin")) return "ADMINISTRATION";
        if (uri.startsWith("/api/upload") || uri.startsWith("/api/batch")
                || uri.startsWith("/api/ops")) return "OPERATIONS";
        if (uri.startsWith("/api/reports")) return "REPORTING";
        if (uri.startsWith("/api/users") || uri.startsWith("/api/tenants")) return "USER_MGMT";
        if (uri.startsWith("/api/sso")) return "AUTH";
        return "API";
    }

    private String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
