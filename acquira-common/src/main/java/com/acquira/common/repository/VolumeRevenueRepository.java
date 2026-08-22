package com.acquira.common.repository;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class VolumeRevenueRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Fee overlay for the Finance Summary — interchange + scheme fee at the
     * same grain and in the same three buckets as
     * {@link #getPerformanceDashboardData}.
     *
     * WHY A SEPARATE QUERY RATHER THAN EXTRA COLUMNS ON THE PIVOT
     * -----------------------------------------------------------
     * The pivot reads sum_daily_insight / sum_monthly_insight, and NEITHER
     * carries an interchange or scheme-fee column — the only day-grain summary
     * that does AND still carries destination + card_type is sum_daily_full.
     * Rather than repoint the pivot (which would change every count / volume /
     * MSF figure the screen has ever shown, since the two tables are built at
     * different grains), the fee stack is fetched as a strictly ADDITIVE
     * overlay and merged onto the existing rows by label. If sum_daily_full has
     * no rows for the range the overlay is simply empty and the report renders
     * exactly as it did before, with zeroed fee columns.
     *
     * BUCKETS: the same partition expressions as the pivot, so a fee column
     * always lines up with the volume column beside it.
     *
     * fee_basis_msf: sum_daily_full's OWN MSF for the row. The UI nets margin
     * against this rather than against the pivot's MSF, so all three terms of
     * the margin come from one table and can never disagree; it also lets the
     * screen flag a row where the two MSF figures diverge materially.
     *
     * @param groupBy MONTH | DAY | MERCHANT — anything else yields an empty map.
     * @return row label -> fee columns, keyed exactly as the pivot's row_label.
     */
    public Map<String, Map<String, Object>> getFinanceFeeOverlay(
            java.time.LocalDate start, java.time.LocalDate end, String groupBy, Long tenantId) {
        requireTenant(tenantId);
        if (start == null || end == null) return new HashMap<>();

        final String label;
        final boolean needMerchant;
        if ("MONTH".equals(groupBy)) {
            label = "TO_CHAR(s.business_date, 'YYYY-MM')";
            needMerchant = false;
        } else if ("DAY".equals(groupBy)) {
            label = "TO_CHAR(s.business_date, 'YYYY-MM-DD')";
            needMerchant = false;
        } else if ("MERCHANT".equals(groupBy)) {
            label = "m.mid";
            needMerchant = true;
        } else {
            return new HashMap<>();
        }

        // Identical to the pivot's partition — see getPerformanceDashboardDataDaily.
        final String DOM_DEBIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID')";
        final String DOM_CREDIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID')";
        final String INTL = "UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC'";

        StringBuilder sql = new StringBuilder("SELECT ").append(label).append(" as row_label, ");
        String[][] buckets = { { "dom_debit", DOM_DEBIT }, { "dom_credit", DOM_CREDIT }, { "int", INTL } };
        for (String[] b : buckets) {
            sql.append(" SUM(CASE WHEN ").append(b[1])
               .append(" THEN COALESCE(s.total_interchange,0) ELSE 0 END) as ").append(b[0]).append("_ic, ");
            sql.append(" SUM(CASE WHEN ").append(b[1])
               .append(" THEN COALESCE(s.total_scheme_fee,0) ELSE 0 END) as ").append(b[0]).append("_sf, ");
        }
        sql.append(" SUM(COALESCE(s.total_interchange,0)) as total_ic, ");
        sql.append(" SUM(COALESCE(s.total_scheme_fee,0)) as total_sf, ");
        sql.append(" SUM(COALESCE(s.total_msf,0)) as fee_basis_msf ");
        sql.append("FROM sum_daily_full s ");
        if (needMerchant)
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = :tenantId ");
        sql.append("AND s.business_date >= :startDate AND s.business_date <= :endDate ");
        sql.append("GROUP BY ").append(label);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        query.setParameter("startDate", start);
        query.setParameter("endDate", end);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, Map<String, Object>> out = new HashMap<>();
        String[] cols = { "dom_debit_ic", "dom_debit_sf", "dom_credit_ic", "dom_credit_sf",
                "int_ic", "int_sf", "total_ic", "total_sf", "fee_basis_msf" };
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < cols.length; i++)
                m.put(cols[i], row[i + 1]);
            out.put(row[0].toString(), m);
        }
        return out;
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null we add
     * `AND s.tenant_id = :tenantId` so cross-tenant rows can never appear in
     * the volume/revenue summary.
     *
     * Month-grain routing: the complete calendar months of the range are served
     * from the pre-aggregate sum_monthly_insight and only a partial head/tail
     * month falls back to sum_daily_insight — the same leg split as
     * getPerformanceDashboardData(groupBy=MONTH).
     *
     * WHY the split rather than the all-or-nothing check alone: the screen's
     * default preset is "this year", i.e. Jan 1 → TODAY, whose tail month is
     * partial. Requiring the WHOLE range to be month-aligned therefore sent
     * every default page load down the daily path, where it exceeded the 30s
     * web statement_timeout (TenantAwareDataSource). Only LAST_MONTH/LAST_YEAR
     * ever qualified.
     *
     * EXACTNESS: the legs produce disjoint month buckets by construction (the
     * head/tail months are excluded from [mStart, mEnd]), so the per-month
     * COUNT(DISTINCT merchant_id) is never split across two legs and the rows
     * can simply be concatenated and re-sorted — no cross-leg merging.
     */
    public List<Map<String, Object>> getSummary(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);

        java.time.LocalDate start = filter.getStartDate();
        java.time.LocalDate end = filter.getEndDate();
        if (start != null && end != null && !start.isAfter(end)) {
            java.time.LocalDate mStart = start.getDayOfMonth() == 1 ? start
                    : start.plusMonths(1).withDayOfMonth(1);
            java.time.LocalDate mEnd = end.getDayOfMonth() == end.lengthOfMonth() ? end
                    : end.withDayOfMonth(1).minusDays(1);
            if (!mStart.isAfter(mEnd)) {
                // At least one whole month → monthly leg, plus any partial head/tail.
                List<Map<String, Object>> result = new ArrayList<>(runSummaryLeg(filter, mStart, mEnd, tenantId));
                if (start.isBefore(mStart))
                    result.addAll(runSummaryLeg(filter, start, mStart.minusDays(1), tenantId));
                if (end.isAfter(mEnd))
                    result.addAll(runSummaryLeg(filter, mEnd.plusDays(1), end, tenantId));
                // Match the single-leg ORDER BY month_label DESC ('YYYY-MM' sorts chronologically)
                result.sort((a, b) -> String.valueOf(b.get("month")).compareTo(String.valueOf(a.get("month"))));
                return result;
            }
        }
        // Range sits inside a single calendar month (or has an open bound) —
        // nothing to route, run it as-is against the daily table.
        return getSummaryLeg(filter, tenantId);
    }

    /**
     * Runs one leg of {@link #getSummary} over a sub-range. Swaps the filter's
     * dates in place for the call and restores them after (the DTO is
     * request-scoped but shared across the legs of one request) — same
     * convention as {@link #runDailyMonthPivot}.
     */
    private List<Map<String, Object>> runSummaryLeg(VolumeRevenueFilterDTO filter,
            java.time.LocalDate start, java.time.LocalDate end, Long tenantId) {
        java.time.LocalDate origStart = filter.getStartDate();
        java.time.LocalDate origEnd = filter.getEndDate();
        try {
            filter.setStartDate(start);
            filter.setEndDate(end);
            return getSummaryLeg(filter, tenantId);
        } finally {
            filter.setStartDate(origStart);
            filter.setEndDate(origEnd);
        }
    }

    /**
     * One grouped scan for the volume/revenue summary. Reads the month-grain
     * pre-aggregate when the leg's range is whole months, the daily table
     * otherwise; callers above guarantee at most one partial month per leg.
     */
    private List<Map<String, Object>> getSummaryLeg(VolumeRevenueFilterDTO filter, Long tenantId) {
        StringBuilder sql = new StringBuilder();

        // ── Monthly pre-aggregate routing ──────────────────────────────────
        // This method is month-grained by construction (GROUP BY YYYY-MM), so it
        // can read the month-grain pre-aggregate sum_monthly_insight instead of
        // the day-grain sum_daily_insight — ~30x fewer rows, the key to keeping
        // wide ranges fast at billions of day rows.
        //
        // EXACTNESS GUARD: monthly = SUM(daily) reconciles EXACTLY only when the
        // range covers WHOLE months. If the range starts mid-month or ends
        // mid-month, month_key <= endMonthKey would include days outside
        // [startDate, endDate], over-counting vs the daily query. So we route to
        // monthly ONLY for whole-month ranges (start = 1st, end = month-end),
        // which is exactly what the trend screens use by default. Any partial
        // month falls back to the daily table, preserving correctness.
        boolean useMonthly = canUseMonthly(filter.getStartDate(), filter.getEndDate());
        final String S_DATE  = useMonthly ? "s.month_key" : "s.business_date";
        final String MONTH_LABEL = useMonthly
                ? "TO_CHAR(TO_DATE(CAST(s.month_key AS text), 'YYYYMM'), 'YYYY-MM')"
                : "TO_CHAR(s.business_date, 'YYYY-MM')";
        final String BASE_TABLE = useMonthly ? "sum_monthly_insight s" : "sum_daily_insight s";
        final Integer startMonthKey = useMonthly ? monthKey(filter.getStartDate()) : null;
        final Integer endMonthKey   = useMonthly ? monthKey(filter.getEndDate())   : null;

        // Base Query joining Fact/Summary with Dimensions
        // We use sum_daily_insight as the base as it has scheme, card_type, etc.
        // But for 'Partner', 'RM', 'Merchant Name' we need dim_merchant.

        sql.append("SELECT ");
        sql.append("  ").append(MONTH_LABEL).append(" as month_label, ");
        sql.append("  SUM(s.total_txns) as total_txns, ");
        sql.append("  SUM(s.total_volume) as total_volume, ");
        sql.append("  SUM(s.total_msf) as total_msf, ");
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as opt_in_volume, ");
        // International volume split — domestic is derivable client-side as
        // total_volume - intl_volume, so a fifth bucket column isn't needed.
        sql.append("  SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("  COUNT(DISTINCT s.merchant_id) as active_merchants ");
        sql.append("FROM ").append(BASE_TABLE).append(" ");

        // dim_merchant is only needed when a merchant-attribute filter is set.
        // It used to be joined unconditionally: no column of it is selected and
        // merchant_id is dim_merchant's PK (so the join can never fan rows out),
        // making it pure cost on every unfiltered run over the daily table.
        boolean needMerchant = (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
                || (filter.getRmList() != null && !filter.getRmList().isEmpty())
                || (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                || (filter.getMidList() != null && !filter.getMidList().isEmpty())
                || (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty());
        if (needMerchant) {
            sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        }

        // dim_store is needed whenever we filter on MCC or SID.
        boolean needStore = (filter.getMccList() != null && !filter.getMccList().isEmpty())
                || (filter.getSidList() != null && !filter.getSidList().isEmpty());
        if (needStore) {
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        }

        sql.append("WHERE 1=1 ");
        if (tenantId != null) {
            sql.append("AND s.tenant_id = :tenantId ");
        }
        if (filter.getStartDate() != null) {
            sql.append("AND ").append(S_DATE).append(useMonthly ? " >= :startMonthKey " : " >= :startDate ");
        }
        if (filter.getEndDate() != null) {
            sql.append("AND ").append(S_DATE).append(useMonthly ? " <= :endMonthKey " : " <= :endDate ");
        }

        // Multi-select Lists

        // Simplification: Assume basic fields for now.
        // We need to support the filters requested: MCC, Industry (proxy MCC), RM,
        // Partner.
        // Partner -> referral_partner in dim_merchant
        // RM -> sales_email in dim_merchant

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) {
            sql.append("AND m.referral_partner IN (:partners) ");
        }

        if (filter.getRmList() != null && !filter.getRmList().isEmpty()) {
            sql.append("AND m.sales_email IN (:rms) ");
        }

        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) {
            sql.append("AND m.name ILIKE :merchName ");
        }

        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty()) {
            sql.append("AND s.card_scheme IN (:schemes) ");
        }

        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty()) {
            sql.append("AND s.card_type IN (:cardTypes) ");
        }

        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty()) {
            sql.append("AND s.destination IN (:destinations) ");
        }

        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("AND st.mcc IN (:mccs) ");
        }

        // P-FIX: midList/sidList were exposed in the BusinessFilters drawer (used
        // on this page) but silently dropped here — picking a MID/SID never
        // narrowed the Volume & Revenue report. Wired through now.
        if (filter.getMidList() != null && !filter.getMidList().isEmpty()) {
            sql.append("AND m.mid IN (:mids) ");
        }

        if (filter.getSidList() != null && !filter.getSidList().isEmpty()) {
            sql.append("AND st.sid IN (:sids) ");
        }

        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty()) {
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        }

        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty()) {
            sql.append("AND s.channel IN (:channels) ");
        }

        sql.append("GROUP BY ").append(MONTH_LABEL).append(" ");
        sql.append("ORDER BY month_label DESC");

        Query query = entityManager.createNativeQuery(sql.toString());

        if (tenantId != null)
            query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) {
            if (useMonthly) query.setParameter("startMonthKey", startMonthKey);
            else            query.setParameter("startDate", filter.getStartDate());
        }
        if (filter.getEndDate() != null) {
            if (useMonthly) query.setParameter("endMonthKey", endMonthKey);
            else            query.setParameter("endDate", filter.getEndDate());
        }
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            query.setParameter("mids", filter.getMidList());
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            query.setParameter("sids", filter.getSidList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("month", row[0]);
            map.put("count", row[1]);
            map.put("volume", row[2]);
            map.put("msf", row[3]);
            map.put("opt_in_volume", row[4]);
            map.put("intl_volume", row[5]);
            map.put("active_merchants", row[6]);
            result.add(map);
        }

        return result;
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so the per-MID/SID
     * financial summary cannot show rows belonging to other tenants.
     */
    public List<Map<String, Object>> getMerchantFinancialSummary(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  m.name as merchant_name, ");
        sql.append("  st.sid as sid, ");
        sql.append("  SUM(s.total_txns) as total_txns, ");
        sql.append("  SUM(s.total_volume) as total_volume, ");
        sql.append("  SUM(s.total_msf) as total_msf, ");
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as opt_in_volume ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id "); // Need store for SID

        sql.append("WHERE 1=1 ");
        if (tenantId != null)
            sql.append("AND s.tenant_id = :tenantId ");

        if (filter.getStartDate() != null)
            sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null)
            sql.append("AND s.business_date <= :endDate ");

        if (filter.getOpenDateStart() != null)
            sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) >= :openStart ");
        if (filter.getOpenDateEnd() != null)
            sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) <= :openEnd ");

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");

        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            sql.append("AND s.card_scheme IN (:schemes) ");
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            sql.append("AND s.card_type IN (:cardTypes) ");

        // P1-6 FIX: previously these filter fields were silently dropped from the SQL.
        // Users could pick MCC / SID / MID / destination / channel / team-leader from
        // the BusinessFilters drawer and the result wouldn't change. Wired through now.
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            sql.append("AND s.destination IN (:destinations) ");
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            sql.append("AND s.channel IN (:channels) ");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            sql.append("AND st.mcc IN (:mccs) ");
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            sql.append("AND m.mid IN (:mids) ");
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            sql.append("AND st.sid IN (:sids) ");
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");

        // Group by MID and SID
        sql.append("GROUP BY m.mid, m.name, st.sid ");
        sql.append("ORDER BY m.mid, st.sid");

        Query query = entityManager.createNativeQuery(sql.toString());

        if (tenantId != null)
            query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null)
            query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null)
            query.setParameter("endDate", filter.getEndDate());
        if (filter.getOpenDateStart() != null)
            query.setParameter("openStart", filter.getOpenDateStart());
        if (filter.getOpenDateEnd() != null)
            query.setParameter("openEnd", filter.getOpenDateEnd());
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            query.setParameter("mids", filter.getMidList());
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            query.setParameter("sids", filter.getSidList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            query.setParameter("teamLeaders", filter.getTeamLeaderList());

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("merchantName", row[1]); // New column
            map.put("sid", row[2]);
            map.put("count", row[3]);
            map.put("volume", row[4]);
            map.put("msf", row[5]);
            map.put("opt_in_volume", row[6]);
            result.add(map);
        }

        return result;
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so drill-down
     * tables across MONTH/DAY/MERCHANT/STORE granularities never mix tenants.
     *
     * Month-grain routing: for groupBy=MONTH the complete calendar months of
     * the range are served from the pre-aggregate sum_monthly_insight (~30x
     * fewer rows than sum_daily_insight; additive SUMs so monthly = SUM(daily)
     * reconciles exactly), and only a partial head/tail month falls back to the
     * daily table. The legs produce disjoint month buckets, so their results
     * are concatenated and re-sorted — no cross-leg merging needed.
     */
    public List<Map<String, Object>> getPerformanceDashboardData(VolumeRevenueFilterDTO filter, String groupBy,
            String parentValue, String grandParentValue, Long tenantId) {
        requireTenant(tenantId);
        if ("MONTH".equals(groupBy) && filter.getStartDate() != null && filter.getEndDate() != null
                && !filter.getStartDate().isAfter(filter.getEndDate())) {
            java.time.LocalDate start = filter.getStartDate();
            java.time.LocalDate end = filter.getEndDate();
            java.time.LocalDate mStart = start.getDayOfMonth() == 1 ? start
                    : start.plusMonths(1).withDayOfMonth(1);
            java.time.LocalDate mEnd = end.getDayOfMonth() == end.lengthOfMonth() ? end
                    : end.withDayOfMonth(1).minusDays(1);
            if (!mStart.isAfter(mEnd)) {
                List<Map<String, Object>> result = new ArrayList<>(
                        getMonthPivotFromMonthly(filter, monthKey(mStart), monthKey(mEnd), tenantId));
                if (start.isBefore(mStart))
                    result.addAll(runDailyMonthPivot(filter, start, mStart.minusDays(1), tenantId));
                if (end.isAfter(mEnd))
                    result.addAll(runDailyMonthPivot(filter, mEnd.plusDays(1), end, tenantId));
                // Match the daily path's ORDER BY row_label DESC ('YYYY-MM' sorts chronologically)
                result.sort((a, b) -> String.valueOf(b.get("row_label"))
                        .compareTo(String.valueOf(a.get("row_label"))));
                return result;
            }
        }
        return getPerformanceDashboardDataDaily(filter, groupBy, parentValue, grandParentValue, tenantId);
    }

    /**
     * Daily-table pivot for a partial-month sub-range of the MONTH routing.
     * Swaps the filter's dates in place for the call and restores them after
     * (the DTO is request-scoped but shared across the legs of one request).
     */
    private List<Map<String, Object>> runDailyMonthPivot(VolumeRevenueFilterDTO filter,
            java.time.LocalDate start, java.time.LocalDate end, Long tenantId) {
        java.time.LocalDate origStart = filter.getStartDate();
        java.time.LocalDate origEnd = filter.getEndDate();
        try {
            filter.setStartDate(start);
            filter.setEndDate(end);
            return getPerformanceDashboardDataDaily(filter, "MONTH", null, null, tenantId);
        } finally {
            filter.setStartDate(origStart);
            filter.setEndDate(origEnd);
        }
    }

    private List<Map<String, Object>> getPerformanceDashboardDataDaily(VolumeRevenueFilterDTO filter, String groupBy,
            String parentValue, String grandParentValue, Long tenantId) {
        // groupBy: MONTH, DAY, MERCHANT, STORE
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");

        // Label Column
        if ("MONTH".equals(groupBy)) {
            sql.append(" TO_CHAR(s.business_date, 'YYYY-MM') as row_label, s.business_date as sort_key, ");
        } else if ("DAY".equals(groupBy)) {
            sql.append(" TO_CHAR(s.business_date, 'YYYY-MM-DD') as row_label, s.business_date as sort_key, ");
        } else if ("MERCHANT".equals(groupBy)) {
            sql.append(" m.mid as row_label, m.mid as sort_key, ");
        } else if ("STORE".equals(groupBy)) {
            sql.append(" st.sid as row_label, st.sid as sort_key, ");
        } else if ("TOTAL".equals(groupBy)) {
            // Single-row KPI grain: whole filtered range collapsed to one row.
            // Every select item must be an aggregate/constant (no GROUP BY below).
            sql.append(" CAST('TOTAL' AS text) as row_label, MIN(s.business_date) as sort_key, ");
        }

        // Pivoted Columns (Dom Debit&Prepaid, Dom Credit, Intl, Total).
        // [FIX] The three buckets are now an EXHAUSTIVE partition of every row:
        //   dom_debit  = DOMESTIC and card_type in (DEBIT, PREPAID)   — matches the
        //                sum_daily_finance rollup and the UI header "Debit & Prepaid".
        //                (Old version matched only DEBIT, so PREPAID rows counted in
        //                total_vol but in NO bucket -> the three % columns never
        //                summed to 100 and looked "wrong".)
        //   dom_credit = DOMESTIC and everything else (CREDIT + unknown/null card
        //                types) — catch-all so the partition stays exhaustive even
        //                for unmapped scheme codes.
        //   intl       = anything not DOMESTIC (incl. null destination).
        // Invariant: dom_debit + dom_credit + intl == total, per row and per column.
        final String DOM_DEBIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID')";
        final String DOM_CREDIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID')";
        final String INTL = "UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC'";

        // Dom Debit & Prepaid
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_txns ELSE 0 END) as dom_debit_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_volume ELSE 0 END) as dom_debit_vol, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_msf ELSE 0 END) as dom_debit_msf, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_debit_optin, ");

        // Dom Credit (+ catch-all for unknown card types)
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_txns ELSE 0 END) as dom_credit_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_volume ELSE 0 END) as dom_credit_vol, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_msf ELSE 0 END) as dom_credit_msf, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_credit_optin, ");

        // Intl (All Card Types)
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_txns ELSE 0 END) as int_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_volume ELSE 0 END) as int_vol, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_msf ELSE 0 END) as int_msf, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as int_optin, ");

        // Total
        sql.append(" SUM(s.total_volume) as total_vol, ");
        sql.append(" SUM(s.total_msf) as total_msf, ");

        // Extra Context Columns (Index 16)
        if ("MERCHANT".equals(groupBy)) {
            sql.append(" m.name as merchant_name ");
        } else {
            sql.append(" CAST(NULL as text) as merchant_name ");
        }

        // TOTAL-only KPI columns (indices 17-20), appended AFTER merchant_name so
        // the positional mapping of every existing groupBy mode is untouched.
        // Channel note: sum_daily_insight.channel stores DEVICE/PROFILE names
        // (N96, Aisino A90, SoftPOS, ECOM PROFILE, Pay By Link, ...), NOT
        // POS/ECOM literals. ECOM is a small explicit whitelist; POS is the
        // catch-all (everything except ECOM values and 'None'/blank), so new
        // terminal models in the feed default to POS instead of vanishing.
        if ("TOTAL".equals(groupBy)) {
            sql.append(", SUM(s.total_txns) as total_cnt ");
            sql.append(", COUNT(DISTINCT s.merchant_id) as active_merchants ");
            sql.append(", SUM(CASE WHEN UPPER(COALESCE(s.channel,'')) IN ('ECOM PROFILE','PAY BY LINK','PAY ON') THEN s.total_volume ELSE 0 END) as ecom_vol ");
            sql.append(", SUM(CASE WHEN UPPER(COALESCE(s.channel,'')) NOT IN ('ECOM PROFILE','PAY BY LINK','PAY ON','NONE','') THEN s.total_volume ELSE 0 END) as pos_vol ");
        }

        sql.append("FROM sum_daily_insight s ");

        // Dynamic Joins based on Group By or Filters
        boolean needMerchant = "MERCHANT".equals(groupBy) || "STORE".equals(groupBy) ||
                (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) ||
                (filter.getRmList() != null && !filter.getRmList().isEmpty()) ||
                (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) ||
                (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty());

        boolean needStore = "STORE".equals(groupBy) ||
                (grandParentValue != null && "STORE".equals(groupBy)) ||
                (filter.getMccList() != null && !filter.getMccList().isEmpty());

        if (needMerchant)
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");

        if (tenantId != null)
            sql.append("AND s.tenant_id = :tenantId ");

        // Base Filters
        if (filter.getStartDate() != null)
            sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null)
            sql.append("AND s.business_date <= :endDate ");
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            sql.append("AND s.card_scheme IN (:schemes) ");
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            sql.append("AND s.card_type IN (:cardTypes) ");
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            sql.append("AND s.destination IN (:destinations) ");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            sql.append("AND st.mcc IN (:mccs) ");
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            sql.append("AND s.channel IN (:channels) ");

        // Drill-down Logic (Filters from Parent Row)
        if ("DAY".equals(groupBy) && parentValue != null) {
            // Parent is Month (YYYY-MM), fitler strictly for that month
            sql.append("AND TO_CHAR(s.business_date, 'YYYY-MM') = :parentMonth ");
        } else if ("MERCHANT".equals(groupBy) && parentValue != null) {
            // Parent is Day (YYYY-MM-DD)
            sql.append("AND TO_CHAR(s.business_date, 'YYYY-MM-DD') = :parentDay ");
        } else if ("STORE".equals(groupBy) && parentValue != null && grandParentValue != null) {
            // Parent is Merchant MID, GrandParent is Day
            sql.append("AND m.mid = :parentMid ");
            sql.append("AND TO_CHAR(s.business_date, 'YYYY-MM-DD') = :grandParentDay ");
        }

        // Fix Group By for Sort Key
        // Actually, let's simplify Sort Key logic
        // For Month: Sort by Month Label DESC
        // For Day: Sort by Date Label ASC
        // For Merch: Sort by MID ASC

        // Re-writing group by to be clean
        String groupByClause = "";
        String orderByClause = "";

        if ("MONTH".equals(groupBy)) {
            groupByClause = "GROUP BY TO_CHAR(s.business_date, 'YYYY-MM') ";
            orderByClause = "ORDER BY row_label DESC"; // Descending months
            // Remove sort_key from select list to avoid group by issues
        } else if ("DAY".equals(groupBy)) {
            groupByClause = "GROUP BY TO_CHAR(s.business_date, 'YYYY-MM-DD') ";
            orderByClause = "ORDER BY row_label ASC";
        } else if ("MERCHANT".equals(groupBy)) {
            groupByClause = "GROUP BY m.mid, m.name "; // Include m.name in group by
            orderByClause = "ORDER BY row_label ASC";
        } else if ("STORE".equals(groupBy)) {
            groupByClause = "GROUP BY st.sid ";
            orderByClause = "ORDER BY st.sid ASC";
        }

        // Fix sort_key for aggregated groupBy modes (MONTH/DAY) where
        // s.business_date is not in GROUP BY and must be wrapped in an aggregate.
        // For MERCHANT/STORE the sort_key is already m.mid / st.sid which IS in GROUP BY.
        String sqlStr = sql.toString();
        if ("MONTH".equals(groupBy) || "DAY".equals(groupBy)) {
            sqlStr = sqlStr.replace("s.business_date as sort_key", "MIN(s.business_date) as sort_key");
        }
        // Use sqlStr from here on
        sql = new StringBuilder(sqlStr);

        sql.append(groupByClause);
        sql.append(orderByClause);

        Query query = entityManager.createNativeQuery(sql.toString());

        if (tenantId != null)
            query.setParameter("tenantId", tenantId);

        if (filter.getStartDate() != null)
            query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null)
            query.setParameter("endDate", filter.getEndDate());
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());

        if ("DAY".equals(groupBy) && parentValue != null)
            query.setParameter("parentMonth", parentValue);
        else if ("MERCHANT".equals(groupBy) && parentValue != null)
            query.setParameter("parentDay", parentValue);
        else if ("STORE".equals(groupBy) && parentValue != null && grandParentValue != null) {
            query.setParameter("parentMid", parentValue);
            query.setParameter("grandParentDay", grandParentValue);
        }

        List<Object[]> rows = query.getResultList();

        // Debug: log column count from first row to catch index mismatches early
        if (!rows.isEmpty()) {
            System.out.println("[PerformanceDashboard] groupBy=" + groupBy + ", columns=" + rows.get(0).length);
        }

        return mapPivotRows(rows, groupBy);
    }

    /** Maps pivot result rows to the API shape — shared by the daily and monthly legs. */
    private static List<Map<String, Object>> mapPivotRows(List<Object[]> rows, String groupBy) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            int col = 0;
            map.put("row_label", row[col++]);       // 0: row_label
            map.put("sort_date", row[col] != null ? row[col].toString() : "");
            col++;                                       // 1: sort_key

            // Dom Debit (indices 2-5)
            map.put("dom_debit_cnt", row[col++]);
            map.put("dom_debit_vol", row[col++]);
            map.put("dom_debit_msf", row[col++]);
            map.put("dom_debit_optin", row[col++]);

            // Dom Credit (indices 6-9)
            map.put("dom_credit_cnt", row[col++]);
            map.put("dom_credit_vol", row[col++]);
            map.put("dom_credit_msf", row[col++]);
            map.put("dom_credit_optin", row[col++]);

            // Intl (indices 10-13)
            map.put("int_cnt", row[col++]);
            map.put("int_vol", row[col++]);
            map.put("int_msf", row[col++]);
            map.put("int_optin", row[col++]);

            // Totals (indices 14-15)
            map.put("total_vol", row[col++]);
            map.put("total_msf", col < row.length ? row[col++] : 0);

            // Extra context (index 16)
            map.put("merchant_name", col < row.length ? row[col++] : null);

            // TOTAL-only KPI columns (indices 17-20)
            if ("TOTAL".equals(groupBy)) {
                map.put("total_cnt", col < row.length ? row[col++] : 0);
                map.put("active_merchants", col < row.length ? row[col++] : 0);
                map.put("ecom_vol", col < row.length ? row[col++] : 0);
                map.put("pos_vol", col < row.length ? row[col++] : 0);
            }

            result.add(map);
        }

        return result;
    }

    /**
     * groupBy=MONTH pivot served from the month-grain pre-aggregate
     * sum_monthly_insight. Identical bucket expressions and result shape to the
     * daily pivot; the range is whole months expressed as YYYYMM keys.
     */
    private List<Map<String, Object>> getMonthPivotFromMonthly(VolumeRevenueFilterDTO filter,
            Integer startMonthKey, Integer endMonthKey, Long tenantId) {
        // Same exhaustive bucket partition as the daily pivot (see comment there).
        final String DOM_DEBIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID')";
        final String DOM_CREDIT = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID')";
        final String INTL = "UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC'";

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(" TO_CHAR(TO_DATE(CAST(s.month_key AS text), 'YYYYMM'), 'YYYY-MM') as row_label, ");
        sql.append(" TO_DATE(CAST(s.month_key AS text), 'YYYYMM') as sort_key, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_txns ELSE 0 END) as dom_debit_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_volume ELSE 0 END) as dom_debit_vol, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" THEN s.total_msf ELSE 0 END) as dom_debit_msf, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_DEBIT).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_debit_optin, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_txns ELSE 0 END) as dom_credit_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_volume ELSE 0 END) as dom_credit_vol, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" THEN s.total_msf ELSE 0 END) as dom_credit_msf, ");
        sql.append(" SUM(CASE WHEN ").append(DOM_CREDIT).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_credit_optin, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_txns ELSE 0 END) as int_cnt, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_volume ELSE 0 END) as int_vol, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" THEN s.total_msf ELSE 0 END) as int_msf, ");
        sql.append(" SUM(CASE WHEN ").append(INTL).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as int_optin, ");
        sql.append(" SUM(s.total_volume) as total_vol, ");
        sql.append(" SUM(s.total_msf) as total_msf, ");
        sql.append(" CAST(NULL as text) as merchant_name ");
        sql.append("FROM sum_monthly_insight s ");

        boolean needMerchant = (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) ||
                (filter.getRmList() != null && !filter.getRmList().isEmpty()) ||
                (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) ||
                (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty());
        boolean needStore = filter.getMccList() != null && !filter.getMccList().isEmpty();

        if (needMerchant)
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE s.month_key BETWEEN :startMonthKey AND :endMonthKey ");
        if (tenantId != null)
            sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            sql.append("AND s.card_scheme IN (:schemes) ");
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            sql.append("AND s.card_type IN (:cardTypes) ");
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            sql.append("AND s.destination IN (:destinations) ");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            sql.append("AND st.mcc IN (:mccs) ");
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            sql.append("AND s.channel IN (:channels) ");

        sql.append("GROUP BY s.month_key ");
        sql.append("ORDER BY s.month_key DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("startMonthKey", startMonthKey);
        query.setParameter("endMonthKey", endMonthKey);
        if (tenantId != null)
            query.setParameter("tenantId", tenantId);
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());

        List<Object[]> rows = query.getResultList();
        return mapPivotRows(rows, "MONTH");
    }

    /**
     * Tenant-scoped filter options. When a tenantId is provided, dim_store and
     * dim_merchant lookups (sids, mids, mccs) filter to that tenant. Cross-tenant
     * lookups (referral_partner, sales_email, schemes) remain unscoped because
     * those values are typically shared.
     *
     * Adds sids and mids — these were previously missing from the response
     * causing the SID and MID dropdowns in BusinessFilters and DailyMerchantDashboard
     * to render empty.
     *
     * Cached: the destination/channel/scheme/card-type lookups are unbounded
     * DISTINCT scans over sum_daily_insight (a tenant's full history) and run
     * on every report-page open. The value set changes only on ingest; the
     * batch jobs evict this cache on completion.
     */
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
            key = "'vrFilters:' + (#tenantId != null ? #tenantId : 'all')",
            unless = "#result.isEmpty()")
    public Map<String, List<String>> getFilterOptions(Long tenantId) {
        requireTenant(tenantId);
        Map<String, List<String>> options = new HashMap<>();

        try {
            // Partners
            Query qPartner = entityManager.createNativeQuery(
                    "SELECT DISTINCT referral_partner FROM dim_merchant WHERE referral_partner IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qPartner.setParameter("tid", tenantId);
            options.put("partners", qPartner.getResultList());

            // RMs
            Query qRm = entityManager.createNativeQuery(
                    "SELECT DISTINCT sales_email FROM dim_merchant WHERE sales_email IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qRm.setParameter("tid", tenantId);
            options.put("rms", qRm.getResultList());

            // MCCs
            Query qMcc = entityManager.createNativeQuery(
                    "SELECT DISTINCT mcc FROM dim_store WHERE mcc IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qMcc.setParameter("tid", tenantId);
            options.put("mccs", qMcc.getResultList());

            // MCC category labels + Industry (sector) options — sourced from
            // ref_mcc_category (the bank's MCC sector sheet, V2026_07_10_01).
            // mccCategories is index-aligned with mccs (both DISTINCT + ORDER BY
            // mcc over the same dim_store predicate; ref_mcc_category.mcc is a PK
            // so each code maps to exactly one category). We overwrite "mccs"
            // from the same result set to make the alignment structural.
            // Guarded in its own try so an environment missing the reference
            // table (prod before the migration lands) degrades to plain-code
            // MCC dropdowns instead of throwing and losing every later option.
            try {
                Query qMccCat = entityManager.createNativeQuery(
                        "SELECT DISTINCT st.mcc, COALESCE(rc.category, 'MIS') " +
                        "FROM dim_store st LEFT JOIN ref_mcc_category rc ON rc.mcc = st.mcc " +
                        "WHERE st.mcc IS NOT NULL " +
                        (tenantId != null ? "AND st.tenant_id = :tid " : "") + "ORDER BY 1");
                if (tenantId != null) qMccCat.setParameter("tid", tenantId);
                @SuppressWarnings("unchecked")
                List<Object[]> catRows = qMccCat.getResultList();
                List<String> codes = new ArrayList<>();
                List<String> cats = new ArrayList<>();
                java.util.TreeSet<String> industrySet = new java.util.TreeSet<>();
                for (Object[] r : catRows) {
                    String code = r[0] != null ? r[0].toString() : null;
                    if (code == null) continue;
                    String cat = r[1] != null ? r[1].toString() : "MIS";
                    codes.add(code);
                    cats.add(cat);
                    if (!"MIS".equals(cat)) industrySet.add(cat);
                }
                options.put("mccs", codes);
                options.put("mccCategories", cats);
                options.put("industries", new ArrayList<>(industrySet));
            } catch (Exception refEx) {
                // ref_mcc_category absent — keep the plain mccs list from above.
            }

            // SIDs (NEW — unblocks the SID dropdown in BusinessFilters / DailyMerchantDashboard)
            // Capped at 5000 — if a tenant has more stores than that, the dropdown UX
            // is broken anyway and a search-as-you-type endpoint would be the right fix.
            Query qSids = entityManager.createNativeQuery(
                    "SELECT DISTINCT sid FROM dim_store WHERE sid IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1 LIMIT 5000");
            if (tenantId != null) qSids.setParameter("tid", tenantId);
            options.put("sids", qSids.getResultList());

            // MIDs (NEW — same rationale as sids)
            Query qMids = entityManager.createNativeQuery(
                    "SELECT DISTINCT mid FROM dim_merchant WHERE mid IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1 LIMIT 5000");
            if (tenantId != null) qMids.setParameter("tid", tenantId);
            options.put("mids", qMids.getResultList());

            // Team Leaders
            Query qTeamLeads = entityManager.createNativeQuery(
                    "SELECT DISTINCT team_lead_name FROM sales_team_mapping WHERE team_lead_name IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qTeamLeads.setParameter("tid", tenantId);
            options.put("teamLeaders", qTeamLeads.getResultList());

            // Destinations. Falls back to sum_daily_full when sum_daily_insight
            // has no rows for the tenant (e.g. a DB where only the full rollup
            // was rebuilt) — the Executive Daily Merchant page filters on
            // sum_daily_full, so its dropdowns must not go empty with it.
            options.put("destinations", distinctWithFallback("destination", tenantId));

            // Channels
            Query qChan = entityManager.createNativeQuery(
                    "SELECT DISTINCT channel FROM sum_daily_insight WHERE channel IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY channel");
            if (tenantId != null) qChan.setParameter("tid", tenantId);
            options.put("channels", qChan.getResultList());

            // Terminal Types
            Query qTermType = entityManager.createNativeQuery(
                    "SELECT DISTINCT type FROM dim_terminal WHERE type IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY type");
            if (tenantId != null) qTermType.setParameter("tid", tenantId);
            options.put("terminalTypes", qTermType.getResultList());

            // Schemes (same sum_daily_full fallback as destinations)
            options.put("schemes", distinctWithFallback("card_scheme", tenantId));

            // Card Types (same sum_daily_full fallback as destinations)
            options.put("cardTypes", distinctWithFallback("card_type", tenantId));

            // MCC fallback: the lists above come from dim_store; if the dims are
            // empty but daily rollups exist, offer the codes present in the data.
            if (options.getOrDefault("mccs", List.of()).isEmpty()) {
                List<String> mccCodes = distinctFromTable("sum_daily_full", "mcc", tenantId);
                if (!mccCodes.isEmpty()) {
                    options.put("mccs", mccCodes);
                    options.remove("mccCategories"); // no longer index-aligned
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return options;
    }

    /**
     * DISTINCT values of a dimension column, from sum_daily_insight first and
     * sum_daily_full as fallback when insight is empty for the tenant. Column
     * names are compile-time constants at every call site — never user input.
     */
    private List<String> distinctWithFallback(String column, Long tenantId) {
        List<String> vals = distinctFromTable("sum_daily_insight", column, tenantId);
        if (vals.isEmpty()) vals = distinctFromTable("sum_daily_full", column, tenantId);
        return vals;
    }

    @SuppressWarnings("unchecked")
    private List<String> distinctFromTable(String table, String column, Long tenantId) {
        Query q = entityManager.createNativeQuery(
                "SELECT DISTINCT " + column + " FROM " + table +
                " WHERE " + column + " IS NOT NULL " +
                (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
        if (tenantId != null) q.setParameter("tid", tenantId);
        List<Object> rows = q.getResultList();
        List<String> out = new ArrayList<>(rows.size());
        for (Object r : rows) if (r != null) out.add(r.toString());
        return out;
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so debit/prepaid
     * metrics never include rows from other tenants.
     *
     * The card_type filter is intentionally permissive: real-world card_type
     * values vary wildly across processors (single letter 'D', abbreviations
     * like 'DBT', numeric codes '2'/'3'/'4', full words 'DEBIT', etc.) so we
     * accept any of the common spellings AND join through ref_card_scheme as a
     * fallback.
     *
     * Note: the frontend banner historically claimed this filter required
     * `destination = 'DOMESTIC'` but the SQL never actually enforced that
     * — the banner was wrong. We honor the user's destinationList filter from
     * the drawer instead, which is the correct behaviour.
     */
    public List<Map<String, Object>> getDebitPrepaidMetrics(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  st2.sid as sid, ");
        sql.append("  m.name as merchant_name, ");
        sql.append("  SUM(s.total_txns) as count, ");
        sql.append("  SUM(s.total_volume) as volume, ");
        sql.append("  SUM(s.total_msf) as msf, ");
        sql.append("  SUM(CASE WHEN ").append(debitBucketMatcherSql()).append(" THEN s.total_volume ELSE 0 END) as debit_volume, ");
        sql.append("  SUM(CASE WHEN ").append(prepaidBucketMatcherSql()).append(" THEN s.total_volume ELSE 0 END) as prepaid_volume ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN dim_store st2 ON s.store_id = st2.store_id AND st2.tenant_id = s.tenant_id ");

        // P1-7 FIX: when the user explicitly picks card types from the drawer,
        // honor THAT instead of forcing the hardcoded DEBIT/PREPAID matcher.
        // The hardcoded matcher is only the *default* behaviour for this report.
        boolean userPickedCardTypes = filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty();
        sql.append("WHERE 1=1 ");
        if (userPickedCardTypes) {
            sql.append("AND s.card_type IN (:cardTypes) ");
        } else {
            // Permissive card_type matching. Cover the variants we've actually seen in
            // production data plus the canonical forms.
            // - Full words:    DEBIT, PREPAID, DEBIT PREPAID, CREDIT PREPAID
            // - Abbreviated:   DEB, DBT, PREP, PPD
            // - Single-letter: D (debit), P (prepaid)  -- common in tokenised feeds
            // - Numeric codes: 2, 3, 4 (per ref_card_scheme convention)
            // - Suffixed:      'DEBIT CARD', 'PREPAID CARD', etc — caught by LIKE
            // Plus the original ref_card_scheme join as a final fallback.
            sql.append("AND ( ");
            sql.append("      UPPER(TRIM(s.card_type)) IN ('DEBIT','PREPAID','DEBIT PREPAID','CREDIT PREPAID', ");
            sql.append("                                   'DEB','DBT','PREP','PPD','D','P','2','3','4') ");
            sql.append("   OR UPPER(TRIM(s.card_type)) LIKE 'DEBIT%' ");
            sql.append("   OR UPPER(TRIM(s.card_type)) LIKE 'PREPAID%' ");
            sql.append("   OR s.card_type IN (SELECT code FROM ref_card_scheme WHERE card_type IN (2, 3, 4)) ");
            sql.append("   OR s.card_scheme IN (SELECT code FROM ref_card_scheme WHERE card_type IN (2, 3, 4)) ");
            sql.append("    ) ");
        }

        if (tenantId != null)
            sql.append("AND s.tenant_id = :tenantId ");

        if (filter.getStartDate() != null)
            sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null)
            sql.append("AND s.business_date <= :endDate ");
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        // Note: Destination and Card Type filters from UI might conflict if user
        // selects CREDIT or INTERNATIONAL
        // But the report is specifically for Dom Debit/Prepaid. Use filters if they
        // don't contradict?
        // Let's apply other filters (Industry, MCC etc) if needed, but user said "same
        // filters".
        // Applying generic filters:
        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("AND st2.mcc IN (:mccs) ");
        }
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty()) {
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        }
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty()) {
            sql.append("AND s.channel IN (:channels) ");
        }
        // P1-7 FIX: scheme / destination / mid / sid filters were silently dropped.
        // Wired through now so the drawer picks actually narrow the result.
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty()) {
            sql.append("AND s.card_scheme IN (:schemes) ");
        }
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty()) {
            sql.append("AND s.destination IN (:destinations) ");
        }
        if (filter.getMidList() != null && !filter.getMidList().isEmpty()) {
            sql.append("AND m.mid IN (:mids) ");
        }
        if (filter.getSidList() != null && !filter.getSidList().isEmpty()) {
            sql.append("AND st2.sid IN (:sids) ");
        }

        sql.append("GROUP BY m.mid, st2.sid, m.name ");
        sql.append("ORDER BY m.mid ASC, st2.sid ASC");

        // Diagnostic logging: when the result is empty we want to know whether the
        // problem is the card_type filter (data exists in this date range but no
        // matching card_types) or a different filter. One log line, only on empty.
        Query query = entityManager.createNativeQuery(sql.toString());

        if (tenantId != null)
            query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null)
            query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null)
            query.setParameter("endDate", filter.getEndDate());
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty())
            query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());
        // P1-7 FIX: bind newly-supported filter parameters
        if (userPickedCardTypes)
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            query.setParameter("mids", filter.getMidList());
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            query.setParameter("sids", filter.getSidList());

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("sid", row[1]);
            map.put("merchantName", row[2]);
            long rowCount = lng(row[3]);
            java.math.BigDecimal rowVolume = bd(row[4]);
            java.math.BigDecimal rowMsf = bd(row[5]);
            java.math.BigDecimal rowDebitVol = bd(row[6]);
            java.math.BigDecimal rowPrepaidVol = bd(row[7]);
            map.put("count", rowCount);
            map.put("volume", rowVolume);
            map.put("msf", rowMsf);
            map.put("msfRateBps", bpsOf(rowMsf, rowVolume));
            map.put("avgTicket", ratioOf(rowVolume, java.math.BigDecimal.valueOf(rowCount)));
            map.put("debitVolume", rowDebitVol);
            map.put("prepaidVolume", rowPrepaidVol);
            result.add(map);
        }

        // Diagnostic: if empty, find out what card_types DO exist in the date
        // range so we can extend the matcher above. One-time log per empty call.
        if (result.isEmpty()) {
            try {
                StringBuilder diag = new StringBuilder();
                diag.append("SELECT DISTINCT s.card_type, s.card_scheme, COUNT(*) cnt ");
                diag.append("FROM sum_daily_insight s ");
                diag.append("WHERE 1=1 ");
                if (tenantId != null) diag.append("AND s.tenant_id = :tid ");
                if (filter.getStartDate() != null) diag.append("AND s.business_date >= :start ");
                if (filter.getEndDate() != null)   diag.append("AND s.business_date <= :end ");
                diag.append("GROUP BY s.card_type, s.card_scheme ORDER BY cnt DESC LIMIT 20");
                Query dq = entityManager.createNativeQuery(diag.toString());
                if (tenantId != null) dq.setParameter("tid", tenantId);
                if (filter.getStartDate() != null) dq.setParameter("start", filter.getStartDate());
                if (filter.getEndDate() != null)   dq.setParameter("end",   filter.getEndDate());
                List<Object[]> diagRows = dq.getResultList();
                System.out.println("[DebitPrepaid] EMPTY result. card_type/card_scheme distribution in range: " + diagRows.size() + " distinct combos");
                for (Object[] r : diagRows) {
                    System.out.println("  card_type='" + r[0] + "' scheme='" + r[1] + "' count=" + r[2]);
                }
            } catch (Exception e) {
                System.out.println("[DebitPrepaid] diagnostic query failed: " + e.getMessage());
            }
        }

        return result;
    }

    // ── Debit/Prepaid segment summary (tiles + charts) ───────────────────────
    // Additive & isolated: reuses the exact card-type matcher semantics from
    // getDebitPrepaidMetrics (including the P1-7 "user-picked card types
    // override the hardcoded matcher" rule) so the tiles and table always
    // agree. New method, touches nothing else.

    /** Debit-only bucket matcher (card_type=2 family, explicitly excluding prepaid text). */
    private static String debitBucketMatcherSql() {
        return "( (UPPER(TRIM(s.card_type)) IN ('DEBIT','DEB','DBT','D','2') " +
               "    OR UPPER(TRIM(s.card_type)) LIKE 'DEBIT%' " +
               "    OR s.card_type IN (SELECT code FROM ref_card_scheme WHERE card_type = 2) " +
               "    OR s.card_scheme IN (SELECT code FROM ref_card_scheme WHERE card_type = 2)) " +
               "  AND UPPER(TRIM(s.card_type)) NOT LIKE '%PREPAID%' ) ";
    }

    /** Prepaid-only bucket matcher (card_type 3/4 family). */
    private static String prepaidBucketMatcherSql() {
        return "( UPPER(TRIM(s.card_type)) IN ('PREPAID','PREP','PPD','P','3','4','DEBIT PREPAID','CREDIT PREPAID') " +
               "   OR UPPER(TRIM(s.card_type)) LIKE 'PREPAID%' " +
               "   OR s.card_type IN (SELECT code FROM ref_card_scheme WHERE card_type IN (3,4)) " +
               "   OR s.card_scheme IN (SELECT code FROM ref_card_scheme WHERE card_type IN (3,4)) ) ";
    }

    /** Debit OR Prepaid — the default segment matcher (mirrors getDebitPrepaidMetrics). */
    private static String debitPrepaidMatcherSql() {
        return "( " + debitBucketMatcherSql() + " OR " + prepaidBucketMatcherSql() + " ) ";
    }

    public Map<String, Object> getDebitPrepaidSummary(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        boolean userPickedCardTypes = listNonEmpty(filter.getCardTypeList());
        String segmentLabel = userPickedCardTypes ? "CUSTOM" : "DEBIT_PREPAID";
        String cardMatcher = userPickedCardTypes ? "s.card_type IN (:cardTypes) " : debitPrepaidMatcherSql();

        // SETTLEMENT-BASIS source: sum_daily_full carries settlement volume
        // (total_volume = store_base_currency_amount at build time), real fees
        // (interchange/scheme/ecom/net), AND the full dimensional grain
        // (mid/sid/mcc/channel/destination/scheme/card_type/is_opt_in). Channel
        // is ALREADY normalized to POS/ECOM in this table, so no device-name
        // remapping is needed here. dim_store is only joined when a SID filter is
        // active (sum_daily_full has store_id + mcc but not sid).
        boolean needSid = listNonEmpty(filter.getSidList());
        final String fromJoins = "FROM sum_daily_full s " +
                "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id " +
                (needSid ? "LEFT JOIN dim_store st2 ON s.store_id = st2.store_id AND st2.tenant_id = s.tenant_id " : "");

        StringBuilder commonB = new StringBuilder();
        if (tenantId != null) commonB.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) commonB.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) commonB.append("AND s.business_date <= :endDate ");
        if (listNonEmpty(filter.getPartnerList())) commonB.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList())) commonB.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            commonB.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList())) commonB.append("AND s.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getTeamLeaderList())) commonB.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getChannelList())) commonB.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList())) commonB.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getDestinationList())) commonB.append("AND s.destination IN (:destinations) ");
        if (listNonEmpty(filter.getMidList())) commonB.append("AND m.mid IN (:mids) ");
        if (needSid) commonB.append("AND st2.sid IN (:sids) ");
        final String commonSql = commonB.toString();

        // ── Book totals: ALL card types, same other filters. Denominator for
        // every "% of book" figure — always ignores the card-type matcher so
        // the share is meaningful even if the user picked custom card types.
        java.math.BigDecimal bookVol; long bookCnt; java.math.BigDecimal bookMsf;
        java.math.BigDecimal segVol; long segCnt; java.math.BigDecimal segMsf;
        Object[] bucketRow;

        Query bookQ = entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(s.total_volume),0), COALESCE(SUM(s.total_txns),0), COALESCE(SUM(s.total_msf),0) " +
                fromJoins + "WHERE 1=1 " + commonSql);
        bindDebitPrepaidCommonParams(bookQ, filter, tenantId);
        Object[] bookRow = (Object[]) bookQ.getSingleResult();
        bookVol = bd(bookRow[0]); bookCnt = lng(bookRow[1]); bookMsf = bd(bookRow[2]);

        Query segQ = entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(s.total_volume),0), COALESCE(SUM(s.total_txns),0), COALESCE(SUM(s.total_msf),0) " +
                fromJoins + "WHERE " + cardMatcher + commonSql);
        bindDebitPrepaidCommonParams(segQ, filter, tenantId);
        if (userPickedCardTypes) segQ.setParameter("cardTypes", filter.getCardTypeList());
        Object[] segRow = (Object[]) segQ.getSingleResult();
        segVol = bd(segRow[0]); segCnt = lng(segRow[1]); segMsf = bd(segRow[2]);

        Query bucketQ = entityManager.createNativeQuery(
                "SELECT " +
                "  SUM(CASE WHEN " + debitBucketMatcherSql() + " THEN s.total_volume ELSE 0 END), " +
                "  SUM(CASE WHEN " + debitBucketMatcherSql() + " THEN s.total_txns ELSE 0 END), " +
                "  SUM(CASE WHEN " + prepaidBucketMatcherSql() + " THEN s.total_volume ELSE 0 END), " +
                "  SUM(CASE WHEN " + prepaidBucketMatcherSql() + " THEN s.total_txns ELSE 0 END) " +
                fromJoins + "WHERE " + cardMatcher + commonSql);
        bindDebitPrepaidCommonParams(bucketQ, filter, tenantId);
        if (userPickedCardTypes) bucketQ.setParameter("cardTypes", filter.getCardTypeList());
        bucketRow = (Object[]) bucketQ.getSingleResult();

        List<Object[]> destRows   = debitPrepaidGroupedRows("s.destination", fromJoins, cardMatcher, commonSql, filter, tenantId, userPickedCardTypes, null);
        // Channel is already POS/ECOM in sum_daily_full — group on it directly.
        List<Object[]> chanRows   = debitPrepaidGroupedRows("s.channel", fromJoins, cardMatcher, commonSql, filter, tenantId, userPickedCardTypes, null);
        List<Object[]> schemeRows = debitPrepaidGroupedRows("s.card_scheme", fromJoins, cardMatcher, commonSql, filter, tenantId, userPickedCardTypes, "ORDER BY 2 DESC LIMIT 5");
        List<Object[]> monthRows  = debitPrepaidGroupedRows("TO_CHAR(s.business_date, 'YYYY-MM')", fromJoins, cardMatcher, commonSql, filter, tenantId, userPickedCardTypes, "ORDER BY 1 ASC");

        // ── Assemble response ──
        Map<String, Object> response = new HashMap<>();
        response.put("segmentLabel", segmentLabel);

        Map<String, Object> segment = new HashMap<>();
        segment.put("volume", segVol);
        segment.put("count", segCnt);
        segment.put("msf", segMsf);
        segment.put("msfRateBps", bpsOf(segMsf, segVol));
        segment.put("avgTicket", ratioOf(segVol, java.math.BigDecimal.valueOf(segCnt)));
        segment.put("volumeSharePct", pctOf(segVol, bookVol));
        segment.put("countSharePct", pctOf(java.math.BigDecimal.valueOf(segCnt), java.math.BigDecimal.valueOf(bookCnt)));
        response.put("segment", segment);

        Map<String, Object> book = new HashMap<>();
        book.put("volume", bookVol);
        book.put("count", bookCnt);
        book.put("msf", bookMsf);
        book.put("msfRateBps", bpsOf(bookMsf, bookVol));
        book.put("avgTicket", ratioOf(bookVol, java.math.BigDecimal.valueOf(bookCnt)));
        response.put("book", book);

        java.math.BigDecimal debitVol = bd(bucketRow[0]);
        long debitCnt = lng(bucketRow[1]);
        java.math.BigDecimal prepaidVol = bd(bucketRow[2]);
        long prepaidCnt = lng(bucketRow[3]);
        List<Map<String, Object>> byBucket = new ArrayList<>();
        byBucket.add(bucketMap("DEBIT", debitVol, debitCnt, segVol));
        byBucket.add(bucketMap("PREPAID", prepaidVol, prepaidCnt, segVol));
        response.put("byBucket", byBucket);

        response.put("byDestination", groupedRowsToMaps(destRows, segVol));
        response.put("byChannel", groupedRowsToMaps(chanRows, segVol));
        response.put("byScheme", groupedRowsToMaps(schemeRows, segVol));
        response.put("byMonth", monthRowsToMaps(monthRows));

        return response;
    }

    private List<Object[]> debitPrepaidGroupedRows(String groupExpr, String fromJoins, String cardMatcher,
            String commonSql, VolumeRevenueFilterDTO filter, Long tenantId, boolean userPickedCardTypes,
            String extraOrderLimit) {
        String sql = "SELECT " + groupExpr + " AS grp, COALESCE(SUM(s.total_volume),0), COALESCE(SUM(s.total_txns),0) " +
                fromJoins + "WHERE " + cardMatcher + commonSql +
                "AND " + groupExpr + " IS NOT NULL " +
                "GROUP BY 1 " + (extraOrderLimit != null ? extraOrderLimit : "ORDER BY 2 DESC");
        Query q = entityManager.createNativeQuery(sql);
        bindDebitPrepaidCommonParams(q, filter, tenantId);
        if (userPickedCardTypes) q.setParameter("cardTypes", filter.getCardTypeList());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows;
    }

    private void bindDebitPrepaidCommonParams(Query q, VolumeRevenueFilterDTO filter, Long tenantId) {
        if (tenantId != null) q.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) q.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) q.setParameter("endDate", filter.getEndDate());
        if (listNonEmpty(filter.getPartnerList())) q.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList())) q.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            q.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList())) q.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getChannelList())) q.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList())) q.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getDestinationList())) q.setParameter("destinations", filter.getDestinationList());
        if (listNonEmpty(filter.getMidList())) q.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getSidList())) q.setParameter("sids", filter.getSidList());
    }

    private Map<String, Object> bucketMap(String bucket, java.math.BigDecimal volume, long count, java.math.BigDecimal segVol) {
        Map<String, Object> m = new HashMap<>();
        m.put("bucket", bucket);
        m.put("volume", volume);
        m.put("count", count);
        m.put("sharePct", pctOf(volume, segVol));
        return m;
    }

    private List<Map<String, Object>> groupedRowsToMaps(List<Object[]> rows, java.math.BigDecimal segVol) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            java.math.BigDecimal vol = bd(r[1]);
            m.put("key", r[0]);
            m.put("volume", vol);
            m.put("count", lng(r[2]));
            m.put("sharePct", pctOf(vol, segVol));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> monthRowsToMaps(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("month", r[0]);
            m.put("volume", bd(r[1]));
            m.put("count", lng(r[2]));
            out.add(m);
        }
        return out;
    }

    /** basis points = numerator/denominator * 10000, 1 dp. Null-safe → 0 when denom 0. */
    private static java.math.BigDecimal bpsOf(java.math.BigDecimal num, java.math.BigDecimal den) {
        if (den == null || den.signum() == 0) return java.math.BigDecimal.ZERO;
        return num.multiply(java.math.BigDecimal.valueOf(10000)).divide(den, 1, java.math.RoundingMode.HALF_UP);
    }

    /** percentage = numerator/denominator * 100, 1 dp. */
    private static java.math.BigDecimal pctOf(java.math.BigDecimal num, java.math.BigDecimal den) {
        if (den == null || den.signum() == 0) return java.math.BigDecimal.ZERO;
        return num.multiply(java.math.BigDecimal.valueOf(100)).divide(den, 1, java.math.RoundingMode.HALF_UP);
    }

    /** simple ratio, 2 dp. */
    private static java.math.BigDecimal ratioOf(java.math.BigDecimal num, java.math.BigDecimal den) {
        if (den == null || den.signum() == 0) return java.math.BigDecimal.ZERO;
        return num.divide(den, 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Coverage check for the attrition report's three auto-computed
     * comparison windows (MoM prior month, YoY prior-period, YoY YTD-prior),
     * so the frontend can distinguish "nothing changed" from "the comparison
     * window itself has no data" — the same class of issue fixed for the
     * Retention report. A tenant with less than a year of history will have
     * an empty YoY window well before it runs out of MoM history, so the two
     * are tracked and reported independently rather than collapsed into one
     * flag.
     *
     * Applies the exact same dimension filters as getAttritionReport().
     * Mirrors its window math — must stay in sync with it.
     */
    public Map<String, Object> getAttritionReportMeta(VolumeRevenueFilterDTO filter, Long tenantId) {
        return getAttritionReportMeta(filter, tenantId, null);
    }

    /**
     * Same as {@link #getAttritionReportMeta(VolumeRevenueFilterDTO, Long)}, but
     * accepts an already-computed "latest loaded business date" so the combined
     * {@code /attrition-report-with-meta} endpoint doesn't run the same
     * MAX(business_date) query twice in one request — once here, once inside
     * {@link #getAttritionReport}. Pass null to compute it fresh (the public
     * overload's behavior, unchanged for the standalone endpoint).
     */
    public Map<String, Object> getAttritionReportMeta(VolumeRevenueFilterDTO filter, Long tenantId,
                                                        java.time.LocalDate precomputedLatestData) {
        requireTenant(tenantId);
        java.time.LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);

        java.time.LocalDate prevEnd    = end.minusYears(1);
        java.time.LocalDate prevStart  = start.minusYears(1);
        java.time.LocalDate prevYtdStart = prevEnd.withDayOfYear(1);
        java.time.LocalDate momStart   = start.minusMonths(1);
        java.time.LocalDate momEnd     = end.minusMonths(1);

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        // Same base-table routing as getAttritionReport — see the comment there.
        boolean canUseMerchantGrain = !needStore
                && !listNonEmpty(filter.getChannelList())
                && !listNonEmpty(filter.getSchemeList())
                && !listNonEmpty(filter.getCardTypeList())
                && !listNonEmpty(filter.getDestinationList());

        // Classifier windows — same math as getAttritionReport (latest-data clamp,
        // complete-month baselines). The status column needs its OWN coverage probe:
        // an empty trailing-3-month baseline makes every status read NEW/CHURNED,
        // an artifact of missing history the MoM/YoY probes below cannot see.
        java.time.LocalDate latestData = precomputedLatestData != null ? precomputedLatestData : latestBusinessDate(
                canUseMerchantGrain ? "sum_daily_merchant" : "sum_daily_insight", tenantId);
        java.time.LocalDate classifierEnd = (latestData != null && latestData.isBefore(end)) ? latestData : end;
        int dayCut = classifierEnd.getDayOfMonth();
        boolean monthComplete = dayCut == classifierEnd.lengthOfMonth();
        java.time.LocalDate curMonStart = classifierEnd.withDayOfMonth(1);
        java.time.LocalDate m1Start = curMonStart.minusMonths(1);
        java.time.LocalDate m3Start = curMonStart.minusMonths(3);
        java.time.LocalDate m1End = m1Start.withDayOfMonth(monthComplete ? m1Start.lengthOfMonth() : Math.min(dayCut, m1Start.lengthOfMonth()));

        java.util.function.BiFunction<java.time.LocalDate, java.time.LocalDate, Boolean> windowHasData = (wStart, wEnd) -> {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT EXISTS(SELECT 1 FROM ")
               .append(canUseMerchantGrain ? "sum_daily_merchant" : "sum_daily_insight").append(" s ");
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
            if (needStore) {
                sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
            }
            sql.append("WHERE s.business_date >= :wStart AND s.business_date <= :wEnd AND s.total_volume > 0 ");
            if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

            if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
            if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
            if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
            if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
            if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
            if (filter.getOpenDateStart() != null)        sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) >= :openStart ");
            if (filter.getOpenDateEnd() != null)          sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) <= :openEnd ");
            if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                sql.append("AND m.name ILIKE :merchName ");
            if (listNonEmpty(filter.getMccList()))        sql.append("AND st.mcc IN (:mccs) ");
            if (listNonEmpty(filter.getSidList()))        sql.append("AND st.sid IN (:sids) ");
            if (listNonEmpty(filter.getChannelList()))     sql.append("AND s.channel IN (:channels) ");
            if (listNonEmpty(filter.getSchemeList()))      sql.append("AND s.card_scheme IN (:schemes) ");
            if (listNonEmpty(filter.getCardTypeList()))    sql.append("AND s.card_type IN (:cardTypes) ");
            if (listNonEmpty(filter.getDestinationList())) sql.append("AND s.destination IN (:destinations) ");
            sql.append(")");

            Query q = entityManager.createNativeQuery(sql.toString());
            q.setParameter("wStart", wStart);
            q.setParameter("wEnd", wEnd);
            if (tenantId != null) q.setParameter("tenantId", tenantId);

            if (listNonEmpty(filter.getPartnerList()))    q.setParameter("partners", filter.getPartnerList());
            if (listNonEmpty(filter.getRmList()))         q.setParameter("rms", filter.getRmList());
            if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders", filter.getTeamLeaderList());
            if (listNonEmpty(filter.getMidList()))        q.setParameter("mids", filter.getMidList());
            if (listNonEmpty(filter.getIndustryList()))   q.setParameter("industries", filter.getIndustryList());
            if (filter.getOpenDateStart() != null)        q.setParameter("openStart", filter.getOpenDateStart());
            if (filter.getOpenDateEnd() != null)          q.setParameter("openEnd", filter.getOpenDateEnd());
            if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                q.setParameter("merchName", "%" + filter.getMerchantName() + "%");
            if (listNonEmpty(filter.getMccList()))        q.setParameter("mccs", filter.getMccList());
            if (listNonEmpty(filter.getSidList()))        q.setParameter("sids", filter.getSidList());
            if (listNonEmpty(filter.getChannelList()))     q.setParameter("channels", filter.getChannelList());
            if (listNonEmpty(filter.getSchemeList()))      q.setParameter("schemes", filter.getSchemeList());
            if (listNonEmpty(filter.getCardTypeList()))    q.setParameter("cardTypes", filter.getCardTypeList());
            if (listNonEmpty(filter.getDestinationList())) q.setParameter("destinations", filter.getDestinationList());

            return Boolean.TRUE.equals(q.getSingleResult());
        };

        boolean momHasData = windowHasData.apply(momStart, momEnd);
        boolean yoyHasData = windowHasData.apply(prevStart, prevEnd);
        boolean ytdPrevHasData = windowHasData.apply(prevYtdStart, prevEnd);
        // One probe over the contiguous [m3Start, m1End] span — the three baseline
        // months are consecutive, so any-data-in-span is the right granularity.
        boolean baselineHasData = windowHasData.apply(m3Start, m1End);

        Map<String, Object> meta = new HashMap<>();
        meta.put("currentStart", start.toString());
        meta.put("currentEnd", end.toString());
        meta.put("momPrevStart", momStart.toString());
        meta.put("momPrevEnd", momEnd.toString());
        meta.put("momWindowHasData", momHasData);
        meta.put("yoyPrevStart", prevStart.toString());
        meta.put("yoyPrevEnd", prevEnd.toString());
        meta.put("yoyWindowHasData", yoyHasData);
        meta.put("ytdPrevStart", prevYtdStart.toString());
        meta.put("ytdPrevEnd", prevEnd.toString());
        meta.put("ytdPrevWindowHasData", ytdPrevHasData);
        // Classifier transparency: the anchor the STATUS column is classified as-of
        // (selected end clamped to the latest loaded business date), and whether its
        // trailing-3-month baseline holds any data at all.
        meta.put("classifierAnchor", classifierEnd.toString());
        meta.put("baselineStart", m3Start.toString());
        meta.put("baselineEnd", m1End.toString());
        meta.put("baselineWindowHasData", baselineHasData);
        return meta;
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so attrition
     * comparisons never include other tenants' merchants.
     */
    public List<Map<String, Object>> getAttritionReport(VolumeRevenueFilterDTO filter, Long tenantId) {
        return getAttritionReport(filter, tenantId, null);
    }

    /**
     * Combined rows+meta fetch for the /attrition-report-with-meta endpoint.
     * Computes "latest loaded business date" exactly ONCE and threads it into
     * both the row query and the meta probes — the two were previously run
     * back-to-back and each ran its own identical MAX(business_date) query,
     * doubling that round trip on every page load for no reason (routing is
     * deterministic from the same filter, so the two calls always agreed).
     */
    public Map<String, Object> getAttritionReportWithMeta(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        boolean canUseMerchantGrain = !needStore
                && !listNonEmpty(filter.getChannelList())
                && !listNonEmpty(filter.getSchemeList())
                && !listNonEmpty(filter.getCardTypeList())
                && !listNonEmpty(filter.getDestinationList());
        java.time.LocalDate latestData = latestBusinessDate(
                canUseMerchantGrain ? "sum_daily_merchant" : "sum_daily_insight", tenantId);

        Map<String, Object> response = new HashMap<>();
        response.put("rows", getAttritionReport(filter, tenantId, latestData));
        response.put("meta", getAttritionReportMeta(filter, tenantId, latestData));
        return response;
    }

    /** As {@link #getAttritionReport(VolumeRevenueFilterDTO, Long)}, reusing an already-computed latest-data date. */
    public List<Map<String, Object>> getAttritionReport(VolumeRevenueFilterDTO filter, Long tenantId,
                                                          java.time.LocalDate precomputedLatestData) {
        requireTenant(tenantId);
        // ── Window definitions (all equal-length so comparisons are apples-to-apples) ──
        // Current period  : [start, end]              (the selected range)
        // YoY prev period : [start-1y, end-1y]        (same range, one year earlier)
        // YTD current     : [Jan 1 of endYear, end]
        // YTD prev        : [Jan 1 of prevYear, end-1y]
        // MoM prev        : [start-1mo, end-1mo]      (same-length window one month earlier;
        //                                              the OLD code compared the partial current
        //                                              month to a FULL previous calendar month,
        //                                              which biased every merchant negative early
        //                                              in the month — fixed here.)
        java.time.LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);

        java.time.LocalDate prevEnd   = end.minusYears(1);
        java.time.LocalDate prevStart = start.minusYears(1);
        java.time.LocalDate ytdStart  = end.withDayOfYear(1);
        java.time.LocalDate prevYtdStart = prevEnd.withDayOfYear(1);
        java.time.LocalDate momStart  = start.minusMonths(1);
        java.time.LocalDate momEnd    = end.minusMonths(1);
        // Full previous calendar year — shares prevYtdStart (Jan 1 of the prior
        // year) as its lower bound; always <= endDate, so the WHERE upper bound
        // needs no change.
        java.time.LocalDate pyFullEnd = java.time.LocalDate.of(end.getYear() - 1, 12, 31);

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        boolean canUseMerchantGrain = !needStore
                && !listNonEmpty(filter.getChannelList())
                && !listNonEmpty(filter.getSchemeList())
                && !listNonEmpty(filter.getCardTypeList())
                && !listNonEmpty(filter.getDestinationList());
        String baseTable = canUseMerchantGrain ? "sum_daily_merchant" : "sum_daily_insight";

        // ── Attrition classification windows (calendar months, independent of the
        //    selected range) ──
        // "Current month" is the calendar month containing `end`, read month-to-date.
        // The three prior months are each truncated to the SAME day-of-month, so a
        // partial current month is never compared against full prior months — the
        // same apples-to-apples rule the MoM window above already follows. Without
        // this, every merchant looks churned on the 2nd of the month.
        //
        // [FIX] The anchor is `end` clamped to the latest LOADED business date, not
        // the raw selected end. `end` comes from the calendar ("This month" ends at
        // today), but data always lags it — so the current window held fewer real
        // days than dayCut while the baselines were complete, biasing every ratio
        // low by (missing days / dayCut) and mass-stamping CHURNED right after
        // month start. Historical selections are unaffected (latest >= end there).
        java.time.LocalDate latestData = precomputedLatestData != null ? precomputedLatestData : latestBusinessDate(baseTable, tenantId);
        java.time.LocalDate classifierEnd = (latestData != null && latestData.isBefore(end)) ? latestData : end;
        int dayCut = classifierEnd.getDayOfMonth();
        // [FIX] When the anchored month is COMPLETE, the baselines must be complete
        // months too. The old unconditional day-of-month cut dropped day 29-31 from
        // longer baseline months (Feb selected → Jan/Dec/Nov all read 1st-28th),
        // understating avg3 ~9% and inflating every ratio against the 30%/90%
        // thresholds. Truncation is only the right rule while the month is partial.
        boolean monthComplete = dayCut == classifierEnd.lengthOfMonth();
        java.time.LocalDate curMonStart = classifierEnd.withDayOfMonth(1);
        java.time.LocalDate m1Start = curMonStart.minusMonths(1);
        java.time.LocalDate m2Start = curMonStart.minusMonths(2);
        java.time.LocalDate m3Start = curMonStart.minusMonths(3);
        java.time.LocalDate m1End = m1Start.withDayOfMonth(monthComplete ? m1Start.lengthOfMonth() : Math.min(dayCut, m1Start.lengthOfMonth()));
        java.time.LocalDate m2End = m2Start.withDayOfMonth(monthComplete ? m2Start.lengthOfMonth() : Math.min(dayCut, m2Start.lengthOfMonth()));
        java.time.LocalDate m3End = m3Start.withDayOfMonth(monthComplete ? m3Start.lengthOfMonth() : Math.min(dayCut, m3Start.lengthOfMonth()));

        // Earliest date any window reads — used as the WHERE lower bound for partition pruning.
        //
        // [FIX] prevStart was missing from this minimum. prevYtdStart is Jan 1 of the
        // previous year, so it only dominates prevStart when the SELECTED range sits
        // inside one calendar year. As soon as the range spans a year boundary
        // (e.g. 2025-11-01 → 2026-02-28) prevStart is 2024-11-01, which is BEFORE
        // prevYtdStart (2025-01-01) — and the WHERE clause silently cut those months
        // out of the YoY "previous" window. The prior-year column came back
        // understated and every YoY % change was inflated, with no error anywhere.
        java.time.LocalDate globalLowerBound = prevYtdStart;
        if (prevStart.isBefore(globalLowerBound)) globalLowerBound = prevStart;
        if (momStart.isBefore(globalLowerBound)) globalLowerBound = momStart;
        if (m3Start.isBefore(globalLowerBound)) globalLowerBound = m3Start;

        // ── Base-table routing (booleans computed above, before the classifier
        //    windows, because the latest-data clamp reads the routed table) ──
        // This report is merchant-grained: it only ever reads business_date,
        // merchant_id and the three measures. sum_daily_insight is the
        // merchant x store x scheme x type x destination x channel cross-tab —
        // 10-50x more rows than the merchant-grain sum_daily_merchant carrying
        // the same totals. The insight table is only NEEDED when a card-dimension
        // filter (scheme/type/destination/channel) has to cut rows before the
        // SUM, or when a store filter needs s.store_id (always NULL on
        // sum_daily_merchant). Everything else — including the default unfiltered
        // load, the slow case in practice — routes to the small table.
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid AS mid, m.name AS merchant_name, ");
        // For each window emit volume / txns / msf so the UI can toggle metric without re-querying.
        appendWindowMeasures(sql, "mtdcur", ":startDate",       ":endDate");
        appendWindowMeasures(sql, "mtdprev", ":prevStartDate",  ":prevEndDate");
        appendWindowMeasures(sql, "ytdcur", ":ytdStartDate",    ":endDate");
        appendWindowMeasures(sql, "ytdprev", ":prevYtdStartDate", ":prevEndDate");
        appendWindowMeasures(sql, "momprev", ":momStartDate",   ":momEndDate");
        // Volume-only windows for the attrition classifier. Appended LAST so the
        // hard-coded row[] indices of the five windows above stay valid.
        appendVolumeMeasure(sql, "curmon", ":curMonStart", ":curMonEnd");
        appendVolumeMeasure(sql, "m1",     ":m1Start",     ":m1End");
        appendVolumeMeasure(sql, "m2",     ":m2Start",     ":m2End");
        appendVolumeMeasure(sql, "m3",     ":m3Start",     ":m3End");
        // Last day this merchant actually transacted, within the span the query
        // already reads. "Churned" answers whether to call; this answers how
        // urgent the call is — a merchant quiet for 4 days is a different
        // conversation from one quiet for 90. Appended LAST so the hard-coded
        // row[] indices above stay valid. CASE rather than FILTER: same result,
        // plain SQL. NULL when the merchant never traded in the span.
        sql.append("MAX(CASE WHEN s.total_volume > 0 THEN s.business_date END) AS last_activity, ");
        // ── Presentation windows for the executive grid ──
        // The FULL previous calendar year (the grid shows "<prev year> full"
        // next to "<year> YTD"), plus the classifier months again as full
        // vol/txn/msf so the Measure toggle works on the month columns (the
        // volume-only versions above stay untouched — the classifier and the
        // hard-coded row[] indices 0–21 depend on them). Appended AFTER
        // last_activity, indices 22+.
        appendWindowMeasures(sql, "pyfull", ":prevYtdStartDate", ":pyFullEnd");
        appendWindowMeasures(sql, "curmonx", ":curMonStart", ":curMonEnd");
        appendWindowMeasures(sql, "m1x", ":m1Start", ":m1End");
        appendWindowMeasures(sql, "m2x", ":m2Start", ":m2End");
        // strip trailing comma
        sql.setLength(sql.length() - 2);
        sql.append(" FROM ").append(baseTable).append(" s ");
        // [TENANCY] Join is tenant-scoped (s.tenant_id = m.tenant_id) — the old version
        // joined on merchant_id alone, the [P2-1] cross-tenant time-bomb pattern.
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) {
            sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        }

        sql.append("WHERE s.business_date >= :globalLowerBound AND s.business_date <= :endDate ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

        // Merchant-dimension filters
        if (listNonEmpty(filter.getPartnerList()))  sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))       sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))      sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList())) sql.append("AND m.industry IN (:industries) ");
        // Open-date (onboarding) range — true business onboarding date, falling
        // back to created_date for merchants whose master file never carried one.
        if (filter.getOpenDateStart() != null)      sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) >= :openStart ");
        if (filter.getOpenDateEnd() != null)        sql.append("AND COALESCE(m.date_of_onboarding, m.created_date) <= :openEnd ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        // Store-dimension filters
        if (listNonEmpty(filter.getMccList()))      sql.append("AND st.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getSidList()))      sql.append("AND st.sid IN (:sids) ");
        // Insight-dimension filters (previously ignored by this report)
        if (listNonEmpty(filter.getChannelList()))      sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))       sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getCardTypeList()))     sql.append("AND s.card_type IN (:cardTypes) ");
        if (listNonEmpty(filter.getDestinationList()))  sql.append("AND s.destination IN (:destinations) ");

        sql.append("GROUP BY m.mid, m.name ORDER BY m.mid ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("startDate", start);
        query.setParameter("endDate", end);
        query.setParameter("prevStartDate", prevStart);
        query.setParameter("prevEndDate", prevEnd);
        query.setParameter("ytdStartDate", ytdStart);
        query.setParameter("prevYtdStartDate", prevYtdStart);
        query.setParameter("momStartDate", momStart);
        query.setParameter("momEndDate", momEnd);
        query.setParameter("pyFullEnd", pyFullEnd);
        query.setParameter("curMonStart", curMonStart);
        query.setParameter("curMonEnd", classifierEnd);
        query.setParameter("m1Start", m1Start);
        query.setParameter("m1End", m1End);
        query.setParameter("m2Start", m2Start);
        query.setParameter("m2End", m2End);
        query.setParameter("m3Start", m3Start);
        query.setParameter("m3End", m3End);
        query.setParameter("globalLowerBound", globalLowerBound);
        if (tenantId != null) query.setParameter("tenantId", tenantId);

        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   query.setParameter("industries", filter.getIndustryList());
        if (filter.getOpenDateStart() != null)        query.setParameter("openStart", filter.getOpenDateStart());
        if (filter.getOpenDateEnd() != null)          query.setParameter("openEnd", filter.getOpenDateEnd());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))    query.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))     query.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getCardTypeList()))   query.setParameter("cardTypes", filter.getCardTypeList());
        if (listNonEmpty(filter.getDestinationList())) query.setParameter("destinations", filter.getDestinationList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            // Column order: mid, name, then 5 windows x (vol, txn, msf)
            java.math.BigDecimal mtdVol  = bd(row[2]),  ytdVol  = bd(row[8]),  momVol  = bd(row[14]);
            java.math.BigDecimal mtdVolP = bd(row[5]),  ytdVolP = bd(row[11]);
            long mtdTxn  = lng(row[3]),  ytdTxn  = lng(row[9]),  momTxn  = lng(row[15]);
            long mtdTxnP = lng(row[6]),  ytdTxnP = lng(row[12]);
            java.math.BigDecimal mtdMsf  = bd(row[4]),  ytdMsf  = bd(row[10]), momMsf  = bd(row[16]);
            java.math.BigDecimal mtdMsfP = bd(row[7]),  ytdMsfP = bd(row[13]);
            // Attrition classifier inputs: current month-to-date + the three prior
            // months truncated to the same day-of-month.
            java.math.BigDecimal curMonVol = bd(row[17]);
            java.math.BigDecimal m1Vol = bd(row[18]), m2Vol = bd(row[19]), m3Vol = bd(row[20]);
            // Presentation windows (appended after last_activity at row[21]):
            // full previous calendar year, then the classifier months as full
            // vol/txn/msf triples for the Measure toggle.
            java.math.BigDecimal pyVol = bd(row[22]);
            long pyTxn = lng(row[23]);
            java.math.BigDecimal pyMsf = bd(row[24]);
            long curMonTxn = lng(row[26]), m1Txn = lng(row[29]), m2Txn = lng(row[32]);
            java.math.BigDecimal curMonMsf = bd(row[27]), m1Msf = bd(row[30]), m2Msf = bd(row[33]);

            // Drop merchants with no activity in ANY window — pure noise, never attrition.
            // The rolling-month windows are included so a merchant whose only activity
            // sits in the trailing 3 months is still classified rather than silently
            // dropped; the full prior year likewise, so a merchant who only traded in
            // the back half of last year (after the prior-YTD cutoff) still appears.
            if (isZero(mtdVol) && isZero(mtdVolP) && isZero(ytdVol) && isZero(ytdVolP) && isZero(momVol)
                    && isZero(curMonVol) && isZero(m1Vol) && isZero(m2Vol) && isZero(m3Vol) && isZero(pyVol))
                continue;

            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("name", row[1]);

            // ── Volume (kept under the original key names for backward compatibility) ──
            map.put("mtd_current", mtdVol);
            map.put("mtd_prev", mtdVolP);
            map.put("mtd_pct", calculateGrowth(mtdVol.doubleValue(), mtdVolP.doubleValue()));
            map.put("ytd_current", ytdVol);
            map.put("ytd_prev", ytdVolP);
            double ytdPctVol = calculateGrowth(ytdVol.doubleValue(), ytdVolP.doubleValue());
            map.put("ytd_pct", ytdPctVol);
            map.put("mom_prev", momVol);
            map.put("mom_current", mtdVol); // equal-length current window, for the MoM column group
            map.put("mom_pct", calculateGrowth(mtdVol.doubleValue(), momVol.doubleValue()));

            // ── Transaction count ──
            map.put("mtd_current_txns", mtdTxn);
            map.put("mtd_prev_txns", mtdTxnP);
            map.put("mtd_pct_txns", calculateGrowth(mtdTxn, mtdTxnP));
            map.put("ytd_current_txns", ytdTxn);
            map.put("ytd_prev_txns", ytdTxnP);
            map.put("ytd_pct_txns", calculateGrowth(ytdTxn, ytdTxnP));
            map.put("mom_prev_txns", momTxn);
            map.put("mom_current_txns", mtdTxn);
            map.put("mom_pct_txns", calculateGrowth(mtdTxn, momTxn));

            // ── MSF revenue ──
            map.put("mtd_current_msf", mtdMsf);
            map.put("mtd_prev_msf", mtdMsfP);
            map.put("mtd_pct_msf", calculateGrowth(mtdMsf.doubleValue(), mtdMsfP.doubleValue()));
            map.put("ytd_current_msf", ytdMsf);
            map.put("ytd_prev_msf", ytdMsfP);
            map.put("ytd_pct_msf", calculateGrowth(ytdMsf.doubleValue(), ytdMsfP.doubleValue()));
            map.put("mom_prev_msf", momMsf);
            map.put("mom_current_msf", mtdMsf);
            map.put("mom_pct_msf", calculateGrowth(mtdMsf.doubleValue(), momMsf.doubleValue()));

            // ── Attrition status: current month vs the trailing 3-month average ──
            double avg3 = (m1Vol.doubleValue() + m2Vol.doubleValue() + m3Vol.doubleValue()) / 3.0;
            double curMon = curMonVol.doubleValue();
            // Ratio of current month to the 3-month average, as a %. Null-safe: with no
            // trailing history the ratio is undefined, reported as null rather than 0 or
            // infinity so the UI can show "—" instead of a misleading number.
            Double ratioPct = avg3 > 0 ? (curMon / avg3) * 100.0 : null;

            map.put("avg_3m", java.math.BigDecimal.valueOf(avg3));
            map.put("cur_month", curMonVol);
            map.put("prev_m1", m1Vol);
            map.put("prev_m2", m2Vol);
            map.put("prev_m3", m3Vol);
            // Per-metric month values (same windows as the classifier months) and
            // the full previous calendar year — the executive grid's fixed columns.
            map.put("cur_month_txns", curMonTxn);
            map.put("cur_month_msf", curMonMsf);
            map.put("prev_m1_txns", m1Txn);
            map.put("prev_m1_msf", m1Msf);
            map.put("prev_m2_txns", m2Txn);
            map.put("prev_m2_msf", m2Msf);
            map.put("py_full", pyVol);
            map.put("py_full_txns", pyTxn);
            map.put("py_full_msf", pyMsf);
            map.put("avg_3m_ratio_pct", ratioPct);
            map.put("status", classifyAttrition(curMon, m1Vol.doubleValue(), m2Vol.doubleValue(), m3Vol.doubleValue(), avg3));

            // Last activity date, normalised to an ISO string. The JDBC driver may
            // hand back java.sql.Date or LocalDate depending on column type and
            // driver version, so convert defensively — this runs per row, and a
            // ClassCastException here would fail the whole report.
            Object lastActRaw = row.length > 21 ? row[21] : null;
            String lastActivity = null;
            if (lastActRaw instanceof java.sql.Date) {
                lastActivity = ((java.sql.Date) lastActRaw).toLocalDate().toString();
            } else if (lastActRaw instanceof java.time.LocalDate) {
                lastActivity = lastActRaw.toString();
            } else if (lastActRaw != null) {
                String s = String.valueOf(lastActRaw);
                lastActivity = s.length() >= 10 ? s.substring(0, 10) : s;
            }
            map.put("last_activity", lastActivity);

            result.add(map);
        }

        // Worst first — status severity, then the weakest ratio to the 3-month
        // average, then YTD % so the order stays stable.
        //
        // [FIX] The old sort keyed on the ratio alone with nulls last, on the
        // reasoning that no history = new. But a long-dead merchant (traded months
        // ago, zero in all four classifier windows) also has avg3 = 0 → null ratio
        // → status CHURNED, and sorted to the BOTTOM of a worst-first grid. Rank by
        // severity first so every churned merchant is at the top regardless of how
        // long it has been dead; nulls-last now only orders WITHIN a status band.
        result.sort((a, b) -> {
            int c = Integer.compare(statusSeverity((String) a.get("status")),
                                    statusSeverity((String) b.get("status")));
            if (c != 0) return c;
            Object ra = a.get("avg_3m_ratio_pct"), rb = b.get("avg_3m_ratio_pct");
            double da = ra == null ? Double.MAX_VALUE : ((Number) ra).doubleValue();
            double db = rb == null ? Double.MAX_VALUE : ((Number) rb).doubleValue();
            c = Double.compare(da, db);
            if (c != 0) return c;
            return Double.compare(
                    ((Number) a.getOrDefault("ytd_pct", 0d)).doubleValue(),
                    ((Number) b.getOrDefault("ytd_pct", 0d)).doubleValue());
        });

        return result;
    }

    /** Worst-first rank for the attrition grid. Unknown statuses sort with STABLE. */
    private static int statusSeverity(String status) {
        switch (status == null ? "" : status) {
            case "CHURNED":    return 0;
            case "DECLINING":  return 1;
            case "STABLE":     return 2;
            case "PERFORMING": return 3;
            case "NEW":        return 4;
            default:           return 2;
        }
    }

    /**
     * Latest loaded business date for the tenant on the routed summary table.
     * Anchors the attrition classifier on real data instead of the calendar —
     * see the [FIX] comment in getAttritionReport. Null when the tenant has no
     * rows at all (classifier then falls back to the selected end date).
     */
    private java.time.LocalDate latestBusinessDate(String baseTable, Long tenantId) {
        Object r = entityManager.createNativeQuery(
                "SELECT MAX(s.business_date) FROM " + baseTable + " s WHERE s.tenant_id = :tenantId")
                .setParameter("tenantId", tenantId)
                .getSingleResult();
        if (r == null) return null;
        if (r instanceof java.time.LocalDate) return (java.time.LocalDate) r;
        if (r instanceof java.sql.Date) return ((java.sql.Date) r).toLocalDate();
        return java.time.LocalDate.parse(r.toString().substring(0, 10));
    }

    /** Emits "SUM(CASE WHEN business_date in window THEN <col> ELSE 0 END) AS <alias>_x, " for vol/txn/msf. */
    private void appendWindowMeasures(StringBuilder sql, String alias, String from, String to) {
        String when = "CASE WHEN s.business_date >= " + from + " AND s.business_date <= " + to + " THEN ";
        sql.append("SUM(").append(when).append("s.total_volume ELSE 0 END) AS ").append(alias).append("_vol, ");
        sql.append("SUM(").append(when).append("s.total_txns   ELSE 0 END) AS ").append(alias).append("_txn, ");
        sql.append("SUM(").append(when).append("s.total_msf    ELSE 0 END) AS ").append(alias).append("_msf, ");
    }

    /** Volume-only variant of appendWindowMeasures — one column, for the attrition classifier. */
    private void appendVolumeMeasure(StringBuilder sql, String alias, String from, String to) {
        sql.append("SUM(CASE WHEN s.business_date >= ").append(from)
           .append(" AND s.business_date <= ").append(to)
           .append(" THEN s.total_volume ELSE 0 END) AS ").append(alias).append("_vol, ");
    }

    /**
     * Attrition status from the rolling monthly volumes.
     *
     * All comparisons are against avg3 — the mean of the three months preceding the
     * current one, each truncated to the same day-of-month as the current partial month.
     *
     *   NEW        no trailing 3-month history (avg3 == 0) but trading this month —
     *              there is no baseline to attrite FROM. [FIX] Previously this fell
     *              through to PERFORMING ("current >= 90% of a zero average"), so a
     *              window at the start of the tenant's data history stamped the whole
     *              portfolio Performing against an average that did not exist.
     *   CHURNED    current month is zero, OR below 30% of avg3
     *   DECLINING  the last three months are constantly dropping (m3 > m2 > m1 > current)
     *   PERFORMING current month is at least 90% of avg3. Zero months inside the
     *              trailing window do NOT disqualify — a merchant returning from
     *              dormancy at >=90% of its (correspondingly lower) average counts
     *              as performing.
     *   STABLE     matches none of the above — e.g. running at 60% of avg3 without a
     *              monotonic decline.
     *
     * Evaluated in that order, so the most severe status wins when several apply.
     *
     * A merchant with no classifier-window activity at all (avg3 == 0, current == 0)
     * still classifies CHURNED: it traded in SOME window (the noise filter upstream
     * guarantees that) but has been dead for the whole classification horizon.
     */
    private String classifyAttrition(double curMonth, double m1, double m2, double m3, double avg3) {
        if (avg3 <= 0 && curMonth > 0) return "NEW";
        if (curMonth <= 0 || (avg3 > 0 && curMonth < 0.30 * avg3)) return "CHURNED";
        if (m3 > m2 && m2 > m1 && m1 > curMonth) return "DECLINING";
        if (curMonth >= 0.90 * avg3) return "PERFORMING";
        return "STABLE";
    }

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }
    private static boolean isZero(java.math.BigDecimal b) { return b == null || b.signum() == 0; }
    private static java.math.BigDecimal bd(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal) return (java.math.BigDecimal) o;
        return new java.math.BigDecimal(o.toString());
    }
    private static long lng(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    /**
     * Tenant-scoped variant. Adds `AND tenant_id = :tenantId` so executive
     * KPIs (volume, txns, active merchants) are scoped to the requesting tenant.
     */
    public Map<String, Object> getExecutiveMetrics(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        Map<String, Object> metrics = new HashMap<>();

        // Dates
        java.time.LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        if (days == 0)
            days = 1; // Avoid zero if same day

        java.time.LocalDate prevEnd = start.minusDays(1);
        java.time.LocalDate prevStart = prevEnd.minusDays(days);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        // Current Period
        sql.append("  SUM(CASE WHEN business_date BETWEEN :start AND :end THEN total_volume ELSE 0 END) as vol_curr, ");
        sql.append("  SUM(CASE WHEN business_date BETWEEN :start AND :end THEN total_txns ELSE 0 END) as txns_curr, ");
        sql.append(
                "  COUNT(DISTINCT CASE WHEN business_date BETWEEN :start AND :end THEN merchant_id END) as merch_curr, ");

        // Previous Period
        sql.append(
                "  SUM(CASE WHEN business_date BETWEEN :prevStart AND :prevEnd THEN total_volume ELSE 0 END) as vol_prev, ");
        sql.append(
                "  SUM(CASE WHEN business_date BETWEEN :prevStart AND :prevEnd THEN total_txns ELSE 0 END) as txns_prev, ");
        sql.append(
                "  COUNT(DISTINCT CASE WHEN business_date BETWEEN :prevStart AND :prevEnd THEN merchant_id END) as merch_prev ");

        sql.append("FROM sum_daily_insight ");
        // Optimize: Broad filter to cover both ranges
        sql.append("WHERE business_date >= :prevStart AND business_date <= :end ");
        if (tenantId != null) {
            sql.append("AND tenant_id = :tenantId ");
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("start", start);
        query.setParameter("end", end);
        query.setParameter("prevStart", prevStart);
        query.setParameter("prevEnd", prevEnd);
        if (tenantId != null) query.setParameter("tenantId", tenantId);

        Object[] result = null;
        try {
            result = (Object[]) query.getSingleResult();
        } catch (Exception e) {
            // No result or error
        }

        if (result != null) {
            // Volume
            double volCurr = result[0] != null ? ((Number) result[0]).doubleValue() : 0;
            double volPrev = result[3] != null ? ((Number) result[3]).doubleValue() : 0;
            metrics.put("totalVolume", volCurr);
            metrics.put("volumeGrowth", calculateGrowth(volCurr, volPrev));

            // Txns
            long txnsCurr = result[1] != null ? ((Number) result[1]).longValue() : 0;
            long txnsPrev = result[4] != null ? ((Number) result[4]).longValue() : 0;
            metrics.put("totalTxns", txnsCurr);
            metrics.put("txnsGrowth", calculateGrowth(txnsCurr, txnsPrev));

            // Active Merchants
            long merchCurr = result[2] != null ? ((Number) result[2]).longValue() : 0;
            long merchPrev = result[5] != null ? ((Number) result[5]).longValue() : 0;
            metrics.put("activeMerchants", merchCurr);
            metrics.put("merchantsGrowth", calculateGrowth(merchCurr, merchPrev));

            // Same guard as the Retention/Attrition reports: the "prior period"
            // half of this comparison is auto-computed as an equal-length
            // window immediately before the selected range. If that window has
            // no volume/txn activity at all, every *Growth figure above is a
            // divide-by-near-zero artifact (e.g. "+100%") rather than a real
            // trend — flag it so the dashboard can say so instead of showing a
            // confidently wrong number.
            metrics.put("priorWindowHasData", volPrev > 0 || txnsPrev > 0);
        } else {
            metrics.put("totalVolume", 0);
            metrics.put("volumeGrowth", 0);
            metrics.put("totalTxns", 0);
            metrics.put("txnsGrowth", 0);
            metrics.put("activeMerchants", 0);
            metrics.put("merchantsGrowth", 0);
            metrics.put("priorWindowHasData", false);
        }
        metrics.put("priorStart", prevStart.toString());
        metrics.put("priorEnd", prevEnd.toString());

        // Leakage (Placeholder: Placeholder logic or hardcoded 0 for now as previously
        // discussed)
        metrics.put("leakageCount", 0);
        metrics.put("leakageGrowth", 0.0);

        return metrics;
    }

    private double calculateGrowth(double current, double previous) {
        if (previous == 0)
            return current > 0 ? 100.0 : 0.0;
        return ((current - previous) / previous) * 100.0;
    }

    // ── Monthly pre-aggregate helpers ──────────────────────────────────────
    /**
     * month_key (YYYYMM int) for a date, matching the convention written by
     * populateSummaryStep (year*100 + month).
     */
    private static Integer monthKey(java.time.LocalDate d) {
        return d == null ? null : d.getYear() * 100 + d.getMonthValue();
    }

    /**
     * Whether a [start, end] range can be served EXACTLY from the month-grain
     * sum_monthly_insight. True only when the range covers WHOLE calendar months
     * (start is the 1st, end is the last day of its month). For any partial
     * month, the monthly table would include days outside the range, so we must
     * stay on the daily table. Both bounds must be present.
     */
    private static boolean canUseMonthly(java.time.LocalDate start, java.time.LocalDate end) {
        if (start == null || end == null) return false;
        boolean startIsMonthStart = start.getDayOfMonth() == 1;
        boolean endIsMonthEnd = end.getDayOfMonth() == end.lengthOfMonth();
        return startIsMonthStart && endIsMonthEnd && !start.isAfter(end);
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null, the SQL adds
     * `AND s.tenant_id = :tenantId` so cross-tenant rows can never leak.
     * In single-tenant deployments this is a no-op.
     */
    public Map<String, Object> getMerchantAnalyticsReport(VolumeRevenueFilterDTO filter, int page, int size, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();

        // Select columns matching frontend expectation:
        // merchantId, sid, mid, merchantName, volume, count, msf, interchange, mcc,
        // industry, legalName, dccOptin, totalCount, terminalType
        sql.append("SELECT ");
        // FIX: include merchant_id so the frontend DataGrid can use it as a stable row key
        sql.append("  m.merchant_id as merchant_id, ");
        sql.append("  st.sid as sid, ");
        sql.append("  m.mid as mid, ");
        sql.append("  m.name as merchant_name, ");
        sql.append("  SUM(s.total_volume) as volume, ");
        sql.append("  SUM(s.total_txns) as count, ");
        sql.append("  SUM(s.total_msf) as msf, ");
        // sum_daily_insight does not carry total_interchange — interchange lives in
        // fact_transaction and sum_daily_bank only. Return 0 so the column is still
        // present in the result set (the frontend reads row[7] for the interchange
        // DataGrid column) without causing a SQL grammar error.
        sql.append("  CAST(0 AS NUMERIC) as interchange, ");
        sql.append("  st.mcc as mcc, ");
        // Industry/category now resolved from ref_mcc_category (global reference
        // table seeded from the bank's MCC sector sheet, V2026_07_10_01) instead
        // of the old ISO-18245 range-band CASE — exact per-MCC sectors, and the
        // mapping can be corrected with SQL alone (no redeploy). Any MCC not in
        // the sheet falls back to the 'MIS' bucket (unmapped) so it stays visible
        // rather than silently collapsing.
        sql.append("  COALESCE(rc.category, 'MIS') as industry, ");
        sql.append("  m.name as legal_name, "); // Fallback to name
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dcc_optin, ");
        sql.append("  count(*) OVER() as total_count, "); // Window function
        // FIX (duplicate-rows bug): we used to group by t.type which multiplied rows
        // when one (sid, mid) combo had terminals of different types. Aggregate the
        // type via MAX so each (sid, mid, mcc) triple gets exactly one row.
        sql.append("  MAX(t.type) as terminal_type ");

        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        // Join dim_terminal to get Type
        sql.append("LEFT JOIN dim_terminal t ON s.terminal_id = t.terminal_id AND t.tenant_id = s.tenant_id "); // Use LEFT JOIN in case terminal_id
                                                                                  // is null or missing
        // Global reference table (no tenant_id — same class as ref_country).
        sql.append("LEFT JOIN ref_mcc_category rc ON rc.mcc = st.mcc ");

        sql.append("WHERE 1=1 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

        if (filter.getStartDate() != null)
            sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null)
            sql.append("AND s.business_date <= :endDate ");

        // preciseDateList handling
        if (filter.getPreciseDateList() != null && !filter.getPreciseDateList().isEmpty()) {
            // Compare the raw DATE column, not TO_CHAR(...) — wrapping the
            // partition key in a function defeats both partition pruning and
            // the (tenant_id, business_date) index. Values are parsed to
            // LocalDate at bind time below.
            sql.append("AND s.business_date IN (:preciseDates) ");
        }

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND (m.name ILIKE :merchName OR m.mid ILIKE :merchName) "); // Expanded search
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            sql.append("AND s.card_scheme IN (:schemes) ");
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            sql.append("AND s.card_type IN (:cardTypes) ");
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            sql.append("AND s.destination IN (:destinations) ");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            sql.append("AND st.mcc IN (:mccs) ");
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            sql.append("AND s.channel IN (:channels) ");

        // Terminal Type Filter
        if (filter.getTerminalTypeList() != null && !filter.getTerminalTypeList().isEmpty()) {
            sql.append("AND t.type IN (:terminalTypes) ");
        }

        // Grouping (FIX: dropped t.type — see SELECT above for why)
        sql.append("GROUP BY m.merchant_id, st.sid, m.mid, m.name, st.mcc, rc.category ");

        // Sorting and Pagination
        sql.append("ORDER BY m.name ASC, st.sid ASC ");
        sql.append("OFFSET :offset LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());

        // Bind tenantId early so the binding logic below can stay unchanged.
        if (tenantId != null) query.setParameter("tenantId", tenantId);

        // Params
        if (filter.getStartDate() != null)
            query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null)
            query.setParameter("endDate", filter.getEndDate());
        if (filter.getPreciseDateList() != null && !filter.getPreciseDateList().isEmpty())
            query.setParameter("preciseDates", filter.getPreciseDateList().stream()
                    .map(java.time.LocalDate::parse).collect(java.util.stream.Collectors.toList()));

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            query.setParameter("rms", filter.getRmList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getSchemeList() != null && !filter.getSchemeList().isEmpty())
            query.setParameter("schemes", filter.getSchemeList());
        if (filter.getCardTypeList() != null && !filter.getCardTypeList().isEmpty())
            query.setParameter("cardTypes", filter.getCardTypeList());
        if (filter.getDestinationList() != null && !filter.getDestinationList().isEmpty())
            query.setParameter("destinations", filter.getDestinationList());
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty())
            query.setParameter("channels", filter.getChannelList());
        if (filter.getTerminalTypeList() != null && !filter.getTerminalTypeList().isEmpty())
            query.setParameter("terminalTypes", filter.getTerminalTypeList());

        query.setParameter("offset", page * size);
        query.setParameter("limit", size);

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> content = new ArrayList<>();
        long totalElements = 0;

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            // NOTE: column indices shifted by 1 after merchant_id was added at position 0
            map.put("merchantId",  row[0]);   // m.merchant_id  — stable DataGrid row key
            map.put("sid",         row[1]);   // st.sid
            map.put("mid",         row[2]);   // m.mid
            map.put("merchantName",row[3]);   // m.name
            map.put("volume",      row[4]);   // SUM(s.total_volume)
            map.put("count",       row[5]);   // SUM(s.total_txns)
            map.put("msf",         row[6]);   // SUM(s.total_msf)
            map.put("interchange", row[7]);   // CAST(0 AS NUMERIC) — sum_daily_insight has no interchange column
            map.put("mcc",         row[8]);   // st.mcc
            map.put("industry",    row[9]);   // MCC-derived CASE label — was hardcoded 'Retail'
            map.put("legalName",   row[10]);  // m.name
            map.put("dccOptin",    row[11]);  // SUM(is_opt_in volumes)
            // row[12] = count(*) OVER() — window total, used for pagination
            map.put("terminalType",row[13]);  // MAX(t.type)

            if (totalElements == 0 && row.length > 12) {
                totalElements = ((Number) row[12]).longValue();
            }
            content.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", content);
        response.put("totalElements", totalElements);
        return response;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  EXECUTIVE DAILY MERCHANT DASHBOARD  (single business date, full fee set)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sort keys the executive daily merchant table may order by, mapped to the
     * SQL expression they sort on. User input NEVER reaches the SQL string —
     * an unknown key falls back to volume.
     */
    private static final Map<String, String> DAILY_MERCHANT_SORT = Map.of(
            "sid",    "st.sid",
            "mid",    "m.mid",
            "name",   "m.name",
            "volume", "SUM(s.total_volume)",
            "count",  "SUM(s.total_txns)",
            "msf",    "SUM(s.total_msf)",
            "icf",    "SUM(s.total_interchange)",
            "sf",     "SUM(s.total_scheme_fee)",
            "pg",     "SUM(s.total_ecom_fee)",
            "nm",     "SUM(s.total_net_revenue)");

    /**
     * One page of the Executive Daily Merchant Dashboard: per-(MID, SID) rows
     * read from sum_daily_full — the only summary carrying MSF + interchange +
     * scheme fee + ecom(PG) fee + net revenue together with the
     * MCC/destination/card-type/scheme dimensions. The stored total_net_revenue
     * IS the authoritative NM (= MSF − ICF − SF − PG, written by
     * TransactionJobConfig); it is never recomputed here.
     *
     * Date selection: an explicit list of business dates (one or many — IN
     * semantics), OR a [rangeStart, rangeEnd] window (whole-month view). The
     * caller supplies exactly one of the two.
     *
     * size <= 0 means export: the whole filtered result set in sort order.
     */
    public Map<String, Object> getExecutiveDailyMerchant(VolumeRevenueFilterDTO filter,
            List<java.time.LocalDate> dates, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd,
            String search, String sort, String dir, int page, int size, Long tenantId) {
        requireTenant(tenantId);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.merchant_id, st.sid, m.mid, m.name, ");
        sql.append("  SUM(s.total_volume)      as volume, ");
        sql.append("  SUM(s.total_txns)        as txn_count, ");
        sql.append("  SUM(s.total_msf)         as msf, ");
        sql.append("  SUM(s.total_interchange) as icf, ");
        sql.append("  SUM(s.total_scheme_fee)  as sf, ");
        sql.append("  SUM(s.total_ecom_fee)    as pg, ");
        sql.append("  SUM(s.total_net_revenue) as nm, ");
        sql.append("  count(*) OVER() as total_count ");
        appendDailyMerchantFromWhere(sql, filter, search, dates, rangeStart);
        sql.append("GROUP BY m.merchant_id, st.sid, m.mid, m.name ");

        String sortExpr = DAILY_MERCHANT_SORT.getOrDefault(sort == null ? "" : sort, "SUM(s.total_volume)");
        String sortDir = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
        // Tiebreak on mid so pagination is stable when many rows share a value.
        sql.append("ORDER BY ").append(sortExpr).append(" ").append(sortDir)
           .append(" NULLS LAST, m.mid ASC, st.sid ASC ");
        boolean paged = size > 0;
        if (paged) sql.append("OFFSET :offset LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindDailyMerchantParams(query, filter, search, tenantId, dates, rangeStart, rangeEnd);
        if (paged) {
            query.setParameter("offset", (long) page * size);
            query.setParameter("limit", size);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> content = new ArrayList<>(rows.size());
        long totalElements = 0;
        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("merchantId",   row[0]);
            map.put("sid",          row[1]);
            map.put("mid",          row[2]);
            map.put("merchantName", row[3]);
            map.put("volume",       row[4]);
            map.put("count",        row[5]);
            map.put("msf",          row[6]);
            map.put("icf",          row[7]);
            map.put("sf",           row[8]);
            map.put("pg",           row[9]);
            map.put("nm",           row[10]);
            if (totalElements == 0) totalElements = ((Number) row[11]).longValue();
            content.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", content);
        response.put("totalElements", paged ? totalElements : content.size());
        return response;
    }

    /**
     * Grand totals over the SAME filtered set as {@link #getExecutiveDailyMerchant}
     * (no grouping, no pagination) — the KPI strip and the table always agree
     * because both use appendDailyMerchantFromWhere.
     */
    public Map<String, Object> getExecutiveDailyMerchantTotals(VolumeRevenueFilterDTO filter,
            List<java.time.LocalDate> dates, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd,
            String search, Long tenantId) {
        requireTenant(tenantId);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  COALESCE(SUM(s.total_volume), 0), ");
        sql.append("  COALESCE(SUM(s.total_txns), 0), ");
        sql.append("  COALESCE(SUM(s.total_msf), 0), ");
        sql.append("  COALESCE(SUM(s.total_interchange), 0), ");
        sql.append("  COALESCE(SUM(s.total_scheme_fee), 0), ");
        sql.append("  COALESCE(SUM(s.total_ecom_fee), 0), ");
        sql.append("  COALESCE(SUM(s.total_net_revenue), 0) ");
        appendDailyMerchantFromWhere(sql, filter, search, dates, rangeStart);

        Query query = entityManager.createNativeQuery(sql.toString());
        bindDailyMerchantParams(query, filter, search, tenantId, dates, rangeStart, rangeEnd);

        Object[] row = (Object[]) query.getSingleResult();
        Map<String, Object> totals = new HashMap<>();
        totals.put("volume", row[0]);
        totals.put("count",  row[1]);
        totals.put("msf",    row[2]);
        totals.put("icf",    row[3]);
        totals.put("sf",     row[4]);
        totals.put("pg",     row[5]);
        totals.put("nm",     row[6]);
        return totals;
    }

    /**
     * Per-day totals across a month window, honouring the same filters/search
     * as the table — powers the month ribbon (each loaded day is a bar whose
     * height encodes that day's volume, and which doubles as the date picker).
     */
    public List<Map<String, Object>> getExecutiveDailyMerchantTrend(VolumeRevenueFilterDTO filter,
            java.time.LocalDate monthStart, java.time.LocalDate monthEnd, String search, Long tenantId) {
        requireTenant(tenantId);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT s.business_date, ");
        sql.append("  COALESCE(SUM(s.total_volume), 0), ");
        sql.append("  COALESCE(SUM(s.total_txns), 0), ");
        sql.append("  COALESCE(SUM(s.total_net_revenue), 0) ");
        appendDailyMerchantFromWhere(sql, filter, search, null, monthStart);
        sql.append("GROUP BY s.business_date ORDER BY s.business_date");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindDailyMerchantParams(query, filter, search, tenantId, null, monthStart, monthEnd);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            Object d = r[0];
            m.put("date", d instanceof java.sql.Date sd ? sd.toLocalDate().toString() : String.valueOf(d));
            m.put("volume", r[1]);
            m.put("count", r[2]);
            m.put("nm", r[3]);
            out.add(m);
        }
        return out;
    }

    /**
     * Volume/margin mix by scheme, card type and destination for the current
     * selection — one scan via GROUPING SETS rather than three round trips.
     * Returns {scheme:[...], cardType:[...], destination:[...]}, each entry
     * {label, volume, count, nm}, biggest first.
     */
    public Map<String, List<Map<String, Object>>> getExecutiveDailyMerchantMix(VolumeRevenueFilterDTO filter,
            List<java.time.LocalDate> dates, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd,
            String search, Long tenantId, Long merchantId) {
        requireTenant(tenantId);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT GROUPING(s.card_scheme), s.card_scheme, ");
        sql.append("       GROUPING(s.card_type), s.card_type, ");
        sql.append("       GROUPING(s.destination), s.destination, ");
        sql.append("       COALESCE(SUM(s.total_volume), 0), ");
        sql.append("       COALESCE(SUM(s.total_txns), 0), ");
        sql.append("       COALESCE(SUM(s.total_net_revenue), 0) ");
        appendDailyMerchantFromWhere(sql, filter, search, dates, rangeStart);
        if (merchantId != null) sql.append("AND s.merchant_id = :merchantId ");
        sql.append("GROUP BY GROUPING SETS ((s.card_scheme), (s.card_type), (s.destination))");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindDailyMerchantParams(query, filter, search, tenantId, dates, rangeStart, rangeEnd);
        if (merchantId != null) query.setParameter("merchantId", merchantId);

        List<Map<String, Object>> scheme = new ArrayList<>();
        List<Map<String, Object>> cardType = new ArrayList<>();
        List<Map<String, Object>> destination = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] r : rows) {
            // GROUPING() is 0 for the column this grouping set actually groups by.
            List<Map<String, Object>> bucket;
            Object label;
            if (((Number) r[0]).intValue() == 0)      { bucket = scheme;      label = r[1]; }
            else if (((Number) r[2]).intValue() == 0) { bucket = cardType;    label = r[3]; }
            else if (((Number) r[4]).intValue() == 0) { bucket = destination; label = r[5]; }
            else continue;

            Map<String, Object> m = new HashMap<>();
            m.put("label", label == null ? "Unclassified" : label.toString());
            m.put("volume", r[6]);
            m.put("count", r[7]);
            m.put("nm", r[8]);
            bucket.add(m);
        }
        java.util.Comparator<Map<String, Object>> byVolumeDesc = (a, b) -> Double.compare(
                ((Number) b.getOrDefault("volume", 0)).doubleValue(),
                ((Number) a.getOrDefault("volume", 0)).doubleValue());
        scheme.sort(byVolumeDesc);
        cardType.sort(byVolumeDesc);
        destination.sort(byVolumeDesc);

        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        out.put("scheme", scheme);
        out.put("cardType", cardType);
        out.put("destination", destination);
        return out;
    }

    /** Latest N distinct loaded business dates for the tenant (date-pill row). */
    public List<java.time.LocalDate> getRecentBusinessDates(Long tenantId, int limit) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT DISTINCT business_date FROM sum_daily_full " +
                "WHERE tenant_id = :tenantId ORDER BY business_date DESC LIMIT :limit");
        query.setParameter("tenantId", tenantId);
        query.setParameter("limit", limit);
        return toLocalDates(query.getResultList());
    }

    /** Months (YYYY-MM, newest first) that actually hold loaded daily data. */
    public List<String> getBusinessMonths(Long tenantId, int limit) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT DISTINCT TO_CHAR(business_date, 'YYYY-MM') FROM sum_daily_full " +
                "WHERE tenant_id = :tenantId ORDER BY 1 DESC LIMIT :limit");
        query.setParameter("tenantId", tenantId);
        query.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        List<String> out = new ArrayList<>(rows.size());
        for (Object r : rows) if (r != null) out.add(r.toString());
        return out;
    }

    /** Every loaded business date inside one calendar month (ascending). */
    public List<java.time.LocalDate> getBusinessDatesInMonth(Long tenantId,
            java.time.LocalDate monthStart, java.time.LocalDate monthEnd) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT DISTINCT business_date FROM sum_daily_full " +
                "WHERE tenant_id = :tenantId AND business_date BETWEEN :monthStart AND :monthEnd " +
                "ORDER BY business_date");
        query.setParameter("tenantId", tenantId);
        query.setParameter("monthStart", monthStart);
        query.setParameter("monthEnd", monthEnd);
        return toLocalDates(query.getResultList());
    }

    private static List<java.time.LocalDate> toLocalDates(List<?> rows) {
        List<java.time.LocalDate> out = new ArrayList<>(rows.size());
        for (Object r : rows) {
            if (r instanceof java.sql.Date d) out.add(d.toLocalDate());
            else if (r instanceof java.time.LocalDate d) out.add(d);
        }
        return out;
    }

    /**
     * Shared FROM/WHERE for the executive daily merchant page + totals queries.
     * Date predicates hit the raw partition key (equality / IN / BETWEEN — never
     * wrapped in a function), so partition pruning and
     * idx_sum_daily_full_tenant_date always apply. Dim joins carry the tenant
     * predicate too (defence in depth on top of RLS).
     */
    private void appendDailyMerchantFromWhere(StringBuilder sql, VolumeRevenueFilterDTO filter, String search,
            List<java.time.LocalDate> dates, java.time.LocalDate rangeStart) {
        sql.append("FROM sum_daily_full s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = :tenantId ");
        if (dates != null && !dates.isEmpty()) {
            sql.append(dates.size() == 1 ? "AND s.business_date = :businessDate "
                                         : "AND s.business_date IN (:businessDates) ");
        } else if (rangeStart != null) {
            sql.append("AND s.business_date BETWEEN :rangeStart AND :rangeEnd ");
        }
        if (nonEmptyList(filter.getMccList()))         sql.append("AND s.mcc IN (:mccs) ");
        if (nonEmptyList(filter.getDestinationList())) sql.append("AND s.destination IN (:destinations) ");
        if (nonEmptyList(filter.getCardTypeList()))    sql.append("AND s.card_type IN (:cardTypes) ");
        if (nonEmptyList(filter.getSchemeList()))      sql.append("AND s.card_scheme IN (:schemes) ");
        if (nonEmptyList(filter.getRmList()))          sql.append("AND m.sales_email IN (:rms) ");
        if (search != null && !search.isBlank())
            sql.append("AND (st.sid ILIKE :search OR m.mid ILIKE :search OR m.name ILIKE :search) ");
    }

    private void bindDailyMerchantParams(Query query, VolumeRevenueFilterDTO filter,
            String search, Long tenantId,
            List<java.time.LocalDate> dates, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd) {
        query.setParameter("tenantId", tenantId);
        if (dates != null && !dates.isEmpty()) {
            if (dates.size() == 1) query.setParameter("businessDate", dates.get(0));
            else query.setParameter("businessDates", dates);
        } else if (rangeStart != null) {
            query.setParameter("rangeStart", rangeStart);
            query.setParameter("rangeEnd", rangeEnd);
        }
        if (nonEmptyList(filter.getMccList()))         query.setParameter("mccs", filter.getMccList());
        if (nonEmptyList(filter.getDestinationList())) query.setParameter("destinations", filter.getDestinationList());
        if (nonEmptyList(filter.getCardTypeList()))    query.setParameter("cardTypes", filter.getCardTypeList());
        if (nonEmptyList(filter.getSchemeList()))      query.setParameter("schemes", filter.getSchemeList());
        if (nonEmptyList(filter.getRmList()))          query.setParameter("rms", filter.getRmList());
        if (search != null && !search.isBlank())
            query.setParameter("search", "%" + search.trim() + "%");
    }

    private static boolean nonEmptyList(List<?> list) {
        return list != null && !list.isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RETENTION REPORT  (Merchant Churn + Revenue-Weighted Churn + Reactivation)
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Cheap coverage check for the retention report's auto-computed prior
     * window, so the frontend can tell "0 churn because nothing changed"
     * apart from "0 churn because the prior window has no data at all"
     * (e.g. a wide range like YTD whose prior-equal-length window falls
     * before the tenant's data started).
     *
     * Applies the exact same dimension filters (partner, MCC, RM, team
     * leader, MID, industry, merchant name, SID, channel, scheme, card
     * type, destination) as getRetentionReport() — a heavily-filtered
     * slice can legitimately have no prior activity even when the tenant
     * broadly does, and the guard needs to reflect what the user is
     * actually looking at, not the whole book.
     *
     * Mirrors the exact window math and filter clauses in
     * getRetentionReport() — must stay in sync with it.
     */
    public Map<String, Object> getRetentionReportMeta(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        java.time.LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);
        long lenDays = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        java.time.LocalDate priorEnd   = start.minusDays(1);
        java.time.LocalDate priorStart = priorEnd.minusDays(lenDays);

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT EXISTS(SELECT 1 FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) {
            sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        }
        sql.append("WHERE s.business_date >= :priorStart AND s.business_date <= :priorEnd AND s.total_volume > 0 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

        // Same dimension filters as getRetentionReport() — kept identical on purpose.
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList()))        sql.append("AND st.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getSidList()))        sql.append("AND st.sid IN (:sids) ");
        if (listNonEmpty(filter.getChannelList()))     sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))      sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getCardTypeList()))    sql.append("AND s.card_type IN (:cardTypes) ");
        if (listNonEmpty(filter.getDestinationList())) sql.append("AND s.destination IN (:destinations) ");
        sql.append(")");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("priorStart", priorStart);
        q.setParameter("priorEnd", priorEnd);
        if (tenantId != null) q.setParameter("tenantId", tenantId);

        if (listNonEmpty(filter.getPartnerList()))    q.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         q.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        q.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   q.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            q.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        q.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        q.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))     q.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))      q.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getCardTypeList()))    q.setParameter("cardTypes", filter.getCardTypeList());
        if (listNonEmpty(filter.getDestinationList())) q.setParameter("destinations", filter.getDestinationList());

        boolean priorHasData = Boolean.TRUE.equals(q.getSingleResult());

        Map<String, Object> meta = new HashMap<>();
        meta.put("currentStart", start.toString());
        meta.put("currentEnd", end.toString());
        meta.put("priorStart", priorStart.toString());
        meta.put("priorEnd", priorEnd.toString());
        meta.put("priorWindowHasData", priorHasData);

        // ── True reactivation denominator ──────────────────────────────────
        // The per-row payload can't express a genuine "reactivation rate": it
        // drops merchants silent in BOTH windows, so the population that was
        // dormant entering the current window isn't fully visible client-side.
        // Compute it here: merchants that (a) had some history strictly BEFORE
        // the prior window, AND (b) were silent in the prior window itself. Of
        // those, the ones now transacting are true reactivations. We emit the
        // base count so the frontend can show reactivated / dormantEnteringBase
        // as an honest rate (and fall back to the win-back proxy when null).
        try {
            StringBuilder dSql = new StringBuilder();
            dSql.append("SELECT COUNT(*) FROM ( ");
            dSql.append("  SELECT m.merchant_id ");
            dSql.append("  FROM dim_merchant m ");
            dSql.append("  WHERE 1=1 ");
            if (tenantId != null) dSql.append("AND m.tenant_id = :tenantId ");
            appendMerchantDimFilters(dSql, filter);
            // Had history before the prior window … capped to a 12-month
            // lookback so this EXISTS scan prunes to at most ~one extra year of
            // partitions per merchant instead of the entire history. A merchant
            // dormant for >12 months reads more like a lapsed/new relationship
            // than a reactivation candidate, so the cap also tightens the
            // definition rather than only bounding cost.
            java.time.LocalDate lookbackStart = priorStart.minusMonths(12);
            dSql.append("  AND EXISTS (SELECT 1 FROM sum_daily_insight s2 ");
            dSql.append("       WHERE s2.merchant_id = m.merchant_id AND s2.tenant_id = m.tenant_id ");
            dSql.append("         AND s2.business_date >= :lookbackStart AND s2.business_date < :priorStart AND s2.total_volume > 0");
            appendInsightDimFilters(dSql, filter, "s2");
            dSql.append("  ) ");
            // … but silent in the prior window.
            dSql.append("  AND NOT EXISTS (SELECT 1 FROM sum_daily_insight s3 ");
            dSql.append("       WHERE s3.merchant_id = m.merchant_id AND s3.tenant_id = m.tenant_id ");
            dSql.append("         AND s3.business_date >= :priorStart AND s3.business_date <= :priorEnd AND s3.total_volume > 0");
            appendInsightDimFilters(dSql, filter, "s3");
            dSql.append("  ) ");
            dSql.append(") dormant_base");

            Query dq = entityManager.createNativeQuery(dSql.toString());
            dq.setParameter("priorStart", priorStart);
            dq.setParameter("priorEnd", priorEnd);
            dq.setParameter("lookbackStart", lookbackStart);
            if (tenantId != null) dq.setParameter("tenantId", tenantId);
            bindMerchantDimFilters(dq, filter);
            bindInsightDimFilters(dq, filter);
            long dormantBase = lng(dq.getSingleResult());
            meta.put("dormantEnteringBase", dormantBase);
        } catch (Exception e) {
            // Non-fatal: frontend falls back to the win-back proxy if absent.
            meta.put("dormantEnteringBase", null);
        }
        return meta;
    }

    // ── Shared filter appenders/binders for the retention queries ──────────
    // Extracted so the meta reactivation query reuses the EXACT same clauses as
    // getRetentionReport()/getRetentionReportMeta() without triplicating them.
    private void appendMerchantDimFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
    }

    private void appendInsightDimFilters(StringBuilder sql, VolumeRevenueFilterDTO filter, String alias) {
        if (listNonEmpty(filter.getChannelList()))     sql.append(" AND ").append(alias).append(".channel IN (:channels)");
        if (listNonEmpty(filter.getSchemeList()))      sql.append(" AND ").append(alias).append(".card_scheme IN (:schemes)");
        if (listNonEmpty(filter.getCardTypeList()))    sql.append(" AND ").append(alias).append(".card_type IN (:cardTypes)");
        if (listNonEmpty(filter.getDestinationList())) sql.append(" AND ").append(alias).append(".destination IN (:destinations)");
        // Note: MCC/SID are store-dimension and not applied inside these
        // EXISTS sub-scans; the outer dormant query is merchant-grained, so a
        // store-level filter would need a dim_store join per sub-scan. Kept out
        // deliberately to match the merchant-grained reactivation definition.
    }

    private void bindMerchantDimFilters(Query q, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    q.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         q.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        q.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   q.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            q.setParameter("merchName", "%" + filter.getMerchantName() + "%");
    }

    private void bindInsightDimFilters(Query q, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getChannelList()))     q.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))      q.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getCardTypeList()))    q.setParameter("cardTypes", filter.getCardTypeList());
        if (listNonEmpty(filter.getDestinationList())) q.setParameter("destinations", filter.getDestinationList());
    }

    /**
     * Per-merchant retention classification over two equal-length, back-to-back
     * windows, plus the roll-up KPIs the Retention page needs.
     *
     * Windows (both equal length so the comparison is apples-to-apples):
     *   current : [start, end]                     — the selected range
     *   prior   : [start - len, start - 1 day]     — the window immediately before it
     * where len = (end - start) in days.
     *
     * Per merchant we emit current/prior volume, txns and MSF, plus a status:
     *   CHURNED     — transacted in the prior window, silent in the current window
     *   REACTIVATED — silent in the prior window, transacting again in the current window
     *   RETAINED    — active in both windows
     *   NEW         — first seen in the current window (onboarded in-range, no prior activity)
     *
     * [TENANCY] Every join is tenant-scoped (dX.tenant_id = s.tenant_id) and the
     * base scan carries `AND s.tenant_id = :tenantId`, matching the P2-1 pattern
     * used across this repository.
     */
    public List<Map<String, Object>> getRetentionReport(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        java.time.LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);

        // Guard against an inverted range (start after end). A negative length
        // would push the prior window into nonsense (prior end AFTER prior start)
        // and produce garbage classifications; normalise by swapping so the
        // report degrades to a valid same-length comparison instead.
        if (start.isAfter(end)) { java.time.LocalDate t = start; start = end; end = t; }

        // Equal-length prior window immediately preceding the current one.
        long lenDays = java.time.temporal.ChronoUnit.DAYS.between(start, end); // inclusive length - 1
        java.time.LocalDate priorEnd   = start.minusDays(1);
        java.time.LocalDate priorStart = priorEnd.minusDays(lenDays);

        // Lower bound for partition pruning — earliest date any window reads.
        java.time.LocalDate globalLowerBound = priorStart;

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        StringBuilder sql = new StringBuilder();
        // merchant_id first so the frontend has a stable, globally-unique row key
        // (duplicate MIDs from re-onboarded merchants otherwise collide in the grid).
        sql.append("SELECT m.merchant_id AS merchant_id, m.mid AS mid, m.name AS merchant_name, m.created_date AS created_date, ");
        // Two windows x (vol, txn, msf)
        appendWindowMeasures(sql, "cur",   ":startDate",      ":endDate");
        appendWindowMeasures(sql, "prior", ":priorStartDate", ":priorEndDate");
        sql.setLength(sql.length() - 2); // strip trailing comma
        sql.append(" FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) {
            sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        }

        sql.append("WHERE s.business_date >= :globalLowerBound AND s.business_date <= :endDate ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

        // Merchant-dimension filters
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        // Store-dimension filters
        if (listNonEmpty(filter.getMccList()))        sql.append("AND st.mcc IN (:mccs) ");
        if (listNonEmpty(filter.getSidList()))        sql.append("AND st.sid IN (:sids) ");
        // Insight-dimension filters
        if (listNonEmpty(filter.getChannelList()))     sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))      sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getCardTypeList()))    sql.append("AND s.card_type IN (:cardTypes) ");
        if (listNonEmpty(filter.getDestinationList())) sql.append("AND s.destination IN (:destinations) ");

        sql.append("GROUP BY m.merchant_id, m.mid, m.name, m.created_date ORDER BY m.mid ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("startDate", start);
        query.setParameter("endDate", end);
        query.setParameter("priorStartDate", priorStart);
        query.setParameter("priorEndDate", priorEnd);
        query.setParameter("globalLowerBound", globalLowerBound);
        if (tenantId != null) query.setParameter("tenantId", tenantId);

        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   query.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))     query.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))      query.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getCardTypeList()))    query.setParameter("cardTypes", filter.getCardTypeList());
        if (listNonEmpty(filter.getDestinationList())) query.setParameter("destinations", filter.getDestinationList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            // Column order: merchant_id, mid, name, created_date, then cur(vol,txn,msf), prior(vol,txn,msf)
            java.math.BigDecimal curVol   = bd(row[4]),  priorVol  = bd(row[7]);
            long                 curTxn   = lng(row[5]), priorTxn  = lng(row[8]);
            java.math.BigDecimal curMsf   = bd(row[6]),  priorMsf  = bd(row[9]);

            // Drop merchants with no activity in EITHER window — noise, never retention signal.
            // "Activity" = volume OR transactions, so a merchant with only
            // zero-value/refund txns (volume 0 but txns > 0) still counts as
            // present rather than being mis-flagged as churned/silent.
            boolean activeNow   = !isZero(curVol)   || curTxn   > 0;
            boolean activePrior = !isZero(priorVol) || priorTxn > 0;
            if (!activeNow && !activePrior) continue;

            String status = classifyRetention(activeNow, activePrior, row[3], start, end);

            Map<String, Object> map = new HashMap<>();
            map.put("merchant_id", row[0]);
            map.put("mid", row[1]);
            map.put("name", row[2]);
            map.put("createdDate", row[3] != null ? row[3].toString() : null);

            map.put("cur_volume", curVol);
            map.put("prior_volume", priorVol);
            map.put("volume_pct", calculateGrowth(curVol.doubleValue(), priorVol.doubleValue()));
            map.put("cur_txns", curTxn);
            map.put("prior_txns", priorTxn);
            map.put("txns_pct", calculateGrowth(curTxn, priorTxn));
            map.put("cur_msf", curMsf);
            map.put("prior_msf", priorMsf);
            map.put("msf_pct", calculateGrowth(curMsf.doubleValue(), priorMsf.doubleValue()));
            // Revenue lost to churn = prior-window MSF of a merchant now silent.
            map.put("lost_msf", "CHURNED".equals(status) ? priorMsf : java.math.BigDecimal.ZERO);

            map.put("status", status);
            result.add(map);
        }

        // Churned first (biggest retention concern), then reactivated, then the rest.
        result.sort((a, b) -> retentionRank((String) a.get("status")) - retentionRank((String) b.get("status")));

        return result;
    }

    /**
     * RETAINED / CHURNED / REACTIVATED / NEW from two-window activity flags.
     * NEW wins over REACTIVATED only when the merchant was onboarded inside the
     * current window (created_date within [start, end]); otherwise a merchant
     * that was silent-then-active is a genuine REACTIVATION.
     */
    private String classifyRetention(boolean activeNow, boolean activePrior,
                                     Object createdDateObj,
                                     java.time.LocalDate start, java.time.LocalDate end) {
        if (activeNow && activePrior)  return "RETAINED";
        if (!activeNow && activePrior) return "CHURNED";
        // activeNow && !activePrior  → either brand-new or reactivated
        java.time.LocalDate created = parseDate(createdDateObj);
        boolean onboardedInWindow = created != null && !created.isBefore(start) && !created.isAfter(end);
        return onboardedInWindow ? "NEW" : "REACTIVATED";
    }

    /** Sort weight: churned first, then reactivated, retained, new. */
    private int retentionRank(String status) {
        if (status == null) return 9;
        switch (status) {
            case "CHURNED":     return 0;
            case "REACTIVATED": return 1;
            case "RETAINED":    return 2;
            case "NEW":         return 3;
            default:            return 9;
        }
    }

    private static java.time.LocalDate parseDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date)        return ((java.sql.Date) o).toLocalDate();
        if (o instanceof java.time.LocalDate)  return (java.time.LocalDate) o;
        if (o instanceof java.sql.Timestamp)   return ((java.sql.Timestamp) o).toLocalDateTime().toLocalDate();
        try { return java.time.LocalDate.parse(o.toString().substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
