package com.acquira.common.repository;

import com.acquira.common.service.NetSpreadSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Net Spread dashboard reads — MERCHANT grain over sum_daily_merchant, which
 * carries both the transaction fee stack (rebuilt by SummaryPopulationService)
 * and the ancillary revenue columns (dcc_acquirer / dcc_merchant /
 * rental_amount, maintained by AncillarySql). Net Spread is DERIVED at read
 * time via {@link NetSpreadSql} — the one shared definition every executive
 * page uses — never stored:
 *
 *   net_spread = net margin + dcc_acquirer + rental_amount
 *
 * Net margin is the batch-computed total_margin (MSF − interchange − scheme
 * fee − ecom fee), with NetSpreadSql's 3-leg fallback for rows written before
 * the column existed; this repository never recomputes fees (same contract as
 * the Executive Daily Merchant page). dcc_merchant is carried as an
 * informational column — it is the merchant's money and is never added to
 * the spread.
 *
 * All date predicates are sargable bounds/IN lists on business_date (never
 * wrapped in a function) so the partitioned summary prunes correctly.
 */
@Repository
public class NetSpreadRepository {

    private final JdbcTemplate jdbcTemplate;

    public NetSpreadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Whitelisted sort keys -> SQL expression over the aggregated columns. */
    private static final Map<String, String> SORT_COLS = Map.ofEntries(
            Map.entry("volume", "vol"),
            Map.entry("count", "cnt"),
            Map.entry("msf", "msf"),
            Map.entry("icf", "icf"),
            Map.entry("sf", "sf"),
            Map.entry("pg", "pg"),
            Map.entry("nm", "nm"),
            Map.entry("dcc", "dcc_acquirer"),
            Map.entry("dccMerchant", "dcc_merchant"),
            Map.entry("rental", "rental"),
            Map.entry("spread", "spread"),
            Map.entry("name", "name"));

    private static final class Where {
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();
    }

    /**
     * Shared WHERE over sum_daily_merchant s JOIN dim_merchant m. Exactly one
     * of dateList / [rangeStart, rangeEnd] is set (the controller guarantees
     * it). search matches merchant name or MID; sidList narrows to merchants
     * owning those stores (Net Spread is merchant-grain, so a SID filter
     * selects the whole merchant).
     */
    private Where where(Long tenantId, List<LocalDate> dateList, LocalDate rangeStart, LocalDate rangeEnd,
                        String search, List<String> midList, List<String> sidList, String merchantName) {
        Where w = new Where();
        w.sql.append("WHERE s.tenant_id = ? AND m.tenant_id = s.tenant_id ");
        w.params.add(tenantId);
        if (rangeStart != null) {
            w.sql.append("AND s.business_date BETWEEN ? AND ? ");
            w.params.add(rangeStart);
            w.params.add(rangeEnd);
        } else {
            w.sql.append("AND s.business_date IN (");
            for (int i = 0; i < dateList.size(); i++) {
                if (i > 0) w.sql.append(',');
                w.sql.append('?');
                w.params.add(dateList.get(i));
            }
            w.sql.append(") ");
        }
        if (search != null && !search.isBlank()) {
            w.sql.append("AND (m.name ILIKE ? OR m.mid ILIKE ?) ");
            String like = "%" + search.trim() + "%";
            w.params.add(like);
            w.params.add(like);
        }
        if (midList != null && !midList.isEmpty()) {
            w.sql.append("AND m.mid IN (");
            for (int i = 0; i < midList.size(); i++) {
                if (i > 0) w.sql.append(',');
                w.sql.append('?');
                w.params.add(midList.get(i));
            }
            w.sql.append(") ");
        }
        if (sidList != null && !sidList.isEmpty()) {
            w.sql.append("AND EXISTS (SELECT 1 FROM dim_store st WHERE st.tenant_id = s.tenant_id "
                    + "AND st.merchant_id = s.merchant_id AND st.sid IN (");
            for (int i = 0; i < sidList.size(); i++) {
                if (i > 0) w.sql.append(',');
                w.sql.append('?');
                w.params.add(sidList.get(i));
            }
            w.sql.append(")) ");
        }
        if (merchantName != null && !merchantName.isBlank()) {
            w.sql.append("AND m.name ILIKE ? ");
            w.params.add("%" + merchantName.trim() + "%");
        }
        return w;
    }

    private static final String AGG_SELECT =
            "SELECT m.merchant_id AS merchant_id, m.mid AS mid, m.name AS name, "
            + "SUM(COALESCE(s.total_txns,0)) AS cnt, "
            + "SUM(COALESCE(s.total_base_volume,0)) AS vol, "
            + "SUM(COALESCE(s.total_msf,0)) AS msf, "
            + "SUM(COALESCE(s.total_interchange,0)) AS icf, "
            + "SUM(COALESCE(s.total_scheme_fee,0)) AS sf, "
            + "SUM(COALESCE(s.total_ecom_fee,0)) AS pg, "
            + NetSpreadSql.sumMargin("s") + " AS nm, "
            + "SUM(COALESCE(s.dcc_acquirer,0)) AS dcc_acquirer, "
            + "SUM(COALESCE(s.dcc_merchant,0)) AS dcc_merchant, "
            + "SUM(COALESCE(s.rental_amount,0)) AS rental, "
            + NetSpreadSql.sumSpread("s") + " AS spread "
            + "FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id ";

    /**
     * Per-merchant page. size < 0 = export (no pagination). Returns
     * {content: [...], totalElements: n}.
     */
    public Map<String, Object> getMerchants(Long tenantId, List<LocalDate> dateList,
            LocalDate rangeStart, LocalDate rangeEnd, String search,
            List<String> midList, List<String> sidList, String merchantName,
            String sort, String dir, int page, int size) {

        Where w = where(tenantId, dateList, rangeStart, rangeEnd, search, midList, sidList, merchantName);
        String orderCol = SORT_COLS.getOrDefault(sort, "spread");
        String orderDir = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT s.merchant_id) FROM sum_daily_merchant s "
                + "JOIN dim_merchant m ON m.merchant_id = s.merchant_id " + w.sql,
                Long.class, w.params.toArray());

        StringBuilder sql = new StringBuilder(AGG_SELECT).append(w.sql)
                .append("GROUP BY m.merchant_id, m.mid, m.name ")
                .append("ORDER BY ").append(orderCol).append(' ').append(orderDir)
                .append(" NULLS LAST, m.merchant_id ");
        List<Object> params = new ArrayList<>(w.params);
        if (size >= 0) {
            sql.append("LIMIT ? OFFSET ? ");
            params.add(size);
            params.add(page * size);
        }

        List<Map<String, Object>> content = jdbcTemplate.query(sql.toString(), (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("merchantId", rs.getLong("merchant_id"));
            r.put("mid", rs.getString("mid"));
            r.put("name", rs.getString("name"));
            r.put("count", rs.getLong("cnt"));
            r.put("volume", rs.getBigDecimal("vol"));
            r.put("msf", rs.getBigDecimal("msf"));
            r.put("icf", rs.getBigDecimal("icf"));
            r.put("sf", rs.getBigDecimal("sf"));
            r.put("pg", rs.getBigDecimal("pg"));
            r.put("nm", rs.getBigDecimal("nm"));
            r.put("dcc", rs.getBigDecimal("dcc_acquirer"));
            r.put("dccMerchant", rs.getBigDecimal("dcc_merchant"));
            r.put("rental", rs.getBigDecimal("rental"));
            java.math.BigDecimal nm = rs.getBigDecimal("nm");
            java.math.BigDecimal spread = rs.getBigDecimal("spread");
            r.put("spread", spread);
            r.put("rescued", nm != null && spread != null
                    && nm.signum() < 0 && spread.signum() >= 0);
            return r;
        }, params.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("totalElements", total);
        return out;
    }

    /**
     * Tenant totals for the selection: the fee stack plus ancillary and the
     * rescue counts (lossOnMargin = merchants negative on total_margin;
     * rescued = of those, spread >= 0).
     */
    public Map<String, Object> getTotals(Long tenantId, List<LocalDate> dateList,
            LocalDate rangeStart, LocalDate rangeEnd, String search,
            List<String> midList, List<String> sidList, String merchantName) {

        Where w = where(tenantId, dateList, rangeStart, rangeEnd, search, midList, sidList, merchantName);

        String sql = "SELECT COALESCE(SUM(cnt),0) cnt, COALESCE(SUM(vol),0) vol, COALESCE(SUM(msf),0) msf, "
                + "COALESCE(SUM(icf),0) icf, COALESCE(SUM(sf),0) sf, COALESCE(SUM(pg),0) pg, "
                + "COALESCE(SUM(nm),0) nm, COALESCE(SUM(dcc_acquirer),0) dcc, "
                + "COALESCE(SUM(dcc_merchant),0) dcc_merchant, COALESCE(SUM(rental),0) rental, "
                + "COALESCE(SUM(spread),0) spread, "
                + "COUNT(*) FILTER (WHERE nm < 0) loss_on_margin, "
                + "COUNT(*) FILTER (WHERE nm < 0 AND spread >= 0) rescued, "
                + "COUNT(*) FILTER (WHERE spread < 0) loss_on_spread, "
                + "COUNT(*) merchants "
                + "FROM (" + AGG_SELECT + w.sql + "GROUP BY m.merchant_id, m.mid, m.name) t";

        return jdbcTemplate.query(sql, rs -> {
            Map<String, Object> t = new LinkedHashMap<>();
            if (rs.next()) {
                t.put("count", rs.getLong("cnt"));
                t.put("volume", rs.getBigDecimal("vol"));
                t.put("msf", rs.getBigDecimal("msf"));
                t.put("icf", rs.getBigDecimal("icf"));
                t.put("sf", rs.getBigDecimal("sf"));
                t.put("pg", rs.getBigDecimal("pg"));
                t.put("nm", rs.getBigDecimal("nm"));
                t.put("dcc", rs.getBigDecimal("dcc"));
                t.put("dccMerchant", rs.getBigDecimal("dcc_merchant"));
                t.put("rental", rs.getBigDecimal("rental"));
                t.put("spread", rs.getBigDecimal("spread"));
                t.put("lossOnMargin", rs.getLong("loss_on_margin"));
                t.put("rescued", rs.getLong("rescued"));
                t.put("lossOnSpread", rs.getLong("loss_on_spread"));
                t.put("merchants", rs.getLong("merchants"));
            }
            return t;
        }, w.params.toArray());
    }

    /**
     * Per-day tenant series over [ctxStart, ctxEnd] (the month around the
     * selection) — feeds the ribbon and sparklines. One row per business_date
     * with the same measures as the totals.
     */
    public List<Map<String, Object>> getTrend(Long tenantId, LocalDate ctxStart, LocalDate ctxEnd,
            String search, List<String> midList, List<String> sidList, String merchantName) {

        Where w = where(tenantId, null, ctxStart, ctxEnd, search, midList, sidList, merchantName);

        String sql = "SELECT s.business_date AS d, "
                + "SUM(COALESCE(s.total_txns,0)) cnt, SUM(COALESCE(s.total_base_volume,0)) vol, "
                + "SUM(COALESCE(s.total_msf,0)) msf, SUM(COALESCE(s.total_interchange,0)) icf, "
                + "SUM(COALESCE(s.total_scheme_fee,0)) sf, SUM(COALESCE(s.total_ecom_fee,0)) pg, "
                + NetSpreadSql.sumMargin("s") + " nm, SUM(COALESCE(s.dcc_acquirer,0)) dcc, "
                + "SUM(COALESCE(s.rental_amount,0)) rental, "
                + NetSpreadSql.sumSpread("s") + " spread "
                + "FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id "
                + w.sql
                + "GROUP BY s.business_date ORDER BY s.business_date";

        return jdbcTemplate.query(sql, (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", rs.getDate("d").toLocalDate().toString());
            r.put("count", rs.getLong("cnt"));
            r.put("volume", rs.getBigDecimal("vol"));
            r.put("msf", rs.getBigDecimal("msf"));
            r.put("icf", rs.getBigDecimal("icf"));
            r.put("sf", rs.getBigDecimal("sf"));
            r.put("pg", rs.getBigDecimal("pg"));
            r.put("nm", rs.getBigDecimal("nm"));
            r.put("dcc", rs.getBigDecimal("dcc"));
            r.put("rental", rs.getBigDecimal("rental"));
            r.put("spread", rs.getBigDecimal("spread"));
            return r;
        }, w.params.toArray());
    }
}
