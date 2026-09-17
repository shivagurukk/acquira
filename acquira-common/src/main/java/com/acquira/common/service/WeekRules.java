package com.acquira.common.service;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;

/**
 * The working week is a LOCAL fact, not a regional one — backend mirror of
 * frontend/src/utils/weekRules.js. The UAE moved its weekend to Saturday +
 * Sunday in January 2022; Bahrain, Oman, Egypt and the rest of the Gulf/Levant
 * acquirers on this platform keep Friday + Saturday. Anything that says
 * "weekend" or splits weekday-vs-weekend traffic has to ask the TENANT's
 * country (tenant.home_country_code) rather than assume one of them — and
 * never the Western Mon–Fri/Sat–Sun convention.
 */
public final class WeekRules {

    private static final Set<DayOfWeek> SAT_SUN = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final Set<DayOfWeek> FRI_SAT = EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);

    private WeekRules() {}

    /**
     * Weekend days for an ISO-3166 alpha-2 country. AE → Sat+Sun; BH/OM/EG/
     * SA/KW/QA/JO → Fri+Sat. An unknown or null country keeps the Fri+Sat
     * default rather than a Western week: every tenant onboarded so far is a
     * Gulf or Levant acquirer, so that is the safer wrong answer (same
     * reasoning as the frontend module).
     */
    public static Set<DayOfWeek> weekendDays(String countryCode) {
        String cc = countryCode == null ? "" : countryCode.trim().toUpperCase();
        return "AE".equals(cc) ? SAT_SUN : FRI_SAT;
    }
}
