package com.acquira.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public class TenantContext {

    private static final Logger logger = LoggerFactory.getLogger(TenantContext.class);

    // Core: The active tenant for WRITES (single)
    private static final ThreadLocal<Long> currentTenant = new ThreadLocal<>();

    // New: The list of visible tenants for READS (multi)
    private static final ThreadLocal<List<Long>> visibleTenants = new ThreadLocal<>();

    // New: The effective role in the current context
    private static final ThreadLocal<String> currentRole = new ThreadLocal<>();

    // ===== WRITE CONTEXT (Single Tenant) =====
    public static void setCurrentTenant(Long tenantId) {
        logger.debug("Setting current tenant to {}", tenantId);
        currentTenant.set(tenantId);

        // Default: visible list includes just this tenant
        if (visibleTenants.get() == null) {
            List<Long> list = new ArrayList<>();
            list.add(tenantId);
            visibleTenants.set(list);
        }
    }

    public static Long getCurrentTenant() {
        return currentTenant.get();
    }

    // ===== READ CONTEXT (Multi-Tenant / Scope) =====
    public static void setVisibleTenants(List<Long> tenantIds) {
        logger.debug("Setting visible tenants scope: {}", tenantIds);
        visibleTenants.set(tenantIds);
    }

    public static List<Long> getVisibleTenants() {
        List<Long> list = visibleTenants.get();
        if (list == null) {
            // Fallback to current tenant if set
            Long current = currentTenant.get();
            if (current != null) {
                return List.of(current);
            }
            // P2-5 fix: previously returned an empty list, which silently
            // turned every `WHERE tenant_id IN (?)` query into a zero-row
            // result — masking missing-tenant-context bugs as "no data"
            // in dashboards or, worse, as silent skips in batch jobs.
            // Fail loud instead so the offending call site surfaces immediately.
            throw new IllegalStateException(
                "No tenant in context: neither currentTenant nor visibleTenants is set. " +
                "This usually means TenantContext.setCurrentTenant(...) was not called " +
                "on this thread (common cause: @Async or scheduled job missing context propagation).");
        }
        return list;
    }

    // ===== ROLE CONTEXT =====
    public static void setCurrentRole(String role) {
        currentRole.set(role);
    }

    public static String getCurrentRole() {
        return currentRole.get();
    }

    public static void clear() {
        logger.debug("Clearing tenant context");
        currentTenant.remove();
        visibleTenants.remove();
        currentRole.remove();
    }
}
