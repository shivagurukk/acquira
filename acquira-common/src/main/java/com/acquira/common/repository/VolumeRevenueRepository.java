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

    public List<Map<String, Object>> getSummary(VolumeRevenueFilterDTO filter) {
        return getSummary(filter, null);
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null we add
     * `AND s.tenant_id = :tenantId` so cross-tenant rows can never appear in
     * the volume/revenue summary.
     */
    public List<Map<String, Object>> getSummary(VolumeRevenueFilterDTO filter, Long tenantId) {
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
                ? "TO_CHAR(TO_DATE(s.month_key::text, 'YYYYMM'), 'YYYY-MM')"
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
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as opt_in_volume ");
        sql.append("FROM ").append(BASE_TABLE).append(" ");
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        // sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id "); // Use if
        // Store filters needed

        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
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
            result.add(map);
        }

        return result;
    }

    public List<Map<String, Object>> getMerchantFinancialSummary(VolumeRevenueFilterDTO filter) {
        return getMerchantFinancialSummary(filter, null);
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so the per-MID/SID
     * financial summary cannot show rows belonging to other tenants.
     */
    public List<Map<String, Object>> getMerchantFinancialSummary(VolumeRevenueFilterDTO filter, Long tenantId) {
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
            sql.append("AND m.created_date >= :openStart ");
        if (filter.getOpenDateEnd() != null)
            sql.append("AND m.created_date <= :openEnd ");

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

    public List<Map<String, Object>> getPerformanceDashboardData(VolumeRevenueFilterDTO filter, String groupBy,
            String parentValue, String grandParentValue) {
        return getPerformanceDashboardData(filter, groupBy, parentValue, grandParentValue, null);
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so drill-down
     * tables across MONTH/DAY/MERCHANT/STORE granularities never mix tenants.
     */
    public List<Map<String, Object>> getPerformanceDashboardData(VolumeRevenueFilterDTO filter, String groupBy,
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
        }

        // Pivoted Columns (Dom Debit, Dom Credit, Intl, Total)
        // Dom Debit
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'DEBIT' THEN s.total_txns ELSE 0 END) as dom_debit_cnt, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'DEBIT' THEN s.total_volume ELSE 0 END) as dom_debit_vol, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'DEBIT' THEN s.total_msf ELSE 0 END) as dom_debit_msf, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'DEBIT' AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_debit_optin, ");

        // Dom Credit
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'CREDIT' THEN s.total_txns ELSE 0 END) as dom_credit_cnt, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'CREDIT' THEN s.total_volume ELSE 0 END) as dom_credit_vol, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'CREDIT' THEN s.total_msf ELSE 0 END) as dom_credit_msf, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'DOMESTIC' AND s.card_type = 'CREDIT' AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as dom_credit_optin, ");

        // Intl (All Card Types)
        sql.append(" SUM(CASE WHEN s.destination = 'INTERNATIONAL' THEN s.total_txns ELSE 0 END) as int_cnt, ");
        sql.append(" SUM(CASE WHEN s.destination = 'INTERNATIONAL' THEN s.total_volume ELSE 0 END) as int_vol, ");
        sql.append(" SUM(CASE WHEN s.destination = 'INTERNATIONAL' THEN s.total_msf ELSE 0 END) as int_msf, ");
        sql.append(
                " SUM(CASE WHEN s.destination = 'INTERNATIONAL' AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as int_optin, ");

        // Total
        sql.append(" SUM(s.total_volume) as total_vol, ");
        sql.append(" SUM(s.total_msf) as total_msf, ");

        // Extra Context Columns (Index 16)
        if ("MERCHANT".equals(groupBy)) {
            sql.append(" m.name as merchant_name ");
        } else {
            sql.append(" CAST(NULL as text) as merchant_name ");
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
        List<Map<String, Object>> result = new ArrayList<>();

        // Debug: log column count from first row to catch index mismatches early
        if (!rows.isEmpty()) {
            System.out.println("[PerformanceDashboard] groupBy=" + groupBy + ", columns=" + rows.get(0).length);
        }

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

            result.add(map);
        }

        return result;
    }

    public Map<String, List<String>> getFilterOptions() {
        return getFilterOptions(null);
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
     */
    public Map<String, List<String>> getFilterOptions(Long tenantId) {
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

            // Destinations
            Query qDest = entityManager.createNativeQuery(
                    "SELECT DISTINCT destination FROM sum_daily_insight WHERE destination IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qDest.setParameter("tid", tenantId);
            options.put("destinations", qDest.getResultList());

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

            // Schemes
            Query qScheme = entityManager.createNativeQuery(
                    "SELECT DISTINCT card_scheme FROM sum_daily_insight WHERE card_scheme IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qScheme.setParameter("tid", tenantId);
            options.put("schemes", qScheme.getResultList());

            // Card Types
            Query qCardType = entityManager.createNativeQuery(
                    "SELECT DISTINCT card_type FROM sum_daily_insight WHERE card_type IS NOT NULL " +
                    (tenantId != null ? "AND tenant_id = :tid " : "") + "ORDER BY 1");
            if (tenantId != null) qCardType.setParameter("tid", tenantId);
            options.put("cardTypes", qCardType.getResultList());

        } catch (Exception e) {
            e.printStackTrace();
        }

        return options;
    }

    public List<Map<String, Object>> getDebitPrepaidMetrics(VolumeRevenueFilterDTO filter) {
        return getDebitPrepaidMetrics(filter, null);
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
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  st2.sid as sid, ");
        sql.append("  m.name as merchant_name, ");
        sql.append("  SUM(s.total_txns) as count, ");
        sql.append("  SUM(s.total_volume) as volume ");
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
            map.put("count", row[3]);
            map.put("volume", row[4]);
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

    public List<Map<String, Object>> getAttritionReport(VolumeRevenueFilterDTO filter) {
        return getAttritionReport(filter, null);
    }

    /**
     * Tenant-scoped variant. Adds `AND s.tenant_id = :tenantId` so attrition
     * comparisons never include other tenants' merchants.
     */
    public List<Map<String, Object>> getAttritionReport(VolumeRevenueFilterDTO filter, Long tenantId) {
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

        // Earliest date any window reads — used as the WHERE lower bound for partition pruning.
        java.time.LocalDate globalLowerBound = prevYtdStart.isBefore(momStart) ? prevYtdStart : momStart;

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid AS mid, m.name AS merchant_name, ");
        // For each window emit volume / txns / msf so the UI can toggle metric without re-querying.
        appendWindowMeasures(sql, "mtdcur", ":startDate",       ":endDate");
        appendWindowMeasures(sql, "mtdprev", ":prevStartDate",  ":prevEndDate");
        appendWindowMeasures(sql, "ytdcur", ":ytdStartDate",    ":endDate");
        appendWindowMeasures(sql, "ytdprev", ":prevYtdStartDate", ":prevEndDate");
        appendWindowMeasures(sql, "momprev", ":momStartDate",   ":momEndDate");
        // strip trailing comma
        sql.setLength(sql.length() - 2);
        sql.append(" FROM sum_daily_insight s ");
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

            // Drop merchants with no activity in ANY window — pure noise, never attrition.
            if (isZero(mtdVol) && isZero(mtdVolP) && isZero(ytdVol) && isZero(ytdVolP) && isZero(momVol))
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

            // ── Attrition status (classified on YTD volume, the most stable signal) ──
            map.put("status", classifyAttrition(ytdVol, ytdVolP, ytdPctVol));

            result.add(map);
        }

        // Worst decliners (and churned, at ~ -100%) first — the point of an attrition report.
        result.sort((a, b) -> Double.compare(
                ((Number) a.getOrDefault("ytd_pct", 0d)).doubleValue(),
                ((Number) b.getOrDefault("ytd_pct", 0d)).doubleValue()));

        return result;
    }

    /** Emits "SUM(CASE WHEN business_date in window THEN <col> ELSE 0 END) AS <alias>_x, " for vol/txn/msf. */
    private void appendWindowMeasures(StringBuilder sql, String alias, String from, String to) {
        String when = "CASE WHEN s.business_date >= " + from + " AND s.business_date <= " + to + " THEN ";
        sql.append("SUM(").append(when).append("s.total_volume ELSE 0 END) AS ").append(alias).append("_vol, ");
        sql.append("SUM(").append(when).append("s.total_txns   ELSE 0 END) AS ").append(alias).append("_txn, ");
        sql.append("SUM(").append(when).append("s.total_msf    ELSE 0 END) AS ").append(alias).append("_msf, ");
    }

    /** CHURNED / AT_RISK / DECLINING / STABLE / GROWING based on YTD volume trend. */
    private String classifyAttrition(java.math.BigDecimal cur, java.math.BigDecimal prev, double pct) {
        boolean hadHistory = prev != null && prev.signum() > 0;
        boolean activeNow  = cur  != null && cur.signum()  > 0;
        if (hadHistory && !activeNow) return "CHURNED";   // was trading last year, nothing this year
        if (pct <= -50.0) return "AT_RISK";
        if (pct <= -10.0) return "DECLINING";
        if (pct <=  10.0) return "STABLE";
        return "GROWING";
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

    public Map<String, Object> getExecutiveMetrics(VolumeRevenueFilterDTO filter) {
        return getExecutiveMetrics(filter, null);
    }

    /**
     * Tenant-scoped variant. Adds `AND tenant_id = :tenantId` so executive
     * KPIs (volume, txns, active merchants) are scoped to the requesting tenant.
     */
    public Map<String, Object> getExecutiveMetrics(VolumeRevenueFilterDTO filter, Long tenantId) {
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
        } else {
            metrics.put("totalVolume", 0);
            metrics.put("volumeGrowth", 0);
            metrics.put("totalTxns", 0);
            metrics.put("txnsGrowth", 0);
            metrics.put("activeMerchants", 0);
            metrics.put("merchantsGrowth", 0);
        }

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

    public Map<String, Object> getMerchantAnalyticsReport(VolumeRevenueFilterDTO filter, int page, int size) {
        return getMerchantAnalyticsReport(filter, page, size, null);
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null, the SQL adds
     * `AND s.tenant_id = :tenantId` so cross-tenant rows can never leak.
     * In single-tenant deployments this is a no-op.
     */
    public Map<String, Object> getMerchantAnalyticsReport(VolumeRevenueFilterDTO filter, int page, int size, Long tenantId) {
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
        // FIX: derive industry from MCC using ISO 18245 range bands instead of hardcoded 'Retail'
        sql.append("  CASE ");
        sql.append("    WHEN st.mcc IS NULL THEN 'Other' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 1    AND 1499 THEN 'Agriculture' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 1500 AND 2999 THEN 'Construction' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 3000 AND 3299 THEN 'Airlines & Travel' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 3300 AND 3499 THEN 'Car Rental' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 3500 AND 3999 THEN 'Lodging & Hotels' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 4000 AND 4799 THEN 'Transportation' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 4800 AND 4999 THEN 'Utilities & Telecom' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 5000 AND 5199 THEN 'Wholesale' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 5200 AND 5999 THEN 'Retail' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 5800 AND 5814 THEN 'Food & Beverage' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 6000 AND 6599 THEN 'Financial Services' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 7000 AND 7299 THEN 'Business Services' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 7300 AND 7999 THEN 'Entertainment' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 8000 AND 8099 THEN 'Healthcare' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 8100 AND 8299 THEN 'Professional Services' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 8300 AND 8999 THEN 'Education' ");
        sql.append("    WHEN CAST(st.mcc AS INTEGER) BETWEEN 9000 AND 9999 THEN 'Government' ");
        sql.append("    ELSE 'Other' ");
        sql.append("  END as industry, ");
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

        sql.append("WHERE 1=1 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");

        if (filter.getStartDate() != null)
            sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null)
            sql.append("AND s.business_date <= :endDate ");

        // preciseDateList handling
        if (filter.getPreciseDateList() != null && !filter.getPreciseDateList().isEmpty()) {
            sql.append("AND TO_CHAR(s.business_date, 'YYYY-MM-DD') IN (:preciseDates) ");
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
        sql.append("GROUP BY m.merchant_id, st.sid, m.mid, m.name, st.mcc ");

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
            query.setParameter("preciseDates", filter.getPreciseDateList());

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
    //  RETENTION REPORT  (Merchant Churn + Revenue-Weighted Churn + Reactivation)
    // ════════════════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getRetentionReport(VolumeRevenueFilterDTO filter) {
        return getRetentionReport(filter, null);
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
        java.time.LocalDate end   = filter.getEndDate()   != null ? filter.getEndDate()   : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);

        // Equal-length prior window immediately preceding the current one.
        long lenDays = java.time.temporal.ChronoUnit.DAYS.between(start, end); // inclusive length - 1
        java.time.LocalDate priorEnd   = start.minusDays(1);
        java.time.LocalDate priorStart = priorEnd.minusDays(lenDays);

        // Lower bound for partition pruning — earliest date any window reads.
        java.time.LocalDate globalLowerBound = priorStart;

        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid AS mid, m.name AS merchant_name, m.created_date AS created_date, ");
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

        sql.append("GROUP BY m.mid, m.name, m.created_date ORDER BY m.mid ASC");

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
            // Column order: mid, name, created_date, then cur(vol,txn,msf), prior(vol,txn,msf)
            java.math.BigDecimal curVol   = bd(row[3]),  priorVol  = bd(row[6]);
            long                 curTxn   = lng(row[4]), priorTxn  = lng(row[7]);
            java.math.BigDecimal curMsf   = bd(row[5]),  priorMsf  = bd(row[8]);

            // Drop merchants with no activity in EITHER window — noise, never retention signal.
            if (isZero(curVol) && isZero(priorVol)) continue;

            boolean activeNow   = !isZero(curVol);
            boolean activePrior = !isZero(priorVol);

            String status = classifyRetention(activeNow, activePrior, row[2], start, end);

            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("name", row[1]);
            map.put("createdDate", row[2] != null ? row[2].toString() : null);

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
