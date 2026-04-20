package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id ";
                groupBy = "GROUP BY s.merchant_id ";
                break;
            case "SALES":
            case "SALES_EMAIL":
                selectClause = "m.sales_user_id, COALESCE(m.sales_user_id, 'Unassigned') as label, ";
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id ";
                groupBy = "GROUP BY m.sales_user_id ";
                break;
            case "REFERRAL":
            case "REFERRAL_PARTNER":
                // Fallback to Sales ID if referral is empty
                selectClause = "COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id) as grp_key, COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') as label, ";
                sql = "FROM sum_daily_merchant s JOIN dim_merchant m ON s.merchant_id = m.merchant_id ";
                groupBy = "GROUP BY COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id), COALESCE(NULLIF(m.referral_partner, ''), m.sales_user_id, 'Unassigned') ";
                break;
            default:
                return ResponseEntity.badRequest().body("Invalid Report Type");
        }

        String finalSql = "SELECT " + selectClause +
                "COUNT(DISTINCT s.merchant_id) as merchant_count, " + // distinct merchants in this group
                "SUM(s.total_txns) as total_txns, " +
                "SUM(s.total_volume) as total_volume " +
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
            // Join sum_daily_merchant with dim_store to get MCC and accurate merchant count
            finalSql = "SELECT st.mcc, COALESCE(st.mcc, 'Unknown') as label, " +
                    "COUNT(DISTINCT s.merchant_id) as merchant_count, " +
                    "SUM(s.total_txns) as total_txns, " +
                    "SUM(s.total_volume) as total_volume " +
                    "FROM sum_daily_merchant s " +
                    "JOIN dim_store st ON s.store_id = st.store_id " +
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

        List<Map<String, Object>> response = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row[0]);
            map.put("label", row[1]);
            map.put("merchantCount", ((Number) row[2]).longValue());
            map.put("txnCount", ((Number) row[3]).longValue());
            map.put("volume", (BigDecimal) row[4]);
            response.add(map);
        }

        return ResponseEntity.ok(response);
    }
}
