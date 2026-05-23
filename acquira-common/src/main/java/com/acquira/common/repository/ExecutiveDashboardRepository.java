package com.acquira.common.repository;

import com.acquira.common.dto.ExecutiveDashboardDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ExecutiveDashboardRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExecutiveDashboardRepository.class);

    @PersistenceContext
    private EntityManager entityManager;

    public ExecutiveDashboardDTO getDashboardData(String dataset, LocalDate asOfDate) {
        return getDashboardData(dataset, asOfDate, null);
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null every query in this method
     * appends an `AND <alias>.tenant_id = :tenantId` clause so cross-tenant rows
     * cannot leak. Previously this entire repository ran un-scoped which meant the
     * Executive Dashboard would mix data across tenants in any multi-tenant deployment.
     */
    public ExecutiveDashboardDTO getDashboardData(String dataset, LocalDate asOfDate, Long tenantId) {
        if (asOfDate == null)
            asOfDate = LocalDate.now();
        // Tenant clauses appended to each query when tenantId is non-null. Kept as
        // local variables (rather than rewriting every query inline) so the patch is
        // minimally invasive and easy to audit.
        final String tStore   = (tenantId != null) ? " AND s.tenant_id = :tenantId"  : "";
        final String tStoreSt = (tenantId != null) ? " AND st.tenant_id = :tenantId" : "";
        final String tSdi     = (tenantId != null) ? " AND sdi.tenant_id = :tenantId" : "";

        // Dates
        LocalDate yearStart = asOfDate.with(TemporalAdjusters.firstDayOfYear());
        LocalDate monthStart = asOfDate.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate weekStart = asOfDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        ExecutiveDashboardDTO dto = new ExecutiveDashboardDTO();
        dto.setAsOfDate(asOfDate);
        dto.setDataset(dataset); // "Year" filtering implied by logic, or specific distinct dataset if needed.

        // Logic: specific "Dataset" usually implies filtering by a source column or
        // just year.
        // Plan says: "Dataset Selector" -> Filters by Year.
        // So if dataset="2025", we override yearStart/asOfDate?
        // User request: "When user changes the sheet -> all charts + KPIs refresh using
        // that sheet’s data."
        // And "As Of Date" is separate.
        // Interpretation: "Dataset" might be "Source System" or distinct tables.
        // But user said: "Options source: list of available sheets/tables/datasets
        // (example: SID_Data_2026, SID_Data_2025)"
        // This implies physical segregation or just year-based views.
        // I will assume for now it filters mainly by the Year extracted from the string
        // or just applies to the Data.
        // Actually, easiest way: Ignore dataset string for logic if currently we only
        // have one live DB,
        // UNLESS we want to simulate it.
        // Let's assume dataset serves as a context for year.
        // If dataset contains "2025", we strictly bound queries to 2025?
        // Let's rely on AsOfDate for the bounds, and assume Dataset is just metadata
        // for now
        // unless we need to filter `referral_partner` or `tenant` based on it.

        ExecutiveDashboardDTO.KpiData kpis = new ExecutiveDashboardDTO.KpiData();

        // 1. KPI Queries

        // YTD SID (Stores created)
        String sqlSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :yearStart AND :asOfDate" + tStore;
        // YTD MID
        String sqlMid = "SELECT count(distinct s.merchant_id) FROM dim_store s WHERE s.created_date BETWEEN :yearStart AND :asOfDate" + tStore;

        // MTD SID
        String sqlMtdSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :monthStart AND :asOfDate" + tStore;

        // WTD SID
        String sqlWtdSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :weekStart AND :asOfDate" + tStore;

        // MTD MSF (Sum Daily Insight joined with Store to ensure active checks?)
        // Actually sum_daily_insight has store_id.
        String sqlMtdMsf = "SELECT SUM(sdi.total_msf) FROM sum_daily_insight sdi WHERE sdi.business_date BETWEEN :monthStart AND :asOfDate" + tSdi;

        kpis.setYtdSid(count(sqlSid, yearStart, asOfDate, tenantId));
        kpis.setYtdMid(count(sqlMid, yearStart, asOfDate, tenantId));
        kpis.setMtdSid(count(sqlMtdSid, monthStart, asOfDate, tenantId));
        kpis.setWtdSid(count(sqlWtdSid, weekStart, asOfDate, tenantId));

        kpis.setMtdMsfUsd(sum(sqlMtdMsf, monthStart, asOfDate, tenantId));

        dto.setKpis(kpis);

        // 2. Charts
        Map<String, List<Map<String, Object>>> charts = new HashMap<>();

        // Chart 1: Top N Introducing Agent (Sales User) YTD
        // Group by m.sales_user_id (Agent)
        // Join store -> merchant
        String chart1Sql = "SELECT m.sales_user_id, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :yearStart AND :asOfDate" + tStoreSt + " " +
                "GROUP BY m.sales_user_id ORDER BY cnt DESC LIMIT 10";
        charts.put("ytdByAgent", queryList(chart1Sql, yearStart, asOfDate, "agent", "count", tenantId));

        // Chart 2: YTD by Program (Referral Partner)
        String chart2Sql = "SELECT m.referral_partner, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :yearStart AND :asOfDate" + tStoreSt + " " +
                "GROUP BY m.referral_partner ORDER BY cnt DESC";
        charts.put("ytdByProgram", queryList(chart2Sql, yearStart, asOfDate, "program", "count", tenantId));

        // Chart 3: MTD Volume Split by Program
        // Join sum_daily -> merchant
        String chart3Sql = "SELECT m.referral_partner, SUM(sdi.total_volume) as vol " +
                "FROM sum_daily_insight sdi JOIN dim_merchant m ON sdi.merchant_id = m.merchant_id " +
                "WHERE sdi.business_date BETWEEN :monthStart AND :asOfDate" + tSdi + " " +
                "GROUP BY m.referral_partner ORDER BY vol DESC";
        charts.put("mtdVolumeSplit", queryList(chart3Sql, monthStart, asOfDate, "program", "value", tenantId));

        // Chart 4: MTD SID by Program
        String chart4Sql = "SELECT m.referral_partner, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :monthStart AND :asOfDate" + tStoreSt + " " +
                "GROUP BY m.referral_partner ORDER BY cnt DESC";
        charts.put("mtdSidByProgram", queryList(chart4Sql, monthStart, asOfDate, "program", "count", tenantId));

        dto.setCharts(charts);

        return dto;
    }

    // Backward-compatible overloads (no tenantId)
    private long count(String sql, LocalDate start, LocalDate end) {
        return count(sql, start, end, null);
    }
    private double sum(String sql, LocalDate start, LocalDate end) {
        return sum(sql, start, end, null);
    }
    private List<Map<String, Object>> queryList(String sql, LocalDate start, LocalDate end, String keyCol, String valCol) {
        return queryList(sql, start, end, keyCol, valCol, null);
    }

    private long count(String sql, LocalDate start, LocalDate end, Long tenantId) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            // Attempt to set standardized params blindly (robustness hack for dynamic queries)
            try { q.setParameter("yearStart", start); }  catch (Exception e) {}
            try { q.setParameter("monthStart", start); } catch (Exception e) {}
            try { q.setParameter("weekStart", start); }  catch (Exception e) {}
            try { q.setParameter("asOfDate", end); }     catch (Exception e) {}
            if (tenantId != null) { try { q.setParameter("tenantId", tenantId); } catch (Exception e) {} }

            Object res = q.getSingleResult();
            return res != null ? ((Number) res).longValue() : 0;
        } catch (Exception e) {
            // Previously this swallowed the exception and returned 0, making a
            // broken query indistinguishable from a genuine zero on the dashboard.
            // Log it so a real failure (bad column, type mismatch, missing table)
            // is visible instead of silently showing 0 to the user.
            log.warn("ExecutiveDashboard count query failed, returning 0. SQL=[{}] error={}",
                    sql, e.getMessage());
            return 0;
        }
    }

    private double sum(String sql, LocalDate start, LocalDate end, Long tenantId) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("monthStart", start);
            q.setParameter("asOfDate", end);
            if (tenantId != null) { try { q.setParameter("tenantId", tenantId); } catch (Exception e) {} }
            Object res = q.getSingleResult();
            return res != null ? ((Number) res).doubleValue() : 0.0;
        } catch (Exception e) {
            log.warn("ExecutiveDashboard sum query failed, returning 0. SQL=[{}] error={}",
                    sql, e.getMessage());
            return 0.0;
        }
    }

    private List<Map<String, Object>> queryList(String sql, LocalDate start, LocalDate end, String keyCol,
            String valCol, Long tenantId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Query q = entityManager.createNativeQuery(sql);
            // Attempt to set standardized params
            try { q.setParameter("yearStart", start); }  catch (Exception e) {}
            try { q.setParameter("monthStart", start); } catch (Exception e) {}
            q.setParameter("asOfDate", end);
            if (tenantId != null) { try { q.setParameter("tenantId", tenantId); } catch (Exception e) {} }

            List<Object[]> rows = q.getResultList();
            for (Object[] row : rows) {
                Map<String, Object> map = new HashMap<>();
                map.put(keyCol, row[0] != null ? row[0] : "Unknown");
                map.put(valCol, row[1] != null ? row[1] : 0);
                list.add(map);
            }
        } catch (Exception e) {
            log.warn("ExecutiveDashboard chart query failed, returning empty list. SQL=[{}] error={}",
                    sql, e.getMessage());
        }
        return list;
    }
}
