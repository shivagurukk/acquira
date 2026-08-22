package com.acquira.common.service;

import com.acquira.common.model.AuditLog;
import com.acquira.common.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists {@link AuditLog} rows for a user request.
 *
 * Runs in its OWN transaction (REQUIRES_NEW) so that:
 *   - the audit write commits independently of the user's request, and
 *   - a failed audit insert can NEVER roll back or break the actual request.
 *
 * Any exception is swallowed and logged at WARN — auditing is best-effort and
 * must not affect the user-facing response. Callers (AuditInterceptor) also
 * wrap this call defensively to absorb the proxy-commit UnexpectedRollbackException
 * that can surface when the inner save fails.
 *
 * Also holds the per-request bookkeeping that keeps a single admin action from
 * producing two audit rows: {@link AuditService} marks the ids it wrote, the
 * interceptor reads them back, stamps the outcome on and skips its own row.
 */
@Service
public class AuditRequestRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRequestRecorder.class);

    /** Request attribute holding the ids of descriptive rows written this request. */
    private static final String RECORDED_ATTR = "acquira.audit.recordedIds";

    private final AuditLogRepository auditLogRepository;

    public AuditRequestRecorder(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Note that a descriptive audit row was written for the current request. */
    @SuppressWarnings("unchecked")
    public static void markRecorded(HttpServletRequest request, Long logId) {
        if (request == null || logId == null) return;
        Object existing = request.getAttribute(RECORDED_ATTR);
        List<Long> ids = existing instanceof List ? (List<Long>) existing : new ArrayList<>();
        ids.add(logId);
        request.setAttribute(RECORDED_ATTR, ids);
    }

    /** Ids of descriptive rows written during this request (never null). */
    @SuppressWarnings("unchecked")
    public static List<Long> recordedIds(HttpServletRequest request) {
        if (request == null) return List.of();
        Object existing = request.getAttribute(RECORDED_ATTR);
        return existing instanceof List ? (List<Long>) existing : List.of();
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

    /**
     * Stamp the final HTTP status and elapsed time onto rows that a controller
     * wrote mid-request, when neither was known yet. Only fills rows whose
     * status is still unset, so a caller that recorded an explicit outcome keeps it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyOutcome(List<Long> logIds, Integer statusCode, Long durationMs) {
        if (logIds == null || logIds.isEmpty()) return;
        try {
            auditLogRepository.applyOutcome(logIds, statusCode, durationMs);
        } catch (Exception e) {
            log.warn("Failed to stamp outcome on audit_log rows {} (non-fatal): {}",
                    logIds, e.getMessage());
        }
    }
}
