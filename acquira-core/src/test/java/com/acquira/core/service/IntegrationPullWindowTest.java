package com.acquira.core.service;

import com.acquira.batch.service.DynamicSchedulerService;
import com.acquira.batch.service.DynamicSchedulerService.PullWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the SCHEDULED external-DB-pull date window.
 *
 * THE BUG THESE PIN DOWN: scheduled pulls passed no dates, so the window
 * defaulted to month-to-date (1st-of-current-month .. today) computed in the
 * JVM's default zone. Two consequences:
 *
 *   1. MONTH-END GAP — the 02:00 run on the last day of a month loaded ~2 hours
 *      of that day, and the next run (on the 1st) reset the window to the new
 *      month. Because transaction loads are REPLACE-by-staged-date, that partial
 *      day was published and then never re-pulled: every month's final day stayed
 *      permanently incomplete.
 *   2. WRONG DAY — the cron fired in the schedule's timezone but the window was
 *      built from the server's. A 02:00 Asia/Dubai schedule fires at 22:00 UTC
 *      the PREVIOUS day, so on a UTC host the window was a day (and, on the 1st,
 *      a whole month) behind the run.
 */
class IntegrationPullWindowTest {

    private static final int LOOKBACK = 3;

    private static Clock at(String isoInstant, String zone) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneId.of(zone));
    }

    @Test
    @DisplayName("Running on the 1st still covers the previous month's final day")
    void windowSpansMonthBoundary() {
        // 02:00 on 1 September — the first run after August closed.
        PullWindow w = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), LOOKBACK, null, null);

        assertEquals(LocalDate.of(2026, 8, 29), w.from());
        assertEquals(LocalDate.of(2026, 9, 1), w.to());

        // The whole point: 31 August is inside the window, so the partial load
        // written during the 31st's own run gets replaced with the complete day.
        LocalDate lastDayOfAugust = LocalDate.of(2026, 8, 31);
        assertFalse(w.from().isAfter(lastDayOfAugust), "window must start on or before Aug 31");
        assertFalse(w.to().isBefore(lastDayOfAugust), "window must end on or after Aug 31");

        // The old month-to-date default started here and would have missed it.
        assertNotEquals(LocalDate.of(2026, 9, 1), w.from(),
                "must not reset to the 1st of the current month");
    }

    @Test
    @DisplayName("Every month-start in a year re-covers the prior month's last day")
    void monthBoundaryHoldsAllYearIncludingLeapFebruary() {
        for (int month = 1; month <= 12; month++) {
            LocalDate firstOfMonth = LocalDate.of(2028, month, 1); // 2028 is a leap year
            PullWindow w = DynamicSchedulerService.rollingWindow(
                    Clock.fixed(firstOfMonth.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC")),
                    LOOKBACK, null, null);

            LocalDate priorMonthEnd = firstOfMonth.minusDays(1);
            assertFalse(w.from().isAfter(priorMonthEnd),
                    "window starting " + w.from() + " misses " + priorMonthEnd);
        }
    }

    @Test
    @DisplayName("A minimum lookback of 2 days is enough to close the gap")
    void lookbackOfTwoStillCoversPriorDay() {
        PullWindow w = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), 2, null, null);
        assertEquals(LocalDate.of(2026, 8, 30), w.from());

        // A lookback of 0 collapses to a single day and reopens the gap — this
        // asserts the arithmetic, and is why the property is documented as >= 2.
        PullWindow degenerate = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), 0, null, null);
        assertEquals(degenerate.to(), degenerate.from());
    }

    @Test
    @DisplayName("'Today' comes from the schedule's zone, not the server's")
    void windowUsesScheduleTimezone() {
        // 22:00 UTC on 31 August IS 02:00 on 1 September in Dubai — this is the
        // exact instant a '0 0 2 * * ?' Asia/Dubai schedule fires.
        Instant fireInstant = Instant.parse("2026-08-31T22:00:00Z");

        PullWindow dubai = DynamicSchedulerService.rollingWindow(
                Clock.fixed(fireInstant, ZoneId.of("Asia/Dubai")), LOOKBACK, null, null);
        PullWindow utc = DynamicSchedulerService.rollingWindow(
                Clock.fixed(fireInstant, ZoneId.of("UTC")), LOOKBACK, null, null);

        assertEquals(LocalDate.of(2026, 9, 1), dubai.to(), "Dubai has already rolled over to Sept 1");
        assertEquals(LocalDate.of(2026, 8, 31), utc.to(), "the server is still on Aug 31");
        assertNotEquals(utc.to(), dubai.to(),
                "the zones must disagree here — otherwise this test proves nothing");
    }

    @Test
    @DisplayName("Bahrain and Egypt schedules resolve their own dates")
    void windowIsCorrectForEachTenantZone() {
        Instant fireInstant = Instant.parse("2026-08-31T21:30:00Z");
        // Bahrain is UTC+3 -> already Sept 1; Egypt is UTC+2 (EEST +3 in summer).
        assertEquals(LocalDate.of(2026, 9, 1), DynamicSchedulerService.rollingWindow(
                Clock.fixed(fireInstant, ZoneId.of("Asia/Bahrain")), LOOKBACK, null, null).to());
        assertEquals(LocalDate.of(2026, 8, 31), DynamicSchedulerService.rollingWindow(
                Clock.fixed(fireInstant, ZoneId.of("UTC")), LOOKBACK, null, null).to());
    }

    @Test
    @DisplayName("Operator-supplied dates are never overridden")
    void explicitDatesWin() {
        LocalDate from = LocalDate.of(2026, 1, 5);
        LocalDate to = LocalDate.of(2026, 1, 20);
        PullWindow w = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), LOOKBACK, from, to);

        assertEquals(from, w.from());
        assertEquals(to, w.to());
    }

    @Test
    @DisplayName("A half-specified window fills only the missing end")
    void partiallyExplicitWindow() {
        // Only dateTo given: dateFrom is derived by lookback from THAT date, not
        // from the wall clock — so a backdated re-pull stays self-consistent.
        PullWindow onlyTo = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), LOOKBACK, null, LocalDate.of(2026, 6, 10));
        assertEquals(LocalDate.of(2026, 6, 7), onlyTo.from());
        assertEquals(LocalDate.of(2026, 6, 10), onlyTo.to());

        PullWindow onlyFrom = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), LOOKBACK, LocalDate.of(2026, 8, 1), null);
        assertEquals(LocalDate.of(2026, 8, 1), onlyFrom.from());
        assertEquals(LocalDate.of(2026, 9, 1), onlyFrom.to());
    }

    @Test
    @DisplayName("A negative lookback cannot invert the window")
    void negativeLookbackIsClamped() {
        PullWindow w = DynamicSchedulerService.rollingWindow(
                at("2026-09-01T02:00:00Z", "UTC"), -5, null, null);
        assertFalse(w.from().isAfter(w.to()), "from must never be after to");
        assertEquals(w.to(), w.from());
    }

    @Test
    @DisplayName("The window is always a valid, bounded range")
    void windowIsAlwaysOrderedAndBounded() {
        for (int lookback : new int[]{0, 1, 2, 3, 7, 31, 365}) {
            PullWindow w = DynamicSchedulerService.rollingWindow(
                    at("2026-09-01T02:00:00Z", "UTC"), lookback, null, null);
            assertFalse(w.from().isAfter(w.to()));
            assertEquals(lookback, java.time.temporal.ChronoUnit.DAYS.between(w.from(), w.to()));
        }
    }
}
