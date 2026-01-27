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

    public TenantService(TenantRepository tenantRepository,
            UserTenantAccessRepository userTenantAccessRepository,
            com.acquira.repository.UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.userRepository = userRepository;
    }

    public List<Tenant> getAllowedTenants(String username) {
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Admin Access: Return ALL tenants
        if ("ROLE_ADMIN".equals(user.getRole())) {
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
        List<Tenant> tenants = getAllowedTenants(username);
        return tenants.isEmpty() ? null : tenants.get(0).getTenantId();
    }
}
