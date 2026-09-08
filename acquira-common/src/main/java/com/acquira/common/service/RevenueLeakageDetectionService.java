package com.acquira.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Revenue-leakage / anomaly detector.
 *
 * For each merchant in a tenant, compares RECENT activity (last 7 days) against
 * a trailing BASELINE (the 28 days before that), reading the pre-aggregated
 * {@code sum_daily_merchant} table — so detection is cheap and never touches
 * the raw fact partitions.
 *
 * Signals produced (each an upserted row in {@code revenue_leakage_flags}):
 *   VOLUME_DROP          — recent daily volume fell &gt; threshold vs baseline
 *   MSF_RATE_DROP        — effective MSF rate (|fee| / volume) dropped sharply
 *   ZERO_MSF             — merchant transacting but capturing ~no MSF
 *   DORMANT_REVENUE_LOSS — a previously-active merchant stopped transacting
 *
 * Notes:
 *  - MSF is stored signed (often negative); we use ABS() everywhere so the
 *    "rate" is a positive fraction (e.g. 0.02 = 2%).
 *  - Volume uses total_base_volume (settlement currency) so cross-merchant
 *    comparison is currency-consistent.
 *  - Thresholds are overridable per tenant via tenant_setting (leakage.*).
 *  - Upsert is keyed (tenant, merchant, check_type, business_date); re-running
 *    refreshes an OPEN flag but never resurrects one the user RESOLVED/IGNORED.
 */
@Service
@Slf4j
public class RevenueLeakageDetectionService {

    private final JdbcTemplate jdbc;

    public RevenueLeakageDetectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Defaults (overridable per tenant via tenant_setting).
    private static final double DEF_MIN_DAILY_VOLUME = 100.0;   // ignore tiny merchants
    private static final double DEF_VOLUME_DROP_PCT  = 0.40;    // flag at >= 40% drop
    private static final double DEF_MSF_RATE_DROP_PCT = 0.30;   // flag at >= 30% rate drop
    private static final double DEF_DEFAULT_MSF_RATE  = 0.02;   // fallback rate for impact calc

    private static final int RECENT_DAYS = 7;
    private static final int BASELINE_DAYS = 28;
    private static final int MONTH_DAYS = 30;
    private static final int AUTO_RESOLVE_AFTER_DAYS = 30;      // stale OPEN flags auto-close

    private double setting(Long tenantId, String key, double def) {
        try {
            List<String> v = jdbc.queryForList(
                "SELECT setting_value FROM tenant_setting WHERE tenant_id = ? AND setting_key = ?",
                String.class, tenantId, key);
            if (!v.isEmpty() && v.get(0) != null && !v.get(0).isBlank()) {
                return Double.parseDouble(v.get(0).trim());
            }
        } catch (Exception ignored) { /* fall through to default */ }
        return def;
    }

    /**
     * Run detection for one tenant. Returns the number of flags inserted/updated.
     * Best-effort and self-contained — safe to call from an upload hook, a
     * scheduled job, or an API endpoint.
     */
    public int detectForTenant(Long tenantId) {
        if (tenantId == null) return 0;

        LocalDate asOf;
        try {
            // total_txns > 0: an ancillary-only day (rental/DCC ahead of the
            // transaction file) must not slide the 7-day recent window off
            // the real data and mass-flag DORMANT_REVENUE_LOSS.
            asOf = jdbc.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND COALESCE(total_txns,0) > 0",
                LocalDate.class, tenantId);
        } catch (Exception e) {
            asOf = null;
        }
        if (asOf == null) {
            log.info("Leakage detection: no summary data for tenant {} — skipping", tenantId);
            return 0;
        }

        final double minDailyVol = setting(tenantId, "leakage.min_daily_volume", DEF_MIN_DAILY_VOLUME);
        final double volDropPct  = setting(tenantId, "leakage.volume_drop_pct", DEF_VOLUME_DROP_PCT);
        final double rateDropPct = setting(tenantId, "leakage.msf_rate_drop_pct", DEF_MSF_RATE_DROP_PCT);
        final double defRate     = setting(tenantId, "leakage.default_msf_rate", DEF_DEFAULT_MSF_RATE);

        // Windows. RECENT = [asOf-6 .. asOf] (7 days). BASELINE = [asOf-34 .. asOf-7] (28 days).
        final LocalDate recentStart   = asOf.minusDays(RECENT_DAYS - 1);
        final LocalDate baselineEnd   = asOf.minusDays(RECENT_DAYS);
        final LocalDate baselineStart = asOf.minusDays(RECENT_DAYS + BASELINE_DAYS - 1);

        // Dates are inlined as ISO literals (from LocalDate.toString()) — not user
        // input, so safe — which keeps this to a single bound parameter (tenantId).
        final String R  = recentStart.toString();
        final String BS = baselineStart.toString();
        final String BE = baselineEnd.toString();

        String sql =
            "SELECT s.merchant_id, " +
            "  COALESCE(NULLIF(dm.name,''), dm.mid, CONCAT('Merchant ', s.merchant_id)) AS mname, " +
            "  SUM(CASE WHEN s.business_date >= DATE '" + R + "' THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS r_vol, " +
            "  SUM(CASE WHEN s.business_date >= DATE '" + R + "' THEN ABS(COALESCE(s.total_msf,0))   ELSE 0 END) AS r_msf, " +
            "  SUM(CASE WHEN s.business_date >= DATE '" + R + "' THEN COALESCE(s.total_txns,0)       ELSE 0 END) AS r_txns, " +
            "  SUM(CASE WHEN s.business_date BETWEEN DATE '" + BS + "' AND DATE '" + BE + "' THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS b_vol, " +
            "  SUM(CASE WHEN s.business_date BETWEEN DATE '" + BS + "' AND DATE '" + BE + "' THEN ABS(COALESCE(s.total_msf,0))   ELSE 0 END) AS b_msf, " +
            "  SUM(CASE WHEN s.business_date BETWEEN DATE '" + BS + "' AND DATE '" + BE + "' THEN COALESCE(s.total_txns,0)       ELSE 0 END) AS b_txns " +
            "FROM sum_daily_merchant s " +
            "LEFT JOIN dim_merchant dm ON dm.merchant_id = s.merchant_id AND dm.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.merchant_id IS NOT NULL AND s.business_date >= DATE '" + BS + "' " +
            "GROUP BY s.merchant_id, dm.name, dm.mid";

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(sql, tenantId);
        int flagged = 0;

        for (java.util.Map<String, Object> row : rows) {
            long merchantId = ((Number) row.get("merchant_id")).longValue();
            String name = (String) row.get("mname");

            double rVol  = num(row.get("r_vol"));
            double rMsf  = num(row.get("r_msf"));
            long   rTxns = (long) num(row.get("r_txns"));
            double bVol  = num(row.get("b_vol"));
            double bMsf  = num(row.get("b_msf"));

            double recentDaily = rVol / RECENT_DAYS;
            double baseDaily   = bVol / BASELINE_DAYS;
            double baselineRate = bVol > 0 ? bMsf / bVol : 0.0;
            double recentRate   = rVol > 0 ? rMsf / rVol : 0.0;
            double rate = baselineRate > 0 ? baselineRate : defRate;

            if (rTxns == 0) {
                // No recent activity at all — only the dormancy check applies.
                if (baseDaily >= minDailyVol) {
                    double impact = baseDaily * rate * MONTH_DAYS;
                    flagged += upsert(tenantId, merchantId, name, "DORMANT_REVENUE_LOSS",
                        impact >= minDailyVol * 10 ? "CRITICAL" : "HIGH",
                        String.format("Merchant stopped transacting. Baseline ~%.0f/day over prior %d days; "
                            + "0 transactions in the last %d days.", baseDaily, BASELINE_DAYS, RECENT_DAYS),
                        asOf, 0.0, baseDaily, -100.0, impact);
                }
                continue;
            }

            // Has recent activity.
            if (rVol >= minDailyVol && rMsf <= 0.0001) {
                // Transacting but capturing essentially no fee.
                double impact = recentDaily * rate * MONTH_DAYS;
                flagged += upsert(tenantId, merchantId, name, "ZERO_MSF",
                    impact >= minDailyVol * 10 ? "CRITICAL" : "HIGH",
                    String.format("Processing volume (~%.0f/day) but capturing ~0 MSF. "
                        + "Expected ~%.2f%% based on baseline.", recentDaily, rate * 100),
                    asOf, 0.0, baselineRate * 100, -100.0, impact);
            } else {
                // VOLUME_DROP
                if (baseDaily >= minDailyVol && recentDaily < baseDaily * (1 - volDropPct)) {
                    double drop = (baseDaily - recentDaily) / baseDaily;          // 0..1
                    double impact = (baseDaily - recentDaily) * rate * MONTH_DAYS;
                    flagged += upsert(tenantId, merchantId, name, "VOLUME_DROP", severity(drop),
                        String.format("Daily volume fell %.0f%% — ~%.0f/day now vs ~%.0f/day baseline.",
                            drop * 100, recentDaily, baseDaily),
                        asOf, recentDaily, baseDaily, -drop * 100, impact);
                }
                // MSF_RATE_DROP (recent has real fee + volume, but effective rate slipped)
                if (rVol >= minDailyVol && baselineRate > 0 && recentRate < baselineRate * (1 - rateDropPct)) {
                    double drop = (baselineRate - recentRate) / baselineRate;     // 0..1
                    double impact = (baselineRate - recentRate) * recentDaily * MONTH_DAYS;
                    flagged += upsert(tenantId, merchantId, name, "MSF_RATE_DROP", severity(drop),
                        String.format("Effective MSF rate fell %.0f%% — %.2f%% now vs %.2f%% baseline.",
                            drop * 100, recentRate * 100, baselineRate * 100),
                        asOf, recentRate * 100, baselineRate * 100, -drop * 100, impact);
                }
            }
        }

        // Auto-close stale OPEN flags so the active list stays focused on the
        // current anomaly window. User-resolved/ignored flags are untouched.
        try {
            int closed = jdbc.update(
                "UPDATE revenue_leakage_flags SET status='RESOLVED', is_resolved=true, " +
                "resolved_at=NOW(), resolved_by='AUTO' " +
                "WHERE tenant_id = ? AND status='OPEN' AND business_date < ?",
                tenantId, asOf.minusDays(AUTO_RESOLVE_AFTER_DAYS));
            if (closed > 0) log.info("Leakage detection: auto-resolved {} stale flag(s) for tenant {}", closed, tenantId);
        } catch (Exception e) {
            log.warn("Leakage detection: auto-resolve sweep failed (non-fatal): {}", e.getMessage());
        }

        log.info("Leakage detection: {} flag(s) upserted for tenant {} as of {}", flagged, tenantId, asOf);
        return flagged;
    }

    /** Severity from a 0..1 drop fraction. */
    private static String severity(double dropFraction) {
        if (dropFraction >= 0.70) return "CRITICAL";
        if (dropFraction >= 0.50) return "HIGH";
        if (dropFraction >= 0.30) return "MEDIUM";
        return "LOW";
    }

    private static double num(Object o) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : 0.0;
    }

    private static BigDecimal bd(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Upsert a single flag. The ON CONFLICT clause refreshes an OPEN flag in
     * place but the WHERE guard means a flag the user already RESOLVED/IGNORED
     * is left alone (we don't nag them about something they've actioned).
     */
    private int upsert(Long tenantId, long merchantId, String merchantName, String checkType,
                       String severity, String details, LocalDate businessDate,
                       double metricValue, double baselineValue, double deltaPct, double estImpact) {
        return jdbc.update(
            "INSERT INTO revenue_leakage_flags " +
            "(tenant_id, merchant_id, merchant_name, check_type, severity, details, business_date, " +
            " metric_value, baseline_value, delta_pct, est_monthly_impact, status, is_resolved, detected_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?, 'OPEN', false, NOW()) " +
            "ON CONFLICT (tenant_id, merchant_id, check_type, business_date) DO UPDATE SET " +
            "  merchant_name = EXCLUDED.merchant_name, severity = EXCLUDED.severity, details = EXCLUDED.details, " +
            "  metric_value = EXCLUDED.metric_value, baseline_value = EXCLUDED.baseline_value, " +
            "  delta_pct = EXCLUDED.delta_pct, est_monthly_impact = EXCLUDED.est_monthly_impact, detected_at = NOW() " +
            "WHERE revenue_leakage_flags.status = 'OPEN'",
            tenantId, merchantId, merchantName, checkType, severity, details, java.sql.Date.valueOf(businessDate),
            bd(metricValue), bd(baselineValue), bd(deltaPct), bd(estImpact));
    }
}
