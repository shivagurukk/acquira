package com.acquira.common.service;

import com.acquira.common.model.AuditLog;
import com.acquira.common.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a single {@link AuditLog} row for a user request.
 *
 * Runs in its OWN transaction (REQUIRES_NEW) so that:
 *   - the audit write commits independently of the user's request, and
 *   - a failed audit insert can NEVER roll back or break the actual request.
 *
 * Any exception is swallowed and logged at WARN — auditing is best-effort and
 * must not affect the user-facing response. Callers (AuditInterceptor) also
 * wrap this call defensively to absorb the proxy-commit UnexpectedRollbackException
 * that can surface when the inner save fails.
 */
@Service
public class AuditRequestRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRequestRecorder.class);

    private final AuditLogRepository auditLogRepository;

    public AuditRequestRecorder(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLog entry) {
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write audit_log row for {} {} (non-fatal): {}",
                    entry.getHttpMethod(), entry.getEndpoint(), e.getMessage());
        }
    }
}
