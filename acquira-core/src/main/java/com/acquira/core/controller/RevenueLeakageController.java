package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.service.AuditService;
import com.acquira.common.service.RevenueLeakageDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Revenue-leakage / anomaly detection API.
 *
 * Reads/writes {@code revenue_leakage_flags}; detection itself lives in
 * {@link RevenueLeakageDetectionService}. Every query is explicitly
 * tenant-scoped via {@link TenantContext}.
 */
@RestController
@RequestMapping("/api/leakage")
// /business/revenue-leakage has no sys_menu row; matches the route's RoleGuard.
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class RevenueLeakageController {

    private final JdbcTemplate jdbc;
    private final RevenueLeakageDetectionService detector;
    private final AuditService auditService;

    public RevenueLeakageController(JdbcTemplate jdbc,
                                    RevenueLeakageDetectionService detector,
                                    AuditService auditService) {
        this.jdbc = jdbc;
        this.detector = detector;
        this.auditService = auditService;
    }

    /** List flags. status = OPEN (default) | RESOLVED | IGNORED | ALL. */
    @GetMapping("/flags")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(required = false) String checkType,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "500") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        StringBuilder sql = new StringBuilder(
            "SELECT flag_id, merchant_id, merchant_name, check_type, severity, details, business_date, " +
            "metric_value, baseline_value, delta_pct, est_monthly_impact, status, detected_at, resolved_at, resolved_by " +
            "FROM revenue_leakage_flags WHERE tenant_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !"ALL".equalsIgnoreCase(status)) {
            sql.append("AND status = ? ");
            args.add(status.toUpperCase());
        }
        if (checkType != null && !checkType.isBlank()) {
            sql.append("AND check_type = ? ");
            args.add(checkType.trim().toUpperCase());
        }
        if (severity != null && !severity.isBlank()) {
            sql.append("AND severity = ? ");
            args.add(severity.trim().toUpperCase());
        }
        sql.append("ORDER BY est_monthly_impact DESC NULLS LAST, detected_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 2000));

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("flag_id"));
            m.put("merchantId", rs.getLong("merchant_id"));
            m.put("merchantName", rs.getString("merchant_name"));
            m.put("checkType", rs.getString("check_type"));
            m.put("severity", rs.getString("severity"));
            m.put("details", rs.getString("details"));
            m.put("businessDate", rs.getDate("business_date"));
            m.put("metricValue", rs.getBigDecimal("metric_value"));
            m.put("baselineValue", rs.getBigDecimal("baseline_value"));
            m.put("deltaPct", rs.getBigDecimal("delta_pct"));
            m.put("estMonthlyImpact", rs.getBigDecimal("est_monthly_impact"));
            m.put("status", rs.getString("status"));
            m.put("detectedAt", rs.getTimestamp("detected_at"));
            m.put("resolvedAt", rs.getTimestamp("resolved_at"));
            m.put("resolvedBy", rs.getString("resolved_by"));
            return m;
        }, args.toArray());
        return ResponseEntity.ok(rows);
    }

    /** Headline counts + estimated revenue at risk, for the KPI cards. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> agg = jdbc.queryForMap(
            "SELECT COUNT(*) AS open_count, " +
            "COUNT(DISTINCT merchant_id) AS merchants_affected, " +
            "COALESCE(SUM(est_monthly_impact),0) AS total_impact, " +
            "COUNT(*) FILTER (WHERE severity IN ('CRITICAL','HIGH')) AS high_count " +
            "FROM revenue_leakage_flags WHERE tenant_id = ? AND status = 'OPEN'",
            tenantId);
        out.put("openCount", ((Number) agg.get("open_count")).longValue());
        out.put("merchantsAffected", ((Number) agg.get("merchants_affected")).longValue());
        out.put("totalEstImpact", agg.get("total_impact"));
        out.put("highCount", ((Number) agg.get("high_count")).longValue());

        Map<String, Object> bySeverity = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT severity, COUNT(*) AS c FROM revenue_leakage_flags " +
                "WHERE tenant_id = ? AND status='OPEN' GROUP BY severity", tenantId)) {
            bySeverity.put(String.valueOf(r.get("severity")), ((Number) r.get("c")).longValue());
        }
        out.put("bySeverity", bySeverity);

        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT check_type, COUNT(*) AS c FROM revenue_leakage_flags " +
                "WHERE tenant_id = ? AND status='OPEN' GROUP BY check_type", tenantId)) {
            byType.put(String.valueOf(r.get("check_type")), ((Number) r.get("c")).longValue());
        }
        out.put("byType", byType);

        try {
            out.put("lastDetectedAt", jdbc.queryForObject(
                "SELECT MAX(detected_at) FROM revenue_leakage_flags WHERE tenant_id = ?",
                java.sql.Timestamp.class, tenantId));
        } catch (Exception ignored) { out.put("lastDetectedAt", null); }

        return ResponseEntity.ok(out);
    }

    /** Run detection now for the current tenant. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        int n = detector.detectForTenant(tenantId);
        auditService.log("RUN_LEAKAGE_DETECTION", "Detected/updated " + n + " revenue-leakage flag(s)");
        return ResponseEntity.ok(Map.of("detected", n));
    }

    @PostMapping("/flags/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable Long id) {
        return setStatus(id, "RESOLVED");
    }

    @PostMapping("/flags/{id}/ignore")
    public ResponseEntity<?> ignore(@PathVariable Long id) {
        return setStatus(id, "IGNORED");
    }

    @PostMapping("/flags/{id}/reopen")
    public ResponseEntity<?> reopen(@PathVariable Long id) {
        return setStatus(id, "OPEN");
    }

    private ResponseEntity<?> setStatus(Long id, String status) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        String user = currentUser();
        boolean open = "OPEN".equals(status);
        int updated = jdbc.update(
            "UPDATE revenue_leakage_flags SET status = ?, is_resolved = ?, " +
            "resolved_at = " + (open ? "NULL" : "NOW()") + ", resolved_by = ? " +
            "WHERE flag_id = ? AND tenant_id = ?",
            status, !open, open ? null : user, id, tenantId);
        if (updated == 0) return ResponseEntity.notFound().build();
        auditService.log("UPDATE_LEAKAGE_FLAG", "Flag " + id + " -> " + status);
        return ResponseEntity.ok(Map.of("message", "Flag " + status.toLowerCase()));
    }

    private String currentUser() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "SYSTEM";
        }
    }
}
