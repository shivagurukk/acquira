package com.acquira.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class BudgetService {

    private final JdbcTemplate jdbcTemplate;

    public BudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getBudgetVsActual(Integer institutionId, Integer monthKey) {
        // SQL to join Actuals (sum_monthly_bank) with Targets (bank_budget_target)
        String sql = """
                    SELECT
                        t.metric_type,
                        t.target_value,
                        CASE
                            WHEN t.metric_type = 'REVENUE' THEN s.total_revenue
                            WHEN t.metric_type = 'VOLUME' THEN s.total_volume
                            ELSE 0
                        END as actual_value,
                        CASE
                            WHEN t.metric_type = 'REVENUE' AND s.total_revenue >= t.target_value THEN 'GREEN'
                            WHEN t.metric_type = 'REVENUE' AND s.total_revenue >= (t.target_value * 0.9) THEN 'AMBER'
                            ELSE 'RED'
                        END as status
                    FROM bank_budget_target t
                    LEFT JOIN sum_monthly_bank s ON s.institution_id = t.institution_id AND s.month_key = t.month_key
                    WHERE t.institution_id = ? AND t.month_key = ?
                """;

        return jdbcTemplate.queryForList(sql, institutionId, monthKey);
    }

    // Function to auto-generate default budget if missing (Strategy: Last Year +
    // 10%)
    public void generateDraftBudget(Integer institutionId, Integer monthKey) {
        // Implementation logic
    }
}
