package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.BankBudgetTarget;
import com.acquira.common.model.SumMonthlyBank;
import com.acquira.common.repository.BankBudgetTargetRepository;
import com.acquira.common.repository.SumMonthlyBankRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Forecasting & Benchmarking — slice 1: Forecast Volume, Forecast Revenue,
 * Forecast Target Attainment, and Seasonal Comparison.
 *
 * DESIGN: every number here is transparent run-rate + seasonality arithmetic —
 * NO ML, NO trained model. This is deliberate: the EnterpriseControls in the
 * spec ask us to "show forecast formula and assumptions", and a run-rate
 * projection is fully explainable and needs no training pipeline. The method
 * comments below double as the assumption disclosure the UI surfaces.
 *
 * DATA SOURCES (all existing, all tenant-scoped):
 *   - sum_daily_bank    : MTD run-rate (business_date grain, same measure cols)
 *   - sum_monthly_bank  : last-month + same-month-last-year for seasonality/YoY
 *   - bank_budget_target: monthly targets per metric (VOLUME/NET_REVENUE/MSF/TXNS)
 *
 * FORECAST MODEL (per metric) — v2, two transparent estimators blended:
 *
 *   A. PRIOR-YEAR PACING (intra-month profile)
 *      pacing   = LY same-month volume through the same day-of-month
 *                 / LY same-month full total
 *      forecastA = MTD / pacing
 *      Captures weekday/weekend shape, salary-cycle spikes and holiday timing
 *      exactly as they played out last year, applied to THIS year's level.
 *
 *   B. DAY-OF-WEEK RUN RATE
 *      dowAvg[d] = average daily actual per weekday over the trailing 28 days
 *      forecastB = MTD + sum(dowAvg[weekday(day)] for each remaining day)
 *      Robust when the month has barely started (uses a 28-day window), and
 *      learns THIS year's weekend pattern instead of assuming Mon-Fri.
 *
 *   final = w*A + (1-w)*B, where w = elapsedDays / daysInMonth — pacing gains
 *   weight as the month fills in; the run rate carries the early days. Either
 *   estimator alone is used when the other has no data; plain calendar-day
 *   run-rate is the last resort.
 *
 * WHY v2: the v1 formula multiplied the remaining-days run rate by
 * clamp(sameMonthLY / priorMonthLY) — a month-over-month LY ratio. This year's
 * MTD run rate already reflects this month's seasonal level, so that factor
 * double-counted seasonality (up to the 2x clamp). v1 also projected only
 * Mon-Fri "business days", which is wrong for card volume (7-day flow, and
 * regional weekends are not Mon-Fri universally). All day counts are now
 * CALENDAR days; the DOW averages encode the weekly shape instead.
 *
 * Additive & isolated: new controller only. Reuses the budget + monthly-bank
 * repositories already wired for BudgetTargetController.
 */
@RestController
@RequestMapping("/api/business/forecast")
@PreAuthorize("@menuAccess.canAccess('/business/forecasting')")
public class ForecastController {

    @PersistenceContext
    private EntityManager entityManager;

    private final BankBudgetTargetRepository budgetRepo;
    private final SumMonthlyBankRepository monthlyBankRepo;
    /** Stamps the tenant's currency onto every money-bearing response. */
    private final CurrencyMeta currencyMeta;

    public ForecastController(BankBudgetTargetRepository budgetRepo,
                              SumMonthlyBankRepository monthlyBankRepo,
                              CurrencyMeta currencyMeta) {
        this.budgetRepo = budgetRepo;
        this.monthlyBankRepo = monthlyBankRepo;
        this.currencyMeta = currencyMeta;
    }

    // Metric → sum_daily_bank / sum_monthly_bank column. Matches the metric set
    // BudgetTargetController already validates against.
    private static final Map<String, String> METRIC_COL = Map.of(
            "VOLUME",      "total_volume",
            "NET_REVENUE", "total_net_revenue",
            "MSF",         "total_msf",
            "TXNS",        "total_txns"
    );
    private static final List<String> METRIC_ORDER =
            List.of("VOLUME", "NET_REVENUE", "MSF", "TXNS");

    private Long resolveTenant(Long headerTenant) {
        // SECURITY: the raw X-Tenant-Id header is attacker-controlled; use only the
        // filter-validated TenantContext (JwtRequestFilter rejects spoofed headers).
        return TenantContext.getCurrentTenant();
    }

    /**
     * Month-end forecast for all four metrics, plus attainment vs target and a
     * YoY seasonal comparison, for the month containing asOfDate (default: the
     * latest date with data, else today).
     *
     * GET /api/business/forecast/summary?asOfDate=YYYY-MM-DD
     */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestParam(required = false) String asOfDate) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        // Anchor date: explicit param, else latest business_date in sum_daily_bank,
        // else today. Using the latest-with-data avoids an "empty current month"
        // when the feed lags real time (same rationale as data-bounds elsewhere).
        LocalDate asOf = parseOrNull(asOfDate);
        if (asOf == null) asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();

        YearMonth ym = YearMonth.from(asOf);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        // CALENDAR days — card volume flows every day of the week; the weekly
        // shape is handled by the DOW estimator, not by dropping weekends.
        int elapsedDays = asOf.getDayOfMonth();
        int totalDays = ym.lengthOfMonth();
        int remainingDays = Math.max(0, totalDays - elapsedDays);

        // Current-month MTD actuals (all metrics in one scan).
        Map<String, BigDecimal> mtd = mtdActuals(tenantId, monthStart, asOf);

        // Estimator inputs: LY same-month daily profile (pacing) and the
        // trailing 28 days ending asOf (day-of-week run rate). One scan each,
        // all four metrics per row.
        YearMonth lyMonth = ym.minusYears(1);
        Map<LocalDate, double[]> lyDaily = dailyTotals(tenantId, lyMonth.atDay(1), lyMonth.atEndOfMonth());
        Map<LocalDate, double[]> trailing = dailyTotals(tenantId, asOf.minusDays(27), asOf);

        int thisMonthKey = ym.getYear() * 100 + ym.getMonthValue();
        Map<String, BigDecimal> lySame = monthlyActuals(tenantId, lyMonth.getYear() * 100 + lyMonth.getMonthValue());

        // Last month (this year) for a MoM variance readout.
        YearMonth lastMonth = ym.minusMonths(1);
        Map<String, BigDecimal> lastMonthAct = monthlyActuals(tenantId, lastMonth.getYear() * 100 + lastMonth.getMonthValue());

        // Targets for this month, indexed by metric.
        Map<String, BigDecimal> targets = new HashMap<>();
        for (BankBudgetTarget t : budgetRepo.findByTenantIdAndMonthKeyBetween(tenantId, thisMonthKey, thisMonthKey)) {
            targets.put(t.getMetricType(), t.getTargetValue() == null ? BigDecimal.ZERO : t.getTargetValue());
        }

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int mi = 0; mi < METRIC_ORDER.size(); mi++) {
            String metric = METRIC_ORDER.get(mi);
            BigDecimal mtdVal = mtd.getOrDefault(metric, BigDecimal.ZERO);
            double mtdD = mtdVal.doubleValue();
            double dailyRunRate = elapsedDays > 0 ? mtdD / elapsedDays : 0.0;

            Forecast f = blendForecast(mtdD, mi, asOf, ym, lyDaily, trailing);

            BigDecimal forecastBd = round2(f.value);
            BigDecimal target = targets.get(metric);
            BigDecimal lastMonthVal = lastMonthAct.getOrDefault(metric, BigDecimal.ZERO);
            BigDecimal lySameVal = lySame.getOrDefault(metric, BigDecimal.ZERO);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metricType", metric);
            m.put("mtdActual", mtdVal);
            m.put("dailyRunRate", round2(dailyRunRate));
            // Key names kept for API compatibility — values are CALENDAR days.
            m.put("elapsedBusinessDays", elapsedDays);
            m.put("remainingBusinessDays", remainingDays);
            m.put("totalBusinessDays", totalDays);
            // Effective multiplier vs the naive linear run-rate — how much the
            // pacing/DOW shape moved the forecast. 1.0 = pure linear.
            double naive = dailyRunRate * totalDays;
            m.put("seasonalityFactor", round4(naive > 0 ? f.value / naive : 1.0));
            m.put("forecastMonthEnd", forecastBd);
            m.put("forecastMethod", f.method);
            m.put("forecastPacing", f.pacing == null ? null : round2(f.pacing));
            m.put("forecastDowRunRate", f.dow == null ? null : round2(f.dow));

            // Variance readouts.
            m.put("forecastVsLastMonthPct", growthPct(f.value, lastMonthVal.doubleValue()));
            m.put("forecastVsLastYearPct", growthPct(f.value, lySameVal.doubleValue()));

            // Attainment (only when a target exists for this metric/month).
            if (target != null && target.signum() > 0) {
                double attainment = f.value / target.doubleValue() * 100.0;
                double requiredDaily = remainingDays > 0
                        ? Math.max(0.0, (target.doubleValue() - mtdD) / remainingDays)
                        : 0.0;
                m.put("target", target);
                m.put("forecastAttainmentPct", round1(attainment));
                m.put("expectedShortfall", round2(Math.max(0.0, target.doubleValue() - f.value)));
                m.put("expectedSurplus", round2(Math.max(0.0, f.value - target.doubleValue())));
                m.put("requiredDailyRunRate", round2(requiredDaily));
                m.put("currentDailyRunRate", round2(dailyRunRate));
                m.put("targetRiskStatus", riskStatus(attainment));
            } else {
                m.put("target", null);
                m.put("forecastAttainmentPct", null);
                m.put("targetRiskStatus", "NO_TARGET");
            }
            metrics.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("monthKey", thisMonthKey);
        resp.put("monthLabel", monthLabel(thisMonthKey));
        resp.put("elapsedBusinessDays", elapsedDays);
        resp.put("remainingBusinessDays", remainingDays);
        resp.put("totalBusinessDays", totalDays);
        resp.put("metrics", metrics);
        // Assumption disclosure for the "show forecast formula" control.
        resp.put("assumptions", Map.of(
                "model", "prior-year pacing blended with day-of-week run rate (no ML)",
                "formula", "forecast = w x (MTD / LY same-month pacing) + (1-w) x (MTD + sum of weekday-average for each remaining day); w = elapsed/total days",
                "seasonalityFactor", "reported factor = forecast / naive linear run-rate projection (1.0 = purely linear month)",
                "businessDays", "All calendar days counted; weekly shape comes from last year's profile and the trailing 28-day weekday averages."
        ));
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    /**
     * Actual-vs-forecast daily trend for one metric across the current month:
     * actuals for elapsed days, straight run-rate projection (with the same
     * seasonality factor) for remaining days, plus a target line if present.
     *
     * GET /api/business/forecast/trend?metric=VOLUME&asOfDate=YYYY-MM-DD
     */
    @GetMapping("/trend")
    public ResponseEntity<?> trend(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestParam(defaultValue = "VOLUME") String metric,
            @RequestParam(required = false) String asOfDate) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        metric = metric.trim().toUpperCase();
        if (!METRIC_COL.containsKey(metric))
            return ResponseEntity.badRequest().body(Map.of("error", "metric must be one of " + METRIC_ORDER));

        LocalDate asOf = parseOrNull(asOfDate);
        if (asOf == null) asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();

        YearMonth ym = YearMonth.from(asOf);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        // Per-day actuals for the elapsed part of the month.
        String col = METRIC_COL.get(metric);
        String sql = "SELECT business_date, " + agg(col) + " AS v FROM sum_daily_bank " +
                "WHERE tenant_id = :tid AND business_date >= :start AND business_date <= :asOf " +
                "GROUP BY business_date ORDER BY business_date";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);
        q.setParameter("start", monthStart);
        q.setParameter("asOf", asOf);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        Map<LocalDate, Double> actualByDay = new LinkedHashMap<>();
        double mtd = 0.0;
        for (Object[] r : rows) {
            LocalDate d = toLocalDate(r[0]);
            double v = r[1] == null ? 0.0 : ((Number) r[1]).doubleValue();
            actualByDay.put(d, v);
            mtd += v;
        }

        // Same blended model as summary(), for this one metric.
        int metricIdx = METRIC_ORDER.indexOf(metric);
        YearMonth lyMonth = ym.minusYears(1);
        Map<LocalDate, double[]> lyDaily = dailyTotals(tenantId, lyMonth.atDay(1), lyMonth.atEndOfMonth());
        Map<LocalDate, double[]> trailing = dailyTotals(tenantId, asOf.minusDays(27), asOf);
        Forecast f = blendForecast(mtd, metricIdx, asOf, ym, lyDaily, trailing);

        // Target (month) → flat daily line for overlay.
        int thisMonthKey = ym.getYear() * 100 + ym.getMonthValue();
        BigDecimal target = null;
        for (BankBudgetTarget t : budgetRepo.findByTenantIdAndMonthKeyBetween(tenantId, thisMonthKey, thisMonthKey)) {
            if (t.getMetricType().equals(metric)) target = t.getTargetValue();
        }

        // Distribute the projected remainder (forecast - MTD) across the
        // remaining days, weighted by the trailing weekday averages so the
        // curve keeps the weekly shape and lands exactly on forecastMonthEnd.
        double[] dowAvg = dowAverages(trailing, metricIdx);
        double remainderTotal = Math.max(0.0, f.value - mtd);
        double weightSum = 0.0;
        for (LocalDate d = asOf.plusDays(1); !d.isAfter(monthEnd); d = d.plusDays(1)) {
            weightSum += dowAvg[d.getDayOfWeek().getValue() - 1];
        }
        long remainingCount = monthEnd.toEpochDay() - asOf.toEpochDay();

        List<Map<String, Object>> series = new ArrayList<>();
        double cumActual = 0.0, cumForecast = 0.0;
        for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("date", d.toString());
            if (!d.isAfter(asOf)) {
                double v = actualByDay.getOrDefault(d, 0.0);
                cumActual += v;
                cumForecast = cumActual; // forecast tracks actual up to asOf
                pt.put("actual", round2(cumActual));
                pt.put("forecast", round2(cumForecast));
            } else {
                double w = weightSum > 0
                        ? dowAvg[d.getDayOfWeek().getValue() - 1] / weightSum
                        : (remainingCount > 0 ? 1.0 / remainingCount : 0.0);
                cumForecast += remainderTotal * w;
                pt.put("actual", null);
                pt.put("forecast", round2(cumForecast));
            }
            if (target != null) pt.put("target", target);
            series.add(pt);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("metric", metric);
        resp.put("asOfDate", asOf.toString());
        double naive = asOf.getDayOfMonth() > 0 ? mtd / asOf.getDayOfMonth() * ym.lengthOfMonth() : 0.0;
        resp.put("seasonalityFactor", round4(naive > 0 ? f.value / naive : 1.0));
        resp.put("forecastMethod", f.method);
        resp.put("forecastMonthEnd", round2(f.value));
        resp.put("target", target);
        resp.put("series", series);
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    /**
     * Seasonal comparison: current month vs same month last year, per metric.
     * Separates real growth from seasonal movement.
     *
     * GET /api/business/forecast/seasonal?asOfDate=YYYY-MM-DD
     */
    @GetMapping("/seasonal")
    public ResponseEntity<?> seasonal(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestParam(required = false) String asOfDate) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        LocalDate asOf = parseOrNull(asOfDate);
        if (asOf == null) asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();
        YearMonth ym = YearMonth.from(asOf);

        // Current month = MTD (fair like-for-like needs same elapsed window, but
        // for a monthly seasonal read we compare full-month rollups where the
        // prior year is complete; current month uses MTD and is labelled as such).
        Map<String, BigDecimal> current = mtdActuals(tenantId, ym.atDay(1), asOf);
        YearMonth lySame = ym.minusYears(1);
        Map<String, BigDecimal> priorYear = monthlyActuals(tenantId, lySame.getYear() * 100 + lySame.getMonthValue());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String metric : METRIC_ORDER) {
            BigDecimal cur = current.getOrDefault(metric, BigDecimal.ZERO);
            BigDecimal py = priorYear.getOrDefault(metric, BigDecimal.ZERO);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metricType", metric);
            m.put("currentMTD", cur);
            m.put("priorYearSameMonth", py);
            m.put("yoyGrowthPct", growthPct(cur.doubleValue(), py.doubleValue()));
            // Seasonality index: current vs prior-year, 100 = identical.
            m.put("seasonalityIndex", py.signum() > 0
                    ? round1(cur.doubleValue() / py.doubleValue() * 100.0) : null);
            out.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("currentMonthLabel", monthLabel(ym.getYear() * 100 + ym.getMonthValue()));
        resp.put("priorYearMonthLabel", monthLabel(lySame.getYear() * 100 + lySame.getMonthValue()));
        resp.put("metrics", out);
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CHURN PREDICTION  (heuristic risk score, no ML)
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Per-merchant churn risk score (0–100) + band, from transparent signals:
     *   declineScore   : 30d-over-prev-30d drop in volume and txns
     *   inactivityScore: days since last transaction
     *   ageScore       : very young merchants are inherently higher risk
     * Refund signals are intentionally omitted (feed doesn't flag refunds).
     *
     * Weights (sum 100): volume decline 35, txn decline 20, inactivity 30, age 15.
     * Bands: <25 Low, <50 Medium, <75 High, else Critical.
     *
     * POST /api/business/forecast/churn-prediction  (VolumeRevenueFilterDTO body)
     */
    @PostMapping("/churn-prediction")
    public ResponseEntity<?> churnPrediction(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) com.acquira.common.dto.VolumeRevenueFilterDTO filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new com.acquira.common.dto.VolumeRevenueFilterDTO();

        LocalDate asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();
        LocalDate last30Start = asOf.minusDays(29);
        LocalDate prev30End   = last30Start.minusDays(1);
        LocalDate prev30Start = prev30End.minusDays(29);

        boolean needStore = listNonEmpty(filter.getMccList());
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid, m.name, m.created_date, m.sales_email, m.referral_partner, ");
        // total_txns > 0: ancillary-only rows (rental/DCC on a no-sale day)
        // must not reset a declining merchant's inactivity signal to "today".
        sql.append("  MAX(CASE WHEN COALESCE(s.total_txns,0) > 0 THEN s.business_date END) AS last_txn, ");
        sql.append("  SUM(CASE WHEN s.business_date >= :l30 THEN s.total_base_volume ELSE 0 END) AS vol30, ");
        sql.append("  SUM(CASE WHEN s.business_date >= :p30s AND s.business_date <= :p30e THEN s.total_base_volume ELSE 0 END) AS volPrev30, ");
        sql.append("  SUM(CASE WHEN s.business_date >= :l30 THEN s.total_txns ELSE 0 END) AS txn30, ");
        sql.append("  SUM(CASE WHEN s.business_date >= :p30s AND s.business_date <= :p30e THEN s.total_txns ELSE 0 END) AS txnPrev30, ");
        sql.append("  SUM(CASE WHEN s.business_date >= :l30 THEN s.total_msf ELSE 0 END) AS msf30 ");
        sql.append("FROM sum_daily_merchant s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) sql.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
        sql.append("WHERE s.tenant_id = :tid AND s.business_date >= :p30s AND s.business_date <= :asOf ");
        appendMerchantFilters(sql, filter, needStore);
        sql.append("GROUP BY m.mid, m.name, m.created_date, m.sales_email, m.referral_partner");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("asOf", asOf);
        q.setParameter("l30", last30Start);
        q.setParameter("p30s", prev30Start);
        q.setParameter("p30e", prev30End);
        bindMerchantFilters(q, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        int[] bandCounts = new int[4]; // low, med, high, critical
        double potentialVolLoss = 0, potentialRevLoss = 0;

        for (Object[] r : rows) {
            LocalDate lastTxn = toLocalDate(r[5]);
            double vol30 = dbl(r[6]), volPrev30 = dbl(r[7]);
            long txn30 = lng(r[8]), txnPrev30 = lng(r[9]);
            double msf30 = dbl(r[10]);

            // Skip merchants with no activity in either window — nothing to churn.
            if (vol30 == 0 && volPrev30 == 0) continue;

            int daysSince = lastTxn == null ? 999 : (int) java.time.temporal.ChronoUnit.DAYS.between(lastTxn, asOf);
            LocalDate created = toLocalDate(r[2]);
            int ageDays = created == null ? 365 : (int) java.time.temporal.ChronoUnit.DAYS.between(created, asOf);

            // Signal 1: volume decline (0..1 where 1 = fully collapsed).
            double volDecline = volPrev30 > 0 ? clamp01((volPrev30 - vol30) / volPrev30) : (vol30 == 0 ? 1.0 : 0.0);
            // Signal 2: txn decline.
            double txnDecline = txnPrev30 > 0 ? clamp01((double) (txnPrev30 - txn30) / txnPrev30) : (txn30 == 0 ? 1.0 : 0.0);
            // Signal 3: inactivity — ramps 0..1 across 0..45 idle days.
            double inactivity = clamp01(daysSince / 45.0);
            // Signal 4: youth — <90 days = full weight, fading to 0 by 365 days.
            double youth = ageDays >= 365 ? 0.0 : clamp01((365 - ageDays) / 275.0);

            double score = 35 * volDecline + 20 * txnDecline + 30 * inactivity + 15 * youth;
            score = Math.max(0, Math.min(100, score));
            String band = score < 25 ? "LOW" : score < 50 ? "MEDIUM" : score < 75 ? "HIGH" : "CRITICAL";
            bandCounts[band.equals("LOW") ? 0 : band.equals("MEDIUM") ? 1 : band.equals("HIGH") ? 2 : 3]++;

            // Predicted dormancy date: crude projection — if declining, when the
            // 30d run-rate would hit zero at the current decline slope.
            String predictedDormancy = null;
            if (volDecline > 0 && vol30 > 0) {
                double monthlyDrop = Math.max(0.0001, (volPrev30 - vol30));
                int monthsToZero = (int) Math.ceil(vol30 / monthlyDrop);
                if (monthsToZero > 0 && monthsToZero <= 12)
                    predictedDormancy = asOf.plusMonths(monthsToZero).toString();
            }

            // At-risk contribution to potential loss (High/Critical only).
            if (score >= 50) { potentialVolLoss += vol30; potentialRevLoss += msf30; }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mid", r[0]);
            map.put("name", r[1]);
            map.put("rm", r[3]);
            map.put("referralPartner", r[4]);
            map.put("churnRiskScore", round1(score));
            map.put("churnProbabilityPct", round1(score)); // score already 0-100 → read as probability
            map.put("riskBand", band);
            map.put("daysSinceLastTxn", daysSince);
            map.put("vol30", round2(vol30));
            map.put("volPrev30", round2(volPrev30));
            map.put("volDeclinePct", round1(volPrev30 > 0 ? (vol30 - volPrev30) / volPrev30 * 100 : 0));
            map.put("msf30", round2(msf30));
            map.put("predictedDormancyDate", predictedDormancy);
            out.add(map);
        }

        out.sort((a, b) -> Double.compare(
                ((Number) b.get("churnRiskScore")).doubleValue(),
                ((Number) a.get("churnRiskScore")).doubleValue()));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("rows", out);
        resp.put("summary", Map.of(
                "lowRisk", bandCounts[0], "mediumRisk", bandCounts[1],
                "highRisk", bandCounts[2], "criticalRisk", bandCounts[3],
                "atRiskMerchantCount", bandCounts[2] + bandCounts[3],
                "potentialVolumeLoss", round2(potentialVolLoss),
                "potentialRevenueLoss", round2(potentialRevLoss)));
        resp.put("assumptions", Map.of(
                "model", "weighted heuristic (no ML)",
                "weights", "volume decline 35, txn decline 20, inactivity 30, merchant age 15",
                "windows", "last 30 days vs prior 30 days; inactivity scaled over 45 days"));
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MARGIN RISK FORECAST
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Per-merchant projected margin over the trailing 30 days, flagging low or
     * negative margin with reason tags. Margin = net / gross where
     * net = MSF - interchange - scheme fees, gross ≈ MSF (revenue base).
     *
     * POST /api/business/forecast/margin-risk  (VolumeRevenueFilterDTO body)
     */
    @PostMapping("/margin-risk")
    public ResponseEntity<?> marginRisk(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) com.acquira.common.dto.VolumeRevenueFilterDTO filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new com.acquira.common.dto.VolumeRevenueFilterDTO();

        LocalDate asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();
        LocalDate start = asOf.minusDays(29);

        boolean needStore = listNonEmpty(filter.getMccList());
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid, m.name, m.sales_email, ");
        sql.append("  SUM(s.total_base_volume) AS vol, SUM(s.total_msf) AS msf, ");
        sql.append("  SUM(s.total_interchange) AS interchange, SUM(s.total_scheme_fee) AS scheme, ");
        sql.append("  SUM(s.total_margin) AS margin, SUM(s.total_txns) AS txns ");
        sql.append("FROM sum_daily_merchant s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) sql.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
        sql.append("WHERE s.tenant_id = :tid AND s.business_date >= :start AND s.business_date <= :asOf ");
        appendMerchantFilters(sql, filter, needStore);
        sql.append("GROUP BY m.mid, m.name, m.sales_email HAVING SUM(s.total_base_volume) > 0");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("start", start);
        q.setParameter("asOf", asOf);
        bindMerchantFilters(q, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        int lossMaking = 0, lowMargin = 0, repriceCandidates = 0;
        double expectedShortfall = 0;

        for (Object[] r : rows) {
            double vol = dbl(r[3]), msf = dbl(r[4]), interchange = dbl(r[5]), scheme = dbl(r[6]);
            double marginCol = dbl(r[7]);
            long txns = lng(r[8]);

            // Net margin: prefer stored total_margin when present, else derive.
            double net = marginCol != 0 ? marginCol : (msf - interchange - scheme);
            double gross = msf != 0 ? msf : (net); // revenue base
            double marginPct = gross != 0 ? net / gross * 100.0 : 0.0;
            double msfRateBps = vol > 0 ? msf / vol * 10000.0 : 0.0;
            double interchangeRateBps = vol > 0 ? interchange / vol * 10000.0 : 0.0;

            // Reason tags.
            List<String> reasons = new ArrayList<>();
            if (msfRateBps > 0 && msfRateBps < 80) reasons.add("Low Pricing Rate");
            if (interchangeRateBps > msfRateBps * 0.7 && interchangeRateBps > 0) reasons.add("High Interchange Cost");
            if (scheme > msf * 0.25 && scheme > 0) reasons.add("High Scheme Fees");
            if (net < 0) reasons.add("Loss-Making");

            String riskBand;
            if (net < 0) { riskBand = "LOSS_MAKING"; lossMaking++; expectedShortfall += Math.abs(net); }
            else if (marginPct < 20) { riskBand = "LOW_MARGIN"; lowMargin++; }
            else riskBand = "HEALTHY";
            if (net >= 0 && marginPct < 30 && msfRateBps < 90) repriceCandidates++;

            // Only surface at-risk merchants in the list (healthy excluded to keep it a watchlist).
            if ("HEALTHY".equals(riskBand)) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mid", r[0]);
            map.put("name", r[1]);
            map.put("rm", r[2]);
            map.put("volume", round2(vol));
            map.put("msf", round2(msf));
            map.put("netRevenue", round2(net));
            map.put("forecastMarginPct", round1(marginPct));
            map.put("msfRateBps", round1(msfRateBps));
            map.put("interchangeRateBps", round1(interchangeRateBps));
            map.put("txns", txns);
            map.put("riskBand", riskBand);
            map.put("reasons", reasons);
            out.add(map);
        }

        out.sort((a, b) -> Double.compare(
                ((Number) a.get("forecastMarginPct")).doubleValue(),
                ((Number) b.get("forecastMarginPct")).doubleValue()));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("rows", out);
        resp.put("summary", Map.of(
                "lossMakingCount", lossMaking,
                "lowMarginCount", lowMargin,
                "repriceCandidateCount", repriceCandidates,
                "expectedMarginShortfall", round2(expectedShortfall)));
        resp.put("assumptions", Map.of(
                "model", "trailing 30-day margin",
                "marginFormula", "net / gross * 100; net = total_margin (or MSF - interchange - scheme fees), gross = MSF",
                "lowMarginThreshold", "< 20% margin; loss-making when net < 0"));
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PEER BENCHMARK  (merchant vs MCC-group median)
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Ranks each merchant against peers in the same MCC over the trailing 90
     * days: volume, revenue and average ticket vs the peer-group median, with a
     * percentile and index (merchant / median * 100).
     *
     * POST /api/business/forecast/peer-benchmark  (VolumeRevenueFilterDTO body)
     */
    @PostMapping("/peer-benchmark")
    public ResponseEntity<?> peerBenchmark(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) com.acquira.common.dto.VolumeRevenueFilterDTO filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new com.acquira.common.dto.VolumeRevenueFilterDTO();

        LocalDate asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();
        LocalDate start = asOf.minusDays(89);

        // Per-merchant totals + their MCC (from dim_store, joined on merchant_id
        // since sum_daily_merchant.store_id is always NULL).
        StringBuilder sql = new StringBuilder();
        sql.append("WITH mtot AS ( ");
        sql.append("  SELECT m.merchant_id, m.mid, m.name, m.sales_email, ");
        sql.append("    COALESCE(MAX(st.mcc),'0000') AS mcc, ");
        sql.append("    SUM(s.total_base_volume) AS vol, SUM(s.total_msf) AS msf, SUM(s.total_txns) AS txns ");
        sql.append("  FROM sum_daily_merchant s ");
        sql.append("  JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("  LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
        sql.append("  WHERE s.tenant_id = :tid AND s.business_date >= :start AND s.business_date <= :asOf ");
        appendMerchantFilters(sql, filter, true);
        sql.append("  GROUP BY m.merchant_id, m.mid, m.name, m.sales_email ");
        sql.append("  HAVING SUM(s.total_base_volume) > 0 ");
        sql.append(") ");
        sql.append("SELECT mid, name, sales_email, mcc, vol, msf, txns, ");
        sql.append("  vol / NULLIF(txns,0) AS avg_ticket, ");
        sql.append("  percent_rank() OVER (PARTITION BY mcc ORDER BY vol) AS vol_pctile, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY vol) OVER (PARTITION BY mcc) AS peer_vol_median, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY msf) OVER (PARTITION BY mcc) AS peer_msf_median, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY (vol / NULLIF(txns,0))) OVER (PARTITION BY mcc) AS peer_ticket_median, ");
        sql.append("  COUNT(*) OVER (PARTITION BY mcc) AS peer_count ");
        sql.append("FROM mtot ORDER BY vol DESC");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("start", start);
        q.setParameter("asOf", asOf);
        bindMerchantFilters(q, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            double vol = dbl(r[4]), msf = dbl(r[5]);
            long txns = lng(r[6]);
            double avgTicket = dbl(r[7]);
            double volPctile = dbl(r[8]) * 100.0;
            double peerVolMed = dbl(r[9]), peerMsfMed = dbl(r[10]), peerTicketMed = dbl(r[11]);
            long peerCount = lng(r[12]);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mid", r[0]);
            map.put("name", r[1]);
            map.put("rm", r[2]);
            map.put("mcc", r[3]);
            map.put("peerCount", peerCount);
            map.put("volume", round2(vol));
            map.put("revenue", round2(msf));
            map.put("avgTicket", round2(avgTicket));
            map.put("peerPercentile", round1(volPctile));
            map.put("peerIndex", round1(peerVolMed > 0 ? vol / peerVolMed * 100 : 0));
            map.put("volumeGapVsPeer", round2(vol - peerVolMed));
            map.put("revenueGapVsPeer", round2(msf - peerMsfMed));
            map.put("ticketGapVsPeer", round2(avgTicket - peerTicketMed));
            out.add(map);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("windowDays", 90);
        resp.put("rows", out);
        resp.put("assumptions", Map.of(
                "peerGroup", "same MCC",
                "window", "trailing 90 days",
                "index", "merchant metric / peer-group median * 100 (100 = at median)"));
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RM BENCHMARK  (RM vs all-RM median)
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Ranks each RM (sales_email) against all RMs in the tenant over the
     * trailing 90 days: portfolio volume, revenue, active-merchant count, and
     * per-RM medians / percentile.
     *
     * POST /api/business/forecast/rm-benchmark  (VolumeRevenueFilterDTO body)
     */
    @PostMapping("/rm-benchmark")
    public ResponseEntity<?> rmBenchmark(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) com.acquira.common.dto.VolumeRevenueFilterDTO filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new com.acquira.common.dto.VolumeRevenueFilterDTO();

        LocalDate asOf = latestDataDate(tenantId);
        if (asOf == null) asOf = LocalDate.now();
        LocalDate start = asOf.minusDays(89);

        StringBuilder sql = new StringBuilder();
        sql.append("WITH rmtot AS ( ");
        sql.append("  SELECT m.sales_email AS rm, ");
        sql.append("    SUM(s.total_base_volume) AS vol, SUM(s.total_msf) AS msf, ");
        // FILTER: an ancillary-only row (rental/DCC, no transactions) must
        // not count a merchant as active for the RM benchmark.
        sql.append("    SUM(s.total_txns) AS txns, ");
        sql.append("    COUNT(DISTINCT s.merchant_id) FILTER (WHERE COALESCE(s.total_txns,0) > 0) AS active_merchants ");
        sql.append("  FROM sum_daily_merchant s ");
        sql.append("  JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("  WHERE s.tenant_id = :tid AND s.business_date >= :start AND s.business_date <= :asOf ");
        sql.append("    AND m.sales_email IS NOT NULL AND m.sales_email <> '' ");
        sql.append("  GROUP BY m.sales_email ");
        sql.append("  HAVING SUM(s.total_base_volume) > 0 ");
        sql.append(") ");
        sql.append("SELECT rm, vol, msf, txns, active_merchants, ");
        sql.append("  percent_rank() OVER (ORDER BY vol) AS vol_pctile, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY vol) OVER () AS rm_vol_median, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY msf) OVER () AS rm_msf_median, ");
        sql.append("  percentile_cont(0.5) WITHIN GROUP (ORDER BY active_merchants) OVER () AS rm_merch_median, ");
        sql.append("  COUNT(*) OVER () AS rm_count ");
        sql.append("FROM rmtot ORDER BY vol DESC");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("start", start);
        q.setParameter("asOf", asOf);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Object[] r : rows) {
            double vol = dbl(r[1]), msf = dbl(r[2]);
            long txns = lng(r[3]), activeMerchants = lng(r[4]);
            double volPctile = dbl(r[5]) * 100.0;
            double rmVolMed = dbl(r[6]), rmMsfMed = dbl(r[7]), rmMerchMed = dbl(r[8]);
            long rmCount = lng(r[9]);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", rank++);
            map.put("rm", r[0]);
            map.put("rmCount", rmCount);
            map.put("volume", round2(vol));
            map.put("revenue", round2(msf));
            map.put("txns", txns);
            map.put("activeMerchants", activeMerchants);
            map.put("revenuePerRm", round2(msf));
            map.put("volumePerRm", round2(vol));
            map.put("peerPercentile", round1(volPctile));
            map.put("benchmarkIndex", round1(rmVolMed > 0 ? vol / rmVolMed * 100 : 0));
            map.put("volumeGapVsMedian", round2(vol - rmVolMed));
            map.put("revenueGapVsMedian", round2(msf - rmMsfMed));
            map.put("merchantGapVsMedian", activeMerchants - Math.round(rmMerchMed));
            out.add(map);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asOfDate", asOf.toString());
        resp.put("windowDays", 90);
        resp.put("rows", out);
        resp.put("assumptions", Map.of(
                "peerGroup", "all RMs in tenant",
                "window", "trailing 90 days",
                "index", "RM metric / all-RM median * 100 (100 = at median)"));
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ── Shared filter application for the merchant-grained endpoints above ──
    // Minimal subset of VolumeRevenueFilterDTO relevant to these queries; every
    // predicate is parameterised. Store join must already be present when
    // needStore is true (MCC filter).
    private void appendMerchantFilters(StringBuilder sql, com.acquira.common.dto.VolumeRevenueFilterDTO f, boolean storeJoined) {
        if (listNonEmpty(f.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(f.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(f.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(f.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(f.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (storeJoined && listNonEmpty(f.getMccList())) sql.append("AND st.mcc IN (:mccs) ");
    }

    private void bindMerchantFilters(Query q, com.acquira.common.dto.VolumeRevenueFilterDTO f) {
        if (listNonEmpty(f.getPartnerList()))    q.setParameter("partners", f.getPartnerList());
        if (listNonEmpty(f.getRmList()))         q.setParameter("rms", f.getRmList());
        if (listNonEmpty(f.getTeamLeaderList())) q.setParameter("teamLeaders", f.getTeamLeaderList());
        if (listNonEmpty(f.getMidList()))        q.setParameter("mids", f.getMidList());
        if (listNonEmpty(f.getIndustryList()))   q.setParameter("industries", f.getIndustryList());
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
            q.setParameter("merchName", "%" + f.getMerchantName() + "%");
        if (listNonEmpty(f.getMccList()))        q.setParameter("mccs", f.getMccList());
    }

    private static boolean listNonEmpty(java.util.List<?> l) { return l != null && !l.isEmpty(); }
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double dbl(Object o) { return o == null ? 0.0 : ((Number) o).doubleValue(); }
    private static long lng(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    // ════════════════════════════════════════════════════════════════════════
    //  Forecast estimators (shared by summary() and trend())
    // ════════════════════════════════════════════════════════════════════════

    /** Blended month-end forecast plus its components, for transparency. */
    private static final class Forecast {
        final double value;      // final blended forecast
        final Double pacing;     // estimator A (null when LY profile unavailable)
        final Double dow;        // estimator B (null when no trailing data)
        final String method;     // which path produced `value`
        Forecast(double value, Double pacing, Double dow, String method) {
            this.value = value; this.pacing = pacing; this.dow = dow; this.method = method;
        }
    }

    // Pacing guard: if LY says less than 3% of the month had elapsed by this
    // day-of-month, MTD/pacing would explode on noise — fall back to DOW.
    private static final double MIN_PACING = 0.03;

    /**
     * final = w*A + (1-w)*B with w = elapsed/total calendar days.
     * A = MTD / LY intra-month pacing; B = MTD + DOW-average projection.
     */
    private Forecast blendForecast(double mtd, int metricIdx, LocalDate asOf, YearMonth ym,
                                   Map<LocalDate, double[]> lyDaily, Map<LocalDate, double[]> trailing) {
        int elapsedDays = asOf.getDayOfMonth();
        int totalDays = ym.lengthOfMonth();

        // A: prior-year pacing.
        Double pacingForecast = null;
        if (!lyDaily.isEmpty() && mtd > 0) {
            YearMonth lyMonth = ym.minusYears(1);
            int lyCutDay = Math.min(elapsedDays, lyMonth.lengthOfMonth());
            double lyTotal = 0.0, lyCut = 0.0;
            for (Map.Entry<LocalDate, double[]> e : lyDaily.entrySet()) {
                double v = e.getValue()[metricIdx];
                lyTotal += v;
                if (e.getKey().getDayOfMonth() <= lyCutDay) lyCut += v;
            }
            if (lyTotal > 0 && lyCut / lyTotal >= MIN_PACING) {
                pacingForecast = mtd / (lyCut / lyTotal);
            }
        }

        // B: day-of-week run rate over the trailing 28 days.
        Double dowForecast = null;
        if (!trailing.isEmpty()) {
            double[] dowAvg = dowAverages(trailing, metricIdx);
            double projected = mtd;
            for (LocalDate d = asOf.plusDays(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                projected += dowAvg[d.getDayOfWeek().getValue() - 1];
            }
            dowForecast = projected;
        }

        double w = totalDays > 0 ? (double) elapsedDays / totalDays : 1.0;
        if (pacingForecast != null && dowForecast != null) {
            return new Forecast(w * pacingForecast + (1 - w) * dowForecast,
                    pacingForecast, dowForecast, "PACING_DOW_BLEND");
        }
        if (pacingForecast != null) return new Forecast(pacingForecast, pacingForecast, null, "PACING_ONLY");
        if (dowForecast != null)    return new Forecast(dowForecast, null, dowForecast, "DOW_ONLY");
        // Last resort: plain calendar-day linear run rate.
        double linear = elapsedDays > 0 ? mtd / elapsedDays * totalDays : mtd;
        return new Forecast(linear, null, null, "LINEAR_RUN_RATE");
    }

    /**
     * Average daily value per weekday (index 0 = Monday) over the given daily
     * map. Weekdays with no samples fall back to the overall daily mean, so a
     * short history never projects zeros for an unobserved weekday.
     */
    private static double[] dowAverages(Map<LocalDate, double[]> daily, int metricIdx) {
        double[] sum = new double[7];
        int[] n = new int[7];
        double all = 0.0;
        for (Map.Entry<LocalDate, double[]> e : daily.entrySet()) {
            int i = e.getKey().getDayOfWeek().getValue() - 1;
            double v = e.getValue()[metricIdx];
            sum[i] += v; n[i]++; all += v;
        }
        double overallMean = daily.isEmpty() ? 0.0 : all / daily.size();
        double[] avg = new double[7];
        for (int i = 0; i < 7; i++) avg[i] = n[i] > 0 ? sum[i] / n[i] : overallMean;
        return avg;
    }

    /**
     * Per-day totals for all four metrics (VOLUME, NET_REVENUE, MSF, TXNS —
     * same order as METRIC_ORDER) over [from, to], one scan of sum_daily_bank.
     * Days with no row are simply absent.
     */
    private Map<LocalDate, double[]> dailyTotals(Long tenantId, LocalDate from, LocalDate to) {
        String sql = "SELECT business_date, " +
                "COALESCE(SUM(total_volume),0), COALESCE(SUM(total_net_revenue),0), " +
                "COALESCE(SUM(total_msf),0), COALESCE(SUM(total_txns),0) " +
                "FROM sum_daily_bank WHERE tenant_id = :tid " +
                "AND business_date >= :from AND business_date <= :to " +
                "GROUP BY business_date";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);
        q.setParameter("from", from);
        q.setParameter("to", to);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<LocalDate, double[]> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            LocalDate d = toLocalDate(r[0]);
            if (d == null) continue;
            out.put(d, new double[]{dbl(r[1]), dbl(r[2]), dbl(r[3]), dbl(r[4])});
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Data helpers
    // ════════════════════════════════════════════════════════════════════════

    /** MTD actuals for all metrics in one scan of sum_daily_bank. */
    private Map<String, BigDecimal> mtdActuals(Long tenantId, LocalDate start, LocalDate asOf) {
        String sql = "SELECT " +
                "COALESCE(SUM(total_volume),0), " +
                "COALESCE(SUM(total_net_revenue),0), " +
                "COALESCE(SUM(total_msf),0), " +
                "COALESCE(SUM(total_txns),0) " +
                "FROM sum_daily_bank WHERE tenant_id = :tid " +
                "AND business_date >= :start AND business_date <= :asOf";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);
        q.setParameter("start", start);
        q.setParameter("asOf", asOf);
        Object[] r = (Object[]) q.getSingleResult();
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("VOLUME", bd(r[0]));
        m.put("NET_REVENUE", bd(r[1]));
        m.put("MSF", bd(r[2]));
        m.put("TXNS", bd(r[3]));
        return m;
    }

    /** Monthly actuals for one month_key from sum_monthly_bank (JPA repo). */
    private Map<String, BigDecimal> monthlyActuals(Long tenantId, int monthKey) {
        List<SumMonthlyBank> list = monthlyBankRepo.findByTenantIdAndMonthKeyBetween(tenantId, monthKey, monthKey);
        Map<String, BigDecimal> m = new HashMap<>();
        if (list.isEmpty()) return m;
        SumMonthlyBank a = list.get(0);
        m.put("VOLUME", nz(a.getTotalVolume()));
        m.put("NET_REVENUE", nz(a.getTotalNetRevenue()));
        m.put("MSF", nz(a.getTotalMsf()));
        m.put("TXNS", a.getTotalTxns() == null ? BigDecimal.ZERO : BigDecimal.valueOf(a.getTotalTxns()));
        return m;
    }

    /** Latest business_date with data for the tenant (anchor for "current" month). */
    private LocalDate latestDataDate(Long tenantId) {
        try {
            Query q = entityManager.createNativeQuery(
                    "SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = :tid");
            q.setParameter("tid", tenantId);
            Object r = q.getSingleResult();
            return r == null ? null : toLocalDate(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static String agg(String col) {
        // total_txns is a count → SUM as-is; measures → SUM as-is too. Kept as a
        // seam in case a metric ever needs AVG.
        return "COALESCE(SUM(" + col + "),0)";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pure helpers
    // ════════════════════════════════════════════════════════════════════════

    /** On Track / At Risk / Behind / Likely to Exceed from forecast attainment %. */
    private static String riskStatus(double attainmentPct) {
        if (attainmentPct >= 110) return "LIKELY_TO_EXCEED";
        if (attainmentPct >= 100) return "ON_TRACK";
        if (attainmentPct >= 85)  return "AT_RISK";
        return "BEHIND";
    }

    private static double growthPct(double current, double base) {
        if (base == 0) return current > 0 ? 100.0 : 0.0;
        return round1(((current - base) / base) * 100.0).doubleValue();
    }

    private static LocalDate parseOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date) return ((java.sql.Date) o).toLocalDate();
        if (o instanceof LocalDate) return (LocalDate) o;
        if (o instanceof java.sql.Timestamp) return ((java.sql.Timestamp) o).toLocalDateTime().toLocalDate();
        try { return LocalDate.parse(o.toString().substring(0, 10)); } catch (Exception e) { return null; }
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal round1(double v) { return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP); }
    private static BigDecimal round2(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal round4(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }

    private static String monthLabel(Integer monthKey) {
        if (monthKey == null) return "";
        int y = monthKey / 100, m = monthKey % 100;
        if (m < 1 || m > 12) return String.valueOf(monthKey);
        return YearMonth.of(y, m).getMonth().name().substring(0, 3) + " " + y;
    }
}
