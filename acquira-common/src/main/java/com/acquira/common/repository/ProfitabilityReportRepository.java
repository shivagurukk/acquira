package com.acquira.common.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profitability Report reads — the finance team's monthly MID P&amp;L at the grain
 *
 *   Month x MID x Merchant Type (channel) x MCC Category x Destination x Scheme
 *   x Card Interchange (the interchange rule that priced the row)
 *
 * This grain does not exist in any summary table, so the report reads
 * fact_transaction for ONE calendar month (partition-pruned by payment_date).
 * Fees are the fee engine's stored columns — interchange_fee, scheme_fee,
 * ecom_fee — never re-priced here, so the month ties out with the Net Spread
 * and Product Summary pages.
 *
 * Column map (all money in the tenant's settlement currency):
 * <ul>
 *   <li>Volume      = SUM(store_base_currency_amount)</li>
 *   <li>MSF / IC    = SUM(msf) / SUM(interchange_fee)</li>
 *   <li>Net revenue = MSF - IC</li>
 *   <li>Scheme Fees = SUM(scheme_fee)</li>
 *   <li>MPGS Fees   = SUM(ecom_fee) on ECOM rows whose terminal type is MPGS or
 *                     blank (the fee engine prices an unlisted ECOM gateway at the
 *                     MPGS default — business rule 2026-08-28). ECOM fee on any
 *                     other named gateway is carried as otherPg and still
 *                     deducted from net margin.</li>
 *   <li>PIE MSF Rebate/Recovery, KFCA Processing Fee, KFCA Per Txn Margin,
 *       IC++ Billing = profitability_adjustment_rate terms applied per row
 *       (see {@link #applyAdjustments}).</li>
 *   <li>Net Margin  = Net revenue - Scheme - MPGS - otherPg - PIE - KFCA fee
 *                     + KFCA margin + IC++ billing</li>
 * </ul>
 *
 * "Merchant Type" is the transaction channel (POS / ECOM) the fee engine
 * resolved from terminal_channel_map; dim_merchant carries no merchant-type
 * attribute of its own. "MCC Category" is ref_mcc_category over the STORE's
 * MCC (falling back to the merchant's), the same rule the Industry page uses.
 * "Scheme" is the ref_card_scheme group (Visa / MasterCard / ...) resolved the
 * way the fee engine does it: product code first, network name second.
 */
@Repository
public class ProfitabilityReportRepository {

    public static final String ADJ_PIE_MSF_REBATE     = "PIE_MSF_REBATE";
    public static final String ADJ_KFCA_PROCESSING    = "KFCA_PROCESSING_FEE";
    public static final String ADJ_KFCA_PER_TXN       = "KFCA_PER_TXN_MARGIN";
    public static final String ADJ_ICPP_BILLING       = "ICPP_BILLING";
    public static final List<String> ADJ_CODES = List.of(
            ADJ_PIE_MSF_REBATE, ADJ_KFCA_PROCESSING, ADJ_KFCA_PER_TXN, ADJ_ICPP_BILLING);

    /** Hard cap on grouped rows returned to the UI/CSV for one month. */
    public static final int MAX_ROWS = 50_000;

    private final JdbcTemplate jdbcTemplate;

    public ProfitabilityReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // Scheme-group resolution (mirror of FeeComputationService's two-tier
    // ref_card_scheme match, applied to the GROUPED rows, not per fact row)
    // ------------------------------------------------------------------
    private record SchemeRef(String code, String name, String group) {}

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase().trim().replace(" ", "");
    }

    private List<SchemeRef> loadSchemes() {
        return jdbcTemplate.query(
                "SELECT code, name, group_name FROM ref_card_scheme WHERE COALESCE(is_active, TRUE) ORDER BY id",
                (rs, i) -> new SchemeRef(norm(rs.getString(1)), norm(rs.getString(2)), rs.getString(3)));
    }

    private static String resolveScheme(List<SchemeRef> refs, String productCode, String cardScheme) {
        String pc = norm(productCode), sc = norm(cardScheme);
        if (!pc.isEmpty()) {
            for (SchemeRef r : refs) if (r.code.equals(pc) || r.name.equals(pc)) return r.group;
        }
        if (!sc.isEmpty()) {
            for (SchemeRef r : refs) if (r.code.equals(sc) || r.name.equals(sc)) return r.group;
        }
        return (cardScheme == null || cardScheme.isBlank()) ? "Unknown" : cardScheme.trim();
    }

    // ------------------------------------------------------------------
    // Adjustment rates
    // ------------------------------------------------------------------
    public record AdjRate(long id, String mid, String code, BigDecimal pctOfVolume, BigDecimal pctOfMsf,
                          BigDecimal perTxn, LocalDate from, LocalDate to, String note) {}

    public List<Map<String, Object>> listAdjustments(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT id, mid, adj_code, pct_of_volume, pct_of_msf, per_txn_amount, effective_from, effective_to, "
              + "note, updated_by, updated_at FROM profitability_adjustment_rate WHERE tenant_id = ? "
              + "ORDER BY adj_code, (mid IS NOT NULL), mid, effective_from",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("mid", rs.getString("mid"));
                    m.put("code", rs.getString("adj_code"));
                    m.put("pctOfVolume", rs.getBigDecimal("pct_of_volume"));
                    m.put("pctOfMsf", rs.getBigDecimal("pct_of_msf"));
                    m.put("perTxn", rs.getBigDecimal("per_txn_amount"));
                    m.put("effectiveFrom", rs.getObject("effective_from", LocalDate.class));
                    m.put("effectiveTo", rs.getObject("effective_to", LocalDate.class));
                    m.put("note", rs.getString("note"));
                    m.put("updatedBy", rs.getString("updated_by"));
                    m.put("updatedAt", rs.getTimestamp("updated_at"));
                    return m;
                }, tenantId);
    }

    public long upsertAdjustment(Long tenantId, Long id, String mid, String code, BigDecimal pctOfVolume,
                                 BigDecimal pctOfMsf, BigDecimal perTxn, LocalDate from, LocalDate to,
                                 String note, String user) {
        if (!ADJ_CODES.contains(code)) throw new IllegalArgumentException("Unknown adjustment code: " + code);
        String m = (mid == null || mid.isBlank()) ? null : mid.trim();
        LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
        if (id != null) {
            int n = jdbcTemplate.update(
                    "UPDATE profitability_adjustment_rate SET mid=?, adj_code=?, pct_of_volume=?, pct_of_msf=?, "
                  + "per_txn_amount=?, effective_from=?, effective_to=?, note=?, updated_by=?, updated_at=CURRENT_TIMESTAMP "
                  + "WHERE id=? AND tenant_id=?",
                    m, code, nz(pctOfVolume), nz(pctOfMsf), nz(perTxn), f, to, note, user, id, tenantId);
            if (n == 1) return id;
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO profitability_adjustment_rate (tenant_id, mid, adj_code, pct_of_volume, pct_of_msf, "
              + "per_txn_amount, effective_from, effective_to, note, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class, tenantId, m, code, nz(pctOfVolume), nz(pctOfMsf), nz(perTxn), f, to, note, user);
    }

    public int deleteAdjustment(Long tenantId, long id) {
        return jdbcTemplate.update("DELETE FROM profitability_adjustment_rate WHERE id=? AND tenant_id=?", id, tenantId);
    }

    /** Rates in force for any day of the month; MID-specific rows later win in {@link #pick}. */
    private List<AdjRate> loadRates(Long tenantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query(
                "SELECT id, mid, adj_code, pct_of_volume, pct_of_msf, per_txn_amount, effective_from, effective_to, note "
              + "FROM profitability_adjustment_rate WHERE tenant_id = ? AND effective_from <= ? "
              + "AND (effective_to IS NULL OR effective_to >= ?) ORDER BY effective_from DESC",
                (rs, i) -> new AdjRate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), rs.getString(9)),
                tenantId, end, start);
    }

    /** MID override beats tenant default; among equals the latest effective_from (already sorted) wins. */
    private static AdjRate pick(List<AdjRate> rates, String code, String mid) {
        AdjRate dflt = null;
        for (AdjRate r : rates) {
            if (!code.equals(r.code())) continue;
            if (r.mid() != null) { if (r.mid().equalsIgnoreCase(mid)) return r; }
            else if (dflt == null) dflt = r;
        }
        return dflt;
    }

    private static BigDecimal amount(AdjRate r, BigDecimal vol, BigDecimal msf, long cnt) {
        if (r == null) return BigDecimal.ZERO;
        return nz(vol).multiply(nz(r.pctOfVolume()))
                .add(nz(msf).multiply(nz(r.pctOfMsf())))
                .add(nz(r.perTxn()).multiply(BigDecimal.valueOf(cnt)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    /**
     * One month of grouped rows plus totals. {@code search} (optional) is a
     * partial match on merchant name or MID; {@code mids} narrows to those MIDs.
     */
    public Map<String, Object> getReport(Long tenantId, YearMonth month, String search, List<String> mids) {
        LocalDate start = month.atDay(1);
        LocalDate endExcl = month.plusMonths(1).atDay(1);
        List<Object> params = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
            "SELECT m.mid, m.name, "
          + "  COALESCE(NULLIF(UPPER(TRIM(ft.channel)),''), 'UNMAPPED')          AS merchant_type, "
          + "  COALESCE(rc.category, 'Unmapped')                                 AS mcc_category, "
          + "  COALESCE(NULLIF(UPPER(TRIM(ft.destination)),''), 'UNKNOWN')       AS destination, "
          + "  COALESCE(NULLIF(TRIM(ft.card_product_code),''), '')               AS product_code, "
          + "  COALESCE(NULLIF(TRIM(ft.card_scheme),''), '')                     AS card_scheme, "
          // Card interchange = the rule label the fee engine stamped on the row;
          // when a rule has no label, build one from its match columns; when the
          // row was never priced (feed interchange kept), say so with the card type.
          + "  COALESCE(NULLIF(TRIM(irl.label),''), "
          + "    CASE WHEN irl.id IS NOT NULL THEN CONCAT_WS(' ', irl.scheme_group, INITCAP(LOWER(irl.dest)), irl.channel, "
          + "                                       INITCAP(LOWER(irl.card_type)), irl.tier, irl.mcc_sector) "
          + "         ELSE 'Feed interchange' || COALESCE(' (' || INITCAP(LOWER(NULLIF(TRIM(ft.card_type),''))) || ')', '') END) "
          + "                                                                    AS card_interchange, "
          + "  COUNT(*)                                                          AS cnt, "
          + "  SUM(COALESCE(ft.store_base_currency_amount,0))                    AS vol, "
          + "  SUM(COALESCE(ft.msf,0))                                           AS msf, "
          + "  SUM(COALESCE(ft.interchange_fee,0))                               AS ic, "
          + "  SUM(COALESCE(ft.scheme_fee,0))                                    AS sf, "
          + "  SUM(CASE WHEN UPPER(TRIM(COALESCE(ft.channel,''))) = 'ECOM' "
          + "            AND UPPER(TRIM(COALESCE(dt.type,''))) IN ('MPGS','') THEN COALESCE(ft.ecom_fee,0) ELSE 0 END) AS mpgs, "
          + "  SUM(CASE WHEN UPPER(TRIM(COALESCE(ft.channel,''))) = 'ECOM' "
          + "            AND UPPER(TRIM(COALESCE(dt.type,''))) NOT IN ('MPGS','') THEN COALESCE(ft.ecom_fee,0) ELSE 0 END) AS other_pg "
          + "FROM fact_transaction ft "
          + "JOIN dim_merchant m ON m.merchant_id = ft.merchant_id AND m.tenant_id = ft.tenant_id "
          + "LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id "
          + "LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id "
          + "LEFT JOIN ref_mcc_category rc ON rc.mcc = COALESCE(NULLIF(TRIM(ds.mcc),''), NULLIF(TRIM(m.mcc),'')) "
          + "LEFT JOIN interchange_rate_local irl ON irl.id = ft.interchange_rule_id "
          + "WHERE ft.tenant_id = ? AND ft.payment_date >= ? AND ft.payment_date < ? ");
        params.add(tenantId);
        params.add(start.atStartOfDay());
        params.add(endExcl.atStartOfDay());

        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append("AND (m.name ILIKE ? ESCAPE '\\' OR m.mid ILIKE ? ESCAPE '\\') ");
            params.add(like);
            params.add(like);
        }
        if (mids != null && !mids.isEmpty()) {
            sql.append("AND m.mid IN (");
            for (int i = 0; i < mids.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                params.add(mids.get(i));
            }
            sql.append(") ");
        }
        sql.append("GROUP BY 1,2,3,4,5,6,7,8 ORDER BY m.name, m.mid, 5, 7, 8 LIMIT ").append(MAX_ROWS + 1);

        List<SchemeRef> schemes = loadSchemes();
        List<AdjRate> rates = loadRates(tenantId, start, month.atEndOfMonth());
        String monthName = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + " " + month.getYear();

        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), (rs, i) -> {
            long cnt = rs.getLong("cnt");
            BigDecimal vol = nz(rs.getBigDecimal("vol"));
            BigDecimal msf = nz(rs.getBigDecimal("msf"));
            BigDecimal ic = nz(rs.getBigDecimal("ic"));
            BigDecimal sf = nz(rs.getBigDecimal("sf"));
            BigDecimal mpgs = nz(rs.getBigDecimal("mpgs"));
            BigDecimal otherPg = nz(rs.getBigDecimal("other_pg"));
            String mid = rs.getString("mid");

            BigDecimal pie = amount(pick(rates, ADJ_PIE_MSF_REBATE, mid), vol, msf, cnt);
            BigDecimal kfcaFee = amount(pick(rates, ADJ_KFCA_PROCESSING, mid), vol, msf, cnt);
            BigDecimal kfcaMargin = amount(pick(rates, ADJ_KFCA_PER_TXN, mid), vol, msf, cnt);
            BigDecimal icpp = amount(pick(rates, ADJ_ICPP_BILLING, mid), vol, msf, cnt);
            BigDecimal netRev = msf.subtract(ic);
            BigDecimal netMargin = netRev.subtract(sf).subtract(mpgs).subtract(otherPg)
                    .subtract(pie).subtract(kfcaFee).add(kfcaMargin).add(icpp);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("month", month.toString());
            r.put("monthName", monthName);
            r.put("mid", mid);
            r.put("merchantName", rs.getString("name"));
            r.put("merchantType", rs.getString("merchant_type"));
            r.put("mccCategory", rs.getString("mcc_category"));
            r.put("destination", rs.getString("destination"));
            r.put("scheme", resolveScheme(schemes, rs.getString("product_code"), rs.getString("card_scheme")));
            r.put("cardInterchange", rs.getString("card_interchange"));
            r.put("count", cnt);
            r.put("volume", vol);
            r.put("msf", msf);
            r.put("ic", ic);
            r.put("netRevenue", netRev);
            r.put("schemeFees", sf);
            r.put("mpgsFees", mpgs);
            r.put("otherPgFees", otherPg);
            r.put("pieMsfRebate", pie);
            r.put("kfcaProcessingFee", kfcaFee);
            r.put("kfcaPerTxnMargin", kfcaMargin);
            r.put("icppBilling", icpp);
            r.put("netMargin", netMargin);
            return r;
        }, params.toArray());

        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) rows = new ArrayList<>(rows.subList(0, MAX_ROWS));

        // Rows that differ only in raw product code / card scheme but resolve to
        // the same scheme group collapse here, so the grain the user sees is the
        // one promised in the class javadoc.
        rows = mergeSameGrain(rows);

        Map<String, Object> out = new HashMap<>();
        out.put("month", month.toString());
        out.put("monthName", monthName);
        out.put("start", start.toString());
        out.put("end", month.atEndOfMonth().toString());
        out.put("rows", rows);
        out.put("totals", totals(rows));
        out.put("truncated", truncated);
        out.put("ratesConfigured", rates.stream().map(AdjRate::code).distinct().sorted().toList());
        return out;
    }

    private static final List<String> SUM_KEYS = List.of("count", "volume", "msf", "ic", "netRevenue", "schemeFees",
            "mpgsFees", "otherPgFees", "pieMsfRebate", "kfcaProcessingFee", "kfcaPerTxnMargin", "icppBilling", "netMargin");

    private static List<Map<String, Object>> mergeSameGrain(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = String.join("\u0001", String.valueOf(r.get("mid")), String.valueOf(r.get("merchantType")),
                    String.valueOf(r.get("mccCategory")), String.valueOf(r.get("destination")),
                    String.valueOf(r.get("scheme")), String.valueOf(r.get("cardInterchange")));
            Map<String, Object> acc = byKey.get(key);
            if (acc == null) { byKey.put(key, r); continue; }
            for (String k : SUM_KEYS) {
                Object a = acc.get(k), b = r.get(k);
                if (a instanceof Long la && b instanceof Long lb) acc.put(k, la + lb);
                else acc.put(k, nz((BigDecimal) a).add(nz((BigDecimal) b)));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static Map<String, Object> totals(List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        long cnt = 0;
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (String k : SUM_KEYS) if (!k.equals("count")) sums.put(k, BigDecimal.ZERO);
        for (Map<String, Object> r : rows) {
            cnt += (Long) r.get("count");
            for (String k : sums.keySet()) sums.put(k, sums.get(k).add(nz((BigDecimal) r.get(k))));
        }
        t.put("count", cnt);
        t.putAll(sums);
        t.put("rows", rows.size());
        return t;
    }
}
