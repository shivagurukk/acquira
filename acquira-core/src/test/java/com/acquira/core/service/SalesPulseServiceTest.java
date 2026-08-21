package com.acquira.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Executive Sales Pulse classifier, and the target proration that sits beside it.
 *
 * These lock down the rules a person's label on an executive screen depends on.
 * The ones that matter most are the negative cases: an agent with no target, and
 * an agent with too little history, must never be labelled as underperforming for
 * those reasons.
 */
class SalesPulseServiceTest {

    private final SalesPulseProperties props = new SalesPulseProperties();
    private final SalesPulseService svc = new SalesPulseService(null, props);

    private String state(List<Double> series) {
        return svc.classify(series, null).state();
    }

    // ═══════════════════════════════════════════════════════════
    //  MOMENTUM
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("The spec's worked example — 45K, 58K, 72K — is Accelerating")
    void acceleratingExample() {
        assertEquals(SalesPulseService.ACCELERATING, state(List.of(45_000.0, 58_000.0, 72_000.0)));
    }

    @Test
    @DisplayName("A single strong month off a weak run is not Accelerating — the run has to be up too")
    void spikeIsNotAcceleration() {
        // +50% on the month, but the two months before it fell: one good month is
        // not a trend, and calling it one sends leadership to the wrong person.
        assertNotEquals(SalesPulseService.ACCELERATING,
                state(List.of(90_000.0, 70_000.0, 40_000.0, 60_000.0)));
    }

    @Test
    @DisplayName("Comfortably above the recent average is Strong")
    void strong() {
        assertEquals(SalesPulseService.STRONG, state(List.of(50_000.0, 52_000.0, 48_000.0, 60_000.0)));
    }

    @Test
    @DisplayName("Sitting on the recent average is Stable")
    void stable() {
        assertEquals(SalesPulseService.STABLE, state(List.of(50_000.0, 51_000.0, 49_000.0, 50_500.0)));
    }

    @Test
    @DisplayName("A material single-month drop is Slowing")
    void slowingOnDrop() {
        // -20% on the month, but still well inside the attention band.
        assertEquals(SalesPulseService.SLOWING, state(List.of(40_000.0, 60_000.0, 62_000.0, 49_600.0)));
    }

    @Test
    @DisplayName("Two consecutive declining months is Slowing")
    void slowingOnStreak() {
        assertEquals(SalesPulseService.SLOWING, state(List.of(50_000.0, 55_000.0, 53_000.0, 51_000.0)));
    }

    @Test
    @DisplayName("Below 70% of the recent average is Attention")
    void attentionOnCollapse() {
        assertEquals(SalesPulseService.ATTENTION, state(List.of(60_000.0, 62_000.0, 58_000.0, 30_000.0)));
    }

    @Test
    @DisplayName("Three consecutive declining months is Attention, not merely Slowing")
    void attentionOnStreak() {
        assertEquals(SalesPulseService.ATTENTION, state(List.of(70_000.0, 66_000.0, 63_000.0, 61_000.0)));
    }

    @Test
    @DisplayName("Attention wins over a strong month — being far below your own norm is the headline")
    void attentionOutranksGrowth() {
        // +20% on the month, yet still under 70% of a six-month average built on
        // much bigger numbers. The executive needs to see the second fact.
        List<Double> series = List.of(100_000.0, 110_000.0, 105_000.0, 95_000.0, 25_000.0, 30_000.0);
        assertEquals(SalesPulseService.ATTENTION, state(series));
    }

    @Test
    @DisplayName("Too little history is New, never a performance judgement")
    void insufficientHistoryIsNew() {
        assertEquals(SalesPulseService.NEW, state(List.of(45_000.0)));
        assertEquals(SalesPulseService.NEW, state(List.of(45_000.0, 20_000.0)));
        assertEquals(SalesPulseService.NEW, state(List.of()));
        assertEquals(SalesPulseService.NEW, svc.classify(null, null).state());
    }

    @Test
    @DisplayName("A missing target never worsens the classification")
    void missingTargetIsNeutral() {
        List<Double> series = List.of(50_000.0, 51_000.0, 49_000.0, 50_500.0);
        assertEquals(state(series), svc.classify(series, null).state());
        // And a healthy attainment leaves it alone too.
        assertEquals(SalesPulseService.STABLE, svc.classify(series, 95.0).state());
    }

    @Test
    @DisplayName("A target that exists and is badly missed does push to Attention")
    void badAttainmentTriggersAttention() {
        List<Double> series = List.of(50_000.0, 51_000.0, 49_000.0, 50_500.0);
        assertEquals(SalesPulseService.ATTENTION, svc.classify(series, 40.0).state());
    }

    @Test
    @DisplayName("Growth from zero is undefined, not infinite")
    void growthFromZero() {
        assertNull(svc.classify(List.of(10_000.0, 12_000.0, 0.0, 5_000.0), null).growthPct());
        assertNull(SalesPulseService.changePct(100, 0));
    }

    @Test
    @DisplayName("Decline streaks are counted from the most recent month backwards")
    void declineStreak() {
        var m = svc.classify(List.of(80_000.0, 70_000.0, 60_000.0, 50_000.0), null);
        assertEquals(3, m.consecutiveDeclines());
        assertEquals(0, m.consecutiveGrowth());
    }

    // ═══════════════════════════════════════════════════════════
    //  MOMENTUM WINDOW
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("A part-way-through month is excluded from the momentum window")
    void partialMonthExcluded() {
        // Data through 12 August: comparing 12 days against full months would flag
        // the entire salesforce as declining, so August is left out.
        var w = svc.momentumWindow(LocalDate.parse("2026-08-12"));
        assertEquals(YearMonth.of(2026, 7), w.last());
        assertEquals(YearMonth.of(2026, 2), w.first());
    }

    @Test
    @DisplayName("A month that has actually completed is included")
    void completeMonthIncluded() {
        var w = svc.momentumWindow(LocalDate.parse("2026-08-31"));
        assertEquals(YearMonth.of(2026, 8), w.last());
        assertEquals(YearMonth.of(2026, 3), w.first());
    }

    // ═══════════════════════════════════════════════════════════
    //  DEPENDENCY
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("One salesperson carrying over half the team is High Dependency")
    void highDependency() {
        List<Map<String, Object>> members = List.of(
                Map.of("name", "Sara", "sales", 58_000.0),
                Map.of("name", "John", "sales", 25_000.0),
                Map.of("name", "Ali",  "sales", 17_000.0));
        var dep = svc.dependency(members, 100_000.0);
        assertEquals("HIGH", dep.status());
        assertEquals("Sara", dep.topContributor());
        assertEquals(58.0, dep.topSharePct());
    }

    @Test
    @DisplayName("An evenly spread team is Normal")
    void normalDependency() {
        List<Map<String, Object>> members = List.of(
                Map.of("name", "Sara", "sales", 35_000.0),
                Map.of("name", "John", "sales", 33_000.0),
                Map.of("name", "Ali",  "sales", 32_000.0));
        assertEquals("NORMAL", svc.dependency(members, 100_000.0).status());
    }

    @Test
    @DisplayName("A team with no sales has no dependency to report")
    void zeroTeamSales() {
        assertEquals("NORMAL", svc.dependency(List.of(Map.of("name", "Sara", "sales", 0.0)), 0).status());
    }

    // ═══════════════════════════════════════════════════════════
    //  INSIGHT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("The insight sentence states the growth, the top team and the concern")
    void insightReadsAsWritten() {
        String s = svc.insight(8.4, "Ahmed", 4, 2);
        assertTrue(s.contains("up 8.4%"), s);
        assertTrue(s.contains("Ahmed's team"), s);
        assertTrue(s.contains("Four sales executives"), s);
        assertTrue(s.contains("two who have"), s);
        assertTrue(s.contains("3 consecutive months"), s);
        // Split on a period FOLLOWED BY whitespace — the '.' inside "8.4%" is not a
        // sentence end, and counting bare periods would fail on any decimal figure.
        assertTrue(s.split("\\.\\s+").length <= 2, "at most two sentences: " + s);
        assertTrue(s.endsWith("."), s);
    }

    @Test
    @DisplayName("With no comparison period the insight says so rather than inventing a direction")
    void insightWithoutComparison() {
        String s = svc.insight(null, "Ahmed", 0, 0);
        assertTrue(s.contains("no equivalent previous period"), s);
        assertFalse(s.contains("up "), s);
    }

    // ═══════════════════════════════════════════════════════════
    //  TARGET PRORATION
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("A full month weighs 1.0")
    void fullMonthWeight() {
        var w = SalesTargetResolver.monthWeights(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertEquals(1, w.size());
        assertEquals(1.0, w.get(202608), 1e-9);
    }

    @Test
    @DisplayName("Twelve days of a 31-day month weigh 12/31 — not a whole month")
    void partialMonthWeight() {
        // Without this, everyone looks ~60% behind on the 12th of every month.
        var w = SalesTargetResolver.monthWeights(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-12"));
        assertEquals(12.0 / 31.0, w.get(202608), 1e-9);
    }

    @Test
    @DisplayName("A window spanning a year boundary produces contiguous month keys, not an integer range")
    void crossYearWeights() {
        var w = SalesTargetResolver.monthWeights(LocalDate.parse("2025-11-15"), LocalDate.parse("2026-02-10"));
        assertEquals(List.of(202511, 202512, 202601, 202602), List.copyOf(w.keySet()));
        assertEquals(16.0 / 30.0, w.get(202511), 1e-9);   // 15-30 Nov
        assertEquals(1.0, w.get(202512), 1e-9);
        assertEquals(1.0, w.get(202601), 1e-9);
        assertEquals(10.0 / 28.0, w.get(202602), 1e-9);   // 1-10 Feb
    }

    @Test
    @DisplayName("A quarter weighs three whole months")
    void quarterWeights() {
        var w = SalesTargetResolver.monthWeights(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        assertEquals(3, w.size());
        assertEquals(3.0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }
}
