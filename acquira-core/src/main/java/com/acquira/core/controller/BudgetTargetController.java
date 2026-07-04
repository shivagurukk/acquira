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
 * The bank_budget_target table shipped in schema.sql but had no entity, repo,
 * controller or UI — so no target was ever enterable and no dashboard could
 * show actual-vs-budget. This controller closes that gap:
 *
 *   - CRUD (upsert) of monthly targets per metric  [ADMIN/SUPER_ADMIN]
 *   - GET /attainment  — for a month range, join each target to the matching
 *     sum_monthly_bank actual and compute attainment % + variance.
 *
 * Metrics supported (mapped to sum_monthly_bank columns):
 *   VOLUME      -> total_volume
 *   NET_REVENUE -> total_net_revenue
 *   MSF         -> total_msf
 *   TXNS        -> total_txns
 *
 * Additive & isolated: new controller + new entity/repo, touches nothing else.
 * Every query is tenant-scoped (repo methods take tenantId; table is RLS too).
 */
@RestController
@RequestMapping("/api/business/budget")
public class BudgetTargetController {

    private static final Set<String> VALID_METRICS =
            Set.of("VOLUME", "NET_REVENUE", "MSF", "TXNS");

    private final BankBudgetTargetRepository budgetRepo;
    private final SumMonthlyBankRepository monthlyBankRepo;

    public BudgetTargetController(BankBudgetTargetRepository budgetRepo,
                                  SumMonthlyBankRepository monthlyBankRepo) {
        this.budgetRepo = budgetRepo;
        this.monthlyBankRepo = monthlyBankRepo;
    }

    private Long resolveTenant(Long headerTenant) {
        if (headerTenant != null) return headerTenant;
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

        BankBudgetTarget row = budgetRepo
                .findByTenantIdAndMonthKeyAndMetricType(tenantId, monthKey, metric)
                .orElseGet(BankBudgetTarget::new);
        row.setTenantId(tenantId);
        row.setMonthKey(monthKey);
        row.setMetricType(metric);
        row.setTargetValue(target);
        if (row.getCreatedAt() == null) row.setCreatedAt(java.time.LocalDateTime.now());
        return ResponseEntity.ok(budgetRepo.save(row));
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
     * Attainment for a month range (defaults to the current calendar year up to
     * the latest month present in sum_monthly_bank). For every target in range,
     * pairs it with the matching monthly actual and computes attainment % and
     * variance. Rows with a target but no actual show actual=0 / 0%; a month can
     * have several metrics.
     */
    @GetMapping("/attainment")
    public ResponseEntity<?> attainment(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toMonth) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        // Default range: Jan of current year → current month.
        LocalDate now = LocalDate.now();
        if (fromMonth == null) fromMonth = now.getYear() * 100 + 1;
        if (toMonth == null)   toMonth   = now.getYear() * 100 + now.getMonthValue();
        if (fromMonth > toMonth) { Integer t = fromMonth; fromMonth = toMonth; toMonth = t; }

        List<BankBudgetTarget> targets =
                budgetRepo.findByTenantIdAndMonthKeyBetween(tenantId, fromMonth, toMonth);
        List<SumMonthlyBank> actuals =
                monthlyBankRepo.findByTenantIdAndMonthKeyBetween(tenantId, fromMonth, toMonth);

        // Index actuals by monthKey for O(1) lookup.
        Map<Integer, SumMonthlyBank> actualByMonth = new HashMap<>();
        for (SumMonthlyBank a : actuals) actualByMonth.put(a.getMonthKey(), a);

        List<Map<String, Object>> rows = new ArrayList<>();
        // Aggregate roll-up per metric across the whole range (for headline tiles).
        Map<String, BigDecimal> targetByMetric = new HashMap<>();
        Map<String, BigDecimal> actualByMetric = new HashMap<>();

        for (BankBudgetTarget t : targets) {
            SumMonthlyBank a = actualByMonth.get(t.getMonthKey());
            BigDecimal actualVal = metricActual(t.getMetricType(), a);
            BigDecimal targetVal = t.getTargetValue() == null ? BigDecimal.ZERO : t.getTargetValue();
            BigDecimal attainmentPct = pct(actualVal, targetVal);
            BigDecimal variance = actualVal.subtract(targetVal);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("budgetId", t.getBudgetId());
            row.put("monthKey", t.getMonthKey());
            row.put("monthLabel", monthLabel(t.getMonthKey()));
            row.put("metricType", t.getMetricType());
            row.put("targetValue", targetVal);
            row.put("actualValue", actualVal);
            row.put("attainmentPct", attainmentPct);
            row.put("variance", variance);
            row.put("status", statusOf(attainmentPct));
            rows.add(row);

            targetByMetric.merge(t.getMetricType(), targetVal, BigDecimal::add);
            actualByMetric.merge(t.getMetricType(), actualVal, BigDecimal::add);
        }

        // Sort rows by month then metric for a stable grid.
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
            s.put("targetValue", tgt);
            s.put("actualValue", act);
            s.put("attainmentPct", pct(act, tgt));
            s.put("variance", act.subtract(tgt));
            s.put("status", statusOf(pct(act, tgt)));
            summary.add(s);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("fromMonth", fromMonth);
        resp.put("toMonth", toMonth);
        resp.put("rows", rows);
        resp.put("summary", summary);
        return ResponseEntity.ok(resp);
    }

    // ── helpers ──

    private static BigDecimal metricActual(String metric, SumMonthlyBank a) {
        if (a == null) return BigDecimal.ZERO;
        switch (metric) {
            case "VOLUME":      return nz(a.getTotalVolume());
            case "NET_REVENUE": return nz(a.getTotalNetRevenue());
            case "MSF":         return nz(a.getTotalMsf());
            case "TXNS":        return a.getTotalTxns() == null ? BigDecimal.ZERO : BigDecimal.valueOf(a.getTotalTxns());
            default:            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** attainment % = actual / target * 100, 1 dp. 0 target → 0. */
    private static BigDecimal pct(BigDecimal actual, BigDecimal target) {
        if (target == null || target.signum() == 0) return BigDecimal.ZERO;
        return actual.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP);
    }

    private static String statusOf(BigDecimal attainmentPct) {
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
