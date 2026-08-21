package com.acquira.service;

import com.acquira.model.Tenant;
import com.acquira.model.UserTenantAccess;

import com.acquira.repository.TenantRepository;
import com.acquira.repository.UserTenantAccessRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;
    private final com.acquira.repository.UserRepository userRepository;
    private final com.acquira.repository.UserCombinedViewRepository userCombinedViewRepository;

    public TenantService(TenantRepository tenantRepository,
            UserTenantAccessRepository userTenantAccessRepository,
            com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserCombinedViewRepository userCombinedViewRepository) {
        this.tenantRepository = tenantRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.userRepository = userRepository;
        this.userCombinedViewRepository = userCombinedViewRepository;
    }

    public List<Tenant> getAllowedTenants(String username) {
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // SECURITY FIX: Only SUPER_ADMIN sees ALL tenants.
        // Bank Admin (ROLE_ADMIN) sees only their explicitly assigned tenants,
        // just like regular users. This prevents cross-tenant data access.
        String userRole = user.getRole();
        if ("ROLE_SUPER_ADMIN".equals(userRole)) {
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

    public Tenant createTenant(Tenant tenant) {
        return tenantRepository.save(tenant);
    }

    public List<Tenant> getAllowedTenantsForUser(String username) {
        return getAllowedTenants(username);
    }

    public Long getDefaultTenantIdForUser(String username) {
        com.acquira.model.User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return null;

        // 1. Find the access marked as default
        List<UserTenantAccess> accessList = userTenantAccessRepository.findByUser(user);
        UserTenantAccess defaultAccess = accessList.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsDefaultTenant()))
                .findFirst()
                .orElse(null);

        if (defaultAccess != null) {
            return defaultAccess.getTenant().getTenantId();
        }

        // 2. Fallback to first available tenant
        if (!accessList.isEmpty()) {
            return accessList.get(0).getTenant().getTenantId();
        }

        // 3. Super admin fallback — first tenant in system (SUPER_ADMIN only)
        String role = user.getRole();
        if ("ROLE_SUPER_ADMIN".equals(role)) {
            List<Tenant> all = tenantRepository.findAll();
            return all.isEmpty() ? null : all.get(0).getTenantId();
        }

        return null;
    }

    public Long getCurrentTenantId() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Fallback or throw? For now return 1L or null
            return 1L;
        }
        String username = auth.getName();
        // If username is "anonymousUser", handle?
        if ("anonymousUser".equals(username))
            return 1L;

        return getDefaultTenantIdForUser(username);
    }

    // ===== Multi-Tenant View Management =====

    public List<com.acquira.model.UserCombinedView> getCombinedViews(String username) {
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return userCombinedViewRepository.findByUser(user);
    }

    public com.acquira.model.UserCombinedView createCombinedView(String username, String viewName,
            List<Long> tenantIds) {
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Validate user has access to all these tenants
        List<Long> allowedIds = getAllowedTenantIds(username);
        if (!allowedIds.containsAll(tenantIds)) {
            throw new RuntimeException("User does not have access to some of the requested tenants");
        }

        com.acquira.model.UserCombinedView view = new com.acquira.model.UserCombinedView();
        view.setUser(user);
        view.setViewName(viewName);
        view.setTenantIds(tenantIds.stream().map(String::valueOf).collect(Collectors.joining(",")));

        return userCombinedViewRepository.save(view);
    }

    public void deleteCombinedView(Long viewId) {
        userCombinedViewRepository.deleteById(viewId);
    }
}
