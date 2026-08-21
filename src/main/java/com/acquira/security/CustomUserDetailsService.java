package com.acquira.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final com.acquira.repository.UserRepository userRepository;
    private final com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository;

    public CustomUserDetailsService(com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository) {
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        List<com.acquira.model.UserTenantAccess> accessList = userTenantAccessRepository.findByUser(user);

        // Determine Roles based on Groups
        java.util.Set<String> roles = new java.util.HashSet<>();
        roles.add("ROLE_USER"); // Default

        for (com.acquira.model.UserTenantAccess access : accessList) {
            // New way: explicit role in tenant
            if (access.getRoleInTenant() != null && !access.getRoleInTenant().isEmpty()) {
                roles.add(access.getRoleInTenant());
            }

            // Old way: fallback to Group
            if (access.getSysUserGroup() != null) {
                String groupName = access.getSysUserGroup().getGroupName();
                if ("Super Admin".equalsIgnoreCase(groupName)) {
                    roles.add("ROLE_SUPER_ADMIN");
                    roles.add("ROLE_ADMIN");
                } else if ("Bank Admin".equalsIgnoreCase(groupName)) {
                    roles.add("ROLE_ADMIN");
                }
            }
        }

        List<org.springframework.security.core.GrantedAuthority> authorities = AuthorityUtils
                .createAuthorityList(roles.toArray(new String[0]));

        return new User(user.getUsername(), user.getPassword(), authorities);
    }
}
