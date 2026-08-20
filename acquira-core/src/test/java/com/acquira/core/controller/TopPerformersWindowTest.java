package com.acquira.core.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Comparison-window maths for the Top Performers movers board.
 *
 * The regression these lock down: the original implementation shifted back by a rolling
 * N days, which slides off calendar boundaries. A full July (31 days) compared against
 * "May 31 – Jun 30" rather than June, and August month-to-date compared against the last
 * days of July rather than the opening days of July.
 */
class TopPerformersWindowTest {

    private final TopPerformersController controller = new TopPerformersController();

    private LocalDate[] prior(String from, String to) {
        return controller.priorWindow(new LocalDate[]{ LocalDate.parse(from), LocalDate.parse(to) });
    }

    private void assertWindow(LocalDate[] actual, String expectedFrom, String expectedTo) {
        assertEquals(LocalDate.parse(expectedFrom), actual[0], "prior window start");
        assertEquals(LocalDate.parse(expectedTo), actual[1], "prior window end");
    }

    @Test
    @DisplayName("month-to-date compares with the opening days of the previous month")
    void monthToDate() {
        assertWindow(prior("2026-08-01", "2026-08-02"), "2026-07-01", "2026-07-02");
    }

    @Test
    @DisplayName("a full month compares with the whole previous month, not 31 days back")
    void fullMonth() {
        // The old rolling-days logic produced 2026-05-31 → 2026-06-30 here.
        assertWindow(prior("2026-07-01", "2026-07-31"), "2026-06-01", "2026-06-30");
    }

    @Test
    @DisplayName("a full month ending on a short previous month clamps to that month's length")
    void fullMonthAgainstShorterMonth() {
        assertWindow(prior("2026-03-01", "2026-03-31"), "2026-02-01", "2026-02-28");
    }

    @Test
    @DisplayName("month-to-date past the length of the previous month clamps")
    void monthToDateClampsToShortPreviousMonth() {
        assertWindow(prior("2026-03-01", "2026-03-30"), "2026-02-01", "2026-02-28");
    }

    @Test
    @DisplayName("leap year February is 29 days")
    void leapYear() {
        assertWindow(prior("2028-03-01", "2028-03-31"), "2028-02-01", "2028-02-29");
    }

    @Test
    @DisplayName("January month-to-date crosses the year boundary")
    void januaryCrossesYearBoundary() {
        assertWindow(prior("2026-01-01", "2026-01-15"), "2025-12-01", "2025-12-15");
    }

    @Test
    @DisplayName("a range spanning months has no calendar anchor and shifts by its own length")
    void multiMonthRangeFallsBackToRollingWindow() {
        // 2026-01-01..2026-03-31 is 90 days; the preceding 90 days end 2025-12-31.
        assertWindow(prior("2026-01-01", "2026-03-31"), "2025-10-03", "2025-12-31");
    }

    @Test
    @DisplayName("a mid-month custom range shifts by its own length")
    void customRangeFallsBackToRollingWindow() {
        assertWindow(prior("2026-05-10", "2026-05-16"), "2026-05-03", "2026-05-09");
    }

    @Test
    @DisplayName("a single day compares with the day before")
    void singleDay() {
        assertWindow(prior("2026-05-10", "2026-05-10"), "2026-05-09", "2026-05-09");
    }
}
