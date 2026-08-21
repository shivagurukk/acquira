package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the super-admin-managed tenant provisioning scripts
 * (tenant_provision_script) against a tenant, in script_order, logging every
 * execution to tenant_provision_log.
 *
 * Called automatically after tenant creation (BankController.createBank,
 * AdminController.createTenant) and manually via
 * POST /api/admin/provision/run/{tenantId}.
 *
 * Placeholder substitution: values come EXCLUSIVELY from the tenant row
 * (never request free-text), so substitution is not an injection surface.
 * Supported: ${TENANT_ID} ${INSTITUTION_ID} ${BANK_SHORT_CODE}
 * ${BASE_CURRENCY} ${BANK_NAME}.
 *
 * Failure semantics: a script failure stops the chain unless the script row
 * has continue_on_error = true. Provisioning failures NEVER abort tenant
 * creation — callers invoke provision() after the tenant row is committed and
 * swallow exceptions (the log table is the record of what happened).
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final JdbcTemplate jdbc;

    public TenantProvisioningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Run all active provisioning scripts for a tenant. Returns a per-script
     * result list (scriptName, status, durationMs, error) for the caller/UI.
     */
    public List<Map<String, Object>> provision(Long tenantId, String executedBy) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (tenantId == null) return results;

        Map<String, Object> tenant;
        try {
            tenant = jdbc.queryForMap(
                    "SELECT tenant_id, institution_id, bank_name, bank_short_code, base_currency "
                            + "FROM tenant WHERE tenant_id = ?", tenantId);
        } catch (Exception e) {
            log.error("Provisioning aborted: tenant {} not found", tenantId);
            return results;
        }

        Map<String, String> placeholders = buildPlaceholders(tenant);

        List<Map<String, Object>> scripts = jdbc.queryForList(
                "SELECT script_id, script_name, script_sql, continue_on_error "
                        + "FROM tenant_provision_script WHERE is_active = TRUE "
                        + "ORDER BY script_order ASC, script_id ASC");

        for (Map<String, Object> script : scripts) {
            Long scriptId = ((Number) script.get("script_id")).longValue();
            String name = (String) script.get("script_name");
            String sql = substitute((String) script.get("script_sql"), placeholders);
            boolean continueOnError = Boolean.TRUE.equals(script.get("continue_on_error"));

            long start = System.currentTimeMillis();
            String status;
            String error = null;
            try {
                jdbc.execute(sql);
                status = "SUCCESS";
            } catch (Exception e) {
                status = "FAILED";
                error = truncate(e.getMessage(), 4000);
                log.error("Provisioning script '{}' failed for tenant {}: {}", name, tenantId, error);
            }
            long duration = System.currentTimeMillis() - start;

            logExecution(tenantId, scriptId, name, status, error, duration, executedBy);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scriptName", name);
            result.put("status", status);
            result.put("durationMs", duration);
            result.put("error", error);
            results.add(result);

            if ("FAILED".equals(status) && !continueOnError) {
                // Mark remaining scripts as skipped so the log tells the whole story.
                int idx = scripts.indexOf(script);
                for (int i = idx + 1; i < scripts.size(); i++) {
                    Map<String, Object> skipped = scripts.get(i);
                    String skippedName = (String) skipped.get("script_name");
                    logExecution(tenantId, ((Number) skipped.get("script_id")).longValue(),
                            skippedName, "SKIPPED", "Skipped: '" + name + "' failed", 0L, executedBy);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("scriptName", skippedName);
                    r.put("status", "SKIPPED");
                    r.put("durationMs", 0L);
                    r.put("error", "Skipped: '" + name + "' failed");
                    results.add(r);
                }
                break;
            }
        }
        return results;
    }

    private Map<String, String> buildPlaceholders(Map<String, Object> tenant) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("${TENANT_ID}", String.valueOf(tenant.get("tenant_id")));
        p.put("${INSTITUTION_ID}", sqlLiteralSafe(tenant.get("institution_id")));
        p.put("${BANK_SHORT_CODE}", sqlLiteralSafe(tenant.get("bank_short_code")));
        p.put("${BASE_CURRENCY}", sqlLiteralSafe(tenant.get("base_currency")));
        p.put("${BANK_NAME}", sqlLiteralSafe(tenant.get("bank_name")));
        return p;
    }

    /**
     * Values are substituted INSIDE single-quoted literals in the script text,
     * so any single quote in a tenant field (e.g. a bank name like O'Neil) must
     * be doubled to stay a valid literal.
     */
    private String sqlLiteralSafe(Object value) {
        if (value == null) return "";
        return String.valueOf(value).replace("'", "''");
    }

    private String substitute(String sql, Map<String, String> placeholders) {
        String out = sql;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private void logExecution(Long tenantId, Long scriptId, String scriptName, String status,
                              String error, Long durationMs, String executedBy) {
        try {
            jdbc.update("INSERT INTO tenant_provision_log "
                            + "(tenant_id, script_id, script_name, status, error_message, duration_ms, executed_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    tenantId, scriptId, scriptName, status, error, durationMs, executedBy);
        } catch (Exception e) {
            log.error("Failed to write tenant_provision_log row for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
