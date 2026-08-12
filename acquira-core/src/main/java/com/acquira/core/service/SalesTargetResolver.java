package com.acquira.core.service;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesAgentTarget;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesAgentTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * THE one place that answers "what is this agent's target for this window?".
 *
 * <p>Three rules, and every consumer inherits them by using this class rather
 * than reading the table directly:
 *
 * <ol>
 *   <li><b>Null, never zero.</b> An agent with no configured target resolves to
 *       {@code null}. Returning {@code BigDecimal.ZERO} would make attainment
 *       read as "0% of target" — indistinguishable from a real miss, and the
 *       precise failure the Executive Sales Pulse spec forbids. Callers render
 *       {@code null} as an em dash and must not derive any performance
 *       classification from it.</li>
 *   <li><b>Prorated.</b> Targets are stored monthly. A window that covers part of
 *       a month contributes that fraction of the month's target, by days. So a
 *       12-day MTD view against a 30-day month with a 30k target resolves to 12k,
 *       not 30k — otherwise every agent looks 60% behind on the 12th of the
 *       month, every month.</li>
 *   <li><b>Legacy fallback.</b> If an agent has no row in
 *       {@code sales_agent_target} for the window, the deprecated flat
 *       {@code sales_agent_profile.monthly_target} is used, scaled by the same
 *       month-fraction weights. This keeps the pre-existing Sales Portfolio
 *       screens working unchanged. When that column is retired, delete
 *       {@link #legacyFallback} and nothing else moves.</li>
 * </ol>
 *
 * <p><b>Cost.</b> Two queries for a whole org, regardless of headcount: one for
 * the target rows over the window's months, one for the profiles. Do not call
 * this per agent inside a loop — use {@link #resolveAll}.
 *
 * <p><b>Cross-year windows.</b> {@code month_key} is a YYYYMM integer, so
 * {@code BETWEEN 202511 AND 202602} would wrongly sweep in 202513..202599. The
 * month list is therefore always materialised explicitly by {@link #monthKeys}
 * and queried with {@code IN}. Never hand-roll a BETWEEN over month_key across a
 * year boundary.
 */
@Service
@RequiredArgsConstructor
public class SalesTargetResolver {

    /** The measure targets default to — the same metric the Pulse page calls "sales". */
    public static final String DEFAULT_METRIC = "NET_REVENUE";

    private final SalesAgentTargetRepository targetRepository;
    private final SalesAgentProfileRepository profileRepository;

    /**
     * Target per agent over {@code [from, to]} (inclusive), for every agent the
     * tenant knows about. Agents with no target are ABSENT from the map — callers
     * treat "absent" and "null" identically, as "no target configured".
     */
    public Map<String, BigDecimal> resolveAll(Long tenantId, LocalDate from, LocalDate to, String metricType) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (tenantId == null || from == null || to == null || to.isBefore(from)) return out;

        String metric = (metricType == null || metricType.isBlank()) ? DEFAULT_METRIC : metricType;

        // month_key -> fraction of that month covered by the window (0 < w <= 1).
        Map<Integer, Double> weights = monthWeights(from, to);
        if (weights.isEmpty()) return out;

        List<Integer> keys = new ArrayList<>(weights.keySet());
        for (SalesAgentTarget t : targetRepository.findByTenantIdAndMonthKeyIn(tenantId, keys)) {
            if (t.getTargetValue() == null) continue;          // month present but unset
            if (!metric.equals(t.getMetricType())) continue;
            Double w = weights.get(t.getMonthKey());
            if (w == null) continue;
            BigDecimal share = t.getTargetValue().multiply(BigDecimal.valueOf(w));
            out.merge(t.getSalesUserId(), share, BigDecimal::add);
        }

        legacyFallback(tenantId, weights, out);

        // A resolved total of exactly zero carries no information and would render
        // as "0% attained"; treat it as "not configured" instead.
        out.entrySet().removeIf(e -> e.getValue() == null || e.getValue().signum() <= 0);
        out.replaceAll((k, v) -> v.setScale(4, RoundingMode.HALF_UP));
        return out;
    }

    /** Single-agent convenience. Returns null when no target is configured. */
    public BigDecimal resolve(Long tenantId, String salesUserId, LocalDate from, LocalDate to, String metricType) {
        if (salesUserId == null) return null;
        return resolveAll(tenantId, from, to, metricType).get(salesUserId);
    }

    /** True when the tenant has entered at least one target — drives "targetsConfigured". */
    public boolean anyConfigured(Long tenantId) {
        return tenantId != null && targetRepository.existsByTenantId(tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    //  INTERNALS
    // ═══════════════════════════════════════════════════════════

    /**
     * Agents with no {@code sales_agent_target} contribution fall back to the flat
     * {@code monthly_target}, weighted identically. Agents already resolved from
     * the new table are left alone — the new table always wins.
     */
    private void legacyFallback(Long tenantId, Map<Integer, Double> weights, Map<String, BigDecimal> out) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0) return;

        for (SalesAgentProfile p : profileRepository.findAllByTenantId(tenantId)) {
            if (out.containsKey(p.getSalesUserId())) continue;
            BigDecimal monthly = p.getMonthlyTarget();
            if (monthly == null || monthly.signum() <= 0) continue;
            out.put(p.getSalesUserId(), monthly.multiply(BigDecimal.valueOf(totalWeight)));
        }
    }

    /**
     * Fraction of each calendar month covered by {@code [from, to]} inclusive.
     * A month fully inside the window weighs 1.0; a partial month weighs
     * coveredDays / daysInMonth.
     */
    static Map<Integer, Double> monthWeights(LocalDate from, LocalDate to) {
        Map<Integer, Double> weights = new LinkedHashMap<>();
        YearMonth ym = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        // Guard against a pathological range (the "all time" sentinels) producing
        // a multi-thousand-entry IN list.
        int guard = 0;
        while (!ym.isAfter(last) && guard++ < 600) {
            LocalDate mStart = ym.atDay(1);
            LocalDate mEnd = ym.atEndOfMonth();
            LocalDate covStart = from.isAfter(mStart) ? from : mStart;
            LocalDate covEnd = to.isBefore(mEnd) ? to : mEnd;
            long covered = covEnd.toEpochDay() - covStart.toEpochDay() + 1;
            if (covered > 0) {
                weights.put(monthKey(ym), (double) covered / ym.lengthOfMonth());
            }
            ym = ym.plusMonths(1);
        }
        return weights;
    }

    /** The twelve YYYYMM keys of a calendar year. */
    public static List<Integer> monthKeys(int year) {
        List<Integer> keys = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) keys.add(year * 100 + m);
        return keys;
    }

    public static int monthKey(YearMonth ym) {
        return ym.getYear() * 100 + ym.getMonthValue();
    }

    public static int monthKey(LocalDate d) {
        return d.getYear() * 100 + d.getMonthValue();
    }
}
