package com.acquira.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single writer of the screen catalog.
 *
 * <p>Before this class there were two: the {@code @PostConstruct} array inside
 * {@code MenuController} and the {@code V2026_*_menu.sql} migrations. They
 * chased each other — the comments in that array were a running log of the
 * drift ("safety net for V…", "the migration deletes these, do not recreate").
 * Neither could be called authoritative, and no environment could be checked
 * against anything.
 *
 * <p>The catalog now lives in {@code rbac/menu-catalog.json}, the same way
 * clearing layouts live in {@code baseii-layouts.json} / {@code ipm-layouts.json}.
 * This runner reconciles the database to it at startup and reports what it
 * could not reconcile.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li><b>Insert missing, never update existing.</b> Migrations legitimately
 *       move screens between categories (V2026_08_17_01 moved Top Performers to
 *       EXECUTIVE) and reorder them. Overwriting from the catalog would undo
 *       those on every restart.</li>
 *   <li><b>Never revoke.</b> This runner only ever adds rows. A grant is
 *       removed by an admin action or a migration, never by a restart.</li>
 *   <li><b>A tenant's own decision wins.</b> The legacy {@code sys_group_menu}
 *       rows are mirrored into {@code tenant_group_perm} with
 *       ON CONFLICT DO NOTHING, so a tenant that has explicitly DENIED a screen
 *       keeps that DENY — the mirror cannot resurrect access.</li>
 *   <li><b>Degrade, don't fail.</b> On an environment where
 *       V2026_09_26_02 has not been applied yet, the new-model steps are
 *       skipped with a warning and the legacy path still works.</li>
 * </ul>
 */
@Component
public class RbacCatalogSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacCatalogSync.class);
    private static final String CATALOG = "rbac/menu-catalog.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RbacCatalogSync(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            JsonNode screens = loadCatalog();
            if (screens == null || !screens.isArray()) {
                log.error("[RbacSync] {} missing or malformed — catalog sync skipped", CATALOG);
                return;
            }

            int menusAdded = syncMenus(screens);
            backfillCatalogColumns();

            if (!hasTable("sys_permission")) {
                log.warn("[RbacSync] V2026_09_26_02 not applied (sys_permission absent) — "
                       + "legacy menu path only. {} screens in catalog, {} menu rows added.",
                         screens.size(), menusAdded);
                return;
            }

            int perms   = syncPermissions(screens);
            int modules = syncTenantModules();
            int groups  = syncTenantGroups();
            int mirror  = mirrorLegacyGrants();

            log.info("[RbacSync] catalog={} screens | +{} menus +{} permissions "
                   + "+{} tenant-modules +{} tenant-groups +{} mirrored grants",
                     screens.size(), menusAdded, perms, modules, groups, mirror);

            reportDrift(screens);

        } catch (Exception e) {
            // Never block startup on catalog reconciliation — a broken catalog
            // must not take the platform down, it must be loud in the log.
            log.error("[RbacSync] catalog sync failed: {}", e.getMessage(), e);
        }
    }

    // ── catalog ─────────────────────────────────────────────────────────────

    private JsonNode loadCatalog() throws Exception {
        ClassPathResource res = new ClassPathResource(CATALOG);
        if (!res.exists()) return null;
        try (InputStream in = res.getInputStream()) {
            return mapper.readTree(in).get("screens");
        }
    }

    /** Insert any catalog screen the database does not have. Returns rows added. */
    private int syncMenus(JsonNode screens) {
        int added = 0;
        for (JsonNode s : screens) {
            String path = text(s, "path");
            if (path == null || path.isBlank()) continue;

            added += jdbc.update(
                "INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) " +
                "SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = ?)",
                text(s, "name"), path, text(s, "icon"), text(s, "category"),
                s.path("order").asInt(999), path);

            // Grants for the legacy read path, for newly created rows only
            // (ON CONFLICT DO NOTHING means an admin who removed a grant keeps
            // it removed).
            JsonNode grantView = s.get("grantView");
            if (grantView != null && grantView.isArray()) {
                for (JsonNode g : grantView) {
                    jdbc.update(
                        "INSERT INTO sys_group_menu (group_id, menu_id) " +
                        "SELECT gr.group_id, m.menu_id FROM sys_user_group gr, sys_menu m " +
                        "WHERE gr.group_name = ? AND m.path = ? ON CONFLICT DO NOTHING",
                        g.asText(), path);
                }
            }
        }
        return added;
    }

    /**
     * A screen inserted by a migration that predates V2026_09_26_02 has no
     * menu_key / module_key. Derive them the same way the migration did, so a
     * migration author cannot accidentally create a screen the permission
     * model cannot address.
     */
    private void backfillCatalogColumns() {
        if (!hasColumn("sys_menu", "menu_key")) return;
        jdbc.update("UPDATE sys_menu SET menu_key = REPLACE(LTRIM(path, '/'), '/', '.') " +
                    "WHERE menu_key IS NULL AND path IS NOT NULL AND path <> ''");
        jdbc.update("UPDATE sys_menu SET module_key = " +
                    "LOWER(REPLACE(COALESCE(NULLIF(category, ''), 'CORE'), ' ', '_')) " +
                    "WHERE module_key IS NULL");
    }

    // ── new model ───────────────────────────────────────────────────────────

    /** Every screen gets VIEW; other actions come from the catalog. */
    private int syncPermissions(JsonNode screens) {
        int added = jdbc.update(
            "INSERT INTO sys_permission (perm_code, menu_key, action, description) " +
            "SELECT m.menu_key || ':VIEW', m.menu_key, 'VIEW', 'Open ' || m.menu_name " +
            "FROM sys_menu m WHERE m.menu_key IS NOT NULL " +
            "ON CONFLICT (perm_code) DO NOTHING");

        for (JsonNode s : screens) {
            String key = text(s, "key");
            JsonNode actions = s.get("actions");
            if (key == null || actions == null || !actions.isArray()) continue;
            for (JsonNode a : actions) {
                String action = a.asText();
                if ("VIEW".equals(action)) continue;
                added += jdbc.update(
                    "INSERT INTO sys_permission (perm_code, menu_key, action, description) " +
                    "SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_key = ?) " +
                    "ON CONFLICT (perm_code) DO NOTHING",
                    key + ":" + action, key, action, action + " on " + text(s, "name"), key);
            }
        }
        return added;
    }

    /** A new tenant, or a new module, defaults to enabled — entitlement is an
     *  explicit commercial decision, taken in the admin UI, not by a restart. */
    private int syncTenantModules() {
        return jdbc.update(
            "INSERT INTO tenant_module (tenant_id, module_key, enabled, updated_by) " +
            "SELECT t.tenant_id, m.module_key, TRUE, 'RbacCatalogSync' " +
            "FROM tenant t CROSS JOIN (SELECT DISTINCT module_key FROM sys_menu " +
            "                          WHERE module_key IS NOT NULL) m " +
            "ON CONFLICT (tenant_id, module_key) DO NOTHING");
    }

    private int syncTenantGroups() {
        return jdbc.update(
            "INSERT INTO tenant_group (tenant_id, group_id, is_system, created_by) " +
            "SELECT t.tenant_id, g.group_id, g.is_system, 'RbacCatalogSync' " +
            "FROM tenant t CROSS JOIN sys_user_group g WHERE g.owner_tenant_id IS NULL " +
            "ON CONFLICT (tenant_id, group_id) DO NOTHING");
    }

    /**
     * Legacy bridge: while migrations still seed new screens into
     * {@code sys_group_menu}, mirror those grants into the per-tenant model so
     * the two cannot diverge. Removed with {@code sys_group_menu} itself once
     * every seed writes {@code tenant_group_perm} directly.
     */
    private int mirrorLegacyGrants() {
        return jdbc.update(
            "INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by) " +
            "SELECT t.tenant_id, gm.group_id, m.menu_key || ':VIEW', 'ALLOW', 'RbacCatalogSync' " +
            "FROM tenant t CROSS JOIN sys_group_menu gm " +
            "JOIN sys_menu m ON m.menu_id = gm.menu_id " +
            "WHERE m.menu_key IS NOT NULL " +
            "  AND EXISTS (SELECT 1 FROM sys_permission p WHERE p.perm_code = m.menu_key || ':VIEW') " +
            "ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING");
    }

    // ── drift report ────────────────────────────────────────────────────────

    /**
     * What the catalog and the database disagree about. This is the whole point
     * of having a catalog: "Fee Plans is missing in UAT" becomes a log line at
     * boot instead of a support ticket a week later.
     */
    private void reportDrift(JsonNode screens) {
        Set<String> inCatalog = new LinkedHashSet<>();
        for (JsonNode s : screens) {
            String p = text(s, "path");
            if (p != null) inCatalog.add(p);
        }

        List<String> inDb = jdbc.queryForList(
            "SELECT path FROM sys_menu WHERE path IS NOT NULL ORDER BY path", String.class);

        List<String> onlyInDb = new ArrayList<>(inDb);
        onlyInDb.removeAll(inCatalog);

        List<String> onlyInCatalog = new ArrayList<>(inCatalog);
        onlyInCatalog.removeAll(inDb);

        if (!onlyInCatalog.isEmpty()) {
            log.warn("[RbacSync] DRIFT — in catalog, not created in DB: {}", onlyInCatalog);
        }
        if (!onlyInDb.isEmpty()) {
            log.warn("[RbacSync] DRIFT — in DB, absent from {}: {} "
                   + "(a migration added a screen without updating the catalog)",
                     CATALOG, onlyInDb);
        }

        Integer ungated = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu m WHERE m.menu_key IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.menu_key = m.menu_key)",
            Integer.class);
        if (ungated != null && ungated > 0) {
            log.warn("[RbacSync] DRIFT — {} screens have no permission row at all", ungated);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private boolean hasTable(String table) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasColumn(String table, String column) {
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = ?", Integer.class, table, column);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
