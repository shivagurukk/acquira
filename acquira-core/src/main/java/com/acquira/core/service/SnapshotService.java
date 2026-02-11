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

    // Called by Batch Job at end of day
    public void generateDailySnapshot(LocalDate businessDate) {
        try {
            // 1. Gather Key Metrics
            // In real app, run complex aggregations here
            Map<String, Object> dashboardData = new HashMap<>();
            dashboardData.put("totalVolume", fetchTotalVolume(businessDate));
            dashboardData.put("activeMerchants", fetchActiveMerchants(businessDate));
            dashboardData.put("topPerformingRegion", "North-East"); // Placeholder logic

            String json = objectMapper.writeValueAsString(dashboardData);

            // 2. Insert into Snapshot Table
            String sql = "INSERT INTO kpi_snapshot_daily (business_date, snapshot_type, json_data) VALUES (?, 'MAIN_DASHBOARD', ?::jsonb)";
            jdbcTemplate.update(sql, businessDate, json);

        } catch (Exception e) {
            e.printStackTrace(); // Log properly
        }
    }

    private Double fetchTotalVolume(LocalDate date) {
        // Query sum_daily_merchant
        return 1500000.00; // Mock return
    }

    private Integer fetchActiveMerchants(LocalDate date) {
        return 850;
    }

    // Called by Frontend API
    public String getLatestSnapshot() {
        String sql = "SELECT json_data FROM kpi_snapshot_daily ORDER BY generated_at DESC LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, String.class);
        } catch (Exception e) {
            return "{}"; // Empty JSON if no snapshot
        }
    }
}
