package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only churn-risk endpoint (ML Phase 1).
 *
 * Serves the precomputed merchant_churn_score rows written by the batch
 * scoreMlStep (ChurnScoringService). No computation here — dashboards read
 * precomputed scores so there is zero query-time ML cost.
 *
 * The latest score per merchant is joined to dim_merchant so the frontend can
 * merge by `mid` (the Attrition Report keys its rows by mid, not merchant_id).
 *
 * Additive & isolated: new controller, native read query, touches nothing else.
 * Tenant scoped on the base table and pushed onto the dim join.
 */
@RestController
@RequestMapping("/api/business/churn-risk")
public class ChurnRiskController {

    @PersistenceContext
    private EntityManager entityManager;

    private Long resolveTenant(Long headerTenant) {
        // SECURITY: the raw X-Tenant-Id header is attacker-controlled; use only the
        // filter-validated TenantContext (JwtRequestFilter rejects spoofed headers).
        return TenantContext.getCurrentTenant();
    }

    /**
     * Latest churn score per merchant for the tenant, newest-risk first.
     * Returns: mid, merchantId, name, churnProbability (0..1), riskBand,
     * topReason, scoredBy, calcDate.
     */
    @GetMapping
    public ResponseEntity<?> getChurnRisk(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();

        // One row per merchant = the most recent calc_date for that merchant.
        // Correlated MAX keeps it dialect-agnostic. dim_merchant join is tenant-scoped.
        String sql =
            "SELECT c.merchant_id, m.mid, m.name, c.churn_probability, c.risk_band, " +
            "       c.top_reason, c.scored_by, c.calc_date " +
            "FROM merchant_churn_score c " +
            "LEFT JOIN dim_merchant m ON m.merchant_id = c.merchant_id AND m.tenant_id = c.tenant_id " +
            "WHERE c.tenant_id = :tid " +
            "  AND c.calc_date = (SELECT MAX(c2.calc_date) FROM merchant_churn_score c2 " +
            "                     WHERE c2.tenant_id = c.tenant_id AND c2.merchant_id = c.merchant_id) " +
            "ORDER BY c.churn_probability DESC";

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
            m.put("churnProbability", toDouble(r[3]));
            m.put("riskBand", r[4]);
            m.put("topReason", r[5]);
            m.put("scoredBy", r[6]);
            m.put("calcDate", r[7] == null ? null : r[7].toString());
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
