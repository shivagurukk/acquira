package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only merchant-segmentation endpoints.
 *
 * Serves the precomputed merchant_segment rows written by the batch
 * computeSegmentsStep (MerchantSegmentationService). No computation here — dashboards
 * read precomputed segments so there is zero query-time cost.
 *
 * The latest segment per merchant is joined to dim_merchant so the frontend can merge
 * by `mid` and group/filter by segment, RM, MCC. Additive & isolated: new controller,
 * native read queries, touches nothing else. Tenant scoped on the base table and
 * pushed onto the dim join.
 */
@RestController
@RequestMapping("/api/business/segments")
public class MerchantSegmentController {

    @PersistenceContext
    private EntityManager entityManager;

    private Long resolveTenant(Long headerTenant) {
        // SECURITY: the raw X-Tenant-Id header is attacker-controlled; use only the
        // filter-validated TenantContext (JwtRequestFilter rejects spoofed headers).
        return TenantContext.getCurrentTenant();
    }

    /**
     * Latest segment per merchant for the tenant, highest segment_score first.
     * Returns: merchantId, mid, name, mcc, rm, primarySegment, secondaryTags,
     * segmentReason, segmentScore, and the metric snapshot columns.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        String sql =
            "SELECT s.merchant_id, m.mid, m.name, m.mcc, m.sales_email, " +
            "       s.primary_segment, s.secondary_tags, s.segment_reason, s.segment_score, " +
            "       s.total_volume, s.net_revenue, s.net_margin_pct, s.effective_bps, s.net_take_bps, " +
            "       s.volume_growth_pct, s.days_since_last, s.calc_date " +
            "FROM merchant_segment s " +
            "LEFT JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = :tid " +
            "  AND s.calc_date = (SELECT MAX(s2.calc_date) FROM merchant_segment s2 " +
            "                     WHERE s2.tenant_id = s.tenant_id AND s2.merchant_id = s.merchant_id) " +
            "ORDER BY s.segment_score DESC";

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", r[0] == null ? null : ((Number) r[0]).longValue());
            m.put("mid", r[1]);
            m.put("name", r[2]);
            m.put("mcc", r[3]);
            m.put("rm", r[4]);
            m.put("primarySegment", r[5]);
            m.put("secondaryTags", r[6]);
            m.put("segmentReason", r[7]);
            m.put("segmentScore", toDouble(r[8]));
            m.put("totalVolume", toDouble(r[9]));
            m.put("netRevenue", toDouble(r[10]));
            m.put("netMarginPct", toDouble(r[11]));
            m.put("effectiveBps", toDouble(r[12]));
            m.put("netTakeBps", toDouble(r[13]));
            m.put("volumeGrowthPct", toDouble(r[14]));
            m.put("daysSinceLast", r[15] == null ? null : ((Number) r[15]).intValue());
            m.put("calcDate", r[16] == null ? null : r[16].toString());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Segment-mix counts for the tenant's latest calc_date (for donut/summary tiles). */
    @GetMapping("/mix")
    public ResponseEntity<?> mix(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        String sql =
            "SELECT s.primary_segment, COUNT(*) " +
            "FROM merchant_segment s " +
            "WHERE s.tenant_id = :tid " +
            "  AND s.calc_date = (SELECT MAX(s3.calc_date) FROM merchant_segment s3 WHERE s3.tenant_id = :tid) " +
            "GROUP BY s.primary_segment " +
            "ORDER BY COUNT(*) DESC";

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("segment", r[0]);
            m.put("count", r[1] == null ? 0 : ((Number) r[1]).longValue());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return ((BigDecimal) o).doubleValue();
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }
}
