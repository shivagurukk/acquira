package com.acquira.core.service;

import com.acquira.common.model.SumDailyMerchant;
import com.acquira.common.model.SumMonthlyMerchantMetrics;
import com.acquira.common.service.MerchantMetricCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MerchantMetricCalculator} — monthly volatility,
 * stability label, behavior tag, weekly health, and smart-comment logic.
 *
 * Datasets are built with explicit business dates so the weekend / weekly-health
 * branches are deterministic. (Note: the production code initialises minVolume
 * from the first record's getTotalVolume(); these tests always give the first
 * record a non-null volume, matching how the batch populates the list.)
 */
class MerchantMetricCalculatorTest {

    private final MerchantMetricCalculator calc = new MerchantMetricCalculator();

    private SumDailyMerchant rec(LocalDate date, String volume) {
        SumDailyMerchant r = new SumDailyMerchant();
        r.setBusinessDate(date);
        r.setTotalVolume(volume == null ? null : new BigDecimal(volume));
        return r;
    }

    // ---- empty / null -------------------------------------------------------

    @Test
    @DisplayName("null records returns a metrics shell with ids set and no totals")
    void nullRecords() {
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(null, 1, 100L, "2026-06");
        assertEquals(1, m.getTenantId());
        assertEquals(100L, m.getMerchantId());
        assertEquals("2026-06", m.getMonthYear());
        assertNull(m.getTotalVolume());
        assertNull(m.getStabilityLabel());
    }

    @Test
    @DisplayName("empty records returns a metrics shell with ids set and no totals")
    void emptyRecords() {
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(new ArrayList<>(), 2, 200L, "2026-05");
        assertEquals(2, m.getTenantId());
        assertEquals(200L, m.getMerchantId());
        assertEquals("2026-05", m.getMonthYear());
        assertNull(m.getTotalVolume());
    }

    // ---- basic stats --------------------------------------------------------

    @Test
    @DisplayName("single record: total = max = min = avg = that value, volatility 0, Stable")
    void singleRecord() {
        List<SumDailyMerchant> recs = List.of(rec(LocalDate.of(2026, 6, 10), "500"));
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(recs, 1, 1L, "2026-06");

        assertEquals(0, new BigDecimal("500").compareTo(m.getTotalVolume()));
        assertEquals(0, new BigDecimal("500").compareTo(m.getMaxDailyVolume()));
        assertEquals(0, new BigDecimal("500").compareTo(m.getMinDailyVolume()));
        assertEquals(0, new BigDecimal("500.00").compareTo(m.getAvgDailyVolume()));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.getVolatilityIndex()));
        assertEquals("Stable", m.getStabilityLabel());
    }

    @Test
    @DisplayName("total / max / min / avg computed across several days")
    void aggregateStats() {
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "100"),
                rec(LocalDate.of(2026, 6, 11), "200"),
                rec(LocalDate.of(2026, 6, 12), "300"));
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(recs, 1, 1L, "2026-06");

        assertEquals(0, new BigDecimal("600").compareTo(m.getTotalVolume()));
        assertEquals(0, new BigDecimal("300").compareTo(m.getMaxDailyVolume()));
        assertEquals(0, new BigDecimal("100").compareTo(m.getMinDailyVolume()));
        assertEquals(0, new BigDecimal("200.00").compareTo(m.getAvgDailyVolume()));
    }

    @Test
    @DisplayName("a null daily volume is treated as zero (not an NPE) for non-first records")
    void nullVolumeTreatedAsZero() {
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "100"),
                rec(LocalDate.of(2026, 6, 11), null));
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(recs, 1, 1L, "2026-06");
        assertEquals(0, new BigDecimal("100").compareTo(m.getTotalVolume()));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.getMinDailyVolume()));
    }

    // ---- stability label thresholds ----------------------------------------

    @Test
    @DisplayName("equal volumes -> stdDev 0 -> Stable")
    void stableLabel() {
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "100"),
                rec(LocalDate.of(2026, 6, 11), "100"),
                rec(LocalDate.of(2026, 6, 12), "100"));
        assertEquals("Stable", calc.calculateMetrics(recs, 1, 1L, "2026-06").getStabilityLabel());
    }

    @Test
    @DisplayName("moderate variance (ratio ~0.30) -> Fluctuating")
    void fluctuatingLabel() {
        // 70 & 130 -> mean 100, stdDev 30, ratio 0.30 (between 0.20 and 0.50)
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "70"),
                rec(LocalDate.of(2026, 6, 11), "130"));
        assertEquals("Fluctuating", calc.calculateMetrics(recs, 1, 1L, "2026-06").getStabilityLabel());
    }

    @Test
    @DisplayName("high variance (ratio ~0.90) -> Unstable")
    void unstableLabel() {
        // 10 & 190 -> mean 100, stdDev 90, ratio 0.90 (> 0.50)
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "10"),
                rec(LocalDate.of(2026, 6, 11), "190"));
        assertEquals("Unstable", calc.calculateMetrics(recs, 1, 1L, "2026-06").getStabilityLabel());
    }

    // ---- behavior tag -------------------------------------------------------

    @Test
    @DisplayName("weekend-weighted volume (>40%) -> 'Weekend Heavy'")
    void weekendHeavyTag() {
        // 2024-06-01 Sat, 2024-06-02 Sun, 2024-06-03 Mon
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2024, 6, 1), "200"),  // Sat
                rec(LocalDate.of(2024, 6, 2), "200"),  // Sun
                rec(LocalDate.of(2024, 6, 3), "10"));  // Mon
        assertEquals("Weekend Heavy", calc.calculateMetrics(recs, 1, 1L, "2024-06").getBehaviorTag());
    }

    @Test
    @DisplayName("weekday-weighted volume -> 'Steady Performer'")
    void steadyPerformerTag() {
        // mostly weekday volume; weekends small
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2024, 6, 3), "100"),  // Mon
                rec(LocalDate.of(2024, 6, 4), "100"),  // Tue
                rec(LocalDate.of(2024, 6, 5), "100"),  // Wed
                rec(LocalDate.of(2024, 6, 1), "5"));   // Sat
        assertEquals("Steady Performer", calc.calculateMetrics(recs, 1, 1L, "2024-06").getBehaviorTag());
    }

    // ---- weekly health ------------------------------------------------------

    @Test
    @DisplayName("uniform month -> all weeks Green, week5 Grey when no days 29-31")
    void weeklyHealthAllGreenWeek5Grey() {
        List<SumDailyMerchant> recs = new ArrayList<>();
        for (int day = 1; day <= 28; day++) {
            recs.add(rec(LocalDate.of(2026, 6, day), "100"));
        }
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(recs, 1, 1L, "2026-06");
        assertEquals("Green", m.getWeek1Health());
        assertEquals("Green", m.getWeek2Health());
        assertEquals("Green", m.getWeek3Health());
        assertEquals("Green", m.getWeek4Health());
        assertEquals("Grey", m.getWeek5Health());
    }

    @Test
    @DisplayName("a weak first week (well below month avg) -> week1 Red")
    void weeklyHealthWeek1Red() {
        List<SumDailyMerchant> recs = new ArrayList<>();
        for (int day = 1; day <= 7; day++) recs.add(rec(LocalDate.of(2026, 6, day), "10"));
        for (int day = 8; day <= 28; day++) recs.add(rec(LocalDate.of(2026, 6, day), "100"));
        SumMonthlyMerchantMetrics m = calc.calculateMetrics(recs, 1, 1L, "2026-06");
        assertEquals("Red", m.getWeek1Health());
    }

    // ---- smart comment ------------------------------------------------------

    @Test
    @DisplayName("unstable dataset -> volatility smart comment")
    void smartCommentUnstable() {
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2026, 6, 10), "10"),
                rec(LocalDate.of(2026, 6, 11), "190"));
        assertEquals("Highly volatile volume detected. Recommend risk review.",
                calc.calculateMetrics(recs, 1, 1L, "2026-06").getSmartComment());
    }

    @Test
    @DisplayName("stable steady weekday dataset -> default consistent comment")
    void smartCommentConsistent() {
        List<SumDailyMerchant> recs = List.of(
                rec(LocalDate.of(2024, 6, 3), "100"),  // Mon
                rec(LocalDate.of(2024, 6, 4), "100"),  // Tue
                rec(LocalDate.of(2024, 6, 5), "100")); // Wed
        assertEquals("Consistent performance observed.",
                calc.calculateMetrics(recs, 1, 1L, "2024-06").getSmartComment());
    }
}
