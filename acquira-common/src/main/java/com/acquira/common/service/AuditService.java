package com.acquira.common.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.AuditLog;
import com.acquira.common.repository.AuditLogRepository;
import com.acquira.common.repository.UserRepository;
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

    public void log(String actionType, String details) {
        AuditLog log = new AuditLog();

        // Try to get current tenant
        Long tenantId = TenantContext.getCurrentTenant();
        log.setTenantId(tenantId);

        // Try to get current user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            // Assuming username matches
            userRepository.findByUsername(auth.getName()).ifPresent(u -> log.setUserId(u.getId()));
        }

        log.setActionType(actionType);
        log.setDetails(details);
        // IP Address handling skipped for brevity or requires RequestContextHolder
        log.setIpAddress("127.0.0.1");

        auditLogRepository.save(log);
    }
}
