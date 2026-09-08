package com.acquira.core.service;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.PricingSegmentMatrixRepository;
import com.acquira.common.repository.TenantSettingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pricing Simulator v2 — segment margin matrix over sum_daily_full.
 *
 * Owns everything that is NOT plain SQL aggregation:
 *  - the per-tenant enable flag ({@code pricing.simulator_enabled} in
 *    tenant_setting; absent = enabled, so existing tenants keep the screen
 *    they already have — an admin turns it OFF explicitly),
 *  - default window resolution (trailing 3 months anchored on the summary's
 *    own MAX(business_date), attrition-report style — never LocalDate.now(),
 *    which can point past the data and open the screen on zeros),
 *  - derived rates in bps (MSF / cost / net take) per segment,
 *  - card-type coverage per scheme (share of volume with a REAL card_type —
 *    the 'has card type or not' signal: UNSPECIFIED cells are surfaced, never
 *    blended, and the frontend disables levers on them),
 *  - below-cost + low-coverage flags and the portfolio median net take.
 *
 * Churn/uplift modelling stays a frontend assumption (labelled, tunable),
 * exactly as in v1 — this service reports realized economics only.
 */
@Service
public class PricingSimulatorService {

    /** tenant_setting key: "false" disables the calculation for that tenant. Absent/anything-else = enabled. */
    public static final String ENABLED_KEY = "pricing.simulator_enabled";

    /** A scheme whose untyped share exceeds this is flagged: its typed split is unreliable. */
    private static final double UNKNOWN_SHARE_WARN_PCT = 10.0;

    private static final BigDecimal TEN_K = BigDecimal.valueOf(10000);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final PricingSegmentMatrixRepository matrixRepository;
    private final TenantSettingRepository tenantSettingRepository;

    public PricingSimulatorService(PricingSegmentMatrixRepository matrixRepository,
                                   TenantSettingRepository tenantSettingRepository) {
        this.matrixRepository = matrixRepository;
        this.tenantSettingRepository = tenantSettingRepository;
    }

    /** Per-tenant flag; fail OPEN (read-only feature that predates the flag). */
    public boolean isEnabled(Long tenantId) {
        if (tenantId == null) return false;
        try {
            return tenantSettingRepository.findByTenant_TenantIdAndKey(tenantId, ENABLED_KEY)
                    .map(s -> !"false".equalsIgnoreCase(String.valueOf(s.getValue()).trim()))
                    .orElse(true);
        } catch (Exception e) {
            return true;
        }
    }

    public Map<String, Object> getBounds(Long tenantId) {
        return matrixRepository.getBounds(tenantId);
    }

    /**
     * Fill a missing window with the trailing 3 months ending at the
     * summary's own latest date. Mutates the DTO so the repository always
     * receives a sargable BETWEEN.
     */
    private void defaultWindow(VolumeRevenueFilterDTO filter, Long tenantId) {
        if (filter.getStartDate() != null && filter.getEndDate() != null) return;
        Map<String, Object> bounds = matrixRepository.getBounds(tenantId);
        Object latest = bounds.get("latest");
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate()
                : (latest != null ? LocalDate.parse(latest.toString()) : LocalDate.now());
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate()
                : end.minusMonths(3).plusDays(1);
        filter.setEndDate(end);
        filter.setStartDate(start);
    }

    private static BigDecimal bps(BigDecimal numerator, BigDecimal volume) {
        if (volume == null || volume.signum() == 0 || numerator == null) return null;
        return numerator.multiply(TEN_K).divide(volume, MC).setScale(1, RoundingMode.HALF_UP);
    }

    public Map<String, Object> segmentMatrix(VolumeRevenueFilterDTO filter, Long tenantId) {
        defaultWindow(filter, tenantId);
        List<Map<String, Object>> segments = matrixRepository.getSegmentMatrix(filter, tenantId);

        // ── derived rates + totals ──────────────────────────────────────
        BigDecimal totalVol = BigDecimal.ZERO, totalMsf = BigDecimal.ZERO,
                totalCost = BigDecimal.ZERO, totalNet = BigDecimal.ZERO;
        long totalTxns = 0;

        for (Map<String, Object> seg : segments) {
            BigDecimal vol = (BigDecimal) seg.get("volume");
            BigDecimal msf = (BigDecimal) seg.get("msf");
            BigDecimal cost = ((BigDecimal) seg.get("interchange"))
                    .add((BigDecimal) seg.get("schemeFee"))
                    .add((BigDecimal) seg.get("ecomFee"));
            BigDecimal net = (BigDecimal) seg.get("netRevenue");

            seg.put("cost", cost);
            seg.put("msfBps", bps(msf, vol));
            seg.put("costBps", bps(cost, vol));
            seg.put("netBps", bps(net, vol));
            // Below-cost flag: realized MSF does not cover interchange+scheme+ecom.
            seg.put("belowCost", vol.signum() > 0 && net.signum() < 0);
            seg.put("hasCardType", !"UNSPECIFIED".equals(seg.get("cardType")));

            totalVol = totalVol.add(vol);
            totalMsf = totalMsf.add(msf);
            totalCost = totalCost.add(cost);
            totalNet = totalNet.add(net);
            totalTxns += ((Number) seg.get("txns")).longValue();
        }

        // ── card-type coverage per scheme ("has card type or not") ──────
        Map<String, BigDecimal[]> bySchemeVol = new LinkedHashMap<>(); // [typed, untyped]
        for (Map<String, Object> seg : segments) {
            String scheme = (String) seg.get("scheme");
            BigDecimal vol = (BigDecimal) seg.get("volume");
            BigDecimal[] acc = bySchemeVol.computeIfAbsent(scheme,
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (Boolean.TRUE.equals(seg.get("hasCardType"))) acc[0] = acc[0].add(vol);
            else acc[1] = acc[1].add(vol);
        }
        List<Map<String, Object>> schemeCoverage = new ArrayList<>();
        BigDecimal typedVolAll = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : bySchemeVol.entrySet()) {
            BigDecimal typed = e.getValue()[0], untyped = e.getValue()[1];
            BigDecimal tot = typed.add(untyped);
            typedVolAll = typedVolAll.add(typed);
            double unknownPct = tot.signum() == 0 ? 0.0
                    : untyped.multiply(BigDecimal.valueOf(100)).divide(tot, MC).doubleValue();
            Map<String, Object> cov = new HashMap<>();
            cov.put("scheme", e.getKey());
            cov.put("volume", tot);
            cov.put("unknownSharePct", Math.round(unknownPct * 10.0) / 10.0);
            cov.put("lowCoverage", unknownPct > UNKNOWN_SHARE_WARN_PCT);
            schemeCoverage.add(cov);
        }
        double typedPct = totalVol.signum() == 0 ? 100.0
                : typedVolAll.multiply(BigDecimal.valueOf(100)).divide(totalVol, MC).doubleValue();

        // ── portfolio median net take across typed segments with volume ──
        List<BigDecimal> netRates = segments.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("hasCardType")))
                .map(s -> (BigDecimal) s.get("netBps"))
                .filter(v -> v != null)
                .sorted()
                .toList();
        BigDecimal medianNetBps = null;
        if (!netRates.isEmpty()) {
            int n = netRates.size();
            medianNetBps = (n % 2 == 1) ? netRates.get(n / 2)
                    : netRates.get(n / 2 - 1).add(netRates.get(n / 2))
                              .divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
        }
        // Compressed flag: a typed segment priced well under the portfolio median net take.
        for (Map<String, Object> seg : segments) {
            BigDecimal netBps = (BigDecimal) seg.get("netBps");
            seg.put("compressed", medianNetBps != null && netBps != null
                    && Boolean.TRUE.equals(seg.get("hasCardType"))
                    && netBps.compareTo(medianNetBps.divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP)) < 0);
        }

        Map<String, Object> totals = new HashMap<>();
        totals.put("txns", totalTxns);
        totals.put("volume", totalVol);
        totals.put("msf", totalMsf);
        totals.put("cost", totalCost);
        totals.put("netRevenue", totalNet);
        totals.put("msfBps", bps(totalMsf, totalVol));
        totals.put("costBps", bps(totalCost, totalVol));
        totals.put("netBps", bps(totalNet, totalVol));

        Map<String, Object> out = new HashMap<>();
        out.put("enabled", true);
        out.put("windowStart", filter.getStartDate().toString());
        out.put("windowEnd", filter.getEndDate().toString());
        out.put("segments", segments);
        out.put("schemeCoverage", schemeCoverage);
        out.put("cardTypeCoveragePct", Math.round(typedPct * 10.0) / 10.0);
        out.put("medianNetBps", medianNetBps);
        out.put("totals", totals);
        return out;
    }

    /**
     * MID-wise repricing view: one merchant's full segment breakdown with
     * derived rates. The frontend compares each row against the tenant
     * segment benchmarks it already holds from segmentMatrix, so no peer
     * re-query happens here.
     */
    public Map<String, Object> merchantMatrix(VolumeRevenueFilterDTO filter, Long tenantId, String mid) {
        defaultWindow(filter, tenantId);
        List<Map<String, Object>> segments = matrixRepository.getMerchantSegmentMatrix(filter, tenantId, mid);

        BigDecimal totalVol = BigDecimal.ZERO, totalMsf = BigDecimal.ZERO, totalNet = BigDecimal.ZERO;
        for (Map<String, Object> seg : segments) {
            BigDecimal vol = (BigDecimal) seg.get("volume");
            BigDecimal msf = (BigDecimal) seg.get("msf");
            BigDecimal cost = ((BigDecimal) seg.get("interchange"))
                    .add((BigDecimal) seg.get("schemeFee"))
                    .add((BigDecimal) seg.get("ecomFee"));
            BigDecimal net = (BigDecimal) seg.get("netRevenue");
            seg.put("cost", cost);
            seg.put("msfBps", bps(msf, vol));
            seg.put("costBps", bps(cost, vol));
            seg.put("netBps", bps(net, vol));
            seg.put("belowCost", vol.signum() > 0 && net.signum() < 0);
            seg.put("hasCardType", !"UNSPECIFIED".equals(seg.get("cardType")));
            totalVol = totalVol.add(vol);
            totalMsf = totalMsf.add(msf);
            totalNet = totalNet.add(net);
        }

        Map<String, Object> totals = new HashMap<>();
        totals.put("volume", totalVol);
        totals.put("msf", totalMsf);
        totals.put("netRevenue", totalNet);
        totals.put("msfBps", bps(totalMsf, totalVol));
        totals.put("netBps", bps(totalNet, totalVol));

        Map<String, Object> out = new HashMap<>();
        out.put("mid", mid);
        out.put("windowStart", filter.getStartDate().toString());
        out.put("windowEnd", filter.getEndDate().toString());
        out.put("segments", segments);
        out.put("totals", totals);
        return out;
    }

    public Map<String, Object> segmentMerchants(VolumeRevenueFilterDTO filter, Long tenantId,
                                                String scheme, String cardType, String destination,
                                                int limit) {
        defaultWindow(filter, tenantId);
        List<Map<String, Object>> merchants = matrixRepository.getSegmentMerchants(
                filter, tenantId, scheme, cardType, destination, limit);

        for (Map<String, Object> row : merchants) {
            BigDecimal vol = (BigDecimal) row.get("volume");
            row.put("msfBps", bps((BigDecimal) row.get("msf"), vol));
            row.put("costBps", bps((BigDecimal) row.get("cost"), vol));
            row.put("netBps", bps((BigDecimal) row.get("netRevenue"), vol));
        }

        // p25 / median MSF-rate markers over the returned set (already rate-ascending).
        List<BigDecimal> rates = merchants.stream()
                .map(m -> (BigDecimal) m.get("msfBps")).filter(v -> v != null).toList();
        Map<String, Object> out = new HashMap<>();
        out.put("windowStart", filter.getStartDate().toString());
        out.put("windowEnd", filter.getEndDate().toString());
        out.put("merchants", merchants);
        out.put("p25MsfBps", rates.isEmpty() ? null : rates.get(rates.size() / 4));
        out.put("medianMsfBps", rates.isEmpty() ? null : rates.get(rates.size() / 2));
        return out;
    }
}
