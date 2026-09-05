package com.acquira.common.repository;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs Pricing Simulator v2 (/business/pricing-simulator) — the segment
 * margin matrix: card_scheme × card_type (CREDIT/DEBIT/PREPAID/…) ×
 * destination group (DOMESTIC/INTERNATIONAL), each cell carrying the REAL
 * realized fee stack from sum_daily_full (total_msf / total_interchange /
 * total_scheme_fee / total_ecom_fee / total_net_revenue).
 *
 * This is the whole point of v2: the simulator prices off REALIZED effective
 * rates per segment instead of the bank-average cost approximation the v1
 * page used (sum_daily_insight carries no fee columns). Realized rates
 * automatically absorb caps (UAE debit AED cap), tier blends and mix — no
 * re-implementation of the fee engine.
 *
 * Normalization conventions (must stay aligned with the sibling dashboards):
 *  - card_type: blank/NULL folds into 'UNSPECIFIED' (CardTypeDashboardRepository
 *    convention) so untyped volume never silently disappears — the frontend
 *    renders it as its own muted "No card type" column with levers disabled.
 *  - destination: UPPER(destination)='DOMESTIC' is the confirmed stored
 *    literal (DestinationDashboardRepository); everything else — including
 *    blank — counts as INTERNATIONAL, same as that page.
 *  - card_scheme: already normalized at populate time; blank still folds to
 *    'UNCLASSIFIED' defensively.
 *
 * cardTypeList / destinationList / schemeList in the filter DTO are
 * intentionally ignored — all three are split dimensions here, never
 * narrowing filters (same convention as the split dimension on the
 * Destination/Card Type dashboards). Cohort narrowing uses the remaining
 * drawer filters (MCC / RM / partner / MID / …).
 */
@Repository
public class PricingSegmentMatrixRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String SCHEME_EXPR =
            "UPPER(COALESCE(NULLIF(TRIM(s.card_scheme),''),'UNCLASSIFIED'))";
    private static final String CT_EXPR =
            "UPPER(COALESCE(NULLIF(TRIM(s.card_type),''),'UNSPECIFIED'))";
    private static final String DEST_EXPR =
            "CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' THEN 'DOMESTIC' ELSE 'INTERNATIONAL' END";

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    private static long lng(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    private void appendJoins(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        // LEFT: merchant_id can be NULL on sum_daily_full (unmatched-merchant fact rows).
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (listNonEmpty(filter.getSidList()))
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
    }

    /**
     * Cohort filters only — scheme/card_type/destination are deliberately
     * absent (split dimensions, see class javadoc). Mirrors
     * CardTypeDashboardRepository.appendCommonFilters otherwise.
     */
    private void appendCohortFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList()))        sql.append("AND s.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getSidList()))        sql.append("AND st.sid IN (:sids) ");
        if (listNonEmpty(filter.getChannelList()))    sql.append("AND s.channel IN (:channels) ");
    }

    private void bindCohortParams(Query query, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   query.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))    query.setParameter("channels", filter.getChannelList());
    }

    /**
     * MIN/MAX business_date in sum_daily_full for this tenant — the page
     * anchors its default window here, not on the fact-anchored shared
     * bounds (same reasoning as CardTypeDashboardRepository.getBounds).
     */
    public Map<String, Object> getBounds(Long tenantId) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT MIN(s.business_date), MAX(s.business_date) FROM sum_daily_full s WHERE s.tenant_id = :tenantId");
        query.setParameter("tenantId", tenantId);

        Object[] r = (Object[]) query.getSingleResult();
        Map<String, Object> out = new HashMap<>();
        out.put("earliest", r[0] == null ? null : r[0].toString());
        out.put("latest", r[1] == null ? null : r[1].toString());
        return out;
    }

    /**
     * One row per (scheme, card_type, destination group) over the window:
     * raw sums only — all derived rates (bps), coverage and flags are
     * computed in PricingSimulatorService so the SQL stays a plain
     * summary-grain GROUP BY. Dates must already be defaulted by the caller
     * (sargable BETWEEN — never wrap business_date).
     */
    public List<Map<String, Object>> getSegmentMatrix(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate start = filter.getStartDate();
        LocalDate end = filter.getEndDate();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(SCHEME_EXPR).append(" as scheme, ");
        sql.append(CT_EXPR).append(" as card_type, ");
        sql.append(DEST_EXPR).append(" as dest_group, ");
        sql.append("SUM(s.total_txns) as txns, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) as interchange, ");
        sql.append("SUM(s.total_scheme_fee) as scheme_fee, ");
        sql.append("SUM(s.total_ecom_fee) as ecom_fee, ");
        sql.append("SUM(s.total_net_revenue) as net_revenue, ");
        sql.append("COUNT(DISTINCT s.merchant_id) as merchants ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        appendCohortFilters(sql, filter);
        sql.append("GROUP BY ").append(SCHEME_EXPR).append(", ").append(CT_EXPR).append(", ").append(DEST_EXPR).append(" ");
        sql.append("ORDER BY vol DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        bindCohortParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> seg = new HashMap<>();
            seg.put("scheme",      r[0] == null ? "UNCLASSIFIED" : r[0].toString());
            seg.put("cardType",    r[1] == null ? "UNSPECIFIED" : r[1].toString());
            seg.put("destination", r[2] == null ? "INTERNATIONAL" : r[2].toString());
            seg.put("txns",        lng(r[3]));
            seg.put("volume",      bd(r[4]));
            seg.put("msf",         bd(r[5]));
            seg.put("interchange", bd(r[6]));
            seg.put("schemeFee",   bd(r[7]));
            seg.put("ecomFee",     bd(r[8]));
            seg.put("netRevenue",  bd(r[9]));
            seg.put("merchants",   lng(r[10]));
            out.add(seg);
        }
        return out;
    }

    /**
     * ONE merchant's full segment breakdown — every (scheme, card_type,
     * destination) cell that MID trades in, with its realized fee stack.
     * The MID-wise repricing view: compare each row against the tenant
     * segment benchmark (already loaded by getSegmentMatrix) and lever
     * per segment. Same shape as getSegmentMatrix rows minus the
     * merchant count (it is always 1 merchant).
     */
    public List<Map<String, Object>> getMerchantSegmentMatrix(VolumeRevenueFilterDTO filter, Long tenantId,
                                                              String mid) {
        requireTenant(tenantId);
        LocalDate start = filter.getStartDate();
        LocalDate end = filter.getEndDate();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(SCHEME_EXPR).append(" as scheme, ");
        sql.append(CT_EXPR).append(" as card_type, ");
        sql.append(DEST_EXPR).append(" as dest_group, ");
        sql.append("SUM(s.total_txns) as txns, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) as interchange, ");
        sql.append("SUM(s.total_scheme_fee) as scheme_fee, ");
        sql.append("SUM(s.total_ecom_fee) as ecom_fee, ");
        sql.append("SUM(s.total_net_revenue) as net_revenue ");
        sql.append("FROM sum_daily_full s ");
        // INNER join: this query is keyed on dim_merchant.mid, so unmatched
        // fact rows (NULL merchant_id) can never belong to it anyway.
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        sql.append("AND m.mid = :mid ");
        sql.append("GROUP BY ").append(SCHEME_EXPR).append(", ").append(CT_EXPR).append(", ").append(DEST_EXPR).append(" ");
        sql.append("ORDER BY vol DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        query.setParameter("mid", mid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> seg = new HashMap<>();
            seg.put("scheme",      r[0] == null ? "UNCLASSIFIED" : r[0].toString());
            seg.put("cardType",    r[1] == null ? "UNSPECIFIED" : r[1].toString());
            seg.put("destination", r[2] == null ? "INTERNATIONAL" : r[2].toString());
            seg.put("txns",        lng(r[3]));
            seg.put("volume",      bd(r[4]));
            seg.put("msf",         bd(r[5]));
            seg.put("interchange", bd(r[6]));
            seg.put("schemeFee",   bd(r[7]));
            seg.put("ecomFee",     bd(r[8]));
            seg.put("netRevenue",  bd(r[9]));
            out.add(seg);
        }
        return out;
    }

    /**
     * Merchant drill-down inside ONE segment, sorted by effective MSF rate
     * ascending — the top of the list IS the repricing worklist (merchants
     * priced furthest below their peers in this segment). Segment keys must
     * be the normalized values getSegmentMatrix returned.
     */
    public List<Map<String, Object>> getSegmentMerchants(VolumeRevenueFilterDTO filter, Long tenantId,
                                                         String scheme, String cardType, String destGroup,
                                                         int limit) {
        requireTenant(tenantId);
        LocalDate start = filter.getStartDate();
        LocalDate end = filter.getEndDate();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid, COALESCE(m.name,'(unmatched)') as name, ");
        sql.append("SUM(s.total_txns) as txns, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) + SUM(s.total_scheme_fee) + SUM(s.total_ecom_fee) as cost, ");
        sql.append("SUM(s.total_net_revenue) as net_revenue ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        sql.append("AND ").append(SCHEME_EXPR).append(" = :segScheme ");
        sql.append("AND ").append(CT_EXPR).append(" = :segCardType ");
        sql.append("AND ").append(DEST_EXPR).append(" = :segDest ");
        appendCohortFilters(sql, filter);
        sql.append("GROUP BY m.mid, m.name ");
        // Volume floor: rate on a near-zero denominator is noise, not signal.
        sql.append("HAVING SUM(s.total_volume) > 0 ");
        sql.append("ORDER BY (SUM(s.total_msf) / NULLIF(SUM(s.total_volume), 0)) ASC NULLS LAST ");
        sql.append("LIMIT :lim");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        query.setParameter("segScheme", scheme);
        query.setParameter("segCardType", cardType);
        query.setParameter("segDest", destGroup);
        query.setParameter("lim", limit);
        bindCohortParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> row = new HashMap<>();
            row.put("mid",        r[0] == null ? null : r[0].toString());
            row.put("name",       r[1] == null ? null : r[1].toString());
            row.put("txns",       lng(r[2]));
            row.put("volume",     bd(r[3]));
            row.put("msf",        bd(r[4]));
            row.put("cost",       bd(r[5]));
            row.put("netRevenue", bd(r[6]));
            out.add(row);
        }
        return out;
    }
}
