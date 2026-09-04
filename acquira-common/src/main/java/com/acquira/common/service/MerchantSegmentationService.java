package com.acquira.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merchant portfolio segmentation (Phase 1 — six data-backed segments).
 *
 * Runs in the transaction batch after business metrics are fresh. For each tenant it
 * computes, over a trailing 90-day window from sum_daily_merchant (settlement
 * total_base_volume) joined to dim_merchant:
 *   - volume, net margin (total_margin), txns, effective & net-take bps, margin %,
 *   - per-tenant percentile ranks of volume and net margin (PERCENT_RANK),
 *   - volume growth (recent 30d vs prior 30d),
 *   - days since last transaction, days since onboarding.
 * Then applies priority rules to assign ONE primary segment + secondary tags.
 *
 * Six segments (spec Phase 1), priority order (highest wins the primary slot):
 *   1 AT_RISK        steep decline OR long dormancy
 *   2 STRATEGIC      volume ≥ p80 AND net margin ≥ p80
 *   3 VOLUME_DRIVER  volume ≥ p80 AND low margin
 *   4 PROFIT_DRIVER  volume p40–p80 AND high margin
 *   5 NEW            onboarded ≤ 90 days
 *   6 LONG_TAIL      volume ≤ p30 AND net margin ≤ p30
 * Any other segment a merchant also qualifies for becomes a secondary tag.
 *
 * Thresholds are per-tenant percentiles computed over the tenant's own active
 * merchants in the window (80th/40th/30th), so "high/low" is relative to that bank's
 * portfolio, not a global constant. Margin/dormancy/growth cutoffs are fixed sensible
 * defaults (tenant-configurable override deferred to a later phase).
 *
 * Best-effort and exception-isolated: the batch step wraps this in try/catch and the
 * public entry point additionally swallows its own errors, so segmentation can never
 * fail ingestion.
 */
@Service
@Slf4j
public class MerchantSegmentationService {

    private final JdbcTemplate jdbc;

    private static final int WINDOW_DAYS = 90;
    private static final int NEW_MERCHANT_DAYS = 90;
    private static final int DORMANCY_DAYS = 30;         // no txns in ≥30d ⇒ at-risk
    private static final double DECLINE_PCT = -30.0;     // ≤ -30% growth ⇒ at-risk
    private static final double GROWTH_PCT = 25.0;       // ≥ +25% growth ⇒ growth signal (secondary)
    private static final double HIGH_MARGIN_PCT = 40.0;  // net margin high-water
    private static final double LOW_MARGIN_PCT = 15.0;   // net margin low-water
    private static final double P_HIGH = 0.80, P_MID = 0.40, P_LOW = 0.30;
    private static final String MODEL_VERSION = "seg-rules-v1";

    // Segment codes
    private static final String AT_RISK = "AT_RISK";
    private static final String STRATEGIC = "STRATEGIC";
    private static final String VOLUME_DRIVER = "VOLUME_DRIVER";
    private static final String PROFIT_DRIVER = "PROFIT_DRIVER";
    private static final String NEW = "NEW";
    private static final String LONG_TAIL = "LONG_TAIL";
    private static final String GROWTH = "GROWTH"; // secondary-only in Phase 1

    public MerchantSegmentationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Compute + upsert segments for one tenant as of its latest business_date.
     * Returns the number of merchants segmented, 0 on any failure.
     */
    public int computeForTenant(Long tenantId) {
        if (tenantId == null) return 0;
        try {
            LocalDate asOf = maxBusinessDate(tenantId);
            if (asOf == null) {
                log.info("Segmentation: no summary data for tenant {} — skipping", tenantId);
                return 0;
            }
            List<Map<String, Object>> rows = loadMetrics(tenantId, asOf);
            if (rows.isEmpty()) return 0;

            int written = 0;
            for (Map<String, Object> r : rows) {
                Long merchantId = ((Number) r.get("merchant_id")).longValue();

                double vol       = num(r.get("total_volume"));
                double netRev    = num(r.get("net_revenue"));
                double msf       = num(r.get("total_msf"));
                double volRank   = num(r.get("vol_rank"));    // 0..1 PERCENT_RANK
                double revRank   = num(r.get("rev_rank"));    // 0..1
                double marginPct = num(r.get("net_margin_pct"));
                double effBps    = num(r.get("effective_bps"));
                double takeBps   = num(r.get("net_take_bps"));
                double growthPct = num(r.get("volume_growth_pct"));
                int daysSince    = (int) num(r.get("days_since_last"));
                int daysOnboard  = (int) num(r.get("days_since_onboard"));
                boolean hasVol   = vol > 0;

                // ── qualification flags ──
                boolean qAtRisk    = (daysSince >= DORMANCY_DAYS) || (hasVol && growthPct <= DECLINE_PCT);
                boolean qStrategic = volRank >= P_HIGH && revRank >= P_HIGH;
                boolean qVolDriver = volRank >= P_HIGH && marginPct < LOW_MARGIN_PCT;
                boolean qProfit    = volRank >= P_MID && volRank < P_HIGH && marginPct >= HIGH_MARGIN_PCT;
                boolean qNew       = daysOnboard >= 0 && daysOnboard <= NEW_MERCHANT_DAYS;
                boolean qLongTail  = volRank <= P_LOW && revRank <= P_LOW;
                boolean qGrowth    = hasVol && growthPct >= GROWTH_PCT;

                // ── primary by priority ──
                String primary; String reason;
                if (qAtRisk) {
                    primary = AT_RISK;
                    reason = (daysSince >= DORMANCY_DAYS)
                        ? "No transactions for " + daysSince + " days"
                        : "Volume down " + fmtPct(growthPct) + " vs prior period";
                } else if (qStrategic) {
                    primary = STRATEGIC; reason = "Top-tier volume and revenue";
                } else if (qVolDriver) {
                    primary = VOLUME_DRIVER; reason = "High volume, thin margin (" + fmtPct(marginPct) + ")";
                } else if (qProfit) {
                    primary = PROFIT_DRIVER; reason = "Strong margin (" + fmtPct(marginPct) + ") at mid volume";
                } else if (qNew) {
                    primary = NEW; reason = "Onboarded " + daysOnboard + " days ago";
                } else if (qLongTail) {
                    primary = LONG_TAIL; reason = "Low volume and revenue";
                } else {
                    primary = "UNCLASSIFIED"; reason = "No segment rule matched";
                }

                // ── secondary tags: every OTHER qualifying segment ──
                List<String> tags = new ArrayList<>();
                addTagIf(tags, qAtRisk, AT_RISK, primary);
                addTagIf(tags, qStrategic, STRATEGIC, primary);
                addTagIf(tags, qVolDriver, VOLUME_DRIVER, primary);
                addTagIf(tags, qProfit, PROFIT_DRIVER, primary);
                addTagIf(tags, qGrowth, GROWTH, primary);      // growth only ever a tag in P1
                addTagIf(tags, qNew, NEW, primary);
                addTagIf(tags, qLongTail, LONG_TAIL, primary);
                String secondary = String.join(",", tags);

                // ── priority-weighted score (higher = more urgent/important) ──
                double score = scoreFor(primary, volRank, revRank, growthPct, daysSince);

                written += upsert(tenantId, merchantId, asOf, primary, secondary, reason, score,
                        vol, netRev, marginPct, effBps, takeBps, growthPct, daysSince);
            }
            log.info("Segmentation: segmented {} merchant(s) for tenant {} as of {}", written, tenantId, asOf);
            return written;
        } catch (Exception e) {
            log.warn("Segmentation failed for tenant {} (non-fatal): {}", tenantId, e.toString());
            return 0;
        }
    }

    // ── metrics + percentiles in one pass ──────────────────────────────────

    private List<Map<String, Object>> loadMetrics(Long tenantId, LocalDate asOf) {
        final LocalDate winStart   = asOf.minusDays(WINDOW_DAYS - 1);
        final LocalDate recentStart = asOf.minusDays(29);        // last 30d
        final LocalDate priorStart  = asOf.minusDays(59);        // prior 30d
        final LocalDate priorEnd    = asOf.minusDays(30);

        // Aggregate per merchant over the window, compute growth windows, then rank
        // by volume and net margin with PERCENT_RANK across the tenant's merchants.
        // Volume = settlement total_base_volume; net margin = total_margin.
        String sql =
            "WITH agg AS ( " +
            "  SELECT s.merchant_id, " +
            "    SUM(COALESCE(s.total_base_volume,0)) AS total_volume, " +
            "    SUM(COALESCE(s.total_margin,0))      AS net_revenue, " +
            "    SUM(COALESCE(s.total_msf,0))         AS total_msf, " +
            "    SUM(COALESCE(s.total_txns,0))        AS total_txns, " +
            "    SUM(CASE WHEN s.business_date >= ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS recent_vol, " +
            "    SUM(CASE WHEN s.business_date BETWEEN ? AND ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS prior_vol, " +
            "    MAX(CASE WHEN COALESCE(s.total_txns,0) > 0 THEN s.business_date END) AS last_active " +
            "  FROM sum_daily_merchant s " +
            "  WHERE s.tenant_id = ? AND s.merchant_id IS NOT NULL " +
            // total_txns > 0: a merchant whose only rows in the window are
            // ancillary charges (rental/DCC, no transactions) must not enter
            // the percentile population — extra zero rows at the bottom would
            // shift the STRATEGIC/VOLUME_DRIVER/LONG_TAIL cutoffs tenant-wide.
            "    AND COALESCE(s.total_txns,0) > 0 " +
            "    AND s.business_date >= ? AND s.business_date <= ? " +
            "  GROUP BY s.merchant_id " +
            ") " +
            "SELECT a.merchant_id, a.total_volume, a.net_revenue, a.total_msf, a.total_txns, " +
            "  m.created_date, a.last_active, " +
            "  CASE WHEN a.total_volume > 0 THEN a.net_revenue * 100.0 / a.total_volume ELSE 0 END AS net_margin_pct, " +
            "  CASE WHEN a.total_volume > 0 THEN a.total_msf   * 10000.0 / a.total_volume ELSE 0 END AS effective_bps, " +
            "  CASE WHEN a.total_volume > 0 THEN a.net_revenue * 10000.0 / a.total_volume ELSE 0 END AS net_take_bps, " +
            "  CASE WHEN a.prior_vol > 0 THEN (a.recent_vol - a.prior_vol) * 100.0 / a.prior_vol " +
            "       WHEN a.recent_vol > 0 THEN 100.0 ELSE 0 END AS volume_growth_pct, " +
            "  PERCENT_RANK() OVER (ORDER BY a.total_volume) AS vol_rank, " +
            "  PERCENT_RANK() OVER (ORDER BY a.net_revenue)  AS rev_rank " +
            "FROM agg a " +
            "JOIN dim_merchant m ON m.merchant_id = a.merchant_id AND m.tenant_id = ? ";

        List<Map<String, Object>> raw = jdbc.queryForList(sql,
            java.sql.Date.valueOf(recentStart),                                   // recent_vol
            java.sql.Date.valueOf(priorStart), java.sql.Date.valueOf(priorEnd),   // prior_vol
            tenantId,
            java.sql.Date.valueOf(winStart), java.sql.Date.valueOf(asOf),         // window
            tenantId);                                                            // dim join scope

        // Post-process date-derived ints (days since last txn / onboarding) in Java
        // to avoid dialect date-diff quirks.
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            int daysSinceLast = daysBetween(r.get("last_active"), asOf, 999);
            int daysOnboard   = daysBetween(r.get("created_date"), asOf, -1);
            r.put("days_since_last", daysSinceLast);
            r.put("days_since_onboard", daysOnboard);
            out.add(r);
        }
        return out;
    }

    // ── scoring + helpers ──────────────────────────────────────────────────

    /**
     * A 0..100 priority-weighted score: primary segment sets the band, the merchant's
     * own metrics modulate within it. Lets the UI sort "most important first".
     */
    private static double scoreFor(String primary, double volRank, double revRank,
                                   double growthPct, int daysSince) {
        switch (primary) {
            case AT_RISK:       return clamp(70 + Math.min(30, daysSince / 2.0), 0, 100);
            case STRATEGIC:     return clamp(80 + 20 * ((volRank + revRank) / 2.0), 0, 100);
            case VOLUME_DRIVER: return clamp(60 + 20 * volRank, 0, 100);
            case PROFIT_DRIVER: return clamp(55 + 20 * revRank, 0, 100);
            case NEW:           return 45;
            case LONG_TAIL:     return clamp(10 + 20 * volRank, 0, 100);
            default:            return 30;
        }
    }

    private static void addTagIf(List<String> tags, boolean qualifies, String seg, String primary) {
        if (qualifies && !seg.equals(primary)) tags.add(seg);
    }

    private int upsert(Long tenantId, long merchantId, LocalDate asOf, String primary,
                       String secondary, String reason, double score,
                       double vol, double netRev, double marginPct, double effBps,
                       double takeBps, double growthPct, int daysSince) {
        return jdbc.update(
            "INSERT INTO merchant_segment " +
            "(tenant_id, merchant_id, calc_date, primary_segment, secondary_tags, segment_reason, segment_score, " +
            " total_volume, net_revenue, net_margin_pct, effective_bps, net_take_bps, volume_growth_pct, " +
            " days_since_last, model_version, created_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW()) " +
            "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET " +
            "  primary_segment=EXCLUDED.primary_segment, secondary_tags=EXCLUDED.secondary_tags, " +
            "  segment_reason=EXCLUDED.segment_reason, segment_score=EXCLUDED.segment_score, " +
            "  total_volume=EXCLUDED.total_volume, net_revenue=EXCLUDED.net_revenue, " +
            "  net_margin_pct=EXCLUDED.net_margin_pct, effective_bps=EXCLUDED.effective_bps, " +
            "  net_take_bps=EXCLUDED.net_take_bps, volume_growth_pct=EXCLUDED.volume_growth_pct, " +
            "  days_since_last=EXCLUDED.days_since_last, model_version=EXCLUDED.model_version, created_at=NOW()",
            tenantId, merchantId, java.sql.Date.valueOf(asOf), primary, secondary, reason, bd(score, 2),
            bd(vol, 2), bd(netRev, 2), bd(clampRatio(marginPct), 2), bd(clampRatio(effBps), 2),
            bd(clampRatio(takeBps), 2), bd(clampRatio(growthPct), 2),
            daysSince, MODEL_VERSION);
    }

    /**
     * The four ratio columns are NUMERIC(9,2). A near-zero denominator — e.g. a
     * merchant whose 90-day settlement volume nets to fractions of a dinar
     * against a real MSF — yields bps/pct values past 10^7, and the single
     * oversized row killed the WHOLE tenant's segmentation upsert with
     * "numeric field overflow" (seen live UAT 2026-08-28, every run). A figure
     * that large carries no analytical meaning, so cap it at the column bound
     * rather than losing every merchant's segmentation.
     */
    private static double clampRatio(double v) {
        return clamp(v, -9_999_999.99, 9_999_999.99);
    }

    private LocalDate maxBusinessDate(Long tenantId) {
        try {
            // total_txns > 0: an ancillary-only day must not shift the as-of
            // anchor (it would inflate days_since_last for every merchant).
            return jdbc.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND COALESCE(total_txns,0) > 0",
                LocalDate.class, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private static int daysBetween(Object from, LocalDate to, int nullDefault) {
        if (from == null) return nullDefault;
        LocalDate d;
        if (from instanceof java.sql.Date) d = ((java.sql.Date) from).toLocalDate();
        else if (from instanceof java.sql.Timestamp) d = ((java.sql.Timestamp) from).toLocalDateTime().toLocalDate();
        else if (from instanceof java.time.LocalDateTime) d = ((java.time.LocalDateTime) from).toLocalDate();
        else if (from instanceof LocalDate) d = (LocalDate) from;
        else return nullDefault;
        long days = java.time.temporal.ChronoUnit.DAYS.between(d, to);
        return days < 0 ? 0 : (int) days;
    }

    private static String fmtPct(double v) { return String.format("%.0f%%", v); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double num(Object o) { return (o instanceof Number) ? ((Number) o).doubleValue() : 0.0; }
    private static BigDecimal bd(double d, int scale) { return BigDecimal.valueOf(d).setScale(scale, RoundingMode.HALF_UP); }
}
