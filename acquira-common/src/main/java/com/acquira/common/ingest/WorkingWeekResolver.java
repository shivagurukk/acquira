package com.acquira.common.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side twin of frontend/src/utils/weekRules.js.
 *
 * WHY THIS HAD TO EXIST
 * ---------------------
 * The working week lived only in the frontend. Anything server-side that asks
 * "was a file expected on this date?" needs the same answer, or every Bahraini
 * Friday raises a NO_DATA alert and the board cries wolf until people stop
 * reading it.
 *
 * The UAE moved its weekend to Saturday + Sunday in January 2022; Bahrain,
 * Oman, Egypt and the rest of the Gulf/Levant acquirers on this platform keep
 * Friday + Saturday. The rules below mirror WEEK_RULES in weekRules.js exactly
 * — if one side changes, change both.
 */
@Service
public class WorkingWeekResolver {

    private static final Set<DayOfWeek> FRI_SAT = Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final Set<DayOfWeek> SAT_SUN = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    /**
     * An unknown country keeps the Fri+Sat default rather than falling back to
     * a Mon-Fri Western week: every tenant onboarded so far is a Gulf or Levant
     * acquirer, so that is the safer wrong answer. Same reasoning as the
     * frontend's DEFAULT_WEEK.
     */
    private static final Set<DayOfWeek> DEFAULT_WEEKEND = FRI_SAT;

    private final JdbcTemplate jdbc;

    public WorkingWeekResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<DayOfWeek> weekendDays(String countryCode) {
        String cc = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        switch (cc) {
            case "AE": return SAT_SUN;
            case "BH": case "OM": case "EG": case "SA":
            case "KW": case "QA": case "JO": return FRI_SAT;
            default:   return DEFAULT_WEEKEND;
        }
    }

    /** Weekend set for a tenant, resolved from tenant.home_country_code. */
    public Set<DayOfWeek> weekendDaysForTenant(Long tenantId) {
        return weekendDays(countryCodeOf(tenantId));
    }

    public boolean isWorkingDay(Long tenantId, LocalDate date) {
        return !weekendDaysForTenant(tenantId).contains(date.getDayOfWeek());
    }

    public boolean isWorkingDay(String countryCode, LocalDate date) {
        return !weekendDays(countryCode).contains(date.getDayOfWeek());
    }

    /** ISO country for a tenant; 'AE' if unset, matching the COALESCE used in TransactionJobConfig. */
    public String countryCodeOf(Long tenantId) {
        if (tenantId == null) return "AE";
        try {
            String cc = jdbc.queryForObject(
                "SELECT COALESCE(home_country_code, 'AE') FROM tenant WHERE tenant_id = ?",
                String.class, tenantId);
            return cc == null ? "AE" : cc;
        } catch (Exception e) {
            return "AE";
        }
    }
}
