package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.TenantContext;
import com.acquira.common.service.ChannelSql;
import com.acquira.common.service.ReportCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Volume &amp; Revenue Mix (/executive/volume-revenue-mix) — the Volume &amp;
 * Revenue detail (SID, MID, Merchant and the full fee stack) with THREE MORE
 * KEY COLUMNS: Destination, MCC (with its category) and Scheme. Every row is
 * one SID x MID x Destination x MCC x Scheme combination over the period, so
 * a store that took Visa domestic and Mastercard international shows as
 * separate rows that add up to its Volume &amp; Revenue row.
 *
 * SOURCE. sum_daily_full — the only summary that carries destination, MCC
 * (store MCC, stamped at rollup) and card scheme TOGETHER WITH merchant,
 * store and the real fee columns at settlement basis. Its total_volume IS the
 * settlement figure (store_base_currency_amount), i.e. the same number
 * sum_daily_terminal calls total_base_volume, so the TOTAL row here ties out
 * with the Volume &amp; Revenue total for the same period and channel.
 *
 * NO ANCILLARY. DCC acquirer share, rental and FX income have no
 * destination / MCC / scheme of their own, so this screen stops at net
 * MARGIN — there is no Net Spread column. The Volume &amp; Revenue screen
 * carries it at store grain.
 *
 * CHANNEL. POS / ECOM scope filters on channel_class normalised exactly as
 * ProductSummaryRepository and the V2026_09_08_01 backfill do (anything not
 * ECOM is POS), so the three scopes partition the book.
 *
 * AUTHORIZATION. Gated on the menu grant for this screen's path; the grant
 * IS the access control, not just sidebar visibility.
 */
@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/executive/volume-revenue-mix')")
public class VolumeRevenueMixController {

    public static final String MENU_PATH = "/executive/volume-revenue-mix";

    private final JdbcTemplate jdbcTemplate;
    private final ReportCache reportCache;
    private final CurrencyMeta currencyMeta;
    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    // Dimension expressions are server-side constants; request text never
    // becomes SQL. Destination comes in mixed case from different sources, so
    // 'Domestic' and 'DOMESTIC' are one row; blank -> UNKNOWN. card_scheme was
    // already normalised at rollup (blank -> card_type -> 'Unclassified').
    private static final String DEST_EXPR   = "COALESCE(NULLIF(UPPER(TRIM(f.destination)),''),'UNKNOWN')";
    private static final String MCC_EXPR    = "COALESCE(NULLIF(TRIM(f.mcc),''),'—')";
    private static final String SCHEME_EXPR = "COALESCE(NULLIF(TRIM(f.card_scheme),''),'Unclassified')";

    private static final Set<String> SORT_KEYS = Set.of(
            "txns", "volume", "msf", "interchange", "schemeFee", "ecomFee", "net", "margin",
            "name", "mid", "destination", "mcc", "scheme");

    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        // Warm the default first load: MTD, each channel scope.
        reportCacheWarmup.register("volume-revenue-mix", tenantId -> {
            for (String ch : List.of(ChannelSql.ALL, ChannelSql.POS, ChannelSql.ECOM)) {
                build(tenantId, "MTD", null, 0, 50, "volume", "desc", null, false, ch);
            }
        });
    }

    @GetMapping("/volume-revenue-mix")
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(defaultValue = "MTD") String mode,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "volume") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean export,
            @RequestParam(defaultValue = "ALL") String channel) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.status(403).body(Map.of("message",
                    "No tenant selected, or you do not have access to this tenant."));

        final String ch = ChannelSql.normalize(channel);
        // Canonical key components only (see BusinessController.getCeoVolumeRevenue
        // for why raw request text never reaches the cache key).
        String periodKey = (month != null && month.matches("\\d{4}-\\d{2}")) ? month
                : "YTD".equalsIgnoreCase(mode) ? "YTD"
                : "THIS_MONTH".equalsIgnoreCase(mode) ? "THIS_MONTH" : "MTD";
        String sortKey = SORT_KEYS.contains(sort) ? sort : "volume";
        String dirKey = "asc".equalsIgnoreCase(dir) ? "asc" : "desc";
        boolean cacheable = !export && (search == null || search.isBlank());
        if (!cacheable)
            return ResponseEntity.ok(build(tenantId, mode, month, page, size, sortKey, dirKey, search, export, ch));
        String key = "volRevMix:" + tenantId + ":" + periodKey
                + ":" + page + ":" + size + ":" + sortKey + ":" + dirKey + ":ch" + ch;
        return ResponseEntity.ok(reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> build(tenantId, mode, month, page, size, sortKey, dirKey, search, export, ch)));
    }

    private Map<String, Object> build(Long tenantId, String mode, String month,
            int page, int size, String sort, String dir, String search, boolean export, String ch) {

        // Period anchored to the table being read (same rule as Volume & Revenue).
        LocalDate eff = jdbcTemplate.query(
                "SELECT MAX(business_date) FROM sum_daily_full WHERE tenant_id = ?",
                rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate() : null, tenantId);
        if (eff == null) eff = LocalDate.now();

        LocalDate from, to;
        String resolvedMode;
        if (month != null && month.matches("\\d{4}-\\d{2}")) {
            LocalDate first = LocalDate.of(Integer.parseInt(month.substring(0, 4)),
                    Integer.parseInt(month.substring(5, 7)), 1);
            from = first; to = first.withDayOfMonth(first.lengthOfMonth()); resolvedMode = month;
        } else if ("YTD".equalsIgnoreCase(mode)) {
            from = eff.withDayOfYear(1); to = eff; resolvedMode = "YTD";
        } else if ("THIS_MONTH".equalsIgnoreCase(mode)) {
            from = eff.withDayOfMonth(1); to = eff.withDayOfMonth(eff.lengthOfMonth()); resolvedMode = "THIS_MONTH";
        } else {
            from = eff.withDayOfMonth(1); to = eff; resolvedMode = "MTD";
        }

        if (page < 0) page = 0;
        if (size < 1) size = 50;
        if (size > 500) size = 500;
        if (export) page = 0;

        final boolean chScoped = ChannelSql.isChannel(ch);

        boolean hasSearch = search != null && !search.isBlank();
        String searchTerm = hasSearch
                ? search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") : null;
        StringBuilder where = new StringBuilder("WHERE f.tenant_id = ? AND f.business_date BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId); params.add(Date.valueOf(from)); params.add(Date.valueOf(to));
        if (chScoped) {
            where.append("AND (CASE WHEN f.channel_class = 'ECOM' THEN 'ECOM' ELSE 'POS' END) = ? ");
            params.add(ch);
        }
        if (hasSearch) {
            // Same three keys as Volume & Revenue (name / MID / SID) plus the
            // three dimension columns and the MCC description.
            String[] preds = {
                    "m.name ILIKE ? ESCAPE '\\'", "m.mid ILIKE ? ESCAPE '\\'", "s.sid ILIKE ? ESCAPE '\\'",
                    DEST_EXPR + " ILIKE ? ESCAPE '\\'", MCC_EXPR + " ILIKE ? ESCAPE '\\'",
                    "rc.category ILIKE ? ESCAPE '\\'", SCHEME_EXPR + " ILIKE ? ESCAPE '\\'" };
            where.append("AND (").append(String.join(" OR ", preds)).append(") ");
            for (int i = 0; i < preds.length; i++) params.add("%" + searchTerm + "%");
        }

        String volExpr = "SUM(COALESCE(f.total_volume,0))";
        String netExpr = "SUM(COALESCE(f.total_net_revenue,0))";
        Map<String, String> sortCols = new LinkedHashMap<>();
        sortCols.put("txns",        "SUM(COALESCE(f.total_txns,0))");
        sortCols.put("volume",      volExpr);
        sortCols.put("msf",         "SUM(COALESCE(f.total_msf,0))");
        sortCols.put("interchange", "SUM(COALESCE(f.total_interchange,0))");
        sortCols.put("schemeFee",   "SUM(COALESCE(f.total_scheme_fee,0))");
        sortCols.put("ecomFee",     "SUM(COALESCE(f.total_ecom_fee,0))");
        sortCols.put("net",         netExpr);
        // NULL when volume is zero so those rows land under NULLS LAST, same as marginPct.
        sortCols.put("margin",      "CASE WHEN " + volExpr + " <> 0 THEN " + netExpr + " / " + volExpr + " END");
        sortCols.put("name",        "m.name");
        sortCols.put("mid",         "m.mid");
        sortCols.put("destination", DEST_EXPR);
        sortCols.put("mcc",         MCC_EXPR);
        sortCols.put("scheme",      SCHEME_EXPR);
        String orderExpr = sortCols.getOrDefault(sort, volExpr);
        String orderDir = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
        // Every sort expression above is non-unique; the full row key keeps
        // paging stable (a tied row never lands on two pages or on none).
        String tieBreak = ", m.mid ASC, s.sid ASC, " + DEST_EXPR + " ASC, " + MCC_EXPR + " ASC, " + SCHEME_EXPR + " ASC";

        // dim_merchant is an inner join exactly as on Volume & Revenue; store is
        // optional (AMS MID-only rows have no SID). ref_mcc_category is the
        // shared MCC description (same join as the Profitability Report).
        String base = "FROM sum_daily_full f "
                + "JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = f.tenant_id "
                + "LEFT JOIN dim_store s ON s.store_id = f.store_id AND s.tenant_id = f.tenant_id "
                + "LEFT JOIN ref_mcc_category rc ON rc.mcc = " + MCC_EXPR + " "
                + where
                + "GROUP BY m.mid, s.sid, m.name, " + DEST_EXPR + ", " + MCC_EXPR + ", " + SCHEME_EXPR + " ";

        String rowSql = "SELECT m.mid, s.sid, m.name, "
                + DEST_EXPR + " AS destination, " + MCC_EXPR + " AS mcc, MIN(rc.category) AS mcc_category, "
                + SCHEME_EXPR + " AS scheme, "
                + "SUM(COALESCE(f.total_txns,0)) AS txns, " + volExpr + " AS vol, "
                + "SUM(COALESCE(f.total_msf,0)) AS msf, SUM(COALESCE(f.total_interchange,0)) AS icf, "
                + "SUM(COALESCE(f.total_scheme_fee,0)) AS sf, SUM(COALESCE(f.total_ecom_fee,0)) AS pg, "
                + netExpr + " AS net "
                + base
                + "ORDER BY " + orderExpr + " " + orderDir + " NULLS LAST" + tieBreak
                + (export ? "" : " LIMIT ? OFFSET ?");
        List<Object> rowParams = new ArrayList<>(params);
        if (!export) { rowParams.add(size); rowParams.add((long) page * size); }

        List<Map<String, Object>> rows = jdbcTemplate.query(rowSql, rs -> {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mid", rs.getString("mid"));
                m.put("sid", rs.getString("sid"));
                m.put("name", rs.getString("name"));
                m.put("destination", rs.getString("destination"));
                m.put("mcc", rs.getString("mcc"));
                m.put("mccCategory", rs.getString("mcc_category"));
                m.put("scheme", rs.getString("scheme"));
                long txns = rs.getLong("txns");
                BigDecimal vol = nz(rs.getBigDecimal("vol"));
                BigDecimal net = nz(rs.getBigDecimal("net"));
                m.put("txns", txns);
                m.put("volume", vol);
                m.put("msf", nz(rs.getBigDecimal("msf")));
                m.put("interchange", nz(rs.getBigDecimal("icf")));
                m.put("schemeFee", nz(rs.getBigDecimal("sf")));
                m.put("ecomFee", nz(rs.getBigDecimal("pg")));
                m.put("netRevenue", net);
                m.put("marginPct", pct(net, vol));
                out.add(m);
            }
            return out;
        }, rowParams.toArray());

        // Row count + grand totals in one pass over the grouped aggregate.
        String totSql = "SELECT COUNT(*), COALESCE(SUM(x.c1),0), COALESCE(SUM(x.c2),0), COALESCE(SUM(x.c3),0), "
                + "COALESCE(SUM(x.c4),0), COALESCE(SUM(x.c5),0), COALESCE(SUM(x.c6),0), COALESCE(SUM(x.c7),0) FROM ("
                + "SELECT SUM(COALESCE(f.total_txns,0)) c1, " + volExpr + " c2, SUM(COALESCE(f.total_msf,0)) c3, "
                + "SUM(COALESCE(f.total_interchange,0)) c4, SUM(COALESCE(f.total_scheme_fee,0)) c5, "
                + "SUM(COALESCE(f.total_ecom_fee,0)) c6, " + netExpr + " c7 "
                + base + ") x";
        Map<String, Object> totals = new LinkedHashMap<>();
        long totalRows = jdbcTemplate.query(totSql, rs -> {
            if (!rs.next()) return 0L;
            BigDecimal tVol = nz(rs.getBigDecimal(3));
            BigDecimal tNet = nz(rs.getBigDecimal(8));
            totals.put("txns", rs.getLong(2));
            totals.put("volume", tVol);
            totals.put("msf", nz(rs.getBigDecimal(4)));
            totals.put("interchange", nz(rs.getBigDecimal(5)));
            totals.put("schemeFee", nz(rs.getBigDecimal(6)));
            totals.put("ecomFee", nz(rs.getBigDecimal(7)));
            totals.put("netRevenue", tNet);
            totals.put("marginPct", pct(tNet, tVol));
            return rs.getLong(1);
        }, params.toArray());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("effectiveDate", eff.toString());
        response.put("dataThrough", eff.toString());
        response.put("mode", resolvedMode);
        response.put("channel", ch);
        response.put("from", from.toString());
        response.put("to", to.toString());
        response.put("page", page);
        response.put("size", export ? rows.size() : size);
        response.put("totalRows", totalRows);
        response.put("totals", totals);
        response.put("rows", rows);
        return currencyMeta.attach(response, tenantId);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** NULL (not 0) when volume is zero — the UI renders an em-dash, never a green 0.00%. */
    private static BigDecimal pct(BigDecimal num, BigDecimal den) {
        return den.signum() != 0
                ? num.multiply(BigDecimal.valueOf(100)).divide(den, 2, RoundingMode.HALF_UP) : null;
    }
}
