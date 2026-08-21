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

    @Around("execution(* com.acquira..service..*(..)) || execution(* com.acquira..repository..*(..))")
    public Object setTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        Long tenantId = TenantContext.getCurrentTenant();

        if (tenantId != null) {
            // P1-2 fix:
            //   - Use set_config('app.current_tenant', ?, false) instead of
            //     SET LOCAL. SET LOCAL is silently a no-op outside a transaction
            //     (PG just emits a WARNING), which means it does NOTHING when
            //     called from a tasklet running with propagation NEVER — the
            //     exact pattern most batch steps use. set_config(..., false)
            //     is session-scoped and works regardless of transaction state.
            //   - Use a parameterized statement so the value can never escape
            //     into SQL even if tenantId's type is later widened.
            //   - Removed the lastSetTenant ThreadLocal cache. The setting is
            //     per-CONNECTION (not per-thread), and HikariCP returns connections
            //     to the pool — so the next request on the same thread might get a
            //     different connection that has stale or no tenant context. Setting
            //     it on every aspect invocation guarantees correctness; the cost is
            //     a sub-millisecond local PG roundtrip, dwarfed by the actual query.
            try {
                Session session = entityManager.unwrap(Session.class);
                session.doWork(connection -> {
                    try (java.sql.PreparedStatement ps = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant', ?, false)")) {
                        ps.setString(1, String.valueOf(tenantId.longValue()));
                        try (java.sql.ResultSet rs = ps.executeQuery()) { rs.next(); }
                    }
                });
                logger.trace("Set DB session app.current_tenant = {}", tenantId);
            } catch (Exception e) {
                logger.error("Failed to set tenant context in DB session for method: {}",
                        joinPoint.getSignature().toShortString(), e);
                // Don't block: app-layer WHERE tenant_id = ? still enforces isolation;
                // RLS, if/when forced, will block reads rather than leak them.
            }
        }

        return joinPoint.proceed();
    }
}
