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
 * Backs the Volume Drop screen (/business/volume-drop) — one row per merchant
 * comparing LAST month's full volume against the CURRENT month-to-date, with
 * the month-end projection and the resulting drop/gain. Replaces the manual
 * "Jul vs Aug MTD" Excel the business keeps by hand.
 *
 * All three volume windows come out of ONE conditional-aggregation pass over
 * sum_daily_merchant (merchant-day grain, settlement volume):
 *   - last month, full calendar month
 *   - current month, first day .. as-of date (MTD)
 *   - last month, first day .. same elapsed-day count (the "pace" basis)
 *
 * Two projections are returned per row and the page toggles between them:
 *   linear = MTD / elapsed days × days in month          (the Excel formula)
 *   pace   = MTD / (last month's share done by day N)    (corrects for the
 *            weekday/weekend mix — BH weeks are Fri+Sat — falling back to
 *            linear when last month has no volume in those days)
 *
 * The as-of date is the LATEST LOADED business date, never the calendar —
 * feeds lag, and projecting "13 elapsed days" off a calendar day with no data
 * would understate every merchant. Drop = projected − last month (negative =
 * decline), same sign convention as the source spreadsheet.
 *
 * RM display name is sales_agent_profile.display_name (empty when the profile
 * has none — deliberate, per business instruction), RM email is
 * dim_merchant.sales_email, and the lead comes through sales_user_assignment
 * → sales_team_mapping.team_lead_name.
 */
@Repository
public class VolumeDropRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table.
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getBounds(Long tenantId) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT MIN(s.business_date), MAX(s.business_date) FROM sum_daily_merchant s WHERE s.tenant_id = :tenantId");
        query.setParameter("tenantId", tenantId);

        Object[] r = (Object[]) query.getSingleResult();
        Map<String, Object> out = new HashMap<>();
        out.put("earliest", r[0] == null ? null : r[0].toString());
        out.put("latest", r[1] == null ? null : r[1].toString());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1) Merchant rows + totals — the whole comparison in one payload
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getDropRows(VolumeRevenueFilterDTO filter, Long tenantId, LocalDate asOf) {
        requireTenant(tenantId);

        int elapsedDays = asOf.getDayOfMonth();
        int daysInMonth = asOf.lengthOfMonth();
        LocalDate cmStart = asOf.withDayOfMonth(1);
        LocalDate lmEnd = cmStart.minusDays(1);
        LocalDate lmStart = lmEnd.withDayOfMonth(1);
        // Pace basis: last month's first N days, clamped for short months
        // (elapsed day 30 of a 31-day month vs a 28-day February).
        LocalDate lmPaceEnd = lmStart.plusDays(Math.min(elapsedDays, lmEnd.getDayOfMonth()) - 1L);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid, m.name, ");
        sql.append("COALESCE(sap.display_name, '') as rm_name, ");
        sql.append("COALESCE(m.sales_email, '') as rm_email, ");
        sql.append("COALESCE(stm.team_lead_name, '') as lead_name, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :lmStart AND :lmEnd THEN s.total_volume ELSE 0 END) as lm_vol, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :cmStart AND :asOf THEN s.total_volume ELSE 0 END) as mtd_vol, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :lmStart AND :lmPaceEnd THEN s.total_volume ELSE 0 END) as lm_pace_vol ");
        sql.append("FROM sum_daily_merchant s ");
        sql.append("JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN sales_agent_profile sap ON sap.tenant_id = s.tenant_id AND sap.sales_user_id = m.sales_user_id ");
        sql.append("LEFT JOIN sales_user_assignment sua ON sua.tenant_id = s.tenant_id AND sua.sales_user_id = m.sales_user_id ");
        sql.append("LEFT JOIN sales_team_mapping stm ON stm.id = sua.team_lead_id ");
        sql.append("WHERE s.tenant_id = :tenantId ");
        sql.append("AND s.business_date BETWEEN :lmStart AND :asOf ");
        // Merchant-scope filter surface (same block as the ancillary pass in
        // IndustryAnalyticsRepository — this table has no tech dimensions).
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList()))        sql.append("AND m.mcc IN (:mccs) ");
        sql.append("GROUP BY m.mid, m.name, sap.display_name, m.sales_email, stm.team_lead_name ");
        // A merchant idle in BOTH months is noise, not a drop story.
        sql.append("HAVING SUM(CASE WHEN s.business_date BETWEEN :lmStart AND :lmEnd THEN s.total_volume ELSE 0 END) <> 0 ");
        sql.append("OR SUM(CASE WHEN s.business_date BETWEEN :cmStart AND :asOf THEN s.total_volume ELSE 0 END) <> 0 ");
        sql.append("ORDER BY lm_vol DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("lmStart", lmStart);
        query.setParameter("lmEnd", lmEnd);
        query.setParameter("lmPaceEnd", lmPaceEnd);
        query.setParameter("cmStart", cmStart);
        query.setParameter("asOf", asOf);
        query.setParameter("tenantId", tenantId);
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal tLm = BigDecimal.ZERO, tMtd = BigDecimal.ZERO, tLmPace = BigDecimal.ZERO;
        double tProjLinear = 0, tProjPace = 0;
        int newCount = 0, stoppedCount = 0;
        for (Object[] r : rows) {
            BigDecimal lm = bd(r[5]), mtd = bd(r[6]), lmPace = bd(r[7]);

            double projLinear = elapsedDays > 0
                    ? mtd.doubleValue() / elapsedDays * daysInMonth : 0;
            // Pace: MTD ÷ (share of last month done by the same elapsed day).
            double projPace = lmPace.signum() > 0 && lm.signum() > 0
                    ? mtd.doubleValue() * lm.doubleValue() / lmPace.doubleValue()
                    : projLinear;

            Map<String, Object> m = new HashMap<>();
            m.put("mid", r[0] == null ? "" : String.valueOf(r[0]));
            m.put("merchantName", r[1] == null ? "" : String.valueOf(r[1]));
            m.put("rmName", String.valueOf(r[2]));
            m.put("rmEmail", String.valueOf(r[3]));
            m.put("leadName", String.valueOf(r[4]));
            m.put("lastMonthVolume", lm);
            m.put("mtdVolume", mtd);
            m.put("projectedLinear", projLinear);
            m.put("projectedPace", projPace);
            m.put("dropLinear", projLinear - lm.doubleValue());
            m.put("dropPace", projPace - lm.doubleValue());
            boolean hasLm = lm.signum() != 0;
            m.put("dropPctLinear", hasLm ? (projLinear - lm.doubleValue()) / lm.doubleValue() * 100.0 : null);
            m.put("dropPctPace", hasLm ? (projPace - lm.doubleValue()) / lm.doubleValue() * 100.0 : null);
            m.put("isNew", !hasLm);
            boolean stopped = hasLm && mtd.signum() == 0;
            m.put("isStopped", stopped);
            out.add(m);

            tLm = tLm.add(lm); tMtd = tMtd.add(mtd); tLmPace = tLmPace.add(lmPace);
            tProjLinear += projLinear; tProjPace += projPace;
            if (!hasLm) newCount++;
            if (stopped) stoppedCount++;
        }

        Map<String, Object> totals = new HashMap<>();
        totals.put("lastMonthVolume", tLm);
        totals.put("mtdVolume", tMtd);
        totals.put("projectedLinear", tProjLinear);
        totals.put("projectedPace", tProjPace);
        totals.put("dropLinear", tProjLinear - tLm.doubleValue());
        totals.put("dropPace", tProjPace - tLm.doubleValue());
        boolean hasTLm = tLm.signum() != 0;
        totals.put("dropPctLinear", hasTLm ? (tProjLinear - tLm.doubleValue()) / tLm.doubleValue() * 100.0 : null);
        totals.put("dropPctPace", hasTLm ? (tProjPace - tLm.doubleValue()) / tLm.doubleValue() * 100.0 : null);
        totals.put("newCount", newCount);
        totals.put("stoppedCount", stoppedCount);
        totals.put("merchantCount", out.size());

        Map<String, Object> payload = new HashMap<>();
        payload.put("rows", out);
        payload.put("totals", totals);
        payload.put("asOf", asOf.toString());
        payload.put("elapsedDays", elapsedDays);
        payload.put("daysInMonth", daysInMonth);
        payload.put("currentMonth", asOf.toString().substring(0, 7));
        payload.put("lastMonth", lmStart.toString().substring(0, 7));
        payload.put("basis", "SETTLEMENT");
        return payload;
    }
}
