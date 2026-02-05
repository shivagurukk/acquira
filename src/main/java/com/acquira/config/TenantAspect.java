package com.acquira.config;

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

    // Intercept relevant Service or Repository methods.
    // We target Service layer primarily as it wraps transactions.
    @Around("execution(* com.acquira.service..*(..)) || execution(* com.acquira.repository..*(..))")
    public Object setTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        Long tenantId = TenantContext.getCurrentTenant();

        if (tenantId != null) {
            // Unwrap Hibernate Session to execute SQL directly on the connection
            try {
                Session session = entityManager.unwrap(Session.class);
                session.doWork(connection -> {
                    try (java.sql.Statement stmt = connection.createStatement()) {
                        // SET LOCAL ensures it only lasts for the current transaction
                        stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
                        logger.trace("Set DB Session app.current_tenant = {}", tenantId);
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to set tenant context in DB session", e);
                // We don't block execution here, but RLS might block data access, which is
                // safe.
            }
        } else {
            logger.trace("No Tenant Context found for method: {}", joinPoint.getSignature().toShortString());
            // IMPORTANT: If no tenant is set, we might want to set it to NULL explicitly
            // to prevent leakage from connection pooling reuse, though 'SET LOCAL' handles
            // this
            // if transactions are committed/rolled back properly.
            // For safety in connection pools:
            /*
             * Session session = entityManager.unwrap(Session.class);
             * session.doWork(connection -> {
             * try (Statement stmt = connection.createStatement()) {
             * stmt.execute("SET LOCAL app.current_tenant = NULL");
             * }
             * });
             */
        }

        return joinPoint.proceed();
    }
}
