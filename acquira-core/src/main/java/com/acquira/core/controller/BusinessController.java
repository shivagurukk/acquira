package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.model.MerchantOpportunityScore;
import com.acquira.common.repository.MerchantActivitySummaryRepository;
import com.acquira.common.repository.MerchantOpportunityScoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

        private final MerchantActivitySummaryRepository activityRepository;
        private final MerchantOpportunityScoreRepository opportunityRepository;
        private final com.acquira.common.repository.SumDailyBankRepository dailyBankRepository;
        /** Server-side check of the DB-driven sys_group_menu screen grants. */
        private final com.acquira.common.security.MenuAccessEvaluator menuAccess;

        @PersistenceContext
        private EntityManager entityManager;

        public BusinessController(MerchantActivitySummaryRepository activityRepository,
                        MerchantOpportunityScoreRepository opportunityRepository,
                        com.acquira.common.repository.SumDailyBankRepository dailyBankRepository,
                        com.acquira.common.security.MenuAccessEvaluator menuAccess) {
                this.activityRepository = activityRepository;
                this.opportunityRepository = opportunityRepository;
                this.dailyBankRepository = dailyBankRepository;
                this.menuAccess = menuAccess;
        }

        // SECURITY: use only the filter-validated TenantContext, never the raw
        // X-Tenant-Id header — the raw header is attacker-controlled and reading it
        // directly bypasses the UserTenantAccess check in JwtRequestFilter.
        private Long resolveTenant() {
                return com.acquira.common.config.TenantContext.getCurrentTenant();
        }

        // 1. Dashboard KPIs (Simplistic aggregation for now)
        @GetMapping("/dashboard/kpis")
        public ResponseEntity<Map<String, Object>> getDashboardKpis(
                        @RequestParam(required = false) LocalDate endDate) {
                Long tenantId = resolveTenant();
                if (tenantId == null) return ResponseEntity.status(403).build();

                // Determine effective date: User provided OR Max available
                LocalDate effectiveDate = endDate;
                if (effectiveDate == null) {
                        effectiveDate = activityRepository.findMaxCalcDate(tenantId);
                        if (effectiveDate == null)
                                effectiveDate = LocalDate.now(); // Fallback if no data
                }

                // 1. Merchant Counts (Snapshot at effectiveDate)
                long activeCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ACTIVE",
                                effectiveDate);
                long dormantCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT",
                                effectiveDate);
                long onboardedCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED",
                                effectiveDate);

                // 2. Transaction Metrics (Daily, MTD, YTD)
                // Daily
                java.math.BigDecimal dailyVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId,
                                effectiveDate,
                                effectiveDate);
                Long dailyCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, effectiveDate, effectiveDate);

                // MTD
                LocalDate mtdStart = effectiveDate.withDayOfMonth(1);
                java.math.BigDecimal mtdVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, mtdStart,
                                effectiveDate);
                Long mtdCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, mtdStart, effectiveDate);

                // YTD
                LocalDate ytdStart = effectiveDate.withDayOfYear(1);
                java.math.BigDecimal ytdVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, ytdStart,
                                effectiveDate);
                Long ytdCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, ytdStart, effectiveDate);

                Map<String, Object> response = new HashMap<>();

                // Structure for Frontend
                response.put("dailyVolume", dailyVol != null ? dailyVol : java.math.BigDecimal.ZERO);
                response.put("dailyCount", dailyCnt != null ? dailyCnt : 0);

                response.put("mtdVolume", mtdVol != null ? mtdVol : java.math.BigDecimal.ZERO);
                response.put("mtdCount", mtdCnt != null ? mtdCnt : 0);

                response.put("ytdVolume", ytdVol != null ? ytdVol : java.math.BigDecimal.ZERO);
                response.put("ytdCount", ytdCnt != null ? ytdCnt : 0);

                // Backward compat / Summary tiles
                response.put("transactionCount", mtdCnt != null ? mtdCnt : 0); // Default to MTD for main tile
                response.put("transactionValue", mtdVol != null ? mtdVol : java.math.BigDecimal.ZERO);

                response.put("activeMerchants", activeCount);
                response.put("newMerchants", onboardedCount);
                response.put("dormantMerchants", dormantCount);
                response.put("zeroSalesMerchants", 0);
                response.put("effectiveDate", effectiveDate);

                return ResponseEntity.ok(response);
        }

        /**
         * CEO landing-dashboard summary (V-CEO-1): MTD week-by-week + YTD
         * month-by-month, each bucket carrying count / volume / avg ticket /
         * MSF / net margin / net-margin %, plus prior-period comparison
         * totals and an MTD run-rate projection.
         *
         * Data sourcing (per project rules — bank-level unfiltered):
         *   - MTD weekly buckets + prior comparisons: sum_daily_bank
         *   - YTD monthly buckets:                    sum_monthly_bank
         *
         * Week definition (matches the requested "weeks 1 2 3 4"):
         *   W1 = days 1–7, W2 = 8–14, W3 = 15–21, W4 = 22–28, W5 = 29–end.
         *
         * Everything is anchored on the LATEST business_date in
         * sum_daily_bank — not calendar today — so a data lag never renders
         * fake-zero weeks. Net margin is the corrected
         * msf − interchange − scheme_fee figure.
         */
        @GetMapping("/ceo-summary")
        public ResponseEntity<Map<String, Object>> getCeoSummary() {
                Long tenantId = resolveTenant();
                if (tenantId == null) return ResponseEntity.status(403).build();

                // Anchor on latest available data.
                Object maxD = entityManager
                                .createNativeQuery("SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = :tid")
                                .setParameter("tid", tenantId)
                                .getSingleResult();
                LocalDate eff = toLocalDate(maxD);
                if (eff == null) eff = LocalDate.now();

                LocalDate mtdStart = eff.withDayOfMonth(1);
                int daysInMonth = eff.lengthOfMonth();
                int elapsedDays = eff.getDayOfMonth();
                int currentWeek = Math.min(5, ((elapsedDays - 1) / 7) + 1);

                // ── MTD weekly buckets ─────────────────────────────────────
                @SuppressWarnings("unchecked")
                List<Object[]> wkRows = entityManager.createNativeQuery(
                                "SELECT LEAST(5, ((CAST(EXTRACT(DAY FROM business_date) AS INTEGER) - 1) / 7) + 1) AS wk, " +
                                "SUM(total_txns), SUM(COALESCE(total_base_volume,0)), SUM(total_msf), " +
                                "SUM(COALESCE(total_interchange,0)), SUM(COALESCE(total_scheme_fee,0)), SUM(COALESCE(total_ecom_fee,0)), SUM(total_net_revenue) " +
                                "FROM sum_daily_bank WHERE tenant_id = :tid AND business_date BETWEEN :s AND :e " +
                                "GROUP BY 1 ORDER BY 1")
                                .setParameter("tid", tenantId)
                                .setParameter("s", mtdStart)
                                .setParameter("e", eff)
                                .getResultList();
                Map<Integer, Object[]> byWeek = new HashMap<>();
                for (Object[] r : wkRows) byWeek.put(((Number) r[0]).intValue(), r);

                List<Map<String, Object>> weeks = new ArrayList<>();
                long mtdTxns = 0;
                BigDecimal mtdVol = BigDecimal.ZERO, mtdMsf = BigDecimal.ZERO,
                                mtdIc = BigDecimal.ZERO, mtdSf = BigDecimal.ZERO, mtdEc = BigDecimal.ZERO, mtdNet = BigDecimal.ZERO;
                for (int w = 1; w <= currentWeek; w++) {
                        LocalDate from = mtdStart.plusDays((long) (w - 1) * 7);
                        LocalDate weekEnd = (w == 5) ? eff.withDayOfMonth(daysInMonth)
                                        : from.plusDays(6);
                        if (weekEnd.getMonthValue() != eff.getMonthValue()) weekEnd = eff.withDayOfMonth(daysInMonth);
                        LocalDate to = weekEnd.isAfter(eff) ? eff : weekEnd;
                        Object[] r = byWeek.get(w);
                        long txns = r != null ? toLong(r[1]) : 0L;
                        BigDecimal vol = r != null ? toBigDecimal(r[2]) : BigDecimal.ZERO;
                        BigDecimal msf = r != null ? toBigDecimal(r[3]) : BigDecimal.ZERO;
                        BigDecimal ic = r != null ? toBigDecimal(r[4]) : BigDecimal.ZERO;
                        BigDecimal sf = r != null ? toBigDecimal(r[5]) : BigDecimal.ZERO;
                        BigDecimal ec = r != null ? toBigDecimal(r[6]) : BigDecimal.ZERO;
                        BigDecimal net = r != null ? toBigDecimal(r[7]) : BigDecimal.ZERO;
                        Map<String, Object> m = buildMetricBucket("Week " + w, txns, vol, msf, ic, sf, ec, net);
                        m.put("week", w);
                        m.put("from", from.toString());
                        m.put("to", to.toString());
                        m.put("current", w == currentWeek);
                        m.put("partial", w == currentWeek && to.isBefore(weekEnd));
                        weeks.add(m);
                        mtdTxns += txns;
                        mtdVol = mtdVol.add(vol);
                        mtdMsf = mtdMsf.add(msf);
                        mtdIc = mtdIc.add(ic);
                        mtdSf = mtdSf.add(sf);
                        mtdEc = mtdEc.add(ec);
                        mtdNet = mtdNet.add(net);
                }

                // ── Prior-month pace (day 1 → same day-of-month, clamped) ──
                LocalDate prevMtdStart = mtdStart.minusMonths(1);
                LocalDate prevMtdEnd = prevMtdStart
                                .plusDays(Math.min(elapsedDays, prevMtdStart.lengthOfMonth()) - 1L);
                Object[] prevMtd = singleAggregate(tenantId, prevMtdStart, prevMtdEnd);

                // ── YTD monthly buckets (sum_monthly_bank) ─────────────────
                int year = eff.getYear();
                @SuppressWarnings("unchecked")
                List<Object[]> moRows = entityManager.createNativeQuery(
                                "SELECT month_key, total_txns, COALESCE(total_base_volume,0), total_msf, " +
                                "COALESCE(total_interchange,0), COALESCE(total_scheme_fee,0), COALESCE(total_ecom_fee,0), total_net_revenue " +
                                "FROM sum_monthly_bank WHERE tenant_id = :tid AND month_key BETWEEN :a AND :b " +
                                "ORDER BY month_key")
                                .setParameter("tid", tenantId)
                                .setParameter("a", year * 100 + 1)
                                .setParameter("b", year * 100 + 12)
                                .getResultList();

                String[] moNames = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
                List<Map<String, Object>> months = new ArrayList<>();
                long ytdTxns = 0;
                BigDecimal ytdVol = BigDecimal.ZERO, ytdMsf = BigDecimal.ZERO,
                                ytdIc = BigDecimal.ZERO, ytdSf = BigDecimal.ZERO, ytdEc = BigDecimal.ZERO, ytdNet = BigDecimal.ZERO;
                for (Object[] r : moRows) {
                        int mk = ((Number) r[0]).intValue();
                        int moIdx = (mk % 100) - 1;
                        long txns = toLong(r[1]);
                        BigDecimal vol = toBigDecimal(r[2]);
                        BigDecimal msf = toBigDecimal(r[3]);
                        BigDecimal ic = toBigDecimal(r[4]);
                        BigDecimal sf = toBigDecimal(r[5]);
                        BigDecimal ec = toBigDecimal(r[6]);
                        BigDecimal net = toBigDecimal(r[7]);
                        Map<String, Object> m = buildMetricBucket(
                                        (moIdx >= 0 && moIdx < 12 ? moNames[moIdx] : String.valueOf(mk)),
                                        txns, vol, msf, ic, sf, ec, net);
                        m.put("monthKey", mk);
                        m.put("current", mk == year * 100 + eff.getMonthValue());
                        months.add(m);
                        ytdTxns += txns;
                        ytdVol = ytdVol.add(vol);
                        ytdMsf = ytdMsf.add(msf);
                        ytdIc = ytdIc.add(ic);
                        ytdSf = ytdSf.add(sf);
                        ytdEc = ytdEc.add(ec);
                        ytdNet = ytdNet.add(net);
                }

                // ── Prior YTD (prior year Jan 1 → same day-of-year) ────────
                LocalDate prevYtdStart = eff.withDayOfYear(1).minusYears(1);
                LocalDate prevYtdEnd = eff.minusYears(1);
                Object[] prevYtd = singleAggregate(tenantId, prevYtdStart, prevYtdEnd);

                // ── MTD run-rate projection ────────────────────────────────
                Map<String, Object> runRate = new LinkedHashMap<>();
                runRate.put("elapsedDays", elapsedDays);
                runRate.put("daysInMonth", daysInMonth);
                if (elapsedDays > 0) {
                        BigDecimal factor = BigDecimal.valueOf(daysInMonth)
                                        .divide(BigDecimal.valueOf(elapsedDays), 6, RoundingMode.HALF_UP);
                        runRate.put("projectedVolume", mtdVol.multiply(factor).setScale(2, RoundingMode.HALF_UP));
                        runRate.put("projectedNetRevenue", mtdNet.multiply(factor).setScale(2, RoundingMode.HALF_UP));
                        runRate.put("projectedTxns",
                                        BigDecimal.valueOf(mtdTxns).multiply(factor).setScale(0, RoundingMode.HALF_UP));
                } else {
                        runRate.put("projectedVolume", BigDecimal.ZERO);
                        runRate.put("projectedNetRevenue", BigDecimal.ZERO);
                        runRate.put("projectedTxns", BigDecimal.ZERO);
                }

                // ── Assemble ───────────────────────────────────────────────
                Map<String, Object> mtd = new LinkedHashMap<>();
                mtd.put("label", eff.getMonth().toString().charAt(0)
                                + eff.getMonth().toString().substring(1, 3).toLowerCase() + " " + year);
                mtd.put("start", mtdStart.toString());
                mtd.put("end", eff.toString());
                mtd.put("weeks", weeks);
                mtd.put("totals", buildMetricBucket("MTD", mtdTxns, mtdVol, mtdMsf, mtdIc, mtdSf, mtdEc, mtdNet));
                mtd.put("prev", buildMetricBucket("Prev MTD pace",
                                toLong(prevMtd[0]), toBigDecimal(prevMtd[1]),
                                toBigDecimal(prevMtd[2]), toBigDecimal(prevMtd[3]),
                                toBigDecimal(prevMtd[4]), toBigDecimal(prevMtd[5]),
                                toBigDecimal(prevMtd[6])));
                mtd.put("runRate", runRate);

                Map<String, Object> ytd = new LinkedHashMap<>();
                ytd.put("label", "YTD " + year);
                ytd.put("year", year);
                ytd.put("months", months);
                ytd.put("totals", buildMetricBucket("YTD", ytdTxns, ytdVol, ytdMsf, ytdIc, ytdSf, ytdEc, ytdNet));
                ytd.put("prev", buildMetricBucket("Prev YTD",
                                toLong(prevYtd[0]), toBigDecimal(prevYtd[1]),
                                toBigDecimal(prevYtd[2]), toBigDecimal(prevYtd[3]),
                                toBigDecimal(prevYtd[4]), toBigDecimal(prevYtd[5]),
                                toBigDecimal(prevYtd[6])));

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("effectiveDate", eff.toString());
                response.put("mtd", mtd);
                response.put("ytd", ytd);
                return ResponseEntity.ok(response);
        }

        /**
         * CEO Volume & Revenue detail (V-CEO-2): MID x SID rows with count /
         * settlement volume / MSF / interchange / scheme fee / net margin /
         * net-margin %, for MTD or YTD. Reads sum_daily_terminal ONLY (store
         * grain summary carrying the fee columns since V2026_07_05_02) —
         * never fact_transaction — so it loads in the same speed class as
         * every other summary-backed page. Volume here is SETTLEMENT
         * (total_base_volume), the figure the fees are computed against.
         *
         * AUTHORIZATION: see the menuAccess check at the top of the body. This one
         * endpoint backs TWO screens with separate menu grants — lossOnly=true is
         * Loss-Making Merchants, lossOnly=false is Volume & Revenue — so it
         * enforces whichever grant the caller is actually exercising. Before this
         * check the migrations' group grants were UI-only: the sidebar hid the
         * link, the API served anyone authenticated.
         *
         * The check is in the body rather than a @PreAuthorize SpEL expression on
         * purpose. Referencing a method argument (#lossOnly) requires parameter
         * names in the class file, and this build does not compile with
         * -parameters (verified: no MethodParameters attribute on this class), so
         * the expression would fail at runtime. The explicit form also returns a
         * useful JSON body instead of a bare 403.
         */
        @GetMapping("/ceo-volume-revenue")
        public ResponseEntity<Map<String, Object>> getCeoVolumeRevenue(
                        @RequestParam(defaultValue = "MTD") String mode,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "50") int size,
                        @RequestParam(defaultValue = "volume") String sort,
                        @RequestParam(defaultValue = "desc") String dir,
                        @RequestParam(required = false) String search,
                        @RequestParam(defaultValue = "false") boolean lossOnly,
                        @RequestParam(required = false) String month) {
                Long tenantId = resolveTenant();
                if (tenantId == null)
                        return ResponseEntity.status(403).body(Map.of("message",
                                        "No tenant selected, or you do not have access to this tenant."));

                // Enforce the sys_group_menu grant for whichever screen is being
                // served. Without this the grants in V2026_07_05_02 / _04 were
                // decorative — /api/business/** falls through to
                // anyRequest().authenticated() in SecurityConfig.
                String menuPath = lossOnly ? "/business/loss-making" : "/business/ceo-volume-revenue";
                if (!menuAccess.canAccess(menuPath))
                        return ResponseEntity.status(403).body(Map.of("message",
                                        "You do not have access to this report."));

                // Anchor the period windows to the table this report actually READS.
                // This used to come from sum_daily_bank while every figure below is
                // read from sum_daily_terminal. Those are two independent, concurrent
                // steps of the rollup (TransactionJobConfig.populateSummaryStep), so
                // if the bank step finished and the terminal step lagged or failed,
                // the screen advertised a window (from -> to is printed in the
                // subtitle) whose last days had no terminal rows — quietly reporting
                // a partial period as a complete one, which on the Loss-Making view
                // systematically UNDERSTATES losses.
                Object maxD = entityManager
                                .createNativeQuery("SELECT MAX(business_date) FROM sum_daily_terminal WHERE tenant_id = :tid")
                                .setParameter("tid", tenantId)
                                .getSingleResult();
                LocalDate eff = toLocalDate(maxD);
                if (eff == null) {
                        // No terminal-grain data at all — fall back to the bank summary so
                        // the screen still resolves a sensible period instead of jumping to
                        // today and rendering an empty month.
                        Object bankMaxD = entityManager
                                        .createNativeQuery("SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = :tid")
                                        .setParameter("tid", tenantId)
                                        .getSingleResult();
                        eff = toLocalDate(bankMaxD);
                }
                if (eff == null) eff = LocalDate.now();

                // Period resolution:
                //   month=YYYY-MM (explicit month pick) wins over mode.
                //   mode = YTD        -> Jan 1 .. eff
                //          THIS_MONTH -> 1st of eff's month .. last day of that month
                //          MTD (default) -> 1st of eff's month .. eff
                LocalDate from, to;
                String resolvedMode;
                if (month != null && month.matches("\\d{4}-\\d{2}")) {
                        int yr = Integer.parseInt(month.substring(0, 4));
                        int mo = Integer.parseInt(month.substring(5, 7));
                        LocalDate first = LocalDate.of(yr, mo, 1);
                        from = first;
                        to = first.withDayOfMonth(first.lengthOfMonth());
                        resolvedMode = month;
                } else if ("YTD".equalsIgnoreCase(mode)) {
                        from = eff.withDayOfYear(1);
                        to = eff;
                        resolvedMode = "YTD";
                } else if ("THIS_MONTH".equalsIgnoreCase(mode)) {
                        from = eff.withDayOfMonth(1);
                        to = eff.withDayOfMonth(eff.lengthOfMonth());
                        resolvedMode = "THIS_MONTH";
                } else {
                        from = eff.withDayOfMonth(1);
                        to = eff;
                        resolvedMode = "MTD";
                }

                if (page < 0) page = 0;
                if (size < 1) size = 50;
                if (size > 500) size = 500;

                // Sort key -> aggregate expression (whitelist; user text never
                // becomes a SQL identifier).
                Map<String, String> sortCols = new HashMap<>();
                sortCols.put("volume",      "SUM(t.total_base_volume)");
                sortCols.put("txns",        "SUM(t.total_txns)");
                sortCols.put("msf",         "SUM(t.total_msf)");
                sortCols.put("interchange", "SUM(t.total_interchange)");
                sortCols.put("schemeFee",   "SUM(t.total_scheme_fee)");
                // COALESCE mirrors the SELECT below: a merchant with no ECOM fee
                // DISPLAYS 0.00, so it must SORT as 0 too. Sorting the raw column
                // let NULLS LAST park those merchants at the bottom in BOTH
                // directions — ascending should have put them first.
                sortCols.put("ecomFee",     "SUM(COALESCE(t.total_ecom_fee,0))");
                sortCols.put("net",         "SUM(t.total_revenue)");
                // Net margin % — the most useful ordering on the Loss-Making view
                // (a large merchant losing 0.1% and a small one losing 40% are very
                // different problems, and absolute net margin cannot separate them).
                // NULL when volume is zero, so those rows land under NULLS LAST
                // rather than being treated as 0% — consistent with marginPct below.
                sortCols.put("margin",      "CASE WHEN SUM(t.total_base_volume) <> 0 " +
                                            "THEN SUM(t.total_revenue) / SUM(t.total_base_volume) END");
                sortCols.put("name",        "m.name");
                sortCols.put("mid",         "m.mid");
                String orderExpr = sortCols.getOrDefault(sort, "SUM(t.total_base_volume)");
                String orderDir = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
                // Every sort expression above is non-unique, and ties under a plain
                // LIMIT/OFFSET pager are ordered arbitrarily AND unstably between
                // statements — so a tied row could appear on two pages or on none,
                // and a paged CSV export could duplicate merchants while its
                // server-computed TOTAL row stayed correct. MID is unique at the
                // lossOnly (merchant) grain; MID + SID is unique at MID x SID grain.
                String tieBreak = lossOnly ? ", m.mid ASC" : ", m.mid ASC, s.sid ASC";

                boolean hasSearch = search != null && !search.isBlank();
                // Escape LIKE metacharacters so a merchant searching "50%" or "a_b"
                // gets a literal match instead of wildcard semantics. Backslash first,
                // or it would double-escape the escapes added after it.
                String searchTerm = hasSearch
                                ? search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                                : null;
                // lossOnly -> only merchants whose net margin over the window is
                // negative (a loss). Applied as HAVING on the grouped aggregate so
                // it flows identically into the page, count, and totals queries.
                String havingLoss = lossOnly ? "HAVING SUM(t.total_revenue) < 0 " : "";
                // lossOnly rolls up to MERCHANT level (MID only), not MID x SID. A
                // merchant can be net-negative overall while individual stores are
                // fine (or vice versa) — the loss list must reflect the merchant's
                // true combined position, not flag/hide stores independently of
                // their siblings under the same MID.
                String groupBy = lossOnly ? "GROUP BY m.mid, m.name " : "GROUP BY m.mid, s.sid, m.name ";
                // lossOnly rolls up to MID grain, so filtering on s.sid (which
                // runs in WHERE, before GROUP BY) would evaluate a merchant's net
                // position over only the matching store's rows -- contradicting
                // the merchant-level rollup. Drop the SID predicate in that case.
                String base =
                                "FROM sum_daily_terminal t " +
                                "JOIN dim_merchant m ON m.merchant_id = t.merchant_id AND m.tenant_id = t.tenant_id " +
                                "LEFT JOIN dim_store s ON s.store_id = t.store_id AND s.tenant_id = t.tenant_id " +
                                "WHERE t.tenant_id = :tid AND t.business_date BETWEEN :s AND :e " +
                                (hasSearch
                                                ? (lossOnly
                                                                ? "AND (m.name ILIKE :q ESCAPE '\\' OR m.mid ILIKE :q ESCAPE '\\') "
                                                                : "AND (m.name ILIKE :q ESCAPE '\\' OR m.mid ILIKE :q ESCAPE '\\' " +
                                                                  "OR s.sid ILIKE :q ESCAPE '\\') ")
                                                : "") +
                                groupBy + havingLoss;

                String sidSelect = lossOnly ? "NULL" : "s.sid";
                jakarta.persistence.Query rq = entityManager.createNativeQuery(
                                "SELECT m.mid, " + sidSelect + ", m.name, " +
                                "SUM(t.total_txns), SUM(t.total_base_volume), SUM(t.total_msf), " +
                                "SUM(t.total_interchange), SUM(t.total_scheme_fee), SUM(COALESCE(t.total_ecom_fee,0)), SUM(t.total_revenue) " +
                                base +
                                "ORDER BY " + orderExpr + " " + orderDir + " NULLS LAST" + tieBreak + " " +
                                "LIMIT :lim OFFSET :off");
                rq.setParameter("tid", tenantId);
                rq.setParameter("s", from);
                rq.setParameter("e", to);
                if (hasSearch) rq.setParameter("q", "%" + searchTerm + "%");
                rq.setParameter("lim", size);
                rq.setParameter("off", (long) page * size);

                @SuppressWarnings("unchecked")
                List<Object[]> rows = rq.getResultList();
                List<Map<String, Object>> out = new ArrayList<>(rows.size());
                for (Object[] r : rows) {
                        long txns = toLong(r[3]);
                        BigDecimal vol = toBigDecimal(r[4]);
                        BigDecimal msf = toBigDecimal(r[5]);
                        BigDecimal ic = toBigDecimal(r[6]);
                        BigDecimal sf = toBigDecimal(r[7]);
                        BigDecimal ec = toBigDecimal(r[8]);
                        BigDecimal net = toBigDecimal(r[9]);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("mid", r[0]);
                        m.put("sid", r[1]);
                        m.put("name", r[2]);
                        m.put("txns", txns);
                        m.put("volume", vol);
                        m.put("msf", msf);
                        m.put("interchange", ic);
                        m.put("schemeFee", sf);
                        m.put("ecomFee", ec);
                        m.put("netRevenue", net);
                        // NULL — not ZERO — when the ratio is undefined. The old
                        // `vol > 0 ? ... : ZERO` reported 0.00% for a merchant whose
                        // period was refunds only (volume 0, net negative) — a textbook
                        // loss-maker — and the UI colours on `marginPct >= 0`, so it
                        // rendered GREEN on a success background next to a red
                        // six-figure loss. The client renders null as an em-dash.
                        // signum() != 0 also lets net-refund merchants (negative volume)
                        // report their real ratio instead of being flattened to zero.
                        m.put("marginPct", vol.signum() != 0
                                        ? net.multiply(BigDecimal.valueOf(100)).divide(vol, 2, RoundingMode.HALF_UP)
                                        : null);
                        out.add(m);
                }

                // Row count and grand totals in ONE pass over the grouped
                // aggregate. These were two separate queries, each re-running
                // `base` — with the page query above, the same heavy GROUP BY
                // executed three times per page load.
                jakarta.persistence.Query tq = entityManager.createNativeQuery(
                                "SELECT COUNT(*), COALESCE(SUM(x.c1),0), COALESCE(SUM(x.c2),0), COALESCE(SUM(x.c3),0), " +
                                "COALESCE(SUM(x.c4),0), COALESCE(SUM(x.c5),0), COALESCE(SUM(x.c6),0), COALESCE(SUM(x.c7),0) FROM ( " +
                                "SELECT SUM(t.total_txns) c1, SUM(t.total_base_volume) c2, SUM(t.total_msf) c3, " +
                                "SUM(t.total_interchange) c4, SUM(t.total_scheme_fee) c5, SUM(t.total_revenue) c6, " +
                                "SUM(COALESCE(t.total_ecom_fee,0)) c7 " +
                                base + ") x");
                tq.setParameter("tid", tenantId);
                tq.setParameter("s", from);
                tq.setParameter("e", to);
                if (hasSearch) tq.setParameter("q", "%" + searchTerm + "%");
                Object[] meta = (Object[]) tq.getSingleResult();
                long totalRows = ((Number) meta[0]).longValue();
                // Shift by one: index 0 is the row count, 1..7 are the totals.
                Object[] tot = new Object[]{meta[1], meta[2], meta[3], meta[4], meta[5], meta[6], meta[7]};
                BigDecimal tVol = toBigDecimal(tot[1]);
                BigDecimal tNet = toBigDecimal(tot[5]);
                Map<String, Object> totals = new LinkedHashMap<>();
                totals.put("txns", toLong(tot[0]));
                totals.put("volume", tVol);
                totals.put("msf", toBigDecimal(tot[2]));
                totals.put("interchange", toBigDecimal(tot[3]));
                totals.put("schemeFee", toBigDecimal(tot[4]));
                totals.put("ecomFee", toBigDecimal(tot[6]));
                totals.put("netRevenue", tNet);
                // Same null-vs-zero rule as the per-row marginPct above.
                totals.put("marginPct", tVol.signum() != 0
                                ? tNet.multiply(BigDecimal.valueOf(100)).divide(tVol, 2, RoundingMode.HALF_UP)
                                : null);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("effectiveDate", eff.toString());
                // Last business date actually present in the table being read. The UI
                // clamps the displayed "from -> to" range to this so a period whose
                // window runs past the data (THIS_MONTH always does — it ends on the
                // last day of the calendar month) cannot imply coverage that does not
                // exist.
                response.put("dataThrough", eff.toString());
                response.put("mode", resolvedMode);
                response.put("lossOnly", lossOnly);
                response.put("from", from.toString());
                response.put("to", to.toString());
                response.put("page", page);
                response.put("size", size);
                response.put("totalRows", totalRows);
                response.put("totals", totals);
                response.put("rows", out);
                return ResponseEntity.ok(response);
        }

        /** One-row SUM aggregate over sum_daily_bank for a date window (settlement volume). */
        private Object[] singleAggregate(Long tenantId, LocalDate from, LocalDate to) {
                Object res = entityManager.createNativeQuery(
                                "SELECT COALESCE(SUM(total_txns),0), COALESCE(SUM(total_base_volume),0), " +
                                "COALESCE(SUM(total_msf),0), COALESCE(SUM(total_interchange),0), " +
                                "COALESCE(SUM(total_scheme_fee),0), COALESCE(SUM(total_ecom_fee),0), COALESCE(SUM(total_net_revenue),0) " +
                                "FROM sum_daily_bank WHERE tenant_id = :tid AND business_date BETWEEN :s AND :e")
                                .setParameter("tid", tenantId)
                                .setParameter("s", from)
                                .setParameter("e", to)
                                .getSingleResult();
                return (Object[]) res;
        }

        /** Bucket map: count / volume / msf / interchange / scheme fee / ecom fee / net margin + derived avgTicket / marginPct. */
        private static Map<String, Object> buildMetricBucket(String label, long txns,
                        BigDecimal vol, BigDecimal msf, BigDecimal interchange, BigDecimal schemeFee, BigDecimal ecomFee, BigDecimal net) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", label);
                m.put("txns", txns);
                m.put("volume", vol);
                m.put("msf", msf);
                m.put("interchange", interchange);
                m.put("schemeFee", schemeFee);
                m.put("ecomFee", ecomFee);
                m.put("netRevenue", net);
                m.put("avgTicket", txns > 0
                                ? vol.divide(BigDecimal.valueOf(txns), 2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
                m.put("marginPct", vol.compareTo(BigDecimal.ZERO) > 0
                                ? net.multiply(BigDecimal.valueOf(100)).divide(vol, 2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
                return m;
        }

        private static LocalDate toLocalDate(Object o) {
                if (o == null) return null;
                if (o instanceof LocalDate) return (LocalDate) o;
                if (o instanceof java.sql.Date) return ((java.sql.Date) o).toLocalDate();
                try { return LocalDate.parse(o.toString()); } catch (Exception e) { return null; }
        }

        /**
         * Filtered dashboard KPIs. Same response shape as GET /dashboard/kpis but
         * accepts the full VolumeRevenueFilterDTO body so the BusinessFilters drawer
         * (partner / RM / MCC / team-leader / merchant-name / MID / SID / scheme /
         * card-type / destination / channel) actually narrows the numbers.
         *
         * Enhancements over the original:
         *  - Period-over-period comparison windows computed in the SAME single
         *    scan (extra CASE branches, no extra query): prev daily window
         *    (same-length window immediately preceding), prev MTD pace (prior
         *    month, day 1 → same day-of-month), prev YTD (prior year, Jan 1 →
         *    same day-of-year). Returned as prev* keys so the UI can render
         *    delta chips.
         *  - The scan lower bound is LEAST(prevDailyStart, prevMtdStart,
         *    prevYtdStart) instead of the old hard `>= ytdStart` clamp, which
         *    silently truncated custom startDate ranges that began before Jan 1.
         *  - zeroSalesMerchants is now real: merchants in the filtered
         *    dim_merchant universe with NO volume in the daily window. Card-level
         *    filters (scheme/cardType/destination/channel) are intentionally not
         *    applied to the zero-sales universe — a merchant with zero rows has
         *    no card dimensions to filter on.
         */
        @PostMapping("/dashboard/kpis-filtered")
        public ResponseEntity<Map<String, Object>> getDashboardKpisFiltered(
                        @RequestBody(required = false) VolumeRevenueFilterDTO filter) {
                Long tenantId = resolveTenant();
                if (tenantId == null) return ResponseEntity.status(403).build();

                if (filter == null) filter = new VolumeRevenueFilterDTO();

                // Effective date: end of range if specified, else max in data, else today.
                LocalDate effectiveDate = filter.getEndDate();
                if (effectiveDate == null) {
                        effectiveDate = activityRepository.findMaxCalcDate(tenantId);
                        if (effectiveDate == null) effectiveDate = LocalDate.now();
                }
                LocalDate startDate = filter.getStartDate();
                LocalDate mtdStart = effectiveDate.withDayOfMonth(1);
                LocalDate ytdStart = effectiveDate.withDayOfYear(1);
                LocalDate dailyStart = (startDate != null) ? startDate : effectiveDate;
                if (dailyStart.isAfter(effectiveDate)) dailyStart = effectiveDate;

                // Comparison windows.
                long dailyLen = ChronoUnit.DAYS.between(dailyStart, effectiveDate) + 1;
                LocalDate prevDailyEnd   = dailyStart.minusDays(1);
                LocalDate prevDailyStart = prevDailyEnd.minusDays(dailyLen - 1);
                LocalDate prevMtdStart   = mtdStart.minusMonths(1);
                LocalDate prevMtdEnd     = effectiveDate.minusMonths(1);
                LocalDate prevYtdStart   = ytdStart.minusYears(1);
                LocalDate prevYtdEnd     = effectiveDate.minusYears(1);

                // Scan lower bound: earliest of every window we aggregate over.
                // (Fixes the old `>= ytdStart` clamp that truncated custom ranges
                // starting before Jan 1.)
                LocalDate scanStart = prevYtdStart;
                if (prevDailyStart.isBefore(scanStart)) scanStart = prevDailyStart;
                if (prevMtdStart.isBefore(scanStart))   scanStart = prevMtdStart;

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_volume ELSE 0 END) AS daily_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_txns   ELSE 0 END) AS daily_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS mtd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS mtd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS ytd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS ytd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevDailyStart AND :prevDailyEnd THEN s.total_volume ELSE 0 END) AS prev_daily_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevDailyStart AND :prevDailyEnd THEN s.total_txns   ELSE 0 END) AS prev_daily_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevMtdStart   AND :prevMtdEnd   THEN s.total_volume ELSE 0 END) AS prev_mtd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevMtdStart   AND :prevMtdEnd   THEN s.total_txns   ELSE 0 END) AS prev_mtd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevYtdStart   AND :prevYtdEnd   THEN s.total_volume ELSE 0 END) AS prev_ytd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevYtdStart   AND :prevYtdEnd   THEN s.total_txns   ELSE 0 END) AS prev_ytd_cnt ");
                sql.append("FROM sum_daily_insight s ");

                boolean needMerchant =
                                listNonEmpty(filter.getPartnerList())   ||
                                listNonEmpty(filter.getRmList())        ||
                                listNonEmpty(filter.getTeamLeaderList())||
                                (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) ||
                                listNonEmpty(filter.getMidList())       ||
                                filter.getOpenDateStart() != null || filter.getOpenDateEnd() != null;
                boolean needStore =
                                listNonEmpty(filter.getMccList()) ||
                                listNonEmpty(filter.getSidList()) ||
                                listNonEmpty(filter.getIndustryList());

                if (needMerchant) sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
                if (needStore)    sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id ");

                sql.append("WHERE s.tenant_id = :tid ");
                sql.append("  AND s.business_date BETWEEN :scanStart AND :endDate ");
                if (needMerchant) sql.append("  AND m.tenant_id = :tid ");
                if (needStore)    sql.append("  AND st.tenant_id = :tid ");

                if (listNonEmpty(filter.getPartnerList()))    sql.append("  AND m.referral_partner IN (:partners) ");
                if (listNonEmpty(filter.getRmList()))         sql.append("  AND m.sales_email IN (:rms) ");
                if (listNonEmpty(filter.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              sql.append("  AND m.name ILIKE :merchName ");
                if (listNonEmpty(filter.getMidList()))        sql.append("  AND m.mid IN (:mids) ");
                if (filter.getOpenDateStart() != null)        sql.append("  AND CAST(m.created_date AS DATE) >= :openStart ");
                if (filter.getOpenDateEnd() != null)          sql.append("  AND CAST(m.created_date AS DATE) <= :openEnd ");
                if (listNonEmpty(filter.getMccList()))        sql.append("  AND st.mcc IN (:mccs) ");
                if (listNonEmpty(filter.getSidList()))        sql.append("  AND st.sid IN (:sids) ");
                if (listNonEmpty(filter.getIndustryList()))   sql.append("  AND st.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) ");
                if (listNonEmpty(filter.getSchemeList()))     sql.append("  AND s.card_scheme IN (:schemes) ");
                if (listNonEmpty(filter.getCardTypeList()))   sql.append("  AND s.card_type IN (:cardTypes) ");
                if (listNonEmpty(filter.getDestinationList()))sql.append("  AND s.destination IN (:destinations) ");
                if (listNonEmpty(filter.getChannelList()))    sql.append("  AND s.channel IN (:channels) ");

                jakarta.persistence.Query q = entityManager.createNativeQuery(sql.toString());
                q.setParameter("tid",            tenantId);
                q.setParameter("dailyStart",     dailyStart);
                q.setParameter("mtdStart",       mtdStart);
                q.setParameter("ytdStart",       ytdStart);
                q.setParameter("endDate",        effectiveDate);
                q.setParameter("scanStart",      scanStart);
                q.setParameter("prevDailyStart", prevDailyStart);
                q.setParameter("prevDailyEnd",   prevDailyEnd);
                q.setParameter("prevMtdStart",   prevMtdStart);
                q.setParameter("prevMtdEnd",     prevMtdEnd);
                q.setParameter("prevYtdStart",   prevYtdStart);
                q.setParameter("prevYtdEnd",     prevYtdEnd);
                bindFilterParams(q, filter);

                Object[] row = (Object[]) q.getSingleResult();
                BigDecimal dailyVol     = toBigDecimal(row[0]);
                long       dailyCnt     = toLong(row[1]);
                BigDecimal mtdVol       = toBigDecimal(row[2]);
                long       mtdCnt       = toLong(row[3]);
                BigDecimal ytdVol       = toBigDecimal(row[4]);
                long       ytdCnt       = toLong(row[5]);
                BigDecimal prevDailyVol = toBigDecimal(row[6]);
                long       prevDailyCnt = toLong(row[7]);
                BigDecimal prevMtdVol   = toBigDecimal(row[8]);
                long       prevMtdCnt   = toLong(row[9]);
                BigDecimal prevYtdVol   = toBigDecimal(row[10]);
                long       prevYtdCnt   = toLong(row[11]);

                // Merchant counts — distinct merchants in the filtered universe.
                // Window basis: the explicit range when one is set, else MTD. The old
                // behaviour collapsed to a single day (dailyStart == effectiveDate)
                // when no start date was chosen, so "Active Merchants" showed only
                // merchants that transacted on the latest day — misleading as a
                // portfolio count. Zero-sales uses the same window for symmetry.
                LocalDate activityStart = (startDate != null) ? dailyStart : mtdStart;
                String activeSql =
                                "SELECT COUNT(DISTINCT s.merchant_id) FROM sum_daily_insight s " +
                                (needMerchant ? "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id " : "") +
                                (needStore    ? "LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id " : "") +
                                "WHERE s.tenant_id = :tid " +
                                "  AND s.business_date BETWEEN :activityStart AND :endDate " +
                                "  AND s.total_volume > 0 " +
                                filterFragment(filter);
                jakarta.persistence.Query aq = entityManager.createNativeQuery(activeSql);
                aq.setParameter("tid", tenantId);
                aq.setParameter("activityStart", activityStart);
                aq.setParameter("endDate", effectiveDate);
                bindFilterParams(aq, filter);
                long activeCount = ((Number) aq.getSingleResult()).longValue();

                // Zero-sales — merchants in the filtered dim_merchant universe with
                // no volume rows in the daily window. Store-level filters applied via
                // EXISTS on dim_store; card-level filters are not applicable (a
                // merchant with zero insight rows has no card dimensions).
                StringBuilder zsql = new StringBuilder();
                zsql.append("SELECT COUNT(*) FROM dim_merchant m ");
                zsql.append("WHERE m.tenant_id = :tid ");
                if (listNonEmpty(filter.getPartnerList()))    zsql.append("  AND m.referral_partner IN (:partners) ");
                if (listNonEmpty(filter.getRmList()))         zsql.append("  AND m.sales_email IN (:rms) ");
                if (listNonEmpty(filter.getTeamLeaderList())) zsql.append("  AND m.sales_user_id IN (:teamLeaders) ");
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              zsql.append("  AND m.name ILIKE :merchName ");
                if (listNonEmpty(filter.getMidList()))        zsql.append("  AND m.mid IN (:mids) ");
                if (filter.getOpenDateStart() != null)        zsql.append("  AND CAST(m.created_date AS DATE) >= :openStart ");
                if (filter.getOpenDateEnd() != null)          zsql.append("  AND CAST(m.created_date AS DATE) <= :openEnd ");
                if (listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList())
                                || listNonEmpty(filter.getIndustryList())) {
                        zsql.append("  AND EXISTS (SELECT 1 FROM dim_store st ");
                        zsql.append("       WHERE st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
                        if (listNonEmpty(filter.getMccList())) zsql.append("       AND st.mcc IN (:mccs) ");
                        if (listNonEmpty(filter.getSidList())) zsql.append("       AND st.sid IN (:sids) ");
                        if (listNonEmpty(filter.getIndustryList()))
                                zsql.append("       AND st.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) ");
                        zsql.append("  ) ");
                }
                zsql.append("  AND NOT EXISTS (SELECT 1 FROM sum_daily_insight s ");
                zsql.append("       WHERE s.tenant_id = m.tenant_id AND s.merchant_id = m.merchant_id ");
                zsql.append("         AND s.business_date BETWEEN :activityStart AND :endDate ");
                zsql.append("         AND s.total_volume > 0) ");

                jakarta.persistence.Query zq = entityManager.createNativeQuery(zsql.toString());
                zq.setParameter("tid", tenantId);
                zq.setParameter("activityStart", activityStart);
                zq.setParameter("endDate", effectiveDate);
                if (listNonEmpty(filter.getPartnerList()))    zq.setParameter("partners",    filter.getPartnerList());
                if (listNonEmpty(filter.getRmList()))         zq.setParameter("rms",         filter.getRmList());
                if (listNonEmpty(filter.getTeamLeaderList())) zq.setParameter("teamLeaders", filter.getTeamLeaderList());
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              zq.setParameter("merchName",   "%" + filter.getMerchantName() + "%");
                if (listNonEmpty(filter.getMidList()))        zq.setParameter("mids",        filter.getMidList());
                if (filter.getOpenDateStart() != null)        zq.setParameter("openStart",   filter.getOpenDateStart());
                if (filter.getOpenDateEnd() != null)          zq.setParameter("openEnd",     filter.getOpenDateEnd());
                if (listNonEmpty(filter.getMccList()))        zq.setParameter("mccs",        filter.getMccList());
                if (listNonEmpty(filter.getSidList()))        zq.setParameter("sids",        filter.getSidList());
                if (listNonEmpty(filter.getIndustryList()))   zq.setParameter("industries",  filter.getIndustryList());
                long zeroSalesCount = ((Number) zq.getSingleResult()).longValue();

                // Dormant / new — kept tenant-wide (activity snapshots aren't
                // filter-scoped). The frontend badges these tiles as tenant-wide
                // whenever filtersApplied is true.
                // Snapshot anchor: latest calc_date ON OR BEFORE the effective date.
                // The old exact-match lookup returned 0 whenever the user's endDate
                // didn't coincide with a snapshot day.
                LocalDate snapshotDate = activityRepository.findMaxCalcDateOnOrBefore(tenantId, effectiveDate);
                if (snapshotDate == null) snapshotDate = effectiveDate;
                long dormantCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT", snapshotDate);
                long onboardedCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED", snapshotDate);

                Map<String, Object> response = new HashMap<>();
                response.put("dailyVolume",       dailyVol);
                response.put("dailyCount",        dailyCnt);
                response.put("mtdVolume",         mtdVol);
                response.put("mtdCount",          mtdCnt);
                response.put("ytdVolume",         ytdVol);
                response.put("ytdCount",          ytdCnt);
                response.put("prevDailyVolume",   prevDailyVol);
                response.put("prevDailyCount",    prevDailyCnt);
                response.put("prevMtdVolume",     prevMtdVol);
                response.put("prevMtdCount",      prevMtdCnt);
                response.put("prevYtdVolume",     prevYtdVol);
                response.put("prevYtdCount",      prevYtdCnt);
                response.put("transactionCount",  mtdCnt);
                response.put("transactionValue",  mtdVol);
                response.put("activeMerchants",   activeCount);
                response.put("newMerchants",      onboardedCount);
                response.put("dormantMerchants",  dormantCount);
                response.put("zeroSalesMerchants", zeroSalesCount);
                response.put("effectiveDate",     effectiveDate);
                response.put("rangeStart",        dailyStart);
                response.put("customRange",       startDate != null);
                response.put("merchantWindowStart", activityStart);
                response.put("snapshotDate",      snapshotDate);
                response.put("filtersApplied",    !isFilterEmpty(filter));

                return ResponseEntity.ok(response);
        }

        /** WHERE-fragment for the filterable columns (used by the active-count query). */
        private static String filterFragment(VolumeRevenueFilterDTO filter) {
                return  (listNonEmpty(filter.getPartnerList())    ? "  AND m.referral_partner IN (:partners) " : "") +
                        (listNonEmpty(filter.getRmList())         ? "  AND m.sales_email IN (:rms) " : "") +
                        (listNonEmpty(filter.getTeamLeaderList()) ? "  AND m.sales_user_id IN (:teamLeaders) " : "") +
                        ((filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                                  ? "  AND m.name ILIKE :merchName " : "") +
                        (listNonEmpty(filter.getMidList())        ? "  AND m.mid IN (:mids) " : "") +
                        (filter.getOpenDateStart() != null        ? "  AND CAST(m.created_date AS DATE) >= :openStart " : "") +
                        (filter.getOpenDateEnd() != null          ? "  AND CAST(m.created_date AS DATE) <= :openEnd " : "") +
                        (listNonEmpty(filter.getMccList())        ? "  AND st.mcc IN (:mccs) " : "") +
                        (listNonEmpty(filter.getSidList())        ? "  AND st.sid IN (:sids) " : "") +
                        (listNonEmpty(filter.getIndustryList())   ? "  AND st.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) " : "") +
                        (listNonEmpty(filter.getSchemeList())     ? "  AND s.card_scheme IN (:schemes) " : "") +
                        (listNonEmpty(filter.getCardTypeList())   ? "  AND s.card_type IN (:cardTypes) " : "") +
                        (listNonEmpty(filter.getDestinationList())? "  AND s.destination IN (:destinations) " : "") +
                        (listNonEmpty(filter.getChannelList())    ? "  AND s.channel IN (:channels) " : "");
        }

        /** Bind every filter parameter that filterFragment / the main query emitted. */
        private static void bindFilterParams(jakarta.persistence.Query q, VolumeRevenueFilterDTO filter) {
                if (listNonEmpty(filter.getPartnerList()))    q.setParameter("partners",     filter.getPartnerList());
                if (listNonEmpty(filter.getRmList()))         q.setParameter("rms",          filter.getRmList());
                if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders",  filter.getTeamLeaderList());
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              q.setParameter("merchName",    "%" + filter.getMerchantName() + "%");
                if (listNonEmpty(filter.getMidList()))        q.setParameter("mids",         filter.getMidList());
                if (filter.getOpenDateStart() != null)        q.setParameter("openStart",    filter.getOpenDateStart());
                if (filter.getOpenDateEnd() != null)          q.setParameter("openEnd",      filter.getOpenDateEnd());
                if (listNonEmpty(filter.getMccList()))        q.setParameter("mccs",         filter.getMccList());
                if (listNonEmpty(filter.getIndustryList()))   q.setParameter("industries",   filter.getIndustryList());
                if (listNonEmpty(filter.getSidList()))        q.setParameter("sids",         filter.getSidList());
                if (listNonEmpty(filter.getSchemeList()))     q.setParameter("schemes",      filter.getSchemeList());
                if (listNonEmpty(filter.getCardTypeList()))   q.setParameter("cardTypes",    filter.getCardTypeList());
                if (listNonEmpty(filter.getDestinationList()))q.setParameter("destinations", filter.getDestinationList());
                if (listNonEmpty(filter.getChannelList()))    q.setParameter("channels",     filter.getChannelList());
        }

        private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

        private static boolean isFilterEmpty(VolumeRevenueFilterDTO f) {
                return !listNonEmpty(f.getPartnerList()) && !listNonEmpty(f.getRmList())
                                && !listNonEmpty(f.getTeamLeaderList()) && !listNonEmpty(f.getMidList())
                                && !listNonEmpty(f.getSidList()) && !listNonEmpty(f.getMccList())
                                && !listNonEmpty(f.getIndustryList())
                                && f.getOpenDateStart() == null && f.getOpenDateEnd() == null
                                && !listNonEmpty(f.getSchemeList()) && !listNonEmpty(f.getCardTypeList())
                                && !listNonEmpty(f.getDestinationList()) && !listNonEmpty(f.getChannelList())
                                && (f.getMerchantName() == null || f.getMerchantName().isBlank());
        }

        private static BigDecimal toBigDecimal(Object o) {
                if (o == null) return BigDecimal.ZERO;
                if (o instanceof BigDecimal) return (BigDecimal) o;
                return new BigDecimal(o.toString());
        }

        private static long toLong(Object o) {
                if (o == null) return 0L;
                if (o instanceof Number) return ((Number) o).longValue();
                return Long.parseLong(o.toString());
        }

        // 4. Opportunities
        @GetMapping("/opportunity")
        public ResponseEntity<List<MerchantOpportunityScore>> getOpportunities() {
                Long tenantId = resolveTenant();
                if (tenantId == null) {
                        return ResponseEntity.status(403).build();
                }
                // Use findLatestByTenant: ONE row per merchant (most recent
                // calc_date), not every historical dated snapshot. The old
                // findByTenantIdOrderByScoreDesc returned ~(merchants x dates)
                // rows — hundreds of thousands — which both duplicated every
                // merchant and produced a response large enough to hang the grid.
                return ResponseEntity.ok(opportunityRepository.findLatestByTenant(tenantId));
        }

        // Methods removed: getLifecycleSummary, getZeroSales, getSalesTrends (Feature
        // Cleanup)
}
