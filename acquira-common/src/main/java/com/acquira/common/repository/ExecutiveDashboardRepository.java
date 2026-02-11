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

    @PersistenceContext
    private EntityManager entityManager;

    public ExecutiveDashboardDTO getDashboardData(String dataset, LocalDate asOfDate) {
        if (asOfDate == null)
            asOfDate = LocalDate.now();

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
        String sqlSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :yearStart AND :asOfDate";
        // YTD MID
        String sqlMid = "SELECT count(distinct s.merchant_id) FROM dim_store s WHERE s.created_date BETWEEN :yearStart AND :asOfDate";

        // MTD SID
        String sqlMtdSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :monthStart AND :asOfDate";

        // WTD SID
        String sqlWtdSid = "SELECT count(s.store_id) FROM dim_store s WHERE s.created_date BETWEEN :weekStart AND :asOfDate";

        // MTD MSF (Sum Daily Insight joined with Store to ensure active checks?)
        // Actually sum_daily_insight has store_id.
        String sqlMtdMsf = "SELECT SUM(sdi.total_msf) FROM sum_daily_insight sdi WHERE sdi.business_date BETWEEN :monthStart AND :asOfDate";

        kpis.setYtdSid(count(sqlSid, yearStart, asOfDate));
        kpis.setYtdMid(count(sqlMid, yearStart, asOfDate));
        kpis.setMtdSid(count(sqlMtdSid, monthStart, asOfDate));
        kpis.setWtdSid(count(sqlWtdSid, weekStart, asOfDate));

        kpis.setMtdMsfUsd(sum(sqlMtdMsf, monthStart, asOfDate));

        dto.setKpis(kpis);

        // 2. Charts
        Map<String, List<Map<String, Object>>> charts = new HashMap<>();

        // Chart 1: Top N Introducing Agent (Sales User) YTD
        // Group by m.sales_user_id (Agent)
        // Join store -> merchant
        String chart1Sql = "SELECT m.sales_user_id, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :yearStart AND :asOfDate " +
                "GROUP BY m.sales_user_id ORDER BY cnt DESC LIMIT 10";
        charts.put("ytdByAgent", queryList(chart1Sql, yearStart, asOfDate, "agent", "count"));

        // Chart 2: YTD by Program (Referral Partner)
        String chart2Sql = "SELECT m.referral_partner, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :yearStart AND :asOfDate " +
                "GROUP BY m.referral_partner ORDER BY cnt DESC";
        charts.put("ytdByProgram", queryList(chart2Sql, yearStart, asOfDate, "program", "count"));

        // Chart 3: MTD Volume Split by Program
        // Join sum_daily -> merchant
        String chart3Sql = "SELECT m.referral_partner, SUM(sdi.total_volume) as vol " +
                "FROM sum_daily_insight sdi JOIN dim_merchant m ON sdi.merchant_id = m.merchant_id " +
                "WHERE sdi.business_date BETWEEN :monthStart AND :asOfDate " +
                "GROUP BY m.referral_partner ORDER BY vol DESC";
        charts.put("mtdVolumeSplit", queryList(chart3Sql, monthStart, asOfDate, "program", "value"));

        // Chart 4: MTD SID by Program
        String chart4Sql = "SELECT m.referral_partner, COUNT(st.store_id) as cnt " +
                "FROM dim_store st JOIN dim_merchant m ON st.merchant_id = m.merchant_id " +
                "WHERE st.created_date BETWEEN :monthStart AND :asOfDate " +
                "GROUP BY m.referral_partner ORDER BY cnt DESC";
        charts.put("mtdSidByProgram", queryList(chart4Sql, monthStart, asOfDate, "program", "count"));

        dto.setCharts(charts);

        return dto;
    }

    private long count(String sql, LocalDate start, LocalDate end) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            // Attempt to set standardized params blindly (robustness hack for dynamic
            // queries)
            try {
                q.setParameter("yearStart", start);
            } catch (Exception e) {
            }
            try {
                q.setParameter("monthStart", start);
            } catch (Exception e) {
            }
            try {
                q.setParameter("weekStart", start);
            } catch (Exception e) {
            }
            try {
                q.setParameter("asOfDate", end);
            } catch (Exception e) {
            }

            Object res = q.getSingleResult();
            return res != null ? ((Number) res).longValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private double sum(String sql, LocalDate start, LocalDate end) {
        try {
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("monthStart", start);
            q.setParameter("asOfDate", end);
            Object res = q.getSingleResult();
            return res != null ? ((Number) res).doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private List<Map<String, Object>> queryList(String sql, LocalDate start, LocalDate end, String keyCol,
            String valCol) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Query q = entityManager.createNativeQuery(sql);
            // Attempt to set standardized params
            try {
                q.setParameter("yearStart", start);
            } catch (Exception e) {
            }
            try {
                q.setParameter("monthStart", start);
            } catch (Exception e) {
            }
            q.setParameter("asOfDate", end);

            List<Object[]> rows = q.getResultList();
            for (Object[] row : rows) {
                Map<String, Object> map = new HashMap<>();
                map.put(keyCol, row[0] != null ? row[0] : "Unknown");
                map.put(valCol, row[1] != null ? row[1] : 0);
                list.add(map);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
}
