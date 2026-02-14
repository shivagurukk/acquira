package com.acquira.service;

import com.acquira.config.TenantContext;
import com.acquira.model.AuditLog;
import com.acquira.repository.AuditLogRepository;
import com.acquira.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @org.springframework.scheduling.annotation.Async
    public void log(String actionType, String details) {
        logBuilder().actionType(actionType).details(details).save();
    }

    @org.springframework.scheduling.annotation.Async
    public void logFromAspect(String username, Long tenantId, Long userId, String action, String details,
            String category, String httpMethod, String endpoint, int statusCode,
            String ip, String userAgent, long duration) {

        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setTenantId(tenantId);
        log.setUserId(userId);
        log.setActionType(action);
        log.setDetails(truncate(details, 5000)); // Cap detail size
        log.setCategory(category);
        log.setHttpMethod(httpMethod);
        log.setEndpoint(endpoint);
        log.setStatusCode(statusCode);
        log.setIpAddress(ip);
        log.setUserAgent(truncate(userAgent, 255));
        log.setDuration(duration);

        auditLogRepository.save(log);
    }

    private String truncate(String s, int max) {
        if (s == null)
            return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // Helper builder for manual usage if needed
    private LogBuilder logBuilder() {
        return new LogBuilder(auditLogRepository, userRepository);
    }

    public static class LogBuilder {
        private final AuditLogRepository repo;
        private final UserRepository userRepo;
        private final AuditLog log = new AuditLog();

        public LogBuilder(AuditLogRepository repo, UserRepository userRepo) {
            this.repo = repo;
            this.userRepo = userRepo;
            // Defaults
            log.setIpAddress("0.0.0.0");
            try {
                Long tId = TenantContext.getCurrentTenant();
                if (tId != null)
                    log.setTenantId(tId);

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                    String uname = auth.getName();
                    log.setUsername(uname);
                    userRepo.findByUsername(uname).ifPresent(u -> log.setUserId(u.getId()));
                }
            } catch (Exception e) {
                /* quiet */ }
        }

        public LogBuilder actionType(String a) {
            log.setActionType(a);
            return this;
        }

        public LogBuilder details(String d) {
            log.setDetails(d);
            return this;
        }

        public void save() {
            repo.save(log);
        }
    }
}
