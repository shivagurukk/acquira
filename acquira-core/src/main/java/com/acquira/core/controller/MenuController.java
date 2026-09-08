package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.SysMenu;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.SysMenuRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.repository.UserTenantAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.PostConstruct;

import java.util.*;

@RestController
@RequestMapping("/api/users/me")
public class MenuController {

    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    private final UserRepository userRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;
    private final SysMenuRepository sysMenuRepository;
    private final JdbcTemplate jdbc;

    public MenuController(UserRepository userRepository,
                          UserTenantAccessRepository userTenantAccessRepository,
                          SysMenuRepository sysMenuRepository,
                          JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.sysMenuRepository = sysMenuRepository;
        this.jdbc = jdbc;
    }

    /**
     * Runs once on startup — guarantees ALL menus exist in sys_menu
     * AND are granted to Super Admin via sys_group_menu.
     * This is the final safety net regardless of data.sql / Flyway state.
     */
    @PostConstruct
    public void ensureMenusExist() {
        try {
            log.info("[MenuController] Running startup menu safety net...");

            // ── 1. Ensure all menus exist in sys_menu ────────────────────────
            String upsertMenu =
                "INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (path) DO NOTHING";

            Object[][] adminMenus = {
                // Unified Settings hub (mirrors V2026_07_11_02__settings_hub_menu.sql
                // as a startup safety net — the migration comment promises this).
                // display_order 0 puts it at the top of the ADMINISTRATION group.
                {"Settings",               "/settings",                 "Settings",    "ADMINISTRATION",   0},
                {"User Management",        "/users",                    "Users",       "ADMINISTRATION",   1},
                {"Bank Setup",             "/tenants",                  "Building",    "ADMINISTRATION",   2},
                {"Group Management",       "/admin/groups",             "Shield",      "ADMINISTRATION",   3},
                {"SMTP Settings",          "/admin/smtp-settings",      "Settings",    "ADMINISTRATION",   4},
                {"Audit Logs",             "/admin/audit-logs",         "ScrollText",  "ADMINISTRATION",   5},
                {"Backup & Restore",       "/admin/backups",            "Database",    "ADMINISTRATION",   6},
                {"S3 Report Storage",      "/admin/s3-settings",        "Cloud",       "ADMINISTRATION",   7},
                {"Data Migration",         "/admin/data-migration",     "DatabaseZap", "ADMINISTRATION",   8},
                {"Security Settings",      "/admin/security-settings",  "ShieldCheck", "ADMINISTRATION",   9},
                {"API Management",         "/admin/api-management",     "Code",        "ADMINISTRATION",  10},
                {"SSO Settings",           "/admin/sso-settings",       "Shield",      "ADMINISTRATION",  11},
                {"Upload Files",           "/upload",                   "Upload",      "OPERATIONS",       1},
                {"Server File Processor",  "/ops/server-file",          "HardDrive",   "OPERATIONS",       2},
                {"Batch Logs",             "/ops/batch-logs",           "Activity",    "OPERATIONS",       3},
                {"Email Manager",          "/business/emails",          "Mail",        "OPERATIONS",       4},
                {"Email Campaigns",        "/admin/email-campaigns",    "MailOpen",    "OPERATIONS",       5},
                {"Alerts & Notifications", "/admin/alerts",             "BellRing",    "OPERATIONS",       6},
                {"Integration Hub",        "/admin/integration",        "Cable",       "DATA INTEGRATION", 1},
                {"DB Connections",         "/admin/integration/connections","Database", "DATA INTEGRATION", 2},
                {"Report Configs",         "/admin/integration/reports","FileCode",    "DATA INTEGRATION", 3},
                {"Schedules",              "/admin/integration/schedules","Clock",     "DATA INTEGRATION", 4},
                {"Run History",            "/admin/integration/runs",   "ScrollText",  "DATA INTEGRATION", 5},
                // Business-analytics pages added post-seed are registered here so they
                // appear in the sidebar without a separate DB migration. display_order
                // 15 keeps Retention next to Attrition in the Business group.
                {"Retention Report",       "/business/retention",        "HeartHandshake","BUSINESS",      15},
                {"Forecasting",            "/business/forecasting",      "Gauge",        "BUSINESS",      16},
                // Moved to EXECUTIVE by V2026_08_17_01__executive_menu_group.sql
                {"Top Performers",         "/business/top-performers",   "Trophy",       "EXECUTIVE",      5},
                // Safety net for V2026_08_15_01__destination_dashboard_menu.sql
                {"Destination Dashboard",  "/business/destination-dashboard", "Globe",   "BUSINESS",      18},
                // Safety net for V2026_09_05_02__pricing_simulator_v2.sql — the
                // route existed since v1 but never had a sys_menu row, and the
                // v2 backend gates on @menuAccess for this path.
                {"Pricing Simulator",      "/business/pricing-simulator", "SlidersHorizontal", "BUSINESS", 19},
                // ── SALES suite ──────────────────────────────────────────────
                // Routes for all five screens exist in App.jsx, but only Team
                // Management and Leaderboard ever had sys_menu rows (from an
                // earlier seed) — Country Leads, Agent Directory and the
                // Hierarchy Explorer were unreachable from the sidebar. Register
                // the full set here, idempotently, so every environment gets
                // the complete Sales group on next startup.
                // Moved to EXECUTIVE by V2026_08_17_01__executive_menu_group.sql
                {"Sales Hierarchy",        "/sales/executive",           "LayoutDashboard", "EXECUTIVE",   6},
                // Safety net for V2026_08_19_02__executive_daily_merchant_menu.sql
                {"Daily Merchant Performance", "/executive/daily-merchant", "CalendarClock", "EXECUTIVE",  7},
                {"Sales Team Management",  "/sales/team-management",     "Users",        "SALES",          1},
                {"Country Leads",          "/sales/country-management",  "Globe",        "SALES",          2},
                {"Agent Directory",        "/sales/agents",              "Contact",      "SALES",          3},
                {"Sales Leaderboard",      "/sales/leaderboard",         "Trophy",       "SALES",          4},
                {"Sales Hierarchy",        "/sales/hierarchy",           "Network",      "SALES",          5},
            };

            for (Object[] row : adminMenus) {
                jdbc.update(upsertMenu, row[0], row[1], row[2], row[3], row[4]);
            }

            // ── 2. Grant ALL menus to Super Admin ────────────────────────────
            jdbc.update(
                "INSERT INTO sys_group_menu (group_id, menu_id) " +
                "SELECT g.group_id, m.menu_id " +
                "FROM sys_user_group g, sys_menu m " +
                "WHERE g.group_name = 'Super Admin' " +
                "ON CONFLICT DO NOTHING"
            );

            // ── 3. Grant ALL menus to Bank Admin (except sensitive ones) ─────
            jdbc.update(
                "INSERT INTO sys_group_menu (group_id, menu_id) " +
                "SELECT g.group_id, m.menu_id " +
                "FROM sys_user_group g, sys_menu m " +
                "WHERE g.group_name = 'Bank Admin' " +
                "  AND m.path NOT IN (" +
                "    '/admin/groups','/admin/smtp-settings','/admin/audit-logs'," +
                "    '/admin/backups','/admin/s3-settings','/admin/sso-settings'," +
                "    '/admin/data-migration','/admin/security-settings','/admin/api-management'," +
                // SUPER_ADMIN-only screens (route RoleGuard is SUPER_ADMIN): a Bank
                // Admin must not even see these in the sidebar. Added 2026-08-15 (E2E
                // RBAC-006u) — previously granted here, so the menu showed though the
                // route still redirected.
                "    '/tenants','/admin/tenant-provisioning','/admin/bin-management'," +
                "    '/admin/interchange-normalization'" +
                "  ) ON CONFLICT DO NOTHING"
            );

            // ── 4. Verify S3 menu is in DB ───────────────────────────────────
            Integer s3Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu m " +
                "JOIN sys_group_menu gm ON gm.menu_id = m.menu_id " +
                "JOIN sys_user_group g ON g.group_id = gm.group_id " +
                "WHERE m.path = '/admin/s3-settings' AND g.group_name = 'Super Admin'",
                Integer.class
            );

            log.info("[MenuController] Startup menu check complete. " +
                "S3 menu granted to Super Admin: {}", s3Count != null && s3Count > 0);

        } catch (Exception e) {
            log.error("[MenuController] Startup menu safety net failed: {}", e.getMessage(), e);
        }
    }

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

        Long groupId = access.getSysUserGroup().getGroupId();

        // ── Native SQL query — bypasses JPA cache entirely ───────────────────
        // Hits sys_group_menu + sys_menu directly from DB every request.
        // Newly inserted menus appear immediately without restart/re-login.
        List<SysMenu> menus = sysMenuRepository.findMenusByGroupId(groupId);

        log.debug("[MenuController] user={} group={} tenant={} menus={}",
            username, access.getSysUserGroup().getGroupName(), tenantId, menus.size());

        return ResponseEntity.ok(menus);
    }
}
