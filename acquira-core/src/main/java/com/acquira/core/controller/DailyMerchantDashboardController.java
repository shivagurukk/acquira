package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.MerchantDailyMetricsDTO;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.model.MerchantDailyMetrics;
import com.acquira.common.repository.MerchantDailyMetricsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class DailyMerchantDashboardController {

    private final MerchantDailyMetricsRepository metricsRepository;

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
     * Reason: the underlying merchant_daily_metrics table is a pre-aggregated
     * cache and doesn't carry card-level columns. The frontend should disable
     * those filter fields here, but if they leak through we just don't apply them.
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
     * Common pipeline: (1) load tenant-scoped cache rows for the month,
     * (2) compute the set of merchant_ids that match the dim_merchant/dim_store
     * filters (only when those filters are non-empty), (3) reduce the result.
     *
     * Note that merchant_daily_metrics.merchantId is the dim_merchant.internal_id
     * (a string), not the surrogate key. Both the cache row and the dim lookup
     * are joined on internal_id.
     */
    private List<MerchantDailyMetricsDTO> loadAndFilter(LocalDate reportDate, Long tenantId, VolumeRevenueFilterDTO filter) {
        List<MerchantDailyMetrics> entities = metricsRepository.findByReportDateAndTenantId(reportDate, tenantId);

        // Resolve merchant_id whitelist from dim_merchant / dim_store IF any of
        // the dim-scoped filters are set. Empty whitelist means "don't restrict".
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
            List<Object> rows = q.getResultList();
            merchantWhitelist = new HashSet<>(rows.size());
            for (Object r : rows) if (r != null) merchantWhitelist.add(r.toString());
        }

        final Set<String> finalWhitelist = merchantWhitelist;
        final List<String> midList = filter.getMidList();
        final String nameLower = (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                ? filter.getMerchantName().toLowerCase() : null;

        return entities.stream()
                .filter(e -> finalWhitelist == null || finalWhitelist.contains(e.getMerchantId()))
                .filter(e -> !nonEmpty(midList) || (e.getMid() != null && midList.contains(e.getMid())))
                .filter(e -> nameLower == null || (e.getMerchantName() != null && e.getMerchantName().toLowerCase().contains(nameLower)))
                .map(MerchantDailyMetricsDTO::fromEntity)
                .collect(Collectors.toList());
    }

    private static boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
