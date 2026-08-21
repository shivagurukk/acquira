package com.acquira.core.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
import com.acquira.common.config.TenantContext;

@RestController
@RequestMapping("/api/reports/insight")
@PreAuthorize("@menuAccess.canAccess('/merchant/insight-hub')")
public class InsightController {

    private final JdbcTemplate jdbcTemplate;

    public InsightController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/generate")
    public Map<String, Object> generateReport(@RequestBody InsightFilterRequest request, Authentication auth) {
        Long tenantId = getTenantId(auth);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Base Where Clause Construction
        StringBuilder whereClause = new StringBuilder("WHERE s.tenant_id = ? ");
        
        applyDateFilter(whereClause, params, request);

        if (hasItems(request.getMcc())) {
            whereClause.append(" AND st.mcc IN (").append(placeholders(request.getMcc().size())).append(")");
            params.addAll(request.getMcc());
        }
        if (hasItems(request.getRm())) {
            whereClause.append(" AND m.sales_email IN (").append(placeholders(request.getRm().size())).append(")");
            params.addAll(request.getRm());
        }
        if (hasItems(request.getPartner())) {
            whereClause.append(" AND m.referral_partner IN (").append(placeholders(request.getPartner().size())).append(")");
            params.addAll(request.getPartner());
        }
        if ("OPT_IN".equalsIgnoreCase(request.getOptStatus())) {
            whereClause.append(" AND s.is_opt_in = TRUE");
        } else if ("OPT_OUT".equalsIgnoreCase(request.getOptStatus())) {
            whereClause.append(" AND (s.is_opt_in = FALSE OR s.is_opt_in IS NULL)");
        }
        if (hasItems(request.getMid())) {
            whereClause.append(" AND m.mid IN (").append(placeholders(request.getMid().size())).append(")");
            params.addAll(request.getMid());
        }
        // SID/TID are independent predicates. They used to be nested inside the
        // MID block, so a SID or TID filter sent WITHOUT a MID was silently
        // dropped and the report came back unfiltered.
        if (hasItems(request.getSid())) {
            whereClause.append(" AND st.sid IN (").append(placeholders(request.getSid().size())).append(")");
            params.addAll(request.getSid());
        }
        if (hasItems(request.getTid())) {
            whereClause.append(" AND t.tid IN (").append(placeholders(request.getTid().size())).append(")");
            params.addAll(request.getTid());
        }
        if (hasItems(request.getIntlLocal())) {
            whereClause.append(" AND UPPER(s.destination) IN (").append(placeholders(request.getIntlLocal().size())).append(")");
            request.getIntlLocal().forEach(v -> params.add(v.toUpperCase()));
        }
        if (hasItems(request.getCardType())) {
            whereClause.append(" AND UPPER(s.card_type) IN (").append(placeholders(request.getCardType().size())).append(")");
            request.getCardType().forEach(v -> params.add(v.toUpperCase()));
        }
        if (hasItems(request.getScheme())) {
            whereClause.append(" AND UPPER(s.card_scheme) IN (").append(placeholders(request.getScheme().size())).append(")");
            request.getScheme().forEach(v -> params.add(v.toUpperCase()));
        }
        if (hasItems(request.getPosEcom())) {
            whereClause.append(" AND UPPER(s.channel) IN (").append(placeholders(request.getPosEcom().size())).append(")");
            request.getPosEcom().forEach(v -> params.add(v.toUpperCase()));
        }

        // Count Query
        String countSql = """
            SELECT COUNT(*) 
            FROM (
                SELECT 1
                FROM sum_daily_insight s
                JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id
                LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id
                LEFT JOIN dim_terminal t ON s.terminal_id = t.terminal_id AND t.tenant_id = s.tenant_id
                REQ_WHERE
                GROUP BY m.mid, m.name, st.sid, t.tid, st.mcc, s.destination, s.card_type, s.channel, m.sales_email, s.is_opt_in, s.business_date
            ) as cnt
        """.replace("REQ_WHERE", whereClause.toString());

        long total = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);

        // Data Query
        String dataSql = """
            SELECT
                m.mid,
                m.name as merchant_name,
                st.sid,
                t.tid,
                st.mcc,
                s.destination as intl_local,
                s.card_type,
                s.channel as pos_ecom,
                m.sales_email as rm,
                CASE WHEN s.is_opt_in THEN 'Opt-In' ELSE 'Opt-Out' END as opt_status,
                s.business_date,
                SUM(s.total_txns) as total_txns,
                SUM(s.total_volume) as total_volume,
                SUM(s.total_msf) as total_msf
            FROM sum_daily_insight s
            JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id
            -- LEFT joins: store_id/terminal_id are NULLABLE on sum_daily_insight.
            -- Inner joins silently dropped e-com/aggregate lines with no store or
            -- terminal, under-reporting the Hub versus merchant-grain screens.
            LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id
            LEFT JOIN dim_terminal t ON s.terminal_id = t.terminal_id AND t.tenant_id = s.tenant_id
            REQ_WHERE
            GROUP BY
                m.mid, m.name, st.sid, t.tid, st.mcc,
                s.destination, s.card_type, s.channel, m.sales_email, s.is_opt_in, s.business_date
            ORDER BY s.business_date DESC, m.mid ASC
            LIMIT ? OFFSET ?
        """.replace("REQ_WHERE", whereClause.toString());

        int limit = request.getSize() != null ? request.getSize() : 50;
        int offset = (request.getPage() != null ? request.getPage() : 0) * limit;
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> data = jdbcTemplate.queryForList(dataSql, params.toArray());

        Map<String, Object> result = new HashMap<>();
        result.put("content", data);
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / limit));
        return result;
    }

    // --- Helpers ---

    private void applyDateFilter(StringBuilder sql, List<Object> params, InsightFilterRequest req) {
        String p = req.getDatePreset();
        if ("CURRENT_DAY".equalsIgnoreCase(p)) {
            sql.append(" AND s.business_date = CURRENT_DATE");
        } else if ("PREVIOUS_DAY".equalsIgnoreCase(p)) {
            sql.append(" AND s.business_date = CURRENT_DATE - INTERVAL '1 day'");
        } else if ("CURRENT_YEAR".equalsIgnoreCase(p)) {
            sql.append(" AND s.business_date >= DATE_TRUNC('year', CURRENT_DATE)");
        } else if ("PREVIOUS_YEAR".equalsIgnoreCase(p)) {
            sql.append(
                    " AND s.business_date >= DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') AND s.business_date < DATE_TRUNC('year', CURRENT_DATE)");
        } else if ("CUSTOM".equalsIgnoreCase(p) && req.getDateFrom() != null && req.getDateTo() != null) {
            sql.append(" AND s.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)");
            params.add(req.getDateFrom());
            params.add(req.getDateTo());
        } else {
            // Default to Today if missing
            sql.append(" AND s.business_date = CURRENT_DATE");
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
    public static class InsightFilterRequest {
        private String datePreset;
        private String dateFrom;
        private String dateTo;
        private List<String> mcc;
        private String optStatus; // ALL, OPT_IN, OPT_OUT
        private List<String> rm;
        private List<String> mid;
        private List<String> sid;
        private List<String> tid;
        private List<String> intlLocal;
        private List<String> cardType;
        private List<String> scheme;
        private List<String> posEcom;

        // Getters Setters
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

        public List<String> getMcc() {
            return mcc;
        }

        public void setMcc(List<String> mcc) {
            this.mcc = mcc;
        }

        public String getOptStatus() {
            return optStatus;
        }

        public void setOptStatus(String optStatus) {
            this.optStatus = optStatus;
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

        public List<String> getSid() {
            return sid;
        }

        public void setSid(List<String> sid) {
            this.sid = sid;
        }

        public List<String> getTid() {
            return tid;
        }

        public void setTid(List<String> tid) {
            this.tid = tid;
        }

        public List<String> getIntlLocal() {
            return intlLocal;
        }

        public void setIntlLocal(List<String> intlLocal) {
            this.intlLocal = intlLocal;
        }

        public List<String> getCardType() {
            return cardType;
        }

        public void setCardType(List<String> cardType) {
            this.cardType = cardType;
        }

        public List<String> getScheme() {
            return scheme;
        }

        public void setScheme(List<String> scheme) {
            this.scheme = scheme;
        }

        public List<String> getPosEcom() {
            return posEcom;
        }

        public void setPosEcom(List<String> posEcom) {
            this.posEcom = posEcom;
        }

        private List<String> partner;

        public List<String> getPartner() {
            return partner;
        }

        public void setPartner(List<String> partner) {
            this.partner = partner;
        }

        private Integer page;
        private Integer size;

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public Integer getSize() {
            return size;
        }

        public void setSize(Integer size) {
            this.size = size;
        }
    }
}
