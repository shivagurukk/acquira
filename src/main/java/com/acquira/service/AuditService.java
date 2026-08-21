package com.acquira.service;

import com.acquira.config.TenantContext;
import com.acquira.model.AuditLog;
import com.acquira.repository.AuditLogRepository;
import com.acquira.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Audit logging service.
 *
 * CRITICAL: @Async methods run in a NEW thread where SecurityContextHolder
 * and TenantContext are NOT available. Therefore, ALL context data (username,
 * tenantId, userId) must be resolved by the CALLER on the request thread
 * BEFORE calling async methods.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * Simple logging for manual calls from controllers (e.g. AdminController).
     * Resolves username/tenant on the CURRENT thread, then saves directly.
     * Not async — keeps it simple and reliable for manual audit calls.
     */
    public void log(String actionType, String details) {
        try {
            String username = null;
            Long userId = null;
            Long tenantId = null;

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                username = auth.getName();
                String uname = username;
                userId = userRepository.findByUsername(uname).map(u -> u.getId()).orElse(null);
            }

            try { tenantId = TenantContext.getCurrentTenant(); } catch (Exception ignored) {}

            AuditLog auditLog = new AuditLog();
            auditLog.setUsername(username);
            auditLog.setTenantId(tenantId);
            auditLog.setUserId(userId);
            auditLog.setActionType(actionType);
            auditLog.setDetails(truncate(details, 5000));
            auditLog.setCategory("DATA");
            auditLog.setIpAddress("0.0.0.0");

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Audit log failed: {}", e.getMessage());
        }
    }

    /**
     * Full logging from AuditAspect — all context data pre-resolved on request thread.
     * Runs async to avoid blocking the HTTP response.
     */
    @Async
    public void logFromAspect(String username, Long tenantId, Long userId,
                              String action, String details, String category,
                              String httpMethod, String endpoint, int statusCode,
                              String ip, String userAgent, long duration) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUsername(username);
            auditLog.setTenantId(tenantId);
            auditLog.setUserId(userId);
            auditLog.setActionType(action);
            auditLog.setDetails(truncate(details, 5000));
            auditLog.setCategory(category);
            auditLog.setHttpMethod(httpMethod);
            auditLog.setEndpoint(endpoint);
            auditLog.setStatusCode(statusCode);
            auditLog.setIpAddress(ip);
            auditLog.setUserAgent(truncate(userAgent, 255));
            auditLog.setDuration(duration);

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Async audit log failed: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
