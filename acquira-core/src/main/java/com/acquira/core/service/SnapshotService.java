package com.acquira.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class SnapshotService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SnapshotService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // Called by Batch Job at end of day — generates per-tenant snapshots.
    // tenantId is mandatory: a null tenant would sum sum_daily_merchant across
    // ALL tenants into one snapshot row.
    public void generateDailySnapshot(LocalDate businessDate, Long tenantId) {
        if (tenantId == null)
            throw new IllegalArgumentException("tenantId is required for daily snapshots");
        try {
            Map<String, Object> dashboardData = new HashMap<>();
            dashboardData.put("totalVolume", fetchTotalVolume(businessDate, tenantId));
            dashboardData.put("activeMerchants", fetchActiveMerchants(businessDate, tenantId));
            dashboardData.put("tenantId", tenantId);

            String json = objectMapper.writeValueAsString(dashboardData);

            // Insert into Snapshot Table (tenant-aware if column exists, safe fallback)
            try {
                jdbcTemplate.update(
                    "INSERT INTO kpi_snapshot_daily (business_date, snapshot_type, json_data, tenant_id) VALUES (?, 'MAIN_DASHBOARD', ?::jsonb, ?)",
                    businessDate, json, tenantId);
            } catch (Exception e) {
                // Fallback: table may not have tenant_id column yet
                jdbcTemplate.update(
                    "INSERT INTO kpi_snapshot_daily (business_date, snapshot_type, json_data) VALUES (?, 'MAIN_DASHBOARD', ?::jsonb)",
                    businessDate, json);
            }

        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SnapshotService.class)
                .error("Failed to generate daily snapshot for {}: {}", businessDate, e.getMessage(), e);
        }
    }

    private Double fetchTotalVolume(LocalDate date, Long tenantId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_volume), 0) FROM sum_daily_merchant WHERE business_date = ? AND tenant_id = ?",
                Double.class, date, tenantId);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Integer fetchActiveMerchants(LocalDate date, Long tenantId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT merchant_id) FROM sum_daily_merchant WHERE business_date = ? AND total_volume > 0 AND tenant_id = ?",
                Integer.class, date, tenantId);
        } catch (Exception e) {
            return 0;
        }
    }

    // Called by Frontend API — tenant-scoped: without the predicate this
    // returned whichever tenant happened to write last.
    public String getLatestSnapshot(Long tenantId) {
        if (tenantId == null) return "{}";
        String sql = "SELECT json_data FROM kpi_snapshot_daily WHERE tenant_id = ? ORDER BY generated_at DESC LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, tenantId);
        } catch (Exception e) {
            return "{}"; // Empty JSON if no snapshot
        }
    }
}
