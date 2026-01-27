package com.acquira.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/finance")
@CrossOrigin(origins = "*")
public class FinanceAnalyticsController {

    private final JdbcTemplate jdbcTemplate;

    public FinanceAnalyticsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> getFinanceSummary(
            @RequestParam(defaultValue = "MONTH") String period, // TODAY, MONTH, YEAR, CUSTOM
            @RequestParam(defaultValue = "MONTH") String groupBy, // MONTH, DAY, MERCHANT
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Authentication authentication) {
        Long tenantId = getTenantId(authentication);
        if (tenantId == null)
            throw new RuntimeException("Unauthorized");

        String dateFilterSql;
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Date Logic
        if ("TODAY".equalsIgnoreCase(period)) {
            dateFilterSql = "sd.business_date = CURRENT_DATE";
        } else if ("MONTH".equalsIgnoreCase(period)) {
            // For Month view, we usually show trailing 12 months or just current month?
            // Existing logic was "This Month". Let's keep it but allow flexible range if
            // needed.
            dateFilterSql = "sd.business_date >= DATE_TRUNC('month', CURRENT_DATE) AND sd.business_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'";
        } else if ("YEAR".equalsIgnoreCase(period)) {
            dateFilterSql = "sd.business_date >= DATE_TRUNC('year', CURRENT_DATE) AND sd.business_date < DATE_TRUNC('year', CURRENT_DATE) + INTERVAL '1 year'";
        } else if ("PY".equalsIgnoreCase(period)) {
            dateFilterSql = "sd.business_date >= DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') AND sd.business_date < DATE_TRUNC('year', CURRENT_DATE)";
        } else if ("CUSTOM".equalsIgnoreCase(period) || (startDate != null && endDate != null)) {
            // Explicit range overrides period logic if provided (e.g. for drill down)
            dateFilterSql = "sd.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)";
            params.add(startDate);
            params.add(endDate);
        } else {
            dateFilterSql = "sd.business_date >= DATE_TRUNC('month', CURRENT_DATE) AND sd.business_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'";
        }

        String sql;

        if ("MERCHANT".equalsIgnoreCase(groupBy)) {
            // Level 3: Merchant Breakdown (using sum_daily_merchant)
            // We map available columns. Missing splits (MSF/Count per card type) will be 0.
            sql = """
                        SELECT
                            m.merchant_name as month_label, -- reusing label
                            MIN(sd.business_date) as sort_date,

                            -- Dom Debit (Volume only available)
                            0 as dom_debit_cnt,
                            SUM(sd.total_debit_prepaid_volume) as dom_debit_vol,
                            0 as dom_debit_msf,
                            0 as dom_debit_optin,

                            -- Dom Credit (Volume only available)
                            0 as dom_credit_cnt,
                            SUM(sd.total_credit_volume) as dom_credit_vol,
                            0 as dom_credit_msf,
                            0 as dom_credit_optin,

                            -- International (Inferred: Total - Debit - Credit)
                            0 as int_cnt,
                            (SUM(sd.total_volume) - SUM(COALESCE(sd.total_debit_prepaid_volume,0)) - SUM(COALESCE(sd.total_credit_volume,0))) as int_vol,
                            0 as int_msf,
                            0 as int_optin,

                            -- Totals
                            SUM(sd.total_volume) as total_vol,
                            SUM(sd.total_msf) as total_msf

                        FROM sum_daily_merchant sd
                        JOIN merchant m ON sd.merchant_id = m.merchant_id
                        WHERE sd.tenant_id = ? AND <DATE_FILTER>
                        GROUP BY m.merchant_name, sd.merchant_id
                        ORDER BY SUM(sd.total_volume) DESC
                    """
                    .replace("<DATE_FILTER>", dateFilterSql);
        } else if ("DAY".equalsIgnoreCase(groupBy)) {
            // Level 2: Daily Breakdown
            sql = """
                        SELECT
                            TO_CHAR(sd.business_date, 'YYYY-MM-DD') as month_label,
                            sd.business_date as sort_date,

                            -- Dom Debit
                            SUM(sd.dom_debit_cnt) as dom_debit_cnt,
                            SUM(sd.dom_debit_vol) as dom_debit_vol,
                            SUM(sd.dom_debit_msf) as dom_debit_msf,
                            SUM(sd.dom_debit_optin) as dom_debit_optin,

                            -- Dom Credit
                            SUM(sd.dom_credit_cnt) as dom_credit_cnt,
                            SUM(sd.dom_credit_vol) as dom_credit_vol,
                            SUM(sd.dom_credit_msf) as dom_credit_msf,
                            SUM(sd.dom_credit_optin) as dom_credit_optin,

                            -- International
                            SUM(sd.int_cnt) as int_cnt,
                            SUM(sd.int_vol) as int_vol,
                            SUM(sd.int_msf) as int_msf,
                            SUM(sd.int_optin) as int_optin,

                            -- Totals
                            SUM(sd.total_vol) as total_vol,
                            SUM(sd.total_msf) as total_msf

                        FROM sum_daily_finance sd
                        WHERE sd.tenant_id = ? AND <DATE_FILTER>
                        GROUP BY sd.business_date
                        ORDER BY sd.business_date DESC
                    """.replace("<DATE_FILTER>", dateFilterSql);

        } else {
            // Level 1: Month (Default)
            sql = """
                        SELECT
                            TO_CHAR(sd.business_date, 'Mon-YYYY') as month_label,
                            MIN(sd.business_date) as sort_date,

                            -- Dom Debit
                            SUM(sd.dom_debit_cnt) as dom_debit_cnt,
                            SUM(sd.dom_debit_vol) as dom_debit_vol,
                            SUM(sd.dom_debit_msf) as dom_debit_msf,
                            SUM(sd.dom_debit_optin) as dom_debit_optin,

                            -- Dom Credit
                            SUM(sd.dom_credit_cnt) as dom_credit_cnt,
                            SUM(sd.dom_credit_vol) as dom_credit_vol,
                            SUM(sd.dom_credit_msf) as dom_credit_msf,
                            SUM(sd.dom_credit_optin) as dom_credit_optin,

                            -- International
                            SUM(sd.int_cnt) as int_cnt,
                            SUM(sd.int_vol) as int_vol,
                            SUM(sd.int_msf) as int_msf,
                            SUM(sd.int_optin) as int_optin,

                            -- Totals
                            SUM(sd.total_vol) as total_vol,
                            SUM(sd.total_msf) as total_msf

                        FROM sum_daily_finance sd
                        WHERE sd.tenant_id = ? AND <DATE_FILTER>
                        GROUP BY TO_CHAR(sd.business_date, 'Mon-YYYY'), TO_CHAR(sd.business_date, 'YYYYMM')
                        ORDER BY TO_CHAR(sd.business_date, 'YYYYMM') DESC
                    """.replace("<DATE_FILTER>", dateFilterSql);
        }

        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    private Long getTenantId(Authentication authentication) {
        // Mock tenant ID for now or extract from Auth principal
        // In real app, this comes from SecurityContext holder mapped to user
        return 1L; // Hardcoded for demo parity with other controllers
    }
}
