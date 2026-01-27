package com.acquira.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
import com.acquira.config.TenantContext;

@RestController
@RequestMapping("/api/trends")
@CrossOrigin(origins = "*")
public class TrendsController {

    private final JdbcTemplate jdbcTemplate;

    public TrendsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/monthly")
    public List<Map<String, Object>> getMonthlyTrends(@RequestBody TrendsFilterRequest request, Authentication auth) {
        Long tenantId = getTenantId(auth);

        StringBuilder sql = new StringBuilder("""
                    SELECT
                        TO_CHAR(s.business_date, 'Month') as month_name,
                        TO_CHAR(s.business_date, 'MM') as month_num,
                        TO_CHAR(s.business_date, 'YYYY') as year,

                        SUM(s.total_txns) as count,
                        SUM(s.total_volume) as volume,
                        SUM(s.total_msf) as msf,
                        SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END) as opt_in_volume
                    FROM sum_daily_insight s
                    JOIN dim_merchant m ON s.merchant_id = m.merchant_id
                    JOIN dim_store st ON s.store_id = st.store_id
                    WHERE s.tenant_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        applyCommonFilters(sql, params, request);

        sql.append(
                """
                            GROUP BY TO_CHAR(s.business_date, 'Month'), TO_CHAR(s.business_date, 'MM'), TO_CHAR(s.business_date, 'YYYY')
                            ORDER BY TO_CHAR(s.business_date, 'YYYY'), TO_CHAR(s.business_date, 'MM')
                        """);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray(new Object[0]));
    }

    @PostMapping("/daily")
    public List<Map<String, Object>> getDailyTrends(@RequestBody TrendsFilterRequest request, Authentication auth) {
        Long tenantId = getTenantId(auth); // Mock

        StringBuilder sql = new StringBuilder("""
                    SELECT
                        s.business_date,
                        TO_CHAR(s.business_date, 'DD-Mon') as date_label,

                        SUM(s.total_txns) as count,
                        SUM(s.total_volume) as volume,
                        SUM(s.total_msf) as msf,
                        SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END) as opt_in_volume
                    FROM sum_daily_insight s
                    JOIN dim_merchant m ON s.merchant_id = m.merchant_id
                    JOIN dim_store st ON s.store_id = st.store_id
                    WHERE s.tenant_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Apply Drill-down specific filter (Month/Year)
        if (request.getMonth() != null && request.getYear() != null) {
            sql.append(" AND TO_CHAR(s.business_date, 'MM') = ? AND TO_CHAR(s.business_date, 'YYYY') = ?");
            // Ensure month is 2 digits
            String mm = request.getMonth().length() == 1 ? "0" + request.getMonth() : request.getMonth();
            params.add(mm);
            params.add(request.getYear());
        }

        applyCommonFilters(sql, params, request);

        sql.append("""
                    GROUP BY s.business_date
                    ORDER BY s.business_date ASC
                """);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray(new Object[0]));
    }

    // --- Helpers ---

    private void applyCommonFilters(StringBuilder sql, List<Object> params, TrendsFilterRequest req) {
        // Date Logic
        boolean dateFilterApplied = false;

        if ("CUSTOM".equalsIgnoreCase(req.getDatePreset()) && req.getDateFrom() != null && req.getDateTo() != null) {
            sql.append(" AND s.business_date BETWEEN ? AND ?");
            params.add(java.sql.Date.valueOf(req.getDateFrom())); // Start of day
            params.add(java.sql.Date.valueOf(req.getDateTo())); // End of day (strictly speaking, but Date type works
                                                                // for simple range)
            dateFilterApplied = true;
        } else if ("PREVIOUS_YEAR".equalsIgnoreCase(req.getDatePreset())) {
            sql.append(
                    " AND s.business_date >= date_trunc('year', current_date - interval '1 year') AND s.business_date < date_trunc('year', current_date)");
            dateFilterApplied = true;
        } else if ("CURRENT_YEAR".equalsIgnoreCase(req.getDatePreset())) {
            sql.append(" AND s.business_date >= date_trunc('year', current_date)");
            dateFilterApplied = true;
        }

        // Fallback to Year if no preset logic or explicit year provided
        if (!dateFilterApplied && req.getYear() != null && req.getMonth() == null) {
            sql.append(" AND TO_CHAR(s.business_date, 'YYYY') = ?");
            params.add(req.getYear());
        }

        // MCC
        if (hasItems(req.getMcc())) {
            sql.append(" AND st.mcc IN (").append(placeholders(req.getMcc().size())).append(")");
            params.addAll(req.getMcc());
        }

        // RM
        if (hasItems(req.getRm())) {
            sql.append(" AND m.sales_email IN (").append(placeholders(req.getRm().size())).append(")");
            params.addAll(req.getRm());
        }

        // MID
        if (hasItems(req.getMid())) {
            sql.append(" AND m.mid IN (").append(placeholders(req.getMid().size())).append(")");
            params.addAll(req.getMid());
        }

        // Opt Status
        if ("OPT_IN".equalsIgnoreCase(req.getOptStatus())) {
            sql.append(" AND s.is_opt_in = TRUE");
        } else if ("OPT_OUT".equalsIgnoreCase(req.getOptStatus())) {
            sql.append(" AND (s.is_opt_in = FALSE OR s.is_opt_in IS NULL)");
        }
    }

    private boolean hasItems(List<?> list) {
        return list != null && !list.isEmpty() && !list.contains("ALL");
    }

    private String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }

    private Long getTenantId(Authentication authentication) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context is missing or invalid.");
        }
        return tenantId;
    }

    // --- DTO ---
    public static class TrendsFilterRequest {
        private String year;
        private String month;
        private List<String> mcc;
        private List<String> rm;
        private List<String> mid;
        private String optStatus;

        // New Date Filters
        private String datePreset;
        private String dateFrom;
        private String dateTo;

        // Getters Setters
        public String getYear() {
            return year;
        }

        public void setYear(String year) {
            this.year = year;
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public List<String> getMcc() {
            return mcc;
        }

        public void setMcc(List<String> mcc) {
            this.mcc = mcc;
        }

        public List<String> getRm() {
            return rm;
        }

        public void setRm(List<String> rm) {
            this.rm = rm;
        }

        public List<String> getMid() {
            return mid;
        }

        public void setMid(List<String> mid) {
            this.mid = mid;
        }

        public String getOptStatus() {
            return optStatus;
        }

        public void setOptStatus(String optStatus) {
            this.optStatus = optStatus;
        }

        public String getDatePreset() {
            return datePreset;
        }

        public void setDatePreset(String datePreset) {
            this.datePreset = datePreset;
        }

        public String getDateFrom() {
            return dateFrom;
        }

        public void setDateFrom(String dateFrom) {
            this.dateFrom = dateFrom;
        }

        public String getDateTo() {
            return dateTo;
        }

        public void setDateTo(String dateTo) {
            this.dateTo = dateTo;
        }
    }
}
