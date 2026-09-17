package com.acquira.common.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.AuditLog;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.AuditLogRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.repository.UserTenantAccessRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Writes the descriptive ("what did this action mean") audit rows that
 * controllers raise explicitly, e.g. {@code log("UNLOCK_USER", "...")}.
 *
 * Complements {@link com.acquira.common.config.AuditInterceptor}, which records
 * one generic row per HTTP request. When a controller logs explicitly, the
 * interceptor suppresses its own row so an action produces ONE row rather than
 * two — see {@link AuditRequestRecorder#markRecorded}.
 *
 * Every row is populated with the fields the admin viewer actually renders and
 * filters on: username, category, endpoint, method, IP and tenant. Previously
 * only userId/details/ip were set, so these rows showed a blank User and an
 * "N/A" category and were invisible to the username filter.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final UserTenantAccessRepository accessRepository;

    public AuditService(AuditLogRepository auditLogRepository,
                        UserRepository userRepository,
                        UserTenantAccessRepository accessRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
    }

    /**
     * Log an action performed by the currently authenticated user.
     */
    public void log(String actionType, String details) {
        log(actionType, details, null);
    }

    /**
     * Log an action for a KNOWN user, for flows where the SecurityContext is not
     * populated yet (or never will be): login, login-denied, SSO callback and
     * the password-reset OTP journey. Without the override these rows landed
     * with a null username — the single biggest gap in the trail, since a login
     * record that doesn't say who logged in is useless.
     */
    public void log(String actionType, String details, String usernameOverride) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActionType(actionType);
            entry.setDetails(details);
            entry.setCategory(categoryFor(actionType));

            String username = usernameOverride != null && !usernameOverride.isBlank()
                    ? usernameOverride.trim()
                    : currentUsername();
            entry.setUsername(username);

            User user = username == null ? null
                    : userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                entry.setUserId(user.getId());
            }

            // At login time TenantContext is still empty (the tenant is resolved
            // from the JWT on SUBSEQUENT requests), so these rows used to carry a
            // null tenant and were then hidden from every tenant-scoped viewer.
            // Fall back to the user's default tenant so bank admins can actually
            // see logins for their own bank.
            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId == null && user != null) {
                tenantId = defaultTenantIdFor(user);
            }
            entry.setTenantId(tenantId);

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entry.setHttpMethod(request.getMethod());
                entry.setEndpoint(request.getRequestURI());
                entry.setIpAddress(clientIp(request));
                entry.setUserAgent(truncate(request.getHeader("User-Agent"), 500));
            } else {
                // Background/batch threads have no request bound to them.
                entry.setIpAddress("SYSTEM");
            }

            AuditLog saved = auditLogRepository.save(entry);

            // Tell the interceptor this request already produced a descriptive
            // row, so it skips its generic duplicate and instead stamps the
            // final HTTP status + duration onto the row we just wrote.
            if (request != null) {
                AuditRequestRecorder.markRecorded(request, saved.getLogId());
            }
        } catch (Exception e) {
            // Auditing is best-effort and must never break the caller's action.
        }
    }

    /**
     * Canonical category for an action type. These MUST stay in step with
     * {@code AuditInterceptor.categorize()} and the category dropdown in
     * AuditLogViewer.jsx — a value that exists in only one of the three is a
     * filter option that silently returns nothing.
     */
    static String categoryFor(String actionType) {
        if (actionType == null || actionType.isBlank()) return "API";
        String a = actionType.toUpperCase();

        if (a.startsWith("LOGIN") || a.startsWith("LOGOUT") || a.startsWith("SSO_")
                || a.startsWith("PWRESET") || a.startsWith("MFA") || a.contains("_MFA")
                || a.contains("OTP") || a.contains("PASSWORD") || a.contains("SESSION")) {
            return "AUTH";
        }
        if (a.contains("USER") || a.contains("TENANT_ACCESS")
                || a.startsWith("ASSIGN_TENANT") || a.startsWith("ACCESS_REQUEST")) {
            return "USER_MGMT";
        }
        if (a.startsWith("BATCH") || a.startsWith("MIGRATION") || a.startsWith("SUMMARY_")
                || a.startsWith("DELETE_DAY") || a.startsWith("INTERCHANGE")
                || a.startsWith("PROVISION")) {
            return "OPERATIONS";
        }
        if (a.contains("REPORT") || a.contains("EXPORT")) {
            return "REPORTING";
        }
        return "ADMINISTRATION";
    }

    /** The user's default tenant, else their first grant. */
    private Long defaultTenantIdFor(User user) {
        try {
            List<UserTenantAccess> grants = accessRepository.findAllByUser(user);
            if (grants.isEmpty()) return null;
            return grants.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsDefaultTenant()))
                    .findFirst()
                    .orElse(grants.get(0))
                    .getTenant().getTenantId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest request) {
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
