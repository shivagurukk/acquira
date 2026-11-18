package com.acquira.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the PLATFORM-level identity of a user: who they are and whether they
 * are a super admin. Nothing tenant-specific lives here.
 *
 * <p>Pentest 2026-10-04 (H1/H7): this class used to union every
 * {@code UserTenantAccess.roleInTenant} and every group-derived role across ALL
 * tenants into one authority set. That made a Bank Admin of tenant A an
 * {@code ROLE_ADMIN} in every tenant they could merely view, and made a
 * "Super Admin" group row in one tenant a platform-wide
 * {@code ROLE_SUPER_ADMIN} — while {@code JwtRequestFilter} keyed its
 * X-Tenant-Id bypass on the {@code users.role} column instead. Two definitions
 * of "super admin" and a tenant-blind "admin" were the root of three escalation
 * chains.
 *
 * <p>Now there is exactly one rule, used everywhere:
 * <ul>
 *   <li>{@code ROLE_SUPER_ADMIN} ⇔ {@code users.role == 'ROLE_SUPER_ADMIN'}.
 *       Group membership or a tenant row never confers it.</li>
 *   <li>{@code ROLE_ADMIN}, {@code ROLE_OPS}, … are per-tenant and are added by
 *       {@link JwtRequestFilter} for the ACTIVE tenant only, from that tenant's
 *       access row ({@link #tenantAuthorities}).</li>
 * </ul>
 * Login / refresh / SSO still call {@link #loadUserByUsername} to mint tokens;
 * the authorities on that object are the platform ones (ROLE_USER, and
 * ROLE_SUPER_ADMIN when applicable). Per-request authorities are rebuilt by the
 * filter from the resolved tenant.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    /**
     * Tenant roles an admin may hand out. {@code ROLE_SUPER_ADMIN} is deliberately
     * absent: it is a platform fact on {@code users.role}, never a tenant row.
     */
    public static final Set<String> ASSIGNABLE_TENANT_ROLES = Set.of(ROLE_USER, ROLE_ADMIN, "ROLE_OPS");

    private final com.acquira.common.repository.UserRepository userRepository;

    public CustomUserDetailsService(com.acquira.common.repository.UserRepository userRepository,
            com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository) {
        // userTenantAccessRepository is kept in the signature so existing wiring/tests
        // compile; tenant access is no longer consulted here (see class javadoc).
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        com.acquira.common.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new User(user.getUsername(), user.getPassword(), platformAuthorities(user));
    }

    /** The single super-admin test. Everything in the platform must use this or the authority it yields. */
    public static boolean isSuperAdmin(com.acquira.common.model.User user) {
        return user != null && ROLE_SUPER_ADMIN.equals(user.getRole());
    }

    /** ROLE_USER, plus ROLE_SUPER_ADMIN/ROLE_ADMIN for a platform super admin. */
    public static List<GrantedAuthority> platformAuthorities(com.acquira.common.model.User user) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(ROLE_USER);
        if (isSuperAdmin(user)) {
            roles.add(ROLE_SUPER_ADMIN);
            roles.add(ROLE_ADMIN);
        }
        return AuthorityUtils.createAuthorityList(roles.toArray(new String[0]));
    }

    /**
     * Authorities for ONE tenant: the platform set plus the role the user holds in
     * that tenant's access row. A {@code ROLE_SUPER_ADMIN} stored on a tenant row
     * (legacy data) is downgraded to {@code ROLE_ADMIN} — the platform bit comes
     * only from {@code users.role}. The legacy "Super Admin" / "Bank Admin" groups
     * both map to tenant admin.
     *
     * @param access the access row for the active tenant, or null when the user has
     *               none (super admins browsing a tenant they are not a member of)
     */
    public static List<GrantedAuthority> tenantAuthorities(com.acquira.common.model.User user,
            com.acquira.common.model.UserTenantAccess access) {
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority a : platformAuthorities(user)) roles.add(a.getAuthority());

        String tenantRole = effectiveTenantRole(access);
        if (tenantRole != null) roles.add(tenantRole);
        return AuthorityUtils.createAuthorityList(roles.toArray(new String[0]));
    }

    /** The role a tenant access row grants inside its tenant; null if the row grants nothing beyond ROLE_USER. */
    public static String effectiveTenantRole(com.acquira.common.model.UserTenantAccess access) {
        if (access == null) return null;
        String role = access.getRoleInTenant();
        if (role != null && !role.isBlank()) {
            role = role.trim();
            // Never a platform grant: a tenant row saying SUPER_ADMIN means tenant admin.
            return ROLE_SUPER_ADMIN.equals(role) ? ROLE_ADMIN : role;
        }
        if (access.getSysUserGroup() != null) {
            String group = access.getSysUserGroup().getGroupName();
            if ("Super Admin".equalsIgnoreCase(group) || "Bank Admin".equalsIgnoreCase(group)
                    || "SUPER_ADMIN".equalsIgnoreCase(group) || "ADMIN".equalsIgnoreCase(group)) {
                return ROLE_ADMIN;
            }
        }
        return ROLE_USER;
    }
}
