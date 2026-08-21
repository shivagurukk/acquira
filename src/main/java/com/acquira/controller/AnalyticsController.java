package com.acquira.controller;

import com.acquira.dto.MerchantSummaryDTO;
import com.acquira.service.AnalyticsService;
import com.acquira.config.TenantContext;
import jakarta.persistence.EntityManager;

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
    private final com.acquira.repository.SumDailyMerchantRepository sumDailyMerchantRepository;

    private final EntityManager entityManager;

    public AnalyticsController(AnalyticsService analyticsService,
            com.acquira.repository.SumDailyMerchantRepository sumDailyMerchantRepository,
            EntityManager entityManager) {
        this.analyticsService = analyticsService;
        this.sumDailyMerchantRepository = sumDailyMerchantRepository;
        this.entityManager = entityManager;
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
            targetDate = LocalDate.of(year, month, day);
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

                -- Daily Join
                LEFT JOIN sum_daily_merchant daily ON daily.merchant_id = m.merchant_id
                    AND daily.business_date = :targetDate

                -- MTD Join (Aggregation)
                LEFT JOIN (
                    SELECT merchant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfMonth AND business_date <= :targetDate
                    GROUP BY merchant_id
                ) mtd ON mtd.merchant_id = m.merchant_id

                -- YTD Join (Aggregation)
                LEFT JOIN (
                    SELECT merchant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfYear AND business_date <= :targetDate
                    GROUP BY merchant_id
                ) ytd ON ytd.merchant_id = m.merchant_id

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
            targetDate = LocalDate.of(year, month, day);
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

                -- Daily Join
                LEFT JOIN sum_daily_merchant daily ON daily.merchant_id = m.merchant_id
                    AND daily.business_date = :targetDate

                -- MTD Join (Aggregation)
                LEFT JOIN (
                    SELECT merchant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfMonth AND business_date <= :targetDate
                    GROUP BY merchant_id
                ) mtd ON mtd.merchant_id = m.merchant_id

                -- YTD Join (Aggregation)
                LEFT JOIN (
                    SELECT merchant_id, SUM(total_txns) as total_txns, SUM(total_volume) as total_volume
                    FROM sum_daily_merchant
                    WHERE business_date >= :startOfYear AND business_date <= :targetDate
                    GROUP BY merchant_id
                ) ytd ON ytd.merchant_id = m.merchant_id

                WHERE m.tenant_id = :tenantId
                ORDER BY m.name
                """;

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter("targetDate", targetDate);
        query.setParameter("startOfMonth", startOfMonth);
        query.setParameter("startOfYear", startOfYear);
        query.setParameter("tenantId", tenantId);

        // Limit export for safety (or stream properly, but list is fine for MVP < 100k
        // rows)
        query.setMaxResults(10000);

        List<Object[]> results = query.getResultList();

        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"merchant_summary_" + targetDate + ".csv\"");

        try (java.io.PrintWriter writer = response.getWriter()) {
            writer.println(
                    "Merchant Name,MID,Credit Volume,Debit/Prepaid Volume,Sales User,Daily Count,Daily Volume,MTD Count,MTD Volume,YTD Count,YTD Volume");
            for (Object[] row : results) {
                // row: 0=Name, 1=MID, 2=Store, 3=SID, 4=TID, 5=DevNum, 6=DC, 7=DV, 8=MC, 9=MV,
                // 10=YC, 11=YV, 12=Credit, 13=Debit, 14=SalesUser
                writer.printf("\"%s\",\"%s\",%s,%s,\"%s\",%d,%s,%d,%s,%d,%s%n",
                        row[0], row[1], row[12], row[13], row[14],
                        ((Number) row[6]).longValue(), row[7],
                        ((Number) row[8]).longValue(), row[9],
                        ((Number) row[10]).longValue(), row[11]);
            }
        }
    }

    @GetMapping("/heatmap")
    public ResponseEntity<List<Map<String, Object>>> getMerchantHeatmap(
            @RequestParam(defaultValue = "2025") int year,
            @RequestParam(required = false) List<String> sidList) {
        return ResponseEntity.ok(getHeatmapData(year, sidList));
    }

    private List<Map<String, Object>> getHeatmapData(int year, List<String> sidList) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.name as merchant_name, m.mid as merchant_id, ");
        sql.append("  EXTRACT(MONTH FROM s.business_date) as month, ");
        sql.append("  SUM(s.total_volume) as total_volume ");
        sql.append("FROM sum_daily_merchant s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        if (sidList != null && !sidList.isEmpty()) {
            sql.append("JOIN dim_store st ON st.merchant_id = m.merchant_id ");
        }
        sql.append("WHERE EXTRACT(YEAR FROM s.business_date) = :year ");
        if (sidList != null && !sidList.isEmpty()) {
            sql.append("AND st.sid IN (:sids) ");
        }
        sql.append("GROUP BY m.name, m.mid, EXTRACT(MONTH FROM s.business_date) ");
        sql.append("ORDER BY m.name, EXTRACT(MONTH FROM s.business_date)");

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("year", year);
        if (sidList != null && !sidList.isEmpty()) {
            query.setParameter("sids", sidList);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("merchantName", row[0]);
            map.put("merchantId", row[1]);
            map.put("month", ((Number) row[2]).intValue());
            map.put("totalVolume", row[3]);
            result.add(map);
        }
        return result;
    }

    @GetMapping("/available-years")
    public ResponseEntity<List<Integer>> getAvailableYears() {
        Long tenantId = TenantContext.getCurrentTenant();
        String sql = "SELECT DISTINCT EXTRACT(YEAR FROM business_date) FROM sum_daily_merchant WHERE tenant_id = :tenantId ORDER BY 1 DESC";
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
