package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports/filters")
public class ReportFilterController {

    private final JdbcTemplate jdbcTemplate;

    public ReportFilterController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private Long getTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            // Fallback: If no tenant context (e.g. during certain tests or if Auth filter
            // skipped),
            // strictly we should throw. But to allow basic controller test if context not
            // mocked:
            throw new RuntimeException("Tenant context is missing.");
        }
        return tenantId;
    }

    @GetMapping("/mcc")
    public List<Map<String, Object>> getMccs() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT mcc as value, mcc as label FROM dim_store WHERE tenant_id = ? AND mcc IS NOT NULL ORDER BY mcc",
                getTenantId());
    }

    @GetMapping("/rm")
    public List<Map<String, Object>> getRms() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT sales_email as value, sales_email as label FROM dim_merchant WHERE tenant_id = ? AND sales_email IS NOT NULL ORDER BY sales_email",
                getTenantId());
    }

    @GetMapping("/merchants")
    public List<Map<String, Object>> getMerchants() {
        // Limit to prevent UI overload.
        return jdbcTemplate.queryForList(
                "SELECT mid as value, name || ' (' || mid || ')' as label FROM dim_merchant WHERE tenant_id = ? ORDER BY name LIMIT 2000",
                getTenantId());
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> getStores(@RequestParam(required = false) List<String> mid) {
        Long tenantId = getTenantId();
        StringBuilder sql = new StringBuilder(
                "SELECT s.sid as value, s.name || ' (' || s.sid || ')' as label FROM dim_store s ");
        List<Object> params = new ArrayList<>();

        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id WHERE s.tenant_id = ? ");
        params.add(tenantId);

        if (mid != null && !mid.isEmpty()) {
            sql.append("AND m.mid IN (").append(placeholders(mid.size())).append(") ");
            params.addAll(mid);
        }

        sql.append("ORDER BY s.sid LIMIT 2000");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray(new Object[0]));
    }

    @GetMapping("/terminals")
    public List<Map<String, Object>> getTerminals(@RequestParam(required = false) List<String> sid) {
        Long tenantId = getTenantId();
        StringBuilder sql = new StringBuilder("SELECT t.tid as value, t.tid as label FROM dim_terminal t ");
        List<Object> params = new ArrayList<>();

        sql.append("JOIN dim_store s ON t.store_id = s.store_id AND s.tenant_id = t.tenant_id WHERE t.tenant_id = ? ");
        params.add(tenantId);

        if (sid != null && !sid.isEmpty()) {
            sql.append("AND s.sid IN (").append(placeholders(sid.size())).append(") ");
            params.addAll(sid);
        }

        sql.append("ORDER BY t.tid LIMIT 2000");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray(new Object[0]));
    }

    @GetMapping("/partners")
    public List<Map<String, Object>> getPartners() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT referral_partner as value, referral_partner as label FROM dim_merchant WHERE tenant_id = ? AND referral_partner IS NOT NULL ORDER BY referral_partner",
                getTenantId());
    }

    @GetMapping("/channels")
    public List<Map<String, Object>> getChannels() {
        // Querying summary table for active channels
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT channel as value, channel as label FROM sum_daily_insight WHERE tenant_id = ? AND channel IS NOT NULL ORDER BY channel",
                getTenantId());
    }

    private String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }
}
