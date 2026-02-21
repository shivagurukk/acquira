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

        // Admin Access: Return ALL tenants
        // Supports both global roles (ROLE_SUPER_ADMIN) and checks against the User's
        // primary role
        String userRole = user.getRole();
        if ("ROLE_SUPER_ADMIN".equals(userRole) || "ROLE_ADMIN".equals(userRole)) {
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

    public void deleteCombinedView(Long viewId) {
        userCombinedViewRepository.deleteById(viewId);
    }
}
