package com.acquira.common.service;

import com.acquira.common.config.ReportCacheConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-tenant earliest/latest data dates.
 *
 * This answer gates the FIRST data fetch of most report pages (the frontend's
 * useDataBounds hook blocks on it), so it must be fast. The underlying MIN/MAX
 * over fact_transaction is index-assisted but still fans out across every
 * partition of the tenant's history — cheap once, wasteful on every page open.
 * Hence the cache: bounds move only when an ingest lands, and the batch jobs
 * evict this cache on completion.
 *
 * Source-of-truth order (P2-6): fact_transaction first — if populateSummaryStep
 * failed mid-run the summary tables can be sparse while fact holds the real
 * data — then sum_daily_insight as fallback for fact-empty environments.
 */
@Service
public class DataBoundsService {

    @PersistenceContext
    private EntityManager entityManager;

    // `unless` keeps a transient DB error from being served for the whole TTL.
    @Cacheable(cacheNames = ReportCacheConfig.CACHE_DATA_BOUNDS,
               key = "#tenantId != null ? #tenantId : 'all'",
               unless = "#result.containsKey('error')")
    public Map<String, Object> getBounds(Long tenantId) {
        Map<String, Object> response = new HashMap<>();
        try {
            String factSql = "SELECT MIN(payment_date)::date AS earliest, MAX(payment_date)::date AS latest " +
                             "FROM fact_transaction" +
                             (tenantId != null ? " WHERE tenant_id = :tid" : "");
            Query qFact = entityManager.createNativeQuery(factSql);
            if (tenantId != null) qFact.setParameter("tid", tenantId);
            Object[] factRow = (Object[]) qFact.getSingleResult();
            String earliest = factRow != null && factRow[0] != null ? factRow[0].toString() : null;
            String latest   = factRow != null && factRow[1] != null ? factRow[1].toString() : null;

            if (earliest == null && latest == null) {
                String insSql = "SELECT MIN(business_date) AS earliest, MAX(business_date) AS latest " +
                                "FROM sum_daily_insight" +
                                (tenantId != null ? " WHERE tenant_id = :tid" : "");
                Query qIns = entityManager.createNativeQuery(insSql);
                if (tenantId != null) qIns.setParameter("tid", tenantId);
                Object[] insRow = (Object[]) qIns.getSingleResult();
                earliest = insRow != null && insRow[0] != null ? insRow[0].toString() : null;
                latest   = insRow != null && insRow[1] != null ? insRow[1].toString() : null;
            }

            response.put("earliest", earliest);
            response.put("latest", latest);
        } catch (Exception e) {
            response.put("earliest", null);
            response.put("latest", null);
            response.put("error", e.getMessage());
        }
        return response;
    }
}
