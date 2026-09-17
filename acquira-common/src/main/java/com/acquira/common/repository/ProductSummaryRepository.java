package com.acquira.common.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product-level summary for one tenant over a date range — a compact P&amp;L that
 * puts every revenue PRODUCT on its own row rather than every merchant:
 *
 *   Acquiring · POS    (card transactions routed to a physical terminal)
 *   Acquiring · ECOM   (card transactions routed to an e-commerce gateway)
 *   DCC                (dynamic currency conversion — acquirer's share)
 *   Rental             (terminal rental income)
 *   FX                 (ECOM FX income — only when netspread.fx_enabled)
 *   TOTAL
 *
 * SOURCES &amp; GRAIN
 * ---------------
 * The two acquiring rows come from sum_daily_full, the channel-grain
 * settlement pre-aggregate WITH real fees — the SAME source the executive
 * channel selector reads (ChannelSql), so these rows tie out with the POS /
 * ECOM views. channel_class is normalised the same way the backfill did
 * (anything not ECOM → POS) so every fact row lands in exactly one acquiring
 * row and the two of them sum to the tenant's whole transaction book.
 *
 * The ancillary rows (DCC / rental / FX) come from sum_daily_merchant summed
 * to the tenant — the SAME columns AncillarySql maintains and the Net Spread
 * dashboard reads — so DCC / rental / FX here equal the Net Spread ALL totals.
 *
 * VOCABULARY (one shared definition, never re-derived per screen)
 * --------------------------------------------------------------
 *   net margin  = MSF − interchange − scheme fee − PG/ecom fee        (4-leg)
 *   net spread  = net margin + DCC acquirer share + rental income (+ FX)
 *
 * Net margin is computed from the four fee columns shown, so the row always
 * reconciles on screen. The acquiring rows carry no ancillary (spread = net
 * margin); the ancillary rows carry no transaction fees (spread = their
 * revenue). dcc_merchant is informational — the merchant's money, never part
 * of the spread. The TOTAL net spread therefore equals
 * NetSpreadSql.spread for the tenant over the range.
 *
 * All date predicates are sargable bounds on business_date so the partitioned
 * summaries prune correctly.
 */
@Repository
public class ProductSummaryRepository {

    private final JdbcTemplate jdbc;

    public ProductSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** COALESCE(SUM(...),0) can return an Integer/Long/BigDecimal via queryForMap. */
    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    /** net margin = MSF − interchange − scheme fee − PG (4-leg), from the shown columns. */
    private static BigDecimal margin(BigDecimal msf, BigDecimal icf, BigDecimal sf, BigDecimal pg) {
        return nz(msf).subtract(nz(icf)).subtract(nz(sf)).subtract(nz(pg));
    }

    private static Map<String, Object> acquiringRow(String product, Map<String, Object> agg) {
        BigDecimal msf = nz((BigDecimal) agg.get("msf"));
        BigDecimal icf = nz((BigDecimal) agg.get("icf"));
        BigDecimal sf  = nz((BigDecimal) agg.get("sf"));
        BigDecimal pg  = nz((BigDecimal) agg.get("pg"));
        BigDecimal nm  = margin(msf, icf, sf, pg);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("product", product);
        r.put("kind", "ACQUIRING");
        r.put("volume", nz((BigDecimal) agg.get("vol")));
        r.put("count", agg.get("cnt") == null ? 0L : ((Number) agg.get("cnt")).longValue());
        r.put("msf", msf);
        r.put("icf", icf);
        r.put("sf", sf);
        r.put("pg", pg);
        r.put("nm", nm);
        r.put("dcc", BigDecimal.ZERO);
        r.put("dccMerchant", BigDecimal.ZERO);
        r.put("rental", BigDecimal.ZERO);
        r.put("fx", BigDecimal.ZERO);
        r.put("spread", nm);   // acquiring carries no ancillary
        return r;
    }

    private static Map<String, Object> emptyAgg() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("vol", "msf", "icf", "sf", "pg")) m.put(k, BigDecimal.ZERO);
        m.put("cnt", 0L);
        return m;
    }

    /** Ancillary product row: its revenue IS its spread; no transaction fees. */
    private static Map<String, Object> ancillaryRow(String product, String code,
            BigDecimal revenue, BigDecimal dccMerchant) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("product", product);
        r.put("kind", code);
        r.put("volume", null);
        r.put("count", null);
        r.put("msf", null);
        r.put("icf", null);
        r.put("sf", null);
        r.put("pg", null);
        r.put("nm", null);
        r.put("dcc", "DCC".equals(code) ? revenue : BigDecimal.ZERO);
        r.put("dccMerchant", dccMerchant == null ? BigDecimal.ZERO : dccMerchant);
        r.put("rental", "RENTAL".equals(code) ? revenue : BigDecimal.ZERO);
        r.put("fx", "FX".equals(code) ? revenue : BigDecimal.ZERO);
        r.put("spread", nz(revenue));
        return r;
    }

    /**
     * Builds the product rows + TOTAL for [start, end]. fxEnabled adds the FX
     * product row and folds FX into the total spread (opt-in, tenant flag).
     */
    public Map<String, Object> getSummary(Long tenantId, LocalDate start, LocalDate end, boolean fxEnabled) {

        // ── Acquiring rows, one per channel, from the channel-grain pre-aggregate.
        Map<String, Map<String, Object>> byChannel = new LinkedHashMap<>();
        jdbc.query(
                "SELECT CASE WHEN channel_class = 'ECOM' THEN 'ECOM' ELSE 'POS' END AS ch, "
                + "SUM(COALESCE(total_txns,0)) AS cnt, "
                + "SUM(COALESCE(total_volume,0)) AS vol, "
                + "SUM(COALESCE(total_msf,0)) AS msf, "
                + "SUM(COALESCE(total_interchange,0)) AS icf, "
                + "SUM(COALESCE(total_scheme_fee,0)) AS sf, "
                + "SUM(COALESCE(total_ecom_fee,0)) AS pg "
                + "FROM sum_daily_full "
                + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
                + "GROUP BY 1",
                rs -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("cnt", rs.getLong("cnt"));
                    a.put("vol", rs.getBigDecimal("vol"));
                    a.put("msf", rs.getBigDecimal("msf"));
                    a.put("icf", rs.getBigDecimal("icf"));
                    a.put("sf", rs.getBigDecimal("sf"));
                    a.put("pg", rs.getBigDecimal("pg"));
                    byChannel.put(rs.getString("ch"), a);
                }, tenantId, start, end);

        // ── Ancillary totals, tenant-day, same columns the Net Spread page reads.
        Map<String, Object> anc = jdbc.queryForMap(
                "SELECT COALESCE(SUM(dcc_acquirer),0) AS dcc, COALESCE(SUM(dcc_merchant),0) AS dcc_m, "
                + "COALESCE(SUM(rental_amount),0) AS rental, COALESCE(SUM(fx_revenue),0) AS fx "
                + "FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, start, end);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> pos = acquiringRow("Acquiring · POS", byChannel.getOrDefault("POS", emptyAgg()));
        Map<String, Object> ecom = acquiringRow("Acquiring · ECOM", byChannel.getOrDefault("ECOM", emptyAgg()));
        rows.add(pos);
        rows.add(ecom);

        BigDecimal dcc = bd(anc.get("dcc"));
        BigDecimal dccM = bd(anc.get("dcc_m"));
        BigDecimal rental = bd(anc.get("rental"));
        BigDecimal fx = bd(anc.get("fx"));
        rows.add(ancillaryRow("DCC", "DCC", dcc, dccM));
        rows.add(ancillaryRow("Rental", "RENTAL", rental, null));
        if (fxEnabled) rows.add(ancillaryRow("FX Income", "FX", fx, null));

        // ── TOTAL: fee stack + volume are acquiring only; spread adds ancillary.
        BigDecimal volume = nz((BigDecimal) pos.get("volume")).add(nz((BigDecimal) ecom.get("volume")));
        long count = ((Number) pos.get("count")).longValue() + ((Number) ecom.get("count")).longValue();
        BigDecimal msf = nz((BigDecimal) pos.get("msf")).add(nz((BigDecimal) ecom.get("msf")));
        BigDecimal icf = nz((BigDecimal) pos.get("icf")).add(nz((BigDecimal) ecom.get("icf")));
        BigDecimal sf  = nz((BigDecimal) pos.get("sf")).add(nz((BigDecimal) ecom.get("sf")));
        BigDecimal pg  = nz((BigDecimal) pos.get("pg")).add(nz((BigDecimal) ecom.get("pg")));
        BigDecimal nm  = nz((BigDecimal) pos.get("nm")).add(nz((BigDecimal) ecom.get("nm")));
        BigDecimal spread = nm.add(dcc).add(rental).add(fxEnabled ? fx : BigDecimal.ZERO);

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("product", "TOTAL");
        total.put("kind", "TOTAL");
        total.put("volume", volume);
        total.put("count", count);
        total.put("msf", msf);
        total.put("icf", icf);
        total.put("sf", sf);
        total.put("pg", pg);
        total.put("nm", nm);
        total.put("dcc", dcc);
        total.put("dccMerchant", dccM);
        total.put("rental", rental);
        total.put("fx", fx);
        total.put("spread", spread);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totals", total);
        return out;
    }
}
