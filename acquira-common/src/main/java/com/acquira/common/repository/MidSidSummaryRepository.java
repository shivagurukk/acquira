package com.acquira.common.repository;

import com.acquira.common.service.ChannelSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active MID / SID counts for the shared executive header strip.
 *
 * MID = dim_merchant.mid, SID = dim_store.sid (codes, not surrogate keys),
 * resolved from sum_daily_full.merchant_id / store_id. This is the
 * one summary that carries BOTH grains together with the channel dimension, so
 * the counts are read straight off it rather than through
 * {@link ChannelSql#merchantDay} (which collapses to merchant-day and drops
 * store_id).
 *
 * "Active" = transacted in the window (total_txns &gt; 0), so an ancillary-only
 * merchant-day (e.g. a rental billed with no transactions) is NOT counted as an
 * active MID/SID. COUNT(DISTINCT …) ignores NULLs, so rows without a resolved
 * store_id simply do not contribute to the SID count.
 *
 * All date predicates are sargable bounds on business_date so the partitioned
 * summary prunes correctly. The channel filter interpolates only the two fixed
 * literals ChannelSql exposes ('POS' / 'ECOM'); ALL adds no predicate.
 */
@Repository
public class MidSidSummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public MidSidSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The tenant's latest loaded business date, or null when it has no data. */
    public LocalDate latestBusinessDate(Long tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_full WHERE tenant_id = ?",
                LocalDate.class, tenantId);
    }

    /**
     * Distinct active MID and SID counts for {@code [from, to]} (inclusive),
     * optionally scoped to a channel class.
     *
     * @param channel POS / ECOM / ALL (already normalized, or normalized here)
     * @return {mids, sids, from, to, channel}
     */
    public Map<String, Object> activeCounts(Long tenantId, LocalDate from, LocalDate to, String channel) {
        String ch = ChannelSql.normalize(channel);

        // Count business codes (dim_merchant.mid / dim_store.sid), not surrogate
        // keys: dim_* are unique only on internal_id, so one MID/SID code can own
        // several surrogate rows and COUNT(DISTINCT merchant_id) over-counts.
        // The inner DISTINCT collapses the summary to its few (merchant, store)
        // pairs before the dim joins. A surrogate with no dim row falls back to
        // its key so it is still counted once rather than silently dropped.
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT COALESCE(m.mid, 'id:' || s.merchant_id)) AS mids, "
                + "COUNT(DISTINCT CASE WHEN s.store_id IS NOT NULL "
                + "THEN COALESCE(st.sid, 'id:' || s.store_id) END) AS sids "
                + "FROM (SELECT DISTINCT merchant_id, store_id FROM sum_daily_full "
                + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? AND total_txns > 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(from);
        params.add(to);
        if (ChannelSql.isChannel(ch)) {
            // Fixed literal only — never request input — so no injection surface.
            sql.append(" AND channel_class = '").append(ch).append('\'');
        }
        sql.append(") s "
                + "LEFT JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = ? "
                + "LEFT JOIN dim_store st ON st.store_id = s.store_id AND st.tenant_id = ?");
        params.add(tenantId);
        params.add(tenantId);

        Map<String, Object> row = jdbcTemplate.queryForMap(sql.toString(), params.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mids", ((Number) row.getOrDefault("mids", 0L)).longValue());
        out.put("sids", ((Number) row.getOrDefault("sids", 0L)).longValue());
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("channel", ch);
        return out;
    }
}
