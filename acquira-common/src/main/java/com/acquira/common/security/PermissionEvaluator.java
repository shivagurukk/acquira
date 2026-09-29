package com.acquira.common.security;

import com.acquira.common.config.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Screen and action authorisation — the one place that answers "may this user,
 * in this tenant, do this".
 *
 * <p>Replaces the two axes the platform used to carry at once: Spring roles on
 * the route ({@code RoleGuard requiredRoles={'ROLE_ADMIN'}}) and group grants on
 * the API ({@code @menuAccess.canAccess(path)}). They were orthogonal, so they
 * could and did disagree; that disagreement is pentest finding H-2 (role union).
 * {@link MenuAccessEvaluator} now delegates here, so the 88 existing annotations
 * keep working unchanged while screens are converted one at a time.
 *
 * <h3>The rule</h3>
 * <pre>
 *   effective(user, tenant) = grants of the user's group in that tenant
 *                           − DENY rows                (deny always wins)
 *                           ∩ modules enabled for the tenant
 *                           ∪ (ROLE_SUPER_ADMIN → everything)
 * </pre>
 * The set difference and the module gate live in the {@code v_user_permission}
 * view so the admin matrix and this evaluator can never drift apart. Only the
 * super-admin bypass is here — the matrix must show what a group actually
 * grants, not what a super-admin happens to be able to do.
 *
 * <h3>Usage</h3>
 * <pre>
 *   &#64;PreAuthorize("@perm.can('sales.agents', 'VIEW')")
 *   &#64;PreAuthorize("@perm.can('merchants.list', 'APPROVE')")  // four-eyes
 * </pre>
 *
 * <h3>Cost</h3>
 * The old evaluator ran a four-table {@code COUNT(*)} per guarded request. Here
 * a user's whole permission set for a tenant is loaded once and cached under
 * {@code (username, tenantId, rbacVersion)}; any RBAC write bumps
 * {@code rbac_version} and every affected entry is missed on the next request.
 * The version itself is cached for a few seconds, so the steady-state cost of a
 * guarded call is a primary-key lookup, not a join.
 *
 * <p>Every failure path returns false. An unreadable grant table is not a
 * reason to let a request through.
 */
@Component("perm")
public class PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PermissionEvaluator.class);

    public static final String VIEW    = "VIEW";
    public static final String EDIT    = "EDIT";
    public static final String APPROVE = "APPROVE";
    public static final String EXPORT  = "EXPORT";

    /** Effective permission codes for a (user, tenant), from the resolution view. */
    private static final String PERMS_SQL =
        "SELECT v.perm_code FROM v_user_permission v " +
        "JOIN users u ON u.user_id = v.user_id " +
        "WHERE u.username = ? AND v.tenant_id = ?";

    /**
     * Fallback for an environment where V2026_09_26_02 has not been applied.
     * Grants VIEW only — the legacy model cannot express anything else, and
     * inventing EDIT from a VIEW grant is exactly the conflation this class
     * exists to remove.
     */
    private static final String LEGACY_SQL =
        "SELECT REPLACE(LTRIM(m.path, '/'), '/', '.') || ':VIEW' " +
        "FROM sys_group_menu gm " +
        "JOIN sys_menu m             ON m.menu_id   = gm.menu_id " +
        "JOIN user_tenant_access uta ON uta.group_id = gm.group_id " +
        "JOIN users u                ON u.user_id   = uta.user_id " +
        "WHERE u.username = ? AND uta.tenant_id = ?";

    private final JdbcTemplate jdbc;

    private final Cache<String, Set<String>> permCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final Cache<String, Set<String>> categoryCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /** Short-lived so an RBAC change is visible within seconds without a restart. */
    private final Cache<Integer, Long> versionCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    private volatile Boolean newModelAvailable;

    public PermissionEvaluator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * @param menuKey stable screen id from {@code sys_menu.menu_key}, e.g.
     *                {@code sales.agents} — NOT the URL. Routes get renamed;
     *                keying permissions on the URL would silently revoke access.
     * @param action  VIEW | EDIT | APPROVE | EXPORT
     */
    public boolean can(String menuKey, String action) {
        if (menuKey == null || action == null) return false;
        if (isSuperAdmin()) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return false;

        return permissions(auth.getName(), tenantId.intValue())
                .contains(menuKey + ":" + action.toUpperCase());
    }

    /** Convenience for the common case. */
    public boolean canView(String menuKey) { return can(menuKey, VIEW); }

    /** True if the caller has ANY of the actions on the screen. */
    public boolean canAny(String menuKey, String... actions) {
        for (String a : actions) {
            if (can(menuKey, a)) return true;
        }
        return false;
    }

    /**
     * Path-based check kept for the existing {@code @menuAccess.canAccess(...)}
     * annotations. Deprecated in favour of {@link #can(String, String)}: a path
     * is a routing detail, a menu_key is an identity.
     */
    @Deprecated
    public boolean canAccessPath(String menuPath) {
        return can(keyOf(menuPath), VIEW);
    }

    /**
     * True if the caller may view ANY screen in {@code category}. Backs the
     * cross-cutting endpoints that serve a strip shown on every screen of a
     * category (e.g. the shared MID/SID summary on the EXECUTIVE pages) rather
     * than one specific screen.
     */
    public boolean canCategory(String category) {
        if (category == null) return false;
        if (isSuperAdmin()) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long tenantId = TenantContext.getCurrentTenant();
        if (auth == null || !auth.isAuthenticated() || tenantId == null) return false;

        Set<String> keys = categoryKeys(category);
        if (keys.isEmpty()) return false;

        Set<String> perms = permissions(auth.getName(), tenantId.intValue());
        for (String k : keys) {
            if (perms.contains(k + ":" + VIEW)) return true;
        }
        return false;
    }

    /** Whole permission set for the current caller — used by /me/session. */
    public Set<String> currentPermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long tenantId = TenantContext.getCurrentTenant();
        if (auth == null || !auth.isAuthenticated() || tenantId == null) {
            return Collections.emptySet();
        }
        return permissionsFor(auth.getName(), tenantId.intValue(), isSuperAdmin());
    }

    /**
     * Permission set for an explicitly named user and tenant.
     *
     * <p>Login and tenant-switch need this: at that moment there is no
     * SecurityContext to read the caller from and no TenantContext yet, but the
     * response already has to carry the sidebar. Without it those two endpoints
     * would keep building menus from {@code sys_group_menu} and the sidebar
     * would disagree with every API guard — which is the bug this whole change
     * exists to remove.
     */
    public Set<String> permissionsFor(String username, int tenantId, boolean superAdmin) {
        if (username == null) return Collections.emptySet();
        if (superAdmin) {
            try {
                return new HashSet<>(jdbc.queryForList(
                        "SELECT perm_code FROM sys_permission", String.class));
            } catch (Exception e) {
                log.warn("[Perm] super-admin permission list failed: {}", e.getMessage());
                return Collections.emptySet();
            }
        }
        return permissions(username, tenantId);
    }

    /** Call after any write to groups, grants, modules or the catalog. */
    public void invalidate(Integer tenantId) {
        permCache.invalidateAll();
        versionCache.invalidateAll();
        categoryCache.invalidateAll();
        try {
            jdbc.update("INSERT INTO rbac_version (tenant_id, version, updated_at) " +
                        "VALUES (?, 1, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (tenant_id) DO UPDATE SET version = rbac_version.version + 1, " +
                        "updated_at = CURRENT_TIMESTAMP",
                        tenantId == null ? 0 : tenantId);
        } catch (Exception e) {
            log.warn("[Perm] version bump failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** '/business/loss-making' -> 'business.loss-making' — the same derivation the migration used. */
    public static String keyOf(String path) {
        if (path == null) return null;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.replace('/', '.');
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Set<String> permissions(String username, int tenantId) {
        long version = version(tenantId);
        String key = username + '|' + tenantId + '|' + version;
        Set<String> cached = permCache.getIfPresent(key);
        if (cached != null) return cached;

        Set<String> loaded = load(username, tenantId);
        permCache.put(key, loaded);
        return loaded;
    }

    private Set<String> load(String username, int tenantId) {
        try {
            if (useNewModel()) {
                List<String> rows = jdbc.queryForList(PERMS_SQL, String.class, username, tenantId);
                return new HashSet<>(rows);
            }
            return new HashSet<>(jdbc.queryForList(LEGACY_SQL, String.class, username, tenantId));
        } catch (Exception e) {
            // Fail closed. An empty set denies everything except the
            // super-admin bypass, which is checked before we get here.
            log.warn("[Perm] permission load failed for user={} tenant={}: {}",
                     username, tenantId, e.getMessage());
            return Collections.emptySet();
        }
    }

    /** menu_keys in a category. Catalog data, so it is cached for longer than a grant. */
    private Set<String> categoryKeys(String category) {
        Set<String> cached = categoryCache.getIfPresent(category);
        if (cached != null) return cached;
        Set<String> keys;
        try {
            keys = new HashSet<>(jdbc.queryForList(
                "SELECT menu_key FROM sys_menu WHERE category = ? AND menu_key IS NOT NULL",
                String.class, category));
        } catch (Exception e) {
            log.warn("[Perm] category key lookup failed for {}: {}", category, e.getMessage());
            return Collections.emptySet();
        }
        categoryCache.put(category, keys);
        return keys;
    }

    private long version(int tenantId) {
        Long v = versionCache.get(tenantId, t -> {
            try {
                Long tv = jdbc.queryForObject(
                    "SELECT COALESCE((SELECT version FROM rbac_version WHERE tenant_id = ?), " +
                    "                (SELECT version FROM rbac_version WHERE tenant_id = 0), 1)",
                    Long.class, t);
                return tv == null ? 1L : tv;
            } catch (Exception e) {
                return 1L;
            }
        });
        return v == null ? 1L : v;
    }

    /** Resolved once — the migration state of a running instance does not change. */
    private boolean useNewModel() {
        Boolean cachedFlag = newModelAvailable;
        if (cachedFlag != null) return cachedFlag;
        boolean present;
        try {
            present = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.v_user_permission') IS NOT NULL", Boolean.class));
        } catch (Exception e) {
            present = false;
        }
        if (!present) {
            log.warn("[Perm] v_user_permission absent — falling back to legacy sys_group_menu "
                   + "grants (VIEW only). Apply V2026_09_26_02.");
        }
        newModelAvailable = present;
        return present;
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
