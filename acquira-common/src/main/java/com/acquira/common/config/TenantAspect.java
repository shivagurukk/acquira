package com.acquira.common.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantAspect {

    private static final Logger logger = LoggerFactory.getLogger(TenantAspect.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Track whether we've already set the tenant for the current thread/request.
     * This prevents re-executing SET LOCAL on every nested service/repository call
     * within the same request, which avoids the "transaction aborted" cascade.
     */
    private static final ThreadLocal<Long> lastSetTenant = new ThreadLocal<>();

    @Around("execution(* com.acquira..service..*(..)) || execution(* com.acquira..repository..*(..))")
    public Object setTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        Long tenantId = TenantContext.getCurrentTenant();

        if (tenantId != null) {
            // Only execute SET LOCAL if we haven't already set it for this tenant in this request
            Long alreadySet = lastSetTenant.get();
            if (alreadySet == null || !alreadySet.equals(tenantId)) {
                try {
                    Session session = entityManager.unwrap(Session.class);
                    session.doWork(connection -> {
                        // tenantId is a Long — guaranteed numeric, no injection possible
                        try (java.sql.Statement stmt = connection.createStatement()) {
                            stmt.execute("SET LOCAL app.current_tenant = '" + tenantId.longValue() + "'");
                            logger.trace("Set DB Session app.current_tenant = {}", tenantId);
                        }
                    });
                    lastSetTenant.set(tenantId);
                } catch (Exception e) {
                    logger.error("Failed to set tenant context in DB session for method: {}",
                            joinPoint.getSignature().toShortString(), e);
                    // Don't block — RLS will restrict data access as a safety net
                }
            }
        }

        try {
            return joinPoint.proceed();
        } finally {
            // Clean up only at the outermost call (when we're about to exit the service layer)
            // We check if this is a top-level service call by checking the stack
            // Simple approach: let JwtRequestFilter's finally block handle TenantContext.clear()
            // We just clear our tracking here if tenant context is null (request ending)
            if (TenantContext.getCurrentTenant() == null) {
                lastSetTenant.remove();
            }
        }
    }
}
