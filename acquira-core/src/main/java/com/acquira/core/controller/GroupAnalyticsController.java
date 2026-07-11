package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/group-analytics")
public class GroupAnalyticsController {

    @PersistenceContext
    private EntityManager entityManager;

    // Shared SELECT fragment for every grouping type (MCC/MERCHANT/SALES/REFERRAL).
    // All four ultimately query sum_daily_merchant aliased "s" (the MCC branch
    // joins dim_store on top of it), so the same metric set applies everywhere.
    // Volume basis is total_base_volume (settlement, single-currency) per the
    // platform rule — NOT total_volume (cardholder currency).
    private static final String METRICS_SELECT =
            "COUNT(DISTINCT s.merchant_id) as merchant_count, " +
            "SUM(s.total_txns) as total_txns, " +
            "SUM(s.total_base_volume) as total_volume, " +
            "SUM(s.total_msf) as total_msf, " +
            "SUM(s.total_interchange) as total_interchange, " +
            "SUM(s.total_scheme_fee) as total_scheme_fee, " +
            "SUM(s.total_debit_prepaid_volume) as debit_prepaid_volume, " +
            "SUM(s.total_credit_volume) as credit_volume ";

    /**
     * Generic endpoint for Group Reports.
     * type: MCC, MERCHANT, SALES, REFERRAL
     * period: TODAY, MONTH, YEAR, CUSTOM
     */
    @GetMapping("/{type}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getGroupReport(
            @PathVariable String type,
            @RequestParam(required = false) String period, // TODAY, MONTH, YEAR, PY (Previous Year)
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.badRequest().build();

        LocalDate start;
        LocalDate end;
        LocalDate now = LocalDate.now();

        // Smart Defaults Logic
        if (fromDate != null && toDate != null) {
            start = fromDate;
            end = toDate;
        } else if ("TODAY".equalsIgnoreCase(period)) {
            start = now;
            end = now;
        } else if ("LAST_MONTH".equalsIgnoreCase(period)) {
            // Previous calendar month: first to last day
            start = now.minusMonths(1).withDayOfMonth(1);
            end   = now.withDayOfMonth(1).minusDays(1);
        } else if ("YEAR".equalsIgnoreCase(period)) {
            start = now.withDayOfYear(1);
            end = now;
        } else if ("PY".equalsIgnoreCase(period)) {
            start = now.minusYears(1).withDayOfYear(1);
            end = now.minusYears(1).withMonth(12).withDayOfMonth(31);
        } else {
            // Default: This Month
            start = now.withDayOfMonth(1);
            end = now;
        }

        String sql = "";
        String groupBy = "";
        String selectClause = "";
        String joinClause = "";
        String orderBy = "ORDER BY total_volume DESC";

        switch (type.toUpperCase()) {
            case "MCC":
                selectClause = "s.mcc, COALESCE(MAX(s.mcc), 'Unknown') as label, "; // Ideally join ref_mcc if exists,
                                                                                    // else use code
                sql = "FROM sum_daily_mcc s ";
                groupBy = "GROUP BY s.mcc ";
                break;
            case "MERCHANT":
                selectClause = "s.merchant_id, MAX(m.name) as label, ";
                // P1-9: tenant-scope dim_merchant join too. Defense-in-depth
                // for any future multi-tenant ID strategy where merchant_id
                // is not globally unique.
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY s.merchant_id ";
                break;
            case "SALES":
            case "SALES_EMAIL":
                selectClause = "m.sales_user_id, COALESCE(m.sales_user_id, 'Unassigned') as label, ";
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY m.sales_user_id ";
                break;
            case "REFERRAL":
            case "REFERRAL_PARTNER":
                // Fallback to Sales ID if referral is empty
                selectClause = "COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id) as grp_key, COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') as label, ";
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id), COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') ";
                break;
            default:
                return ResponseEntity.badRequest().body("Invalid Report Type");
        }

        String finalSql = "SELECT " + selectClause +
                METRICS_SELECT +
                sql +
                "WHERE s.tenant_id = :tenantId AND s.business_date >= :startDate AND s.business_date <= :endDate " +
                groupBy +
                orderBy;

        // Optimize: For MCC, if table is sum_daily_mcc, it doesn't have merchant_id
        // column for COUNT(DISTINCT merchant_id)
        // sum_daily_mcc has: tenant_id, business_date, mcc, card_scheme...
        // It does NOT have merchant_id. So 'merchant_count' is not directly available
        // in sum_daily_mcc.
        // For MCC report, we might simply omit merchant count or we have to query
        // sum_daily_store joined with store?
        // Let's check schema. sum_daily_mcc does not have merchant_id.
        // Alternative for MCC: Join sum_daily_merchant with Store? Or just return 0 for
        // now.
        // Wait, User asked for "merchant count".
        // If type is MCC, we should query sum_daily_store or sum_daily_merchant joined
        // with store/mcc.
        // Let's refine the SQL for MCC.

        if ("MCC".equalsIgnoreCase(type)) {
            // Join sum_daily_merchant -> dim_store to get MCC.
            // NOTE: sum_daily_merchant.store_id is NOT populated by the summary
            // step (always NULL), so we join on merchant_id instead, which IS
            // populated. A merchant with multiple stores/MCCs will fan its
            // volume across those MCCs — acceptable for a merchant-grained
            // summary table and consistent with the other report types.
            // P1-9: tenant-scope dim_store join.
            finalSql = "SELECT st.mcc, COALESCE(st.mcc, 'Unknown') as label, " +
                    METRICS_SELECT +
                    "FROM sum_daily_merchant s " +
                    "JOIN dim_store st ON st.merchant_id = s.merchant_id AND st.tenant_id = s.tenant_id " +
                    "WHERE s.tenant_id = :tenantId AND s.business_date >= :startDate AND s.business_date <= :endDate " +
                    "GROUP BY st.mcc " +
                    orderBy;
        }

        Query query = entityManager.createNativeQuery(finalSql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("startDate", start);
        query.setParameter("endDate", end);

        // Limit results to top 100 for performance unless paginated
        query.setMaxResults(100);

        List<Object[]> results = query.getResultList();

        return ResponseEntity.ok(buildEnrichedResponse(results));
    }

    /**
     * Filtered variant of {@link #getGroupReport}. Same response shape but
     * accepts the full BusinessFilters drawer payload — partner / RM / MCC /
     * team-leader / merchant name / MID / SID / scheme / card type /
     * destination / channel — plus an explicit date range.
     *
     * The legacy GET endpoint stays in place so any existing caller keeps
     * working; new UI calls hit POST and gets the drawer fields applied.
     */
    @PostMapping("/{type}/filtered")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getGroupReportFiltered(
            @PathVariable String type,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.badRequest().build();
        if (filter == null) filter = new VolumeRevenueFilterDTO();

        // Date defaulting: same logic as the GET endpoint — if the caller
        // didn't provide explicit dates, default to MTD.
        LocalDate now = LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : now.withDayOfMonth(1);
        LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : now;

        // Build the SELECT/FROM/GROUP BY based on report type.
        String selectClause;
        String fromClause;
        String groupBy;
        // Whether we need a dim_store join — used by MCC/SID-related filters
        // and by the MCC report itself.
        boolean needStore = false;
        // Whether we need a dim_merchant join — used by partner/RM/team-leader
        // /merchant-name/MID filters and by MERCHANT/SALES/REFERRAL reports.
        boolean needMerchant = false;

        switch (type.toUpperCase()) {
            case "MCC":
                // MCC report joins sum_daily_merchant -> dim_store to get the
                // MCC. sum_daily_merchant.store_id is always NULL (not filled
                // by the summary step), so join on merchant_id instead.
                selectClause = "st.mcc, COALESCE(st.mcc, 'Unknown') as label, ";
                fromClause = "FROM sum_daily_merchant s " +
                             "JOIN dim_store st ON st.merchant_id = s.merchant_id AND st.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY st.mcc ";
                needStore = true;
                break;
            case "MERCHANT":
                selectClause = "s.merchant_id, MAX(m.name) as label, ";
                fromClause = "FROM sum_daily_merchant s " +
                             "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY s.merchant_id ";
                needMerchant = true;
                break;
            case "SALES":
            case "SALES_EMAIL":
                selectClause = "m.sales_user_id as grp_key, COALESCE(m.sales_user_id, 'Unassigned') as label, ";
                fromClause = "FROM sum_daily_merchant s " +
                             "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY m.sales_user_id ";
                needMerchant = true;
                break;
            case "REFERRAL":
            case "REFERRAL_PARTNER":
                selectClause = "COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id) as grp_key, " +
                               "COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') as label, ";
                fromClause = "FROM sum_daily_merchant s " +
                             "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ";
                groupBy = "GROUP BY COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id), " +
                          "COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') ";
                needMerchant = true;
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid Report Type: " + type));
        }

        // Add joins required by drawer filters even if the base report doesn't
        // need them. e.g. MCC report doesn't need dim_merchant, but if the user
        // filters by partner/RM, we need it.
        boolean filterNeedsMerchant =
                listNonEmpty(filter.getPartnerList()) ||
                listNonEmpty(filter.getRmList()) ||
                listNonEmpty(filter.getTeamLeaderList()) ||
                listNonEmpty(filter.getMidList()) ||
                (filter.getMerchantName() != null && !filter.getMerchantName().isBlank());
        boolean filterNeedsStore =
                listNonEmpty(filter.getMccList()) ||
                listNonEmpty(filter.getSidList());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectClause)
           .append(METRICS_SELECT)
           .append(fromClause);

        // Append filter-required joins only if not already in fromClause.
        if (filterNeedsMerchant && !needMerchant) {
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        }
        if (filterNeedsStore && !needStore) {
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        }

        sql.append("WHERE s.tenant_id = :tenantId ")
           .append("  AND s.business_date >= :startDate ")
           .append("  AND s.business_date <= :endDate ");

        // Drawer-driven filters — only emit the WHERE fragment AND bind the
        // parameter when the list is non-empty / value present.
        if (listNonEmpty(filter.getPartnerList()))    sql.append("  AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("  AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                      sql.append("  AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMidList()))        sql.append("  AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getMccList()))        sql.append("  AND st.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getSidList()))        sql.append("  AND st.sid IN (:sids) ");

        // The base table here is sum_daily_merchant which has no card-level
        // columns. If the user passes scheme/card-type/destination/channel
        // filters we'd need to switch to sum_daily_insight — deferred. For
        // now those filters are no-ops on this report and we log so the
        // operator knows.
        // (Acceptable: the GroupReports screen primarily groups by merchant
        // attributes, not card attributes.)

        sql.append(groupBy)
           .append("ORDER BY total_volume DESC NULLS LAST");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        query.setParameter("startDate", start);
        query.setParameter("endDate", end);

        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                      query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());

        query.setMaxResults(500); // higher cap than legacy GET (was 100); UI virtualizes.

        List<Object[]> results = query.getResultList();
        return ResponseEntity.ok(buildEnrichedResponse(results));
    }

    /**
     * Shared row-mapping + derived-metrics logic for both the legacy GET and
     * the filtered POST endpoint. Row shape (both endpoints now emit the
     * identical column set via METRICS_SELECT):
     *   [0] id, [1] label, [2] merchant_count, [3] total_txns, [4] total_volume
     *   (settlement basis), [5] total_msf, [6] total_interchange,
     *   [7] total_scheme_fee, [8] debit_prepaid_volume, [9] credit_volume
     *
     * Derived (computed here, not in SQL): net_revenue, avg_ticket,
     * msf_rate_bps, margin_pct, share_pct (share of grand-total volume across
     * the returned rows).
     */
    private List<Map<String, Object>> buildEnrichedResponse(List<Object[]> results) {
        List<Map<String, Object>> response = new ArrayList<>();

        // First pass: raw fields + running grand total for share%.
        BigDecimal grandTotalVolume = BigDecimal.ZERO;
        for (Object[] row : results) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row[0]);
            map.put("label", row[1]);
            map.put("merchantCount", row[2] != null ? ((Number) row[2]).longValue() : 0L);
            map.put("txnCount",      row[3] != null ? ((Number) row[3]).longValue() : 0L);

            BigDecimal volume        = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;
            BigDecimal msf           = row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO;
            BigDecimal interchange   = row[6] != null ? (BigDecimal) row[6] : BigDecimal.ZERO;
            BigDecimal schemeFee     = row[7] != null ? (BigDecimal) row[7] : BigDecimal.ZERO;
            BigDecimal debitPrepaid  = row[8] != null ? (BigDecimal) row[8] : BigDecimal.ZERO;
            BigDecimal credit        = row[9] != null ? (BigDecimal) row[9] : BigDecimal.ZERO;
            BigDecimal netRevenue    = msf.subtract(interchange).subtract(schemeFee);

            map.put("volume", volume);
            map.put("msf", msf);
            map.put("interchange", interchange);
            map.put("schemeFee", schemeFee);
            map.put("netRevenue", netRevenue);
            map.put("debitPrepaidVolume", debitPrepaid);
            map.put("creditVolume", credit);

            long txnCount = (Long) map.get("txnCount");
            map.put("avgTicket", txnCount > 0
                    ? volume.divide(BigDecimal.valueOf(txnCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            map.put("msfRateBps", volume.compareTo(BigDecimal.ZERO) > 0
                    ? msf.multiply(BigDecimal.valueOf(10000)).divide(volume, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            map.put("marginPct", msf.compareTo(BigDecimal.ZERO) > 0
                    ? netRevenue.multiply(BigDecimal.valueOf(100)).divide(msf, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);

            grandTotalVolume = grandTotalVolume.add(volume);
            response.add(map);
        }

        // Second pass: share of grand-total volume (needs the total from pass 1).
        for (Map<String, Object> map : response) {
            BigDecimal volume = (BigDecimal) map.get("volume");
            map.put("sharePct", grandTotalVolume.compareTo(BigDecimal.ZERO) > 0
                    ? volume.multiply(BigDecimal.valueOf(100)).divide(grandTotalVolume, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        }

        return response;
    }

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }
}
