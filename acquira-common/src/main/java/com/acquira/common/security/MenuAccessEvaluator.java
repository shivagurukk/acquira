package com.acquira.common.security;

import com.acquira.common.config.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of the DB-driven menu grants.
 *
 * <p>Screen access in Acquira is granted by <em>group</em>, not by Spring role:
 * migrations insert a {@code sys_menu} row and then grant it to named groups via
 * {@code sys_group_menu} (see V2026_07_05_04__loss_making_menu.sql). The sidebar
 * honours that (GET /api/users/me/menus), but until this class the grants were
 * <strong>UI-only</strong> — the REST endpoints behind those screens sat under
 * {@code anyRequest().authenticated()} and could be called directly by any
 * authenticated user of the tenant.
 *
 * <p>A role annotation cannot express these grants. The {@code role} table seeds
 * only ROLE_ADMIN / ROLE_USER / ROLE_SUPER_ADMIN, while the groups are
 * 'Super Admin', 'Bank Admin', 'Business User', 'Finance User', … — the two
 * axes are orthogonal, so {@code hasAnyRole('SUPER_ADMIN','BANK_ADMIN')} would
 * lock out every legitimate Bank Admin user. This evaluator checks the actual
 * grant instead, keeping the SQL migration as the single source of truth.
 *
 * <p>Usage — reference the bean by name from {@code @PreAuthorize}:
 * <pre>
 *   &#64;PreAuthorize("@menuAccess.canAccess('/business/loss-making')")
 * </pre>
 *
 * <p>ROLE_SUPER_ADMIN always passes. The startup safety net in MenuController
 * grants every menu to the 'Super Admin' group anyway, but a super-admin placed
 * in some other group must not be locked out of the platform by this check.
 */
@Component("menuAccess")
public class MenuAccessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MenuAccessEvaluator.class);

    /** Grant lookup for (username, active tenant, menu path). */
    private static final String GRANT_SQL =
        "SELECT COUNT(*) FROM sys_group_menu gm " +
        "JOIN sys_menu m             ON m.menu_id  = gm.menu_id " +
        "JOIN user_tenant_access uta ON uta.group_id = gm.group_id " +
        "JOIN users u                ON u.user_id  = uta.user_id " +
        "WHERE u.username = ? AND uta.tenant_id = ? AND m.path = ?";

    private final JdbcTemplate jdbc;

    public MenuAccessEvaluator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param menuPath the {@code sys_menu.path} of the screen this endpoint backs
     * @return true if the caller's group in the ACTIVE tenant has been granted
     *         that menu, or the caller is a super-admin
     */
    public boolean canAccess(String menuPath) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || menuPath == null) return false;

        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority())) return true;
        }

        // Tenant scoping comes from the filter-validated TenantContext, never from
        // the attacker-controlled X-Tenant-Id header — same rule the controllers
        // follow. No tenant resolved means no grant can be evaluated.
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return false;

        try {
            Integer granted = jdbc.queryForObject(GRANT_SQL, Integer.class,
                    auth.getName(), tenantId, menuPath);
            return granted != null && granted > 0;
        } catch (Exception e) {
            // Fail closed: an unreadable grant table must not become open access.
            log.warn("[MenuAccess] grant lookup failed for user={} tenant={} path={}: {}",
                    auth.getName(), tenantId, menuPath, e.getMessage());
            return false;
        }
    }
}
