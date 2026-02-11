package com.acquira.config;

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
            return new ArrayList<>();
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
