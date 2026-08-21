package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.BankBudgetTarget;
import com.acquira.common.model.SumMonthlyBank;
import com.acquira.common.repository.BankBudgetTargetRepository;
import com.acquira.common.repository.SumMonthlyBankRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Budget targets + attainment KPI.
 *
 * Storage stays MONTHLY (bank_budget_target, one row per tenant/month/metric)
 * — that's the correct grain for a business that has real seasonality. Two
 * things sit on top of that grain, both additive:
 *
 *   - A "yearly" ENTRY mode (POST /targets/yearly): the user enters one
 *     annual number + a phasing strategy, and the backend writes the 12
 *     monthly rows for them (equal split / prior-year-seasonality / manual
 *     12-cell grid). Nothing new is stored — this only changes how targets
 *     get written.
 *   - A "yearly" VIEW mode (GET /attainment?year=YYYY): same monthly rows,
 *     but the response adds year-scoped roll-ups (ytdTarget/ytdAttainmentPct,
 *     run-rate projection to full-year) so a dashboard can show "on pace for
 *     94% of the annual target" without the caller doing that math.
 *
 * Metrics (mapped to sum_monthly_bank columns):
 *   VOLUME       -> total_volume       (cardholder currency)
 *   BASE_VOLUME  -> total_base_volume  (settlement currency)
 *   NET_REVENUE  -> total_net_revenue
 *   MSF          -> total_msf
 *   TXNS         -> total_txns
 *
 * Numbers-tie-with-dashboards contract: this endpoint is the TARGET source of
 * truth only. Callers (Business Dashboard, Executive Dashboard) should keep
 * using their own existing actuals (kpis-filtered / revenue-kpis) for the
 * "actual" side of any tile and pull only targetValue/attainment context from
 * here — that guarantees a dashboard's budget tile never disagrees with its
 * own KPI tiles. The actualValue returned here (from sum_monthly_bank) is
 * provided for the standalone Budget Targets page, which has no other
 * actuals source.
 *
 * Data-lag / future-month handling (2026-07-10 fix):
 *   `dataThrough` = the latest month_key that actually has a sum_monthly_bank
 *   row for the tenant (clamped to the current calendar month). Any target
 *   row whose monthKey is beyond dataThrough is marked `future: true` /
 *   `status: "UPCOMING"` instead of being scored 0%/BEHIND — a month that
 *   hasn't happened yet, or hasn't been ingested yet, is not a missed target.
 *   YTD accumulation (ytdTarget, monthsElapsed, run-rate) is bounded by
 *   dataThrough rather than the calendar's currentMonthKey, so an ingestion
 *   lag doesn't silently deflate YTD attainment or the run-rate projection.
 *   The in-progress calendar month (if it already has data) contributes a
 *   fractional month (days-elapsed / days-in-month) to the run-rate divisor
 *   instead of a full month, so early-month run-rate isn't under-projected.
 *
 * Additive & isolated: touches nothing else. Every query is tenant-scoped.
 */
@RestController
@RequestMapping("/api/business/budget")
public class BudgetTargetController {

    private static final Set<String> VALID_METRICS =
            Set.of("VOLUME", "BASE_VOLUME", "NET_REVENUE", "MSF", "TXNS");

    private static final Set<String> VALID_PHASING = Set.of("EQUAL", "SEASONAL", "MANUAL");

    private final BankBudgetTargetRepository budgetRepo;
    private final SumMonthlyBankRepository monthlyBankRepo;
    /** Stamps the tenant's currency onto every money-bearing response. */
    private final CurrencyMeta currencyMeta;

    public BudgetTargetController(BankBudgetTargetRepository budgetRepo,
                                  SumMonthlyBankRepository monthlyBankRepo,
                                  CurrencyMeta currencyMeta) {
        this.budgetRepo = budgetRepo;
        this.monthlyBankRepo = monthlyBankRepo;
        this.currencyMeta = currencyMeta;
    }

    private Long resolveTenant(Long headerTenant) {
        // SECURITY: the raw X-Tenant-Id header is attacker-controlled; use only the
        // filter-validated TenantContext (JwtRequestFilter rejects spoofed headers).
        return TenantContext.getCurrentTenant();
    }

    /** List all targets for the tenant, newest month first. */
    @GetMapping("/targets")
    public ResponseEntity<?> listTargets(@RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant) {
        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(budgetRepo.findByTenantIdOrderByMonthKeyDesc(tenantId));
    }

    /**
     * Upsert a target for (tenant, monthKey, metricType). If a row already
     * exists it is updated in place (one target per metric per month), else a
     * new one is created. Keeps the table clean — no duplicate targets.
     */
    @PostMapping("/targets")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> upsertTarget(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody Map<String, Object> body) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        Integer monthKey = toInt(body.get("monthKey"));
        String metric = body.get("metricType") == null ? null : body.get("metricType").toString().trim().toUpperCase();
        BigDecimal target = toBigDecimal(body.get("targetValue"));

        if (monthKey == null || !isValidMonthKey(monthKey))
            return ResponseEntity.badRequest().body(Map.of("error", "monthKey must be YYYYMM"));
        if (metric == null || !VALID_METRICS.contains(metric))
            return ResponseEntity.badRequest().body(Map.of("error", "metricType must be one of " + VALID_METRICS));
        if (target == null || target.signum() < 0)
            return ResponseEntity.badRequest().body(Map.of("error", "targetValue must be >= 0"));

        return ResponseEntity.ok(upsertOne(tenantId, monthKey, metric, target));
    }

    /**
     * Yearly entry: one annual number, phased into 12 monthly rows.
     *
     *   phasing = EQUAL    -> annualTarget / 12 each month (remainder absorbed
     *                         into December so the 12 rows sum exactly).
     *   phasing = SEASONAL -> weighted by the prior year's actual monthly mix
     *                         for the same metric (sum_monthly_bank, year-1).
     *                         Falls back to EQUAL if the prior year has no
     *                         usable actuals (response flags phasingFallback).
     *   phasing = MANUAL   -> monthlyValues[12] used as-is; annualTarget is
     *                         ignored (the 12 cells ARE the plan).
     */
    @PostMapping("/targets/yearly")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> upsertYearlyTarget(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody Map<String, Object> body) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        Integer year = toInt(body.get("year"));
        String metric = body.get("metricType") == null ? null : body.get("metricType").toString().trim().toUpperCase();
        String phasing = body.get("phasing") == null ? "EQUAL" : body.get("phasing").toString().trim().toUpperCase();

        if (year == null || year < 2000 || year > 2100)
            return ResponseEntity.badRequest().body(Map.of("error", "year must be a valid 4-digit year"));
        if (metric == null || !VALID_METRICS.contains(metric))
            return ResponseEntity.badRequest().body(Map.of("error", "metricType must be one of " + VALID_METRICS));
        if (!VALID_PHASING.contains(phasing))
            return ResponseEntity.badRequest().body(Map.of("error", "phasing must be one of " + VALID_PHASING));

        BigDecimal[] monthly = new BigDecimal[12];
        boolean phasingFallback = false;

        if ("MANUAL".equals(phasing)) {
            Object raw = body.get("monthlyValues");
            if (!(raw instanceof List) || ((List<?>) raw).size() != 12)
                return ResponseEntity.badRequest().body(Map.of("error", "monthlyValues must be an array of 12 numbers for MANUAL phasing"));
            List<?> list = (List<?>) raw;
            for (int i = 0; i < 12; i++) {
                BigDecimal v = toBigDecimal(list.get(i));
                if (v == null || v.signum() < 0)
                    return ResponseEntity.badRequest().body(Map.of("error", "monthlyValues[" + i + "] must be a non-negative number"));
                monthly[i] = v;
            }
        } else {
            BigDecimal annualTarget = toBigDecimal(body.get("annualTarget"));
            if (annualTarget == null || annualTarget.signum() < 0)
                return ResponseEntity.badRequest().body(Map.of("error", "annualTarget must be a non-negative number"));

            if ("SEASONAL".equals(phasing)) {
                int priorFrom = (year - 1) * 100 + 1;
                int priorTo = (year - 1) * 100 + 12;
                List<SumMonthlyBank> priorYear = monthlyBankRepo.findByTenantIdAndMonthKeyBetween(tenantId, priorFrom, priorTo);
                Map<Integer, BigDecimal> priorByMonth = new HashMap<>();
                BigDecimal priorTotal = BigDecimal.ZERO;
                for (SumMonthlyBank a : priorYear) {
                    BigDecimal v = metricActual(metric, a);
                    priorByMonth.put(a.getMonthKey() % 100, v);
                    priorTotal = priorTotal.add(v);
                }
                if (priorYear.size() < 12 || priorTotal.signum() <= 0) {
                    // Not enough prior-year signal — fall back to equal split
                    // rather than dividing by a near-zero/incomplete base.
                    phasingFallback = true;
                    monthly = equalSplit(annualTarget);
                } else {
                    BigDecimal allocated = BigDecimal.ZERO;
                    for (int m = 1; m <= 11; m++) {
                        BigDecimal weight = priorByMonth.getOrDefault(m, BigDecimal.ZERO)
                                .divide(priorTotal, 10, RoundingMode.HALF_UP);
                        BigDecimal share = annualTarget.multiply(weight).setScale(2, RoundingMode.HALF_UP);
                        monthly[m - 1] = share;
                        allocated = allocated.add(share);
                    }
                    // December absorbs the rounding remainder so the 12 rows
                    // sum exactly to annualTarget.
                    monthly[11] = annualTarget.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
                }
            } else {
                monthly = equalSplit(annualTarget);
            }
        }

        List<BankBudgetTarget> saved = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            int monthKey = year * 100 + m;
            saved.add(upsertOne(tenantId, monthKey, metric, monthly[m - 1]));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("year", year);
        resp.put("metricType", metric);
        resp.put("phasing", phasing);
        resp.put("phasingFallback", phasingFallback);
        resp.put("targets", saved);
        return ResponseEntity.ok(resp);
    }

    /** Even 12-way split with the rounding remainder absorbed into December. */
    private static BigDecimal[] equalSplit(BigDecimal annualTarget) {
        BigDecimal[] monthly = new BigDecimal[12];
        BigDecimal each = annualTarget.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < 11; i++) {
            monthly[i] = each;
            allocated = allocated.add(each);
        }
        monthly[11] = annualTarget.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
        return monthly;
    }

    private BankBudgetTarget upsertOne(Long tenantId, Integer monthKey, String metric, BigDecimal target) {
        BankBudgetTarget row = budgetRepo
                .findByTenantIdAndMonthKeyAndMetricType(tenantId, monthKey, metric)
                .orElseGet(BankBudgetTarget::new);
        row.setTenantId(tenantId);
        row.setMonthKey(monthKey);
        row.setMetricType(metric);
        row.setTargetValue(target);
        if (row.getCreatedAt() == null) row.setCreatedAt(java.time.LocalDateTime.now());
        return budgetRepo.save(row);
    }

    @DeleteMapping("/targets/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> deleteTarget(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @PathVariable Long id) {
        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        Optional<BankBudgetTarget> row = budgetRepo.findById(id);
        // Tenant-ownership check: never delete another tenant's target.
        if (row.isEmpty() || !tenantId.equals(row.get().getTenantId()))
            return ResponseEntity.status(404).build();
        budgetRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    /**
     * Attainment for a month range or a full year. Three ways to call it:
     *
     *   ?year=2026                       -> Jan..Dec of that year, plus
     *                                        year-scoped summary fields
     *                                        (ytdTarget, ytdAttainmentPct,
     *                                        runRateProjection,
     *                                        projectedAttainmentPct).
     *   ?fromMonth=&toMonth=              -> explicit range, as before.
     *   (no params)                       -> Jan of current year through the
     *                                        LATEST month that actually has
     *                                        data in sum_monthly_bank (never
     *                                        past it — a target for a month
     *                                        that hasn't been ingested yet
     *                                        always reads 0%/BEHIND, which is
     *                                        a data-lag artifact, not a real
     *                                        attainment signal).
     *
     * For whichever row is the CURRENT calendar month, also returns
     * `partial: true` and `paceAttainmentPct` — actual measured against the
     * target pro-rated for days elapsed in the month, so an in-progress
     * month doesn't read as "behind" purely because it isn't over yet.
     *
     * Any row beyond `dataThrough` (the latest month with actual data,
     * clamped to the current calendar month) is returned with `future: true`
     * and `status: "UPCOMING"` instead of being scored — a month that hasn't
     * happened, or hasn't been ingested, is not a missed target. YTD/run-rate
     * accumulation in the year-view summary is likewise bounded by
     * `dataThrough`, not the calendar month, so an ingestion lag doesn't
     * silently deflate the numbers.
     */
    @GetMapping("/attainment")
    public ResponseEntity<?> attainment(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toMonth) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        LocalDate now = LocalDate.now();
        int currentMonthKey = now.getYear() * 100 + now.getMonthValue();
        boolean yearView = year != null;

        // Latest month that actually has ingested data, clamped to "now".
        // Used to distinguish "future/not-yet-ingested" months from
        // genuinely missed targets, and to bound YTD/run-rate math so a
        // batch/ingestion lag doesn't deflate them.
        Integer maxActualMonthKey = monthlyBankRepo.findMaxMonthKey(tenantId);
        Integer dataThrough = (maxActualMonthKey == null)
                ? null
                : Math.min(maxActualMonthKey, currentMonthKey);
        boolean dataLag = dataThrough != null && dataThrough < currentMonthKey;

        if (yearView) {
            fromMonth = year * 100 + 1;
            toMonth = year * 100 + 12;
        } else if (fromMonth == null && toMonth == null) {
            // Default range: Jan of current year -> latest month with actual
            // data (falls back to the current calendar month if the tenant
            // has no sum_monthly_bank rows at all yet).
            int clampTo = (dataThrough != null) ? dataThrough : currentMonthKey;
            fromMonth = now.getYear() * 100 + 1;
            toMonth = clampTo;
        } else if (fromMonth == null) {
            fromMonth = toMonth;
        } else if (toMonth == null) {
            toMonth = fromMonth;
        }
        if (fromMonth > toMonth) { Integer t = fromMonth; fromMonth = toMonth; toMonth = t; }

        List<BankBudgetTarget> targets =
                budgetRepo.findByTenantIdAndMonthKeyBetween(tenantId, fromMonth, toMonth);
        List<SumMonthlyBank> actuals =
                monthlyBankRepo.findByTenantIdAndMonthKeyBetween(tenantId, fromMonth, toMonth);

        Map<Integer, SumMonthlyBank> actualByMonth = new HashMap<>();
        for (SumMonthlyBank a : actuals) actualByMonth.put(a.getMonthKey(), a);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal> targetByMetric = new HashMap<>();
        Map<String, BigDecimal> actualByMetric = new HashMap<>();
        // YTD-specific accumulators: only months with actual data, bounded by
        // dataThrough (not the raw calendar month — see class javadoc).
        Map<String, BigDecimal> ytdTargetByMetric = new HashMap<>();
        Map<String, BigDecimal> monthsElapsedByMetric = new HashMap<>();

        int daysInCurrentMonth = YearMonth.of(now.getYear(), now.getMonthValue()).lengthOfMonth();
        BigDecimal elapsedFraction = BigDecimal.valueOf(now.getDayOfMonth())
                .divide(BigDecimal.valueOf(daysInCurrentMonth), 6, RoundingMode.HALF_UP);

        for (BankBudgetTarget t : targets) {
            SumMonthlyBank a = actualByMonth.get(t.getMonthKey());
            BigDecimal actualVal = metricActual(t.getMetricType(), a);
            BigDecimal targetVal = t.getTargetValue() == null ? BigDecimal.ZERO : t.getTargetValue();
            boolean isCurrentMonth = t.getMonthKey().equals(currentMonthKey);
            // A month beyond dataThrough hasn't happened yet, or hasn't been
            // ingested yet — either way it isn't a missed target.
            boolean isFuture = (dataThrough == null) || (t.getMonthKey() > dataThrough);

            BigDecimal attainmentPct = isFuture ? null : pct(actualVal, targetVal);
            BigDecimal variance = isFuture ? null : actualVal.subtract(targetVal);
            String status = isFuture ? "UPCOMING" : statusOf(attainmentPct);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("budgetId", t.getBudgetId());
            row.put("monthKey", t.getMonthKey());
            row.put("monthLabel", monthLabel(t.getMonthKey()));
            row.put("metricType", t.getMetricType());
            row.put("basis", basisOf(t.getMetricType()));
            row.put("targetValue", targetVal);
            row.put("actualValue", actualVal);
            row.put("attainmentPct", attainmentPct);
            row.put("variance", variance);
            row.put("status", status);
            row.put("future", isFuture);
            row.put("partial", isCurrentMonth && !isFuture);
            if (isCurrentMonth && !isFuture) {
                BigDecimal paceTarget = targetVal.multiply(elapsedFraction);
                row.put("paceAttainmentPct", pct(actualVal, paceTarget));
                row.put("daysElapsed", now.getDayOfMonth());
                row.put("daysInMonth", daysInCurrentMonth);
            }
            rows.add(row);

            targetByMetric.merge(t.getMetricType(), targetVal, BigDecimal::add);
            actualByMetric.merge(t.getMetricType(), actualVal, BigDecimal::add);

            if (!isFuture) {
                ytdTargetByMetric.merge(t.getMetricType(), targetVal, BigDecimal::add);
                // Full month for anything strictly before the current month;
                // a fractional month (days elapsed / days in month) for the
                // in-progress current month, so early-month run-rate isn't
                // dragged down by a nearly-empty partial month.
                BigDecimal monthWeight = isCurrentMonth ? elapsedFraction : BigDecimal.ONE;
                monthsElapsedByMetric.merge(t.getMetricType(), monthWeight, BigDecimal::add);
            }
        }

        rows.sort(Comparator
                .comparingInt((Map<String, Object> r) -> (Integer) r.get("monthKey"))
                .thenComparing(r -> (String) r.get("metricType")));

        List<Map<String, Object>> summary = new ArrayList<>();
        for (String metric : VALID_METRICS) {
            if (!targetByMetric.containsKey(metric)) continue;
            BigDecimal tgt = targetByMetric.getOrDefault(metric, BigDecimal.ZERO);
            BigDecimal act = actualByMetric.getOrDefault(metric, BigDecimal.ZERO);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("metricType", metric);
            s.put("basis", basisOf(metric));
            s.put("targetValue", tgt);
            s.put("actualValue", act);
            s.put("attainmentPct", pct(act, tgt));
            s.put("variance", act.subtract(tgt));
            s.put("status", statusOf(pct(act, tgt)));

            if (yearView) {
                BigDecimal ytdTarget = ytdTargetByMetric.getOrDefault(metric, BigDecimal.ZERO);
                BigDecimal monthsElapsed = monthsElapsedByMetric.getOrDefault(metric, BigDecimal.ZERO);
                // "actual" through dataThrough — future rows contribute 0 to
                // actualByMetric already, so `act` already equals YTD actual.
                BigDecimal ytdAttainmentPct = pct(act, ytdTarget);
                BigDecimal runRate = monthsElapsed.signum() > 0
                        ? act.divide(monthsElapsed, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(12))
                        : BigDecimal.ZERO;
                BigDecimal projectedPct = pct(runRate, tgt);
                s.put("ytdTarget", ytdTarget);
                s.put("ytdAttainmentPct", ytdAttainmentPct);
                s.put("monthsElapsed", monthsElapsed.setScale(2, RoundingMode.HALF_UP));
                s.put("fullYearTarget", tgt);
                s.put("runRateProjection", runRate.setScale(2, RoundingMode.HALF_UP));
                s.put("projectedAttainmentPct", projectedPct);
                // Year-view tile status reflects pace-to-date, not the raw
                // annual %, which would read "behind" for the whole year
                // until December purely from the calendar.
                s.put("status", statusOf(ytdAttainmentPct));
            }
            summary.add(s);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("fromMonth", fromMonth);
        resp.put("toMonth", toMonth);
        resp.put("year", year);
        resp.put("currentMonthKey", currentMonthKey);
        resp.put("dataThrough", dataThrough);
        resp.put("dataLag", dataLag);
        resp.put("rows", rows);
        resp.put("summary", summary);
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ── helpers ──

    private static BigDecimal metricActual(String metric, SumMonthlyBank a) {
        if (a == null) return BigDecimal.ZERO;
        switch (metric) {
            case "VOLUME":      return nz(a.getTotalVolume());
            case "BASE_VOLUME": return nz(a.getTotalBaseVolume());
            case "NET_REVENUE": return nz(a.getTotalNetRevenue());
            case "MSF":         return nz(a.getTotalMsf());
            case "TXNS":        return a.getTotalTxns() == null ? BigDecimal.ZERO : BigDecimal.valueOf(a.getTotalTxns());
            default:            return BigDecimal.ZERO;
        }
    }

    private static String basisOf(String metric) {
        return "BASE_VOLUME".equals(metric) ? "settlement" : "cardholder";
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** attainment % = actual / target * 100, 1 dp. 0 target → 0. */
    private static BigDecimal pct(BigDecimal actual, BigDecimal target) {
        if (target == null || target.signum() == 0) return BigDecimal.ZERO;
        return actual.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP);
    }

    private static String statusOf(BigDecimal attainmentPct) {
        if (attainmentPct == null) return "UPCOMING";
        double p = attainmentPct.doubleValue();
        if (p >= 100) return "MET";
        if (p >= 85)  return "ON_TRACK";
        return "BEHIND";
    }

    private static boolean isValidMonthKey(int mk) {
        int y = mk / 100, m = mk % 100;
        return y >= 2000 && y <= 2100 && m >= 1 && m <= 12;
    }

    private static String monthLabel(Integer monthKey) {
        if (monthKey == null) return "";
        int y = monthKey / 100, m = monthKey % 100;
        if (m < 1 || m > 12) return String.valueOf(monthKey);
        return YearMonth.of(y, m).getMonth().name().substring(0, 3) + " " + y;
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
