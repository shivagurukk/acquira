package com.acquira.common.security;

import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * UserDetails that carries the already-loaded domain {@link User} and its
 * tenant-access rows, so per-request consumers (JwtRequestFilter) don't have
 * to re-run the exact same findByUsername / findByUser queries that
 * {@link CustomUserDetailsService} just executed. Before this, every
 * authenticated API request cost 4 DB round trips, 2 of them duplicates.
 */
public class AcquiraUserDetails extends org.springframework.security.core.userdetails.User {

    private final transient User domainUser;
    private final transient List<UserTenantAccess> tenantAccess;

    public AcquiraUserDetails(User domainUser,
            List<UserTenantAccess> tenantAccess,
            Collection<? extends GrantedAuthority> authorities) {
        super(domainUser.getUsername(), domainUser.getPassword(), authorities);
        this.domainUser = domainUser;
        this.tenantAccess = tenantAccess;
    }

    public User getDomainUser() {
        return domainUser;
    }

    public List<UserTenantAccess> getTenantAccess() {
        return tenantAccess;
    }
}
