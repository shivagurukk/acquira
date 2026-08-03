package com.acquira.core.service;

import com.acquira.common.model.Tenant;
import com.acquira.common.model.UserTenantAccess;

import com.acquira.common.repository.TenantRepository;
import com.acquira.common.repository.UserTenantAccessRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;
    private final com.acquira.common.repository.UserRepository userRepository;
    private final com.acquira.common.repository.UserCombinedViewRepository userCombinedViewRepository;

    public TenantService(TenantRepository tenantRepository,
            UserTenantAccessRepository userTenantAccessRepository,
            com.acquira.common.repository.UserRepository userRepository,
            com.acquira.common.repository.UserCombinedViewRepository userCombinedViewRepository) {
        this.tenantRepository = tenantRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.userRepository = userRepository;
        this.userCombinedViewRepository = userCombinedViewRepository;
    }

    public List<Tenant> getAllowedTenants(String username) {
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only ROLE_SUPER_ADMIN is a PLATFORM role. ROLE_ADMIN is a per-BANK admin, so
        // including it here handed every bank admin the full tenant roster (bankName,
        // institutionId, country, currency) via /api/auth/login, /api/auth/session and
        // /api/banks — and silently defeated BankController's own super-admin guard.
        // JwtRequestFilter still blocks switching into those tenants; this closes the
        // disclosure.
        if ("ROLE_SUPER_ADMIN".equals(user.getRole())) {
            return tenantRepository.findAll();
        }

        List<UserTenantAccess> accessList = userTenantAccessRepository.findByUser(user);
        return accessList.stream()
                .map(UserTenantAccess::getTenant)
                .collect(Collectors.toList());
    }

    public List<Long> getAllowedTenantIds(String username) {
        return getAllowedTenants(username).stream()
                .map(Tenant::getTenantId)
                .collect(Collectors.toList());
    }

    public List<Tenant> getAllTenants() {
        return tenantRepository.findAll();
    }

    public Tenant createTenant(Tenant tenant) {
        return tenantRepository.save(tenant);
    }

    public List<Tenant> getAllowedTenantsForUser(String username) {
        return getAllowedTenants(username);
    }

    public Long getDefaultTenantIdForUser(String username) {
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<UserTenantAccess> accessList = userTenantAccessRepository.findByUser(user);

        // 1. Prefer the tenant marked as default
        return accessList.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsDefaultTenant()))
                .map(a -> a.getTenant().getTenantId())
                .findFirst()
                // 2. Fallback to first assigned tenant
                .orElseGet(() -> {
                    List<Tenant> tenants = getAllowedTenants(username);
                    return tenants.isEmpty() ? null : tenants.get(0).getTenantId();
                });
    }

    public Long getCurrentTenantId() {
        // CRITICAL: the per-request active tenant lives in TenantContext, which
        // JwtRequestFilter populates from the X-Tenant-Id header on EVERY request
        // (validating the user actually has access to it, super-admins excepted).
        // When a user switches tenant, the frontend sends the new tenant in that
        // header, so TenantContext holds the switched-to tenant. We MUST honour it.
        //
        // Previously this method ignored TenantContext and always re-derived the
        // user's DB *default* tenant, so after switching from tenant A to tenant B
        // every endpoint that calls getCurrentTenantId() (the whole
        // BusinessAnalyticsController, finance, etc.) kept serving tenant A's data
        // — a cross-tenant data leak. Prefer the request-scoped tenant here.
        Long ctxTenant = com.acquira.common.config.TenantContext.getCurrentTenant();
        if (ctxTenant != null) {
            return ctxTenant;
        }

        // Fallback path: no request-scoped tenant (e.g. a non-HTTP thread such as
        // a scheduled/batch job, or a request that arrived without X-Tenant-Id).
        // Fall back to the authenticated user's default tenant.
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null; // SECURITY FIX: No hardcoded fallback to tenant 1
        }
        String username = auth.getName();
        if ("anonymousUser".equals(username)) {
            return null; // SECURITY FIX: Anonymous users get no tenant
        }

        return getDefaultTenantIdForUser(username);
    }

    // ===== Multi-Tenant View Management =====

    public List<com.acquira.common.model.UserCombinedView> getCombinedViews(String username) {
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return userCombinedViewRepository.findByUser(user);
    }

    public com.acquira.common.model.UserCombinedView createCombinedView(String username, String viewName,
            List<Long> tenantIds) {
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Validate user has access to all these tenants
        List<Long> allowedIds = getAllowedTenantIds(username);
        if (!allowedIds.containsAll(tenantIds)) {
            throw new RuntimeException("User does not have access to some of the requested tenants");
        }

        com.acquira.common.model.UserCombinedView view = new com.acquira.common.model.UserCombinedView();
        view.setUser(user);
        view.setViewName(viewName);
        view.setTenantIds(tenantIds.stream().map(String::valueOf).collect(Collectors.joining(",")));

        return userCombinedViewRepository.save(view);
    }

    public void deleteCombinedView(String username, Long viewId) {
        // Ownership guard (mirrors createCombinedView's validation): view ids are
        // global, so an unchecked deleteById could remove another user's view.
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        com.acquira.common.model.UserCombinedView view = userCombinedViewRepository.findById(viewId)
                .orElseThrow(() -> new RuntimeException("View not found"));
        if (view.getUser() == null || !view.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("View does not belong to the current user");
        }
        userCombinedViewRepository.deleteById(viewId);
    }
}
