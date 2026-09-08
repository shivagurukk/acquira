package com.acquira.common.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The working week decides whether a missing file is an incident or a weekend.
 *
 * These tests pin the server-side rules to the frontend's weekRules.js. If the
 * two ever diverge, the board flags every Gulf tenant's weekend as missing data
 * and people stop reading it — which is worse than having no board.
 */
class WorkingWeekResolverTest {

    private final WorkingWeekResolver resolver = new WorkingWeekResolver(null);

    @Test
    @DisplayName("the UAE moved to Sat+Sun in 2022; the rest of the Gulf did not")
    void weekendsPerCountry() {
        assertEquals(java.util.Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), resolver.weekendDays("AE"));
        for (String cc : new String[]{"BH", "OM", "EG", "SA", "KW", "QA", "JO"}) {
            assertEquals(java.util.Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), resolver.weekendDays(cc),
                    cc + " should keep the Friday+Saturday weekend");
        }
    }

    @Test
    @DisplayName("country codes are case- and whitespace-insensitive")
    void normalisesInput() {
        assertEquals(resolver.weekendDays("AE"), resolver.weekendDays(" ae "));
        assertEquals(resolver.weekendDays("BH"), resolver.weekendDays("bh"));
    }

    @Test
    @DisplayName("an unknown country defaults to Fri+Sat, not a Western week")
    void unknownDefaultsToGulf() {
        // Every tenant onboarded so far is a Gulf or Levant acquirer, so this is
        // the safer wrong answer — same reasoning as the frontend's DEFAULT_WEEK.
        assertEquals(java.util.Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), resolver.weekendDays("ZZ"));
        assertEquals(java.util.Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), resolver.weekendDays(null));
        assertEquals(java.util.Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), resolver.weekendDays(""));
    }

    @Test
    @DisplayName("a Bahraini Friday is not a working day; a UAE Friday is")
    void theAlertThatWouldHaveCriedWolf() {
        LocalDate friday = LocalDate.of(2026, 8, 28);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek(), "fixture must actually be a Friday");

        assertFalse(resolver.isWorkingDay("BH", friday), "Bahrain: Friday is weekend, no data expected");
        assertTrue(resolver.isWorkingDay("AE", friday), "UAE: Friday is a working day, data IS expected");
    }

    @Test
    @DisplayName("a UAE Sunday is a weekend; a Bahraini Sunday is a working day")
    void sundayIsTheMirrorCase() {
        LocalDate sunday = LocalDate.of(2026, 8, 30);
        assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek());

        assertFalse(resolver.isWorkingDay("AE", sunday));
        assertTrue(resolver.isWorkingDay("BH", sunday));
    }
}
