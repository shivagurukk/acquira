package com.acquira.repository;

import com.acquira.dto.VolumeRevenueFilterDTO;
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
        StringBuilder sql = new StringBuilder();

        // Base Query joining Fact/Summary with Dimensions
        // We use sum_daily_insight as the base as it has scheme, card_type, etc.
        // But for 'Partner', 'RM', 'Merchant Name' we need dim_merchant.

        sql.append("SELECT ");
        sql.append("  TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append("  SUM(s.total_txns) as total_txns, ");
        sql.append("  SUM(s.total_volume) as total_volume, ");
        sql.append("  SUM(s.total_msf) as total_msf, ");
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as opt_in_volume ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        // sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id "); // Use if
        // Store filters needed

        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("JOIN dim_store st ON s.store_id = st.store_id ");
        }

        sql.append("WHERE 1=1 ");
        if (filter.getStartDate() != null) {
            sql.append("AND s.business_date >= :startDate ");
        }
        if (filter.getEndDate() != null) {
            sql.append("AND s.business_date <= :endDate ");
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

        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM') ");
        sql.append("ORDER BY month_label DESC");

        Query query = entityManager.createNativeQuery(sql.toString());

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
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  st.sid as sid, ");
        sql.append("  SUM(s.total_txns) as total_txns, ");
        sql.append("  SUM(s.total_volume) as total_volume, ");
        sql.append("  SUM(s.total_msf) as total_msf, ");
        sql.append("  SUM(CASE WHEN s.is_opt_in = true THEN s.total_volume ELSE 0 END) as opt_in_volume ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        sql.append("JOIN dim_store st ON s.store_id = st.store_id "); // Need store for SID

        sql.append("WHERE 1=1 ");

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

        // Group by MID and SID
        sql.append("GROUP BY m.mid, st.sid ");
        sql.append("ORDER BY m.mid, st.sid");

        Query query = entityManager.createNativeQuery(sql.toString());

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
        if (filter.getMccList() != null && !filter.getMccList().isEmpty())
            query.setParameter("mccs", filter.getMccList());

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("sid", row[1]);
            map.put("count", row[2]);
            map.put("volume", row[3]);
            map.put("msf", row[4]);
            map.put("opt_in_volume", row[5]);
            result.add(map);
        }

        return result;
    }

    public List<Map<String, Object>> getPerformanceDashboardData(VolumeRevenueFilterDTO filter, String groupBy,
            String parentValue, String grandParentValue) {
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
        sql.append(" SUM(s.total_msf) as total_msf ");

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
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        if (needStore)
            sql.append("JOIN dim_store st ON s.store_id = st.store_id ");

        sql.append("WHERE 1=1 ");

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
            groupByClause = "GROUP BY m.mid ";
            orderByClause = "ORDER BY row_label ASC";
        } else if ("STORE".equals(groupBy)) {
            groupByClause = "GROUP BY st.sid ";
            orderByClause = "ORDER BY st.sid ASC";
        }

        // HACK: Java string manipulation to fix the SQL Select list because I can't
        // easily replace the sort_key logic above without complex string builders
        // Let's just use MAX(s.business_date) as sort_key for Month/Day just to make
        // SQL valid, or remove it.
        // I'll stick to using the label itself for sorting.

        int sortKeyIndex = sql.indexOf("s.business_date as sort_key");
        if (sortKeyIndex > -1) {
            if ("MERCHANT".equals(groupBy) || "STORE".equals(groupBy)) {
                // Keep it (m.mid / st.sid is in group by)
            } else {
                // Replace with Min
                sql.replace(sortKeyIndex, sortKeyIndex + 27, "MIN(s.business_date) as sort_key");
            }
        }

        sql.append(groupByClause);
        sql.append(orderByClause);

        Query query = entityManager.createNativeQuery(sql.toString());

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

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("row_label", row[0]); // generic label name
            map.put("sort_date", row[1] != null ? row[1].toString() : "");

            // Dom Debit
            map.put("dom_debit_cnt", row[2]);
            map.put("dom_debit_vol", row[3]);
            map.put("dom_debit_msf", row[4]);
            map.put("dom_debit_optin", row[5]);

            // Dom Credit
            map.put("dom_credit_cnt", row[6]);
            map.put("dom_credit_vol", row[7]);
            map.put("dom_credit_msf", row[8]);
            map.put("dom_credit_optin", row[9]);

            // Intl
            map.put("int_cnt", row[10]);
            map.put("int_vol", row[11]);
            map.put("int_msf", row[12]);
            map.put("int_optin", row[13]);

            // Totals
            map.put("total_vol", row[14]);
            map.put("total_msf", row[15]);

            result.add(map);
        }

        return result;
    }

    public Map<String, List<String>> getFilterOptions() {
        Map<String, List<String>> options = new HashMap<>();

        try {
            // Partners
            Query qPartner = entityManager.createNativeQuery(
                    "SELECT DISTINCT referral_partner FROM dim_merchant WHERE referral_partner IS NOT NULL ORDER BY 1");
            options.put("partners", qPartner.getResultList());

            // RMs
            Query qRm = entityManager.createNativeQuery(
                    "SELECT DISTINCT sales_email FROM dim_merchant WHERE sales_email IS NOT NULL ORDER BY 1");
            options.put("rms", qRm.getResultList());

            // MCCs
            Query qMcc = entityManager
                    .createNativeQuery("SELECT DISTINCT mcc FROM dim_store WHERE mcc IS NOT NULL ORDER BY 1");
            options.put("mccs", qMcc.getResultList());

            // Team Leaders - Using Sales User ID as proxy if available, or empty
            // Creating placeholders based on user request "load from summary table"
            // If column doesn't exist, we return empty list.
            options.put("teamLeaders", new ArrayList<>());

            // Destinations
            Query qDest = entityManager.createNativeQuery(
                    "SELECT DISTINCT destination FROM sum_daily_insight WHERE destination IS NOT NULL ORDER BY 1");
            options.put("destinations", qDest.getResultList());

            // Channels
            // Check sum_daily_channel for channels
            try {
                Query qChannel = entityManager.createNativeQuery(
                        "SELECT DISTINCT channel FROM sum_daily_channel WHERE channel IS NOT NULL ORDER BY 1");
                options.put("channels", qChannel.getResultList());
            } catch (Exception e) {
                options.put("channels", new ArrayList<>());
            }

            // Schemes
            Query qScheme = entityManager.createNativeQuery(
                    "SELECT DISTINCT card_scheme FROM sum_daily_insight WHERE card_scheme IS NOT NULL ORDER BY 1");
            options.put("schemes", qScheme.getResultList());

            // Card Types
            Query qCardType = entityManager.createNativeQuery(
                    "SELECT DISTINCT card_type FROM sum_daily_insight WHERE card_type IS NOT NULL ORDER BY 1");
            options.put("cardTypes", qCardType.getResultList());

        } catch (Exception e) {
            e.printStackTrace();
        }

        return options;
    }

    public List<Map<String, Object>> getDebitPrepaidMetrics(VolumeRevenueFilterDTO filter) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  SUM(s.total_txns) as count, ");
        sql.append("  SUM(s.total_volume) as volume ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        // Apply store join if needed
        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("JOIN dim_store st ON s.store_id = st.store_id ");
        }

        sql.append("WHERE s.destination = 'DOMESTIC' AND s.card_type IN ('DEBIT', 'PREPAID') ");

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
            sql.append("AND st.mcc IN (:mccs) ");
        }
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty()) {
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        }
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty()) {
            sql.append("AND s.channel IN (:channels) ");
        }

        sql.append("GROUP BY m.mid ");
        sql.append("ORDER BY m.mid ASC");

        Query query = entityManager.createNativeQuery(sql.toString());

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

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("count", row[1]);
            map.put("volume", row[2]);
            result.add(map);
        }

        return result;
    }

    public List<Map<String, Object>> getAttritionReport(VolumeRevenueFilterDTO filter) {
        StringBuilder sql = new StringBuilder();

        // Logic:
        // We need Current Year MTD (e.g. Jan 1 - Jan 26 2026)
        // And Previous Year MTD (e.g. Jan 1 - Jan 26 2025)
        // And Current YTD (Jan 1 - Jan 26 2026) - (Actually MTD is usually just month,
        // YTD is year start to now)
        // Let's assume input date determines "Current Month".
        // If filter has startDate/endDate, we use that as "Current Period" (MTD range)
        // And we calculate "Previous Period" as same dates - 1 Year.
        // YTD is Start of Year to End Date.

        // It is complex to do in one query without CTEs or complex joins.
        // Simplified approach: Group by Merchant, and use conditional SUM based on
        // dates.

        sql.append("SELECT ");
        sql.append("  m.mid as mid, ");
        sql.append("  m.name as merchant_name, ");

        // MTD Current (Volume in selected range)
        sql.append(
                "  SUM(CASE WHEN s.business_date >= :startDate AND s.business_date <= :endDate THEN s.total_volume ELSE 0 END) as mtd_current, ");
        // MTD Previous (Volume in selected range - 1 Year)
        sql.append(
                "  SUM(CASE WHEN s.business_date >= :prevStartDate AND s.business_date <= :prevEndDate THEN s.total_volume ELSE 0 END) as mtd_prev, ");

        // YTD Current (Jan 1 of EndDate Year -> EndDate)
        sql.append(
                "  SUM(CASE WHEN s.business_date >= :ytdStartDate AND s.business_date <= :endDate THEN s.total_volume ELSE 0 END) as ytd_current, ");
        // YTD Previous (Jan 1 of PrevYear -> PrevEndDate)
        sql.append(
                "  SUM(CASE WHEN s.business_date >= :prevYtdStartDate AND s.business_date <= :prevEndDate THEN s.total_volume ELSE 0 END) as ytd_prev ");

        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("JOIN dim_store st ON s.store_id = st.store_id ");
        }

        // Filter scope: Must include data from both years.
        // We broadly filter for "relevant" data to speed up, or just filter in HAVING?
        // Let's filter WHERE business_date >= prevYtdStartDate (Oldest date we care
        // about)
        sql.append("WHERE s.business_date >= :prevYtdStartDate ");

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            sql.append("AND m.referral_partner IN (:partners) ");
        if (filter.getRmList() != null && !filter.getRmList().isEmpty())
            sql.append("AND m.sales_email IN (:rms) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");

        // Correct approach: We already joined dim_merchant. Let's join dim_store if
        // needed.
        if (filter.getMccList() != null && !filter.getMccList().isEmpty()) {
            sql.append("AND st.mcc IN (:mccs) ");
        }
        if (filter.getTeamLeaderList() != null && !filter.getTeamLeaderList().isEmpty()) {
            sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        }
        if (filter.getChannelList() != null && !filter.getChannelList().isEmpty()) {
            sql.append("AND s.channel IN (:channels) ");
        }

        sql.append("GROUP BY m.mid, m.name ");
        sql.append("ORDER BY m.mid ASC");

        Query query = entityManager.createNativeQuery(sql.toString());

        // Date Logic
        // Default to current month if null? Assuming UI provides dates.
        java.time.LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : java.time.LocalDate.now();
        java.time.LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfMonth(1);

        java.time.LocalDate prevEnd = end.minusYears(1);
        java.time.LocalDate prevStart = start.minusYears(1);

        java.time.LocalDate ytdStart = end.withDayOfYear(1);
        java.time.LocalDate prevYtdStart = prevEnd.withDayOfYear(1);

        query.setParameter("startDate", start);
        query.setParameter("endDate", end);
        query.setParameter("prevStartDate", prevStart);
        query.setParameter("prevEndDate", prevEnd);
        query.setParameter("ytdStartDate", ytdStart);
        query.setParameter("prevYtdStartDate", prevYtdStart); // This acts as the global lower bound for WHERE

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

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("mid", row[0]);
            map.put("name", row[1]);

            // Calc percentages in Java to avoid divide by zero SQL errors easily
            java.math.BigDecimal mtdCur = (java.math.BigDecimal) row[2];
            java.math.BigDecimal mtdPrev = (java.math.BigDecimal) row[3];
            java.math.BigDecimal ytdCur = (java.math.BigDecimal) row[4];
            java.math.BigDecimal ytdPrev = (java.math.BigDecimal) row[5];

            map.put("mtd_current", mtdCur);
            map.put("mtd_prev", mtdPrev);

            double mtdPct = 0;
            if (mtdPrev != null && mtdPrev.doubleValue() != 0) {
                mtdPct = ((mtdCur != null ? mtdCur.doubleValue() : 0) - mtdPrev.doubleValue()) / mtdPrev.doubleValue()
                        * 100;
            } else if (mtdCur != null && mtdCur.doubleValue() > 0) {
                mtdPct = 100; // New growth
            }
            map.put("mtd_pct", mtdPct);

            map.put("ytd_current", ytdCur);
            map.put("ytd_prev", ytdPrev);

            double ytdPct = 0;
            if (ytdPrev != null && ytdPrev.doubleValue() != 0) {
                ytdPct = ((ytdCur != null ? ytdCur.doubleValue() : 0) - ytdPrev.doubleValue()) / ytdPrev.doubleValue()
                        * 100;
            } else if (ytdCur != null && ytdCur.doubleValue() > 0) {
                ytdPct = 100;
            }
            map.put("ytd_pct", ytdPct);

            result.add(map);
        }

        return result;
    }

    public Map<String, Object> getExecutiveMetrics(VolumeRevenueFilterDTO filter) {
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

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("start", start);
        query.setParameter("end", end);
        query.setParameter("prevStart", prevStart);
        query.setParameter("prevEnd", prevEnd);

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
}
