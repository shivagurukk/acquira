package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.SysMenu;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.repository.UserTenantAccessRepository;
import com.acquira.common.security.PermissionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/users/me")
public class MenuController {

    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    private final UserRepository userRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;
    private final JdbcTemplate jdbc;
    private final PermissionEvaluator perm;
    private final com.acquira.core.service.MenuVisibilityService menuVisibilityService;

    public MenuController(UserRepository userRepository,
                          UserTenantAccessRepository userTenantAccessRepository,
                          JdbcTemplate jdbc,
                          PermissionEvaluator perm,
                          com.acquira.core.service.MenuVisibilityService menuVisibilityService) {
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.jdbc = jdbc;
        this.perm = perm;
        this.menuVisibilityService = menuVisibilityService;
    }


    // The screen catalog used to be seeded by an @PostConstruct array here.
    // It now lives in rbac/menu-catalog.json and is reconciled by
    // RbacCatalogSync, so the catalog has exactly one writer and every
    // environment reports its own drift at boot. This controller serves
    // menus; it no longer creates them.

    /**
     * GET /api/users/me/locale — per-tenant locale settings for the ACTIVE tenant,
     * readable by EVERY authenticated user (unlike /api/admin/settings, admin-only).
     * The frontend applies these to its shared date formatters the same way
     * currency already flows from the tenant row.
     * Keys (tenant_setting, optional):
     *   locale.date_format — DD/MM/YYYY | MM/DD/YYYY | YYYY-MM-DD | DD-MMM-YYYY
     *   locale.timezone    — IANA zone id, e.g. Asia/Bahrain (blank = browser default)
     * Whitelisted read: only locale.* keys are ever returned here.
     */
    @GetMapping("/locale")
    public ResponseEntity<?> getMyLocale() {
        Long tenantId = TenantContext.getCurrentTenant();
        Map<String, String> out = new java.util.HashMap<>();
        out.put("dateFormat", "DD/MM/YYYY");
        out.put("timezone", "");
        if (tenantId != null) {
            try {
                java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT setting_key, setting_value FROM tenant_setting " +
                    "WHERE tenant_id = ? AND setting_key IN ('locale.date_format','locale.timezone')",
                    tenantId);
                for (Map<String, Object> r : rows) {
                    String k = String.valueOf(r.get("setting_key"));
                    Object v = r.get("setting_value");
                    if (v == null || String.valueOf(v).isBlank()) continue;
                    if ("locale.date_format".equals(k)) out.put("dateFormat", String.valueOf(v).trim());
                    if ("locale.timezone".equals(k))    out.put("timezone", String.valueOf(v).trim());
                }
            } catch (Exception e) {
                log.debug("[MenuController] locale lookup failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/menus")
    public ResponseEntity<?> getMyMenus() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        Long tenantId = TenantContext.getCurrentTenant();

        if (tenantId == null) {
            log.debug("[MenuController] No tenant context for user={}", username);
            return ResponseEntity.ok(Collections.emptyList());
        }

        Optional<UserTenantAccess> accessOpt =
            userTenantAccessRepository.findByUserAndTenant_TenantId(user, tenantId);

        if (accessOpt.isEmpty()) {
            log.warn("[MenuController] No access for user={} tenant={}", username, tenantId);
            return ResponseEntity.status(403).body(Map.of("error", "Access denied for this tenant"));
        }

        UserTenantAccess access = accessOpt.get();
        if (access.getSysUserGroup() == null) {
            log.warn("[MenuController] No group for user={} tenant={}", username, tenantId);
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<SysMenu> menus = visibleMenus();

        log.debug("[MenuController] user={} group={} tenant={} menus={}",
            username, access.getSysUserGroup().getGroupName(), tenantId, menus.size());

        return ResponseEntity.ok(menus);
    }

    /**
     * GET /api/users/me/session — everything the shell needs to render, in one
     * call: the sidebar, the action permissions behind it, and the tenant's
     * locale. The frontend used to make three round trips before it could draw
     * anything, which the page-load audit flagged.
     *
     * <p>The permission list is what lets a button disappear rather than fail
     * on click: a Sales User with {@code sales.agents:VIEW} but not
     * {@code sales.agents:EDIT} sees the list and no Save. The server still
     * checks every call — this only stops the UI offering what it will refuse.
     */
    @GetMapping("/session")
    public ResponseEntity<?> getMySession() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        Long tenantId = TenantContext.getCurrentTenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", auth.getName());
        out.put("tenantId", tenantId);
        out.put("menus", tenantId == null ? List.of() : visibleMenus());
        out.put("permissions", perm.currentPermissions());
        out.put("locale", getMyLocale().getBody());
        return ResponseEntity.ok(out);
    }

    /**
     * The screens the caller may open, in sidebar order — resolved by the
     * shared service, so this endpoint, login and tenant-switch cannot drift
     * apart. Filtering is against the permission set rather than a
     * group-to-menu join, which is what brings the tenant's module entitlement
     * and any DENY into the sidebar.
     */
    private List<SysMenu> visibleMenus() {
        return menuVisibilityService.visibleMenusForCurrentUser();
    }
}
