package com.acquira.core.controller;

import com.acquira.common.dto.MerchantSummaryDTO;
import com.acquira.core.service.AnalyticsService;
import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final com.acquira.common.repository.SumDailyMerchantRepository sumDailyMerchantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AnalyticsController(AnalyticsService analyticsService,
            com.acquira.common.repository.SumDailyMerchantRepository sumDailyMerchantRepository) {
        this.analyticsService = analyticsService;
        this.sumDailyMerchantRepository = sumDailyMerchantRepository;
    }

    @GetMapping("/executive")
    public ResponseEntity<Map<String, Object>> getExecutiveDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(analyticsService.getExecutiveDashboard(date));
    }

    @GetMapping("/merchant-summaries")
    public ResponseEntity<Page<MerchantSummaryDTO>> getMerchantSummaries(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month,
            @RequestParam(defaultValue = "0") int day,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.badRequest().build();

        LocalDate targetDate;
        if (year > 0 && month > 0 && day > 0) {
            // Invalid combinations (Feb 30) used to throw DateTimeException →
            // 500, which the grid rendered as "no data". Clamp to a 400.
            try {
                targetDate = LocalDate.of(year, month, day);
            } catch (java.time.DateTimeException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            targetDate = LocalDate.now();
        }

        LocalDate startOfMonth = targetDate.withDayOfMonth(1);
        LocalDate startOfYear = targetDate.withDayOfYear(1);

        String sql = """
                SELECT
                    m.name as merchantName, m.mid,
                    '' as storeName, '' as sid,
                    '' as tid, '' as deviceNumber,
                    COALESCE(daily.total_txns, 0) as dailyCount, COALESCE(daily.total_volume, 0) as dailyVolume,
                    COALESCE(mtd.total_txns, 0) as mtdCount, COALESCE(mtd.total_volume, 0) as mtdVolume,
                    COALESCE(ytd.total_txns, 0) as ytdCount, COALESCE(ytd.total_volume, 0) as ytdVolume,
                    COALESCE(daily.total_credit_volume, 0) as creditVolume,
                    COALESCE(daily.total_debit_prepaid_volume, 0) as debitPrepaidVolume,
                    COALESCE(m.sales_user_id, '') as salesUserId
                FROM dim_merchant m

                -- Daily Join — P2-1: tenant_id added so subquery scans only this tenant's rows
                LEFT JOIN sum_daily_merchant daily ON daily.merchant_id = m.merchant_id
                    AND daily.tenant_id = m.tenant_id
                    AND daily.business_date = :targetDate

                -- MTD Join (Aggregation) — P2-1: tenant_id pushed inside subquery
                LEFT JOIN (
                    SELECT merchant_id, tenant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfMonth AND business_date <= :targetDate
                      AND tenant_id = :tenantId
                    GROUP BY merchant_id, tenant_id
                ) mtd ON mtd.merchant_id = m.merchant_id AND mtd.tenant_id = m.tenant_id

                -- YTD Join (Aggregation) — P2-1: tenant_id pushed inside subquery
                LEFT JOIN (
                    SELECT merchant_id, tenant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfYear AND business_date <= :targetDate
                      AND tenant_id = :tenantId
                    GROUP BY merchant_id, tenant_id
                ) ytd ON ytd.merchant_id = m.merchant_id AND ytd.tenant_id = m.tenant_id

                WHERE m.tenant_id = :tenantId
                ORDER BY m.name
                """;

        // Pagination Limit/Offset
        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter("targetDate", targetDate);
        query.setParameter("startOfMonth", startOfMonth);
        query.setParameter("startOfYear", startOfYear);
        query.setParameter("tenantId", tenantId);

        query.setFirstResult(page * size);
        query.setMaxResults(size);

        List<Object[]> results = query.getResultList();

        List<MerchantSummaryDTO> dtos = results.stream().map(row -> new MerchantSummaryDTO(
                (String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                ((Number) row[6]).longValue(), (BigDecimal) row[7],
                ((Number) row[8]).longValue(), (BigDecimal) row[9],
                ((Number) row[10]).longValue(), (BigDecimal) row[11],
                (BigDecimal) row[12], (BigDecimal) row[13], (String) row[14])).collect(Collectors.toList());

        // Count for Pagination
        String countSql = "SELECT COUNT(*) FROM dim_merchant m WHERE m.tenant_id = :tenantId";
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(countSql);
        countQuery.setParameter("tenantId", tenantId);
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        return ResponseEntity.ok(new PageImpl<>(dtos, PageRequest.of(page, size), totalElements));
    }

    @GetMapping("/merchant-summaries/export")
    public void exportMerchantSummaries(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month,
            @RequestParam(defaultValue = "0") int day,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST, "Tenant Context Missing");
            return;
        }

        LocalDate targetDate;
        if (year > 0 && month > 0 && day > 0) {
            try {
                targetDate = LocalDate.of(year, month, day);
            } catch (java.time.DateTimeException e) {
                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST, "Invalid date");
                return;
            }
        } else {
            targetDate = LocalDate.now();
        }

        LocalDate startOfMonth = targetDate.withDayOfMonth(1);
        LocalDate startOfYear = targetDate.withDayOfYear(1);

        String sql = """
                 SELECT
                    m.name as merchantName, m.mid,
                    '' as storeName, '' as sid,
                    '' as tid, '' as deviceNumber,
                    COALESCE(daily.total_txns, 0) as dailyCount, COALESCE(daily.total_volume, 0) as dailyVolume,
                    COALESCE(mtd.total_txns, 0) as mtdCount, COALESCE(mtd.total_volume, 0) as mtdVolume,
                    COALESCE(ytd.total_txns, 0) as ytdCount, COALESCE(ytd.total_volume, 0) as ytdVolume,
                    COALESCE(daily.total_credit_volume, 0) as creditVolume,
                    COALESCE(daily.total_debit_prepaid_volume, 0) as debitPrepaidVolume,
                    COALESCE(m.sales_user_id, '') as salesUserId
                FROM dim_merchant m

                -- Daily Join — P2-1: tenant_id added so subquery scans only this tenant's rows
                LEFT JOIN sum_daily_merchant daily ON daily.merchant_id = m.merchant_id
                    AND daily.tenant_id = m.tenant_id
                    AND daily.business_date = :targetDate

                -- MTD Join (Aggregation) — P2-1: tenant_id pushed inside subquery
                LEFT JOIN (
                    SELECT merchant_id, tenant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfMonth AND business_date <= :targetDate
                      AND tenant_id = :tenantId
                    GROUP BY merchant_id, tenant_id
                ) mtd ON mtd.merchant_id = m.merchant_id AND mtd.tenant_id = m.tenant_id

                -- YTD Join (Aggregation) — P2-1: tenant_id pushed inside subquery
                LEFT JOIN (
                    SELECT merchant_id, tenant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfYear AND business_date <= :targetDate
                      AND tenant_id = :tenantId
                    GROUP BY merchant_id, tenant_id
                ) ytd ON ytd.merchant_id = m.merchant_id AND ytd.tenant_id = m.tenant_id

                WHERE m.tenant_id = :tenantId
                ORDER BY m.name
                """;

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter("targetDate", targetDate);
        query.setParameter("startOfMonth", startOfMonth);
        query.setParameter("startOfYear", startOfYear);
        query.setParameter("tenantId", tenantId);

        // Fetch cap+1 so truncation can be DETECTED and marked in the file
        // instead of silently shipping a short export that finance would
        // reconcile against as if complete.
        final int EXPORT_CAP = 10000;
        query.setMaxResults(EXPORT_CAP + 1);

        List<Object[]> results = query.getResultList();
        boolean truncated = results.size() > EXPORT_CAP;
        if (truncated) results = results.subList(0, EXPORT_CAP);

        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"merchant_summary_" + targetDate + ".csv\"");

        try (java.io.PrintWriter writer = response.getWriter()) {
            writer.println(
                    "Merchant Name,MID,Credit Volume,Debit/Prepaid Volume,Sales User,Daily Count,Daily Volume,MTD Count,MTD Volume,YTD Count,YTD Volume");
            for (Object[] row : results) {
                // row: 0=Name, 1=MID, 2=Store, 3=SID, 4=TID, 5=DevNum, 6=DC, 7=DV, 8=MC, 9=MV,
                // 10=YC, 11=YV, 12=Credit, 13=Debit, 14=SalesUser
                // Text fields are RFC-4180 escaped + formula-neutralised: a
                // merchant named ACME "Holdings" used to break every row after
                // it, and =/+/-/@ prefixes execute as formulas in Excel.
                writer.printf("%s,%s,%s,%s,%s,%d,%s,%d,%s,%d,%s%n",
                        csvCell(row[0]), csvCell(row[1]), row[12], row[13], csvCell(row[14]),
                        ((Number) row[6]).longValue(), row[7],
                        ((Number) row[8]).longValue(), row[9],
                        ((Number) row[10]).longValue(), row[11]);
            }
            if (truncated) {
                writer.println(csvCell("TRUNCATED — first " + EXPORT_CAP
                        + " merchants by name; refine the export or contact support for a full extract"));
            }
        }
    }

    /** RFC-4180 CSV cell: quote, double internal quotes, neutralise leading =+-@ (formula injection). */
    private static String csvCell(Object v) {
        String s = v == null ? "" : v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/heatmap")
    public ResponseEntity<List<com.acquira.common.dto.MerchantHeatmapDTO>> getMerchantHeatmap(
            @RequestParam(required = false) Integer year) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        // Default to current calendar year (was hardcoded 2025).
        int yr = (year != null) ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(sumDailyMerchantRepository.findMerchantHeatmapDataForTenant(yr, tenantId));
    }

    /**
     * Filtered heatmap. Same shape as GET /heatmap but accepts a full
     * VolumeRevenueFilterDTO so the caller can narrow by partner / RM / MCC /
     * team leader / merchant name / MID / SID / scheme / card type / destination /
     * channel.
     *
     * Two query strategies:
     *  1. "Fast path" (no card-level filters) — query sum_daily_merchant which is
     *     pre-aggregated at the merchant level; ~12 rows-per-merchant per year.
     *  2. "Slow but correct path" (any of scheme/cardType/destination/channel set)
     *     — query sum_daily_insight which has those columns; orders of magnitude
     *     more rows but necessary when the filter logically requires per-card-line
     *     scoping.
     *
     * In both paths we always tenant-scope on s.tenant_id AND m.tenant_id.
     */
    @PostMapping("/heatmap-filtered")
    public ResponseEntity<List<com.acquira.common.dto.MerchantHeatmapDTO>> getMerchantHeatmapFiltered(
            @RequestParam(required = false) Integer year,
            @RequestBody(required = false) com.acquira.common.dto.VolumeRevenueFilterDTO filter) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new com.acquira.common.dto.VolumeRevenueFilterDTO();
        int yr = (year != null) ? year : LocalDate.now().getYear();

        boolean usesCardFilters =
                (filter.getSchemeList()      != null && !filter.getSchemeList().isEmpty())     ||
                (filter.getCardTypeList()    != null && !filter.getCardTypeList().isEmpty())   ||
                (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())||
                (filter.getChannelList()     != null && !filter.getChannelList().isEmpty());

        StringBuilder sql = new StringBuilder();
        if (usesCardFilters) {
            // sum_daily_insight base. Joins dim_merchant for partner/RM/team-leader/name
            // and dim_store for MCC/SID. Per-card-line columns live directly on s.
            sql.append("SELECT m.name AS merchantName, m.internal_id AS merchantId, ");
            sql.append("       EXTRACT(MONTH FROM s.business_date) AS mo, ");
            sql.append("       SUM(s.total_volume) AS totalVolume ");
            sql.append("FROM sum_daily_insight s ");
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
            sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = :tid ");
            sql.append("WHERE EXTRACT(YEAR FROM s.business_date) = :yr ");
            sql.append("  AND s.tenant_id = :tid AND m.tenant_id = :tid ");
        } else {
            // sum_daily_merchant base — fast path. Card-level columns aren't here.
            sql.append("SELECT m.name AS merchantName, m.internal_id AS merchantId, ");
            sql.append("       EXTRACT(MONTH FROM s.business_date) AS mo, ");
            sql.append("       SUM(s.total_volume) AS totalVolume ");
            sql.append("FROM sum_daily_merchant s ");
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
            // dim_store join only when MCC or SID filtering is needed; avoids
            // multiplying merchant-level rows by store count.
            boolean needStore =
                    (filter.getMccList() != null && !filter.getMccList().isEmpty()) ||
                    (filter.getSidList() != null && !filter.getSidList().isEmpty());
            if (needStore) sql.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = :tid ");
            sql.append("WHERE EXTRACT(YEAR FROM s.business_date) = :yr ");
            sql.append("  AND s.tenant_id = :tid AND m.tenant_id = :tid ");
        }

        // Optional filters — only emit the WHERE fragment AND the parameter when
        // the list is non-empty, so the SQL stays clean and the parameter binder
        // never sees an unused name.
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("  AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("  AND m.sales_email IN (:rms) ");
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("  AND m.name ILIKE :merchName ");
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            sql.append("  AND m.mid IN (:mids) ");
        // MCC / SID via dim_store — in fast path the join only exists when needed.
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            sql.append("  AND st.mcc IN (:mccs) ");
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            sql.append("  AND st.sid IN (:sids) ");
        // Card-level filters — only valid against sum_daily_insight.
        if (usesCardFilters) {
            if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
                sql.append("  AND s.card_scheme IN (:schemes) ");
            if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
                sql.append("  AND s.card_type IN (:cardTypes) ");
            if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
                sql.append("  AND s.destination IN (:destinations) ");
            if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
                sql.append("  AND s.channel IN (:channels) ");
        }

        sql.append("GROUP BY m.name, m.internal_id, EXTRACT(MONTH FROM s.business_date) ");
        sql.append("ORDER BY m.name, mo");

        jakarta.persistence.Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("yr", yr);
        q.setParameter("tid", tenantId);
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            q.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            q.setParameter("rms", filter.getRmList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            q.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            q.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            q.setParameter("mids", filter.getMidList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            q.setParameter("mccs", filter.getMccList());
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            q.setParameter("sids", filter.getSidList());
        if (usesCardFilters) {
            if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
                q.setParameter("schemes", filter.getSchemeList());
            if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
                q.setParameter("cardTypes", filter.getCardTypeList());
            if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
                q.setParameter("destinations", filter.getDestinationList());
            if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
                q.setParameter("channels", filter.getChannelList());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<com.acquira.common.dto.MerchantHeatmapDTO> result = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String merchantName = (String) row[0];
            String merchantId   = (String) row[1];
            Integer mo          = ((Number) row[2]).intValue();
            BigDecimal vol      = (row[3] instanceof BigDecimal)
                    ? (BigDecimal) row[3]
                    : (row[3] != null ? new BigDecimal(row[3].toString()) : BigDecimal.ZERO);
            result.add(new com.acquira.common.dto.MerchantHeatmapDTO(merchantName, merchantId, mo, vol));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/available-years")
    public ResponseEntity<List<Integer>> getAvailableYears() {
        Long tenantId = TenantContext.getCurrentTenant();
        // sum_daily_bank, not sum_daily_merchant: same set of business dates
        // (both are written per ingest day by populateSummaryStep), but bank is
        // one row per tenant per day (~365/yr) vs merchant's per-merchant rows —
        // a full DISTINCT scan of it stays milliseconds at any scale.
        String sql = "SELECT DISTINCT EXTRACT(YEAR FROM business_date) FROM sum_daily_bank WHERE tenant_id = :tenantId ORDER BY 1 DESC";
        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        List<Number> results = query.getResultList();
        List<Integer> years = results.stream().map(Number::intValue).collect(Collectors.toList());

        // Ensure current year is always present
        int currentYear = LocalDate.now().getYear();
        if (!years.contains(currentYear)) {
            years.add(0, currentYear); // Add to top if missing
        }
        return ResponseEntity.ok(years);
    }

    /**
     * Scheme breakdown for dashboard pie chart.
     * Queries sum_daily_scheme grouped by card_scheme for a date range.
     */
    @PostMapping("/scheme-breakdown")
    public ResponseEntity<List<Map<String, Object>>> getSchemeBreakdown(
            @RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        LocalDate startDate = body.get("startDate") != null ? LocalDate.parse(body.get("startDate")) : LocalDate.now().minusDays(30);
        LocalDate endDate = body.get("endDate") != null ? LocalDate.parse(body.get("endDate")) : LocalDate.now();

        String sql = """
            SELECT card_scheme, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume,
                   SUM(total_msf) as total_msf
            FROM sum_daily_scheme
            WHERE tenant_id = :tid AND business_date BETWEEN :start AND :end
              AND card_scheme IS NOT NULL
            GROUP BY card_scheme
            ORDER BY total_volume DESC
            """;

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tid", tenantId);
        query.setParameter("start", startDate);
        query.setParameter("end", endDate);

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("card_scheme", row[0]);
            map.put("total_txns", row[1]);
            map.put("total_volume", row[2]);
            map.put("total_msf", row[3]);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }
}
