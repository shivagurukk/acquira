package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.MerchantDailyMetricsDTO;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.SumDailyMerchantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class DailyMerchantDashboardController {

    private final SumDailyMerchantRepository sumDailyMerchantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // NOTE: GET /api/business/data-bounds is intentionally NOT defined here.
    // It already exists in BusinessAnalyticsController, which queries
    // fact_transaction (authoritative) with a sum_daily_insight fallback and
    // returns both "earliest" and "latest". Defining it a second time on the
    // same path caused an "Ambiguous mapping" startup failure. The Daily
    // Merchant Dashboard frontend consumes that shared endpoint.

    @GetMapping("/daily-merchant-dashboard")
    public ResponseEntity<List<MerchantDailyMetricsDTO>> getDashboardData(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month,
            // Inline-filter params (kept for backward compat with the existing GET
            // call from the frontend). The richer drawer filters are accepted via
            // the POST endpoint below.
            @RequestParam(required = false) List<String> midList,
            @RequestParam(required = false) List<String> sidList,
            @RequestParam(required = false) String merchantName) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        // Default to current date if params missing
        LocalDate now = LocalDate.now();
        if (year == 0)  year  = now.getYear();
        if (month == 0) month = now.getMonthValue();
        LocalDate reportDate = LocalDate.of(year, month, 1);

        // Build a synthetic filter from the GET params so we can reuse one
        // filtering pipeline for both endpoints.
        VolumeRevenueFilterDTO filter = new VolumeRevenueFilterDTO();
        filter.setMidList(midList);
        filter.setSidList(sidList);
        filter.setMerchantName(merchantName);

        return ResponseEntity.ok(loadAndFilter(reportDate, tenantId, filter));
    }

    /**
     * Drawer-filter variant. Same response shape as the GET, but accepts the
     * full VolumeRevenueFilterDTO so the BusinessFilters drawer (partner / RM /
     * MCC / team leader / merchant name / MID / SID) can narrow the dashboard.
     *
     * NOT supported by this endpoint and silently ignored if set:
     *   schemeList, cardTypeList, destinationList, channelList
     * Reason: the underlying sum_daily_merchant summary is aggregated per
     * (tenant, date, merchant) and doesn't carry card-level columns. The frontend
     * should disable those filter fields here, but if they leak through we just
     * don't apply them.
     */
    @PostMapping("/daily-merchant-dashboard-filtered")
    public ResponseEntity<List<MerchantDailyMetricsDTO>> getDashboardDataFiltered(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new VolumeRevenueFilterDTO();

        LocalDate now = LocalDate.now();
        if (year == 0)  year  = now.getYear();
        if (month == 0) month = now.getMonthValue();
        LocalDate reportDate = LocalDate.of(year, month, 1);

        return ResponseEntity.ok(loadAndFilter(reportDate, tenantId, filter));
    }

    /**
     * Common pipeline: (1) load the per-(merchant, day) grid for the month from
     * sum_daily_merchant (fast, partition-pruned, index-backed), (2) compute the
     * set of merchant internal_ids matching the dim_merchant/dim_store filters
     * (only when those filters are set), (3) fold the day rows into one DTO per
     * merchant, computing the per-day map, month total, today, 7-day average,
     * trend %, and status ON THE FLY.
     *
     * This replaces the old merchant_daily_metrics path (filled by the async
     * reporting step), which silently emptied recent months whenever that async
     * step failed or lagged. sum_daily_merchant is written synchronously inside
     * the batch job, so a COMPLETED upload always has data here.
     *
     * merchantId in the DTO is dim_merchant.internal_id (a string), matching the
     * old contract and the MID/dim filter join keys.
     */
    private List<MerchantDailyMetricsDTO> loadAndFilter(LocalDate reportDate, Long tenantId, VolumeRevenueFilterDTO filter) {
        LocalDate monthStart = reportDate.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        // One fast query: per (merchant, day) base volume + txns for the month.
        // Columns: [0]=internal_id, [1]=mid, [2]=name, [3]=day, [4]=volume, [5]=txns
        List<Object[]> rows = sumDailyMerchantRepository.findDailyMerchantGrid(tenantId, monthStart, monthEnd);

        // Resolve merchant internal_id whitelist from dim_merchant / dim_store IF any
        // of the dim-scoped filters are set. Null whitelist means "don't restrict".
        Set<String> merchantWhitelist = null;
        boolean needsDimFilter =
                nonEmpty(filter.getPartnerList())   ||
                nonEmpty(filter.getRmList())        ||
                nonEmpty(filter.getTeamLeaderList())||
                nonEmpty(filter.getMccList())       ||
                nonEmpty(filter.getSidList());

        if (needsDimFilter) {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT DISTINCT m.internal_id FROM dim_merchant m ");
            if (nonEmpty(filter.getMccList()) || nonEmpty(filter.getSidList())) {
                sql.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = :tid ");
            }
            sql.append("WHERE m.tenant_id = :tid ");
            if (nonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
            if (nonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
            if (nonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
            if (nonEmpty(filter.getMccList()))        sql.append("AND st.mcc IN (:mccs) ");
            if (nonEmpty(filter.getSidList()))        sql.append("AND st.sid IN (:sids) ");

            jakarta.persistence.Query q = entityManager.createNativeQuery(sql.toString());
            q.setParameter("tid", tenantId);
            if (nonEmpty(filter.getPartnerList()))    q.setParameter("partners",    filter.getPartnerList());
            if (nonEmpty(filter.getRmList()))         q.setParameter("rms",         filter.getRmList());
            if (nonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders", filter.getTeamLeaderList());
            if (nonEmpty(filter.getMccList()))        q.setParameter("mccs",        filter.getMccList());
            if (nonEmpty(filter.getSidList()))        q.setParameter("sids",        filter.getSidList());

            @SuppressWarnings("unchecked")
            List<Object> wlRows = q.getResultList();
            merchantWhitelist = new HashSet<>(wlRows.size());
            for (Object r : wlRows) if (r != null) merchantWhitelist.add(r.toString());
        }

        final Set<String> finalWhitelist = merchantWhitelist;
        final List<String> midList = filter.getMidList();
        final String nameLower = (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                ? filter.getMerchantName().toLowerCase() : null;

        // Fold the day rows into one accumulator per merchant.
        Map<String, Acc> byMerchant = new HashMap<>();
        for (Object[] r : rows) {
            String internalId = r[0] == null ? null : r[0].toString();
            if (internalId == null) continue;
            String mid = r[1] == null ? null : r[1].toString();
            String name = r[2] == null ? null : r[2].toString();
            int day = ((Number) r[3]).intValue();
            double vol = r[4] == null ? 0.0 : ((Number) r[4]).doubleValue();

            // Apply filters once per merchant (cheap to re-check; values are stable).
            if (finalWhitelist != null && !finalWhitelist.contains(internalId)) continue;
            if (nonEmpty(midList) && (mid == null || !midList.contains(mid))) continue;
            if (nameLower != null && (name == null || !name.toLowerCase().contains(nameLower))) continue;

            Acc acc = byMerchant.computeIfAbsent(internalId, k -> new Acc(internalId, mid, name));
            acc.daily.put(day, acc.daily.getOrDefault(day, 0.0) + vol);
        }

        // Today's day-of-month, used only when the selected month IS the current month.
        LocalDate today = LocalDate.now();
        boolean isCurrentMonth = today.getYear() == monthStart.getYear()
                && today.getMonthValue() == monthStart.getMonthValue();
        int todayDom = today.getDayOfMonth();

        List<MerchantDailyMetricsDTO> out = new ArrayList<>(byMerchant.size());
        for (Acc acc : byMerchant.values()) {
            out.add(acc.toDto(isCurrentMonth ? todayDom : -1));
        }
        // Stable, useful default order: biggest month volume first.
        out.sort((a, b) -> Double.compare(
                b.getTotalMtd() == null ? 0 : b.getTotalMtd(),
                a.getTotalMtd() == null ? 0 : a.getTotalMtd()));
        return out;
    }

    /** In-memory accumulator: one per merchant, holding the day->volume map. */
    private static final class Acc {
        final String internalId;
        final String mid;
        final String name;
        final Map<Integer, Double> daily = new HashMap<>();
        Acc(String internalId, String mid, String name) {
            this.internalId = internalId; this.mid = mid; this.name = name;
        }

        MerchantDailyMetricsDTO toDto(int todayDom) {
            MerchantDailyMetricsDTO dto = new MerchantDailyMetricsDTO();
            dto.setMerchantId(internalId);
            dto.setMid(mid);
            dto.setMerchantName(name);
            dto.setDailyVolumes(daily);

            double total = 0.0;
            int maxDay = 0;
            for (Map.Entry<Integer, Double> e : daily.entrySet()) {
                total += e.getValue();
                if (e.getKey() > maxDay) maxDay = e.getKey();
            }
            dto.setTotalMtd(total);

            // "Today" = the current day-of-month if we're viewing the current month,
            // otherwise the latest day that has data in the selected month.
            int todayKey = todayDom > 0 ? todayDom : maxDay;
            double todayVol = daily.getOrDefault(todayKey, 0.0);
            dto.setTodayVolume(todayVol);
            dto.setYesterdayVolume(daily.getOrDefault(todayKey - 1, 0.0));

            // 7-day trailing window ending at todayKey: average + sparkline.
            double sum7 = 0.0; int cnt7 = 0;
            for (int d = Math.max(1, todayKey - 6); d <= todayKey; d++) {
                sum7 += daily.getOrDefault(d, 0.0);
                cnt7++;
            }
            dto.setAvg7Day(cnt7 > 0 ? sum7 / cnt7 : 0.0);

            // Trend %: today vs the trailing-7 average (excluding today), bounded.
            double prevAvg = 0.0; int prevCnt = 0;
            for (int d = Math.max(1, todayKey - 7); d <= todayKey - 1; d++) {
                prevAvg += daily.getOrDefault(d, 0.0);
                prevCnt++;
            }
            prevAvg = prevCnt > 0 ? prevAvg / prevCnt : 0.0;
            double trend = prevAvg > 0 ? ((todayVol - prevAvg) / prevAvg) * 100.0 : 0.0;
            if (trend > 999) trend = 999;
            if (trend < -999) trend = -999;
            dto.setTrendPct(trend);

            // Status: lightweight heuristic from the trend (the heavy volatility/
            // risk model lived in the old async table; this keeps the column useful
            // without a second data source).
            String status = "Stable";
            if (todayVol == 0 && total > 0) status = "Risk";   // active month but nothing recent
            else if (trend <= -40) status = "Risk";
            else if (trend <= -15) status = "Watch";
            dto.setUiStatus(status);

            return dto;
        }
    }

    private static boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
