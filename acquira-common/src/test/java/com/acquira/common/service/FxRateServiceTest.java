package com.acquira.common.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure rules behind date-aware USD conversion (FxRateService):
 * "rate of the day, else the previous available day's", and a period rate
 * that is the volume-weighted rate of exactly the days in the period.
 */
class FxRateServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate D9 = LocalDate.of(2026, 10, 9);

    private static TreeMap<LocalDate, FxRateService.StoredRate> rows() {
        TreeMap<LocalDate, FxRateService.StoredRate> m = new TreeMap<>();
        m.put(D1, new FxRateService.StoredRate(D1, new BigDecimal("50"), "MPS_SEED")); // 1 USD = 50 EGP
        m.put(D2, new FxRateService.StoredRate(D2, new BigDecimal("40"), "MPS_SEED")); // 1 USD = 40 EGP
        return m;
    }

    @Test
    void exactDayWins() {
        FxRateService.DayRate r = FxRateService.dayRate(rows(), D2).orElseThrow();
        assertEquals(D2, r.rateDate());
        assertEquals(0, r.staleDays());
        assertFalse(r.carried());
        assertEquals(0, new BigDecimal("0.025").compareTo(r.usdPerUnit()));
    }

    @Test
    void missingDayUsesPreviousAvailableDay_andSaysHowStale() {
        FxRateService.DayRate r = FxRateService.dayRate(rows(), D9).orElseThrow();
        assertEquals(D2, r.rateDate());
        assertEquals(7, r.staleDays());
        assertTrue(r.carried());
        assertEquals(0, new BigDecimal("40").compareTo(r.unitsPerUsd()));
    }

    @Test
    void dateBeforeFirstRateHasNoRate() {
        assertEquals(Optional.empty(), FxRateService.dayRate(rows(), D1.minusDays(1)));
        assertEquals(Optional.empty(), FxRateService.dayRate(new TreeMap<>(), D1));
    }

    @Test
    void periodRateIsVolumeWeightedOverItsDays() {
        List<FxRateService.DayRate> days = List.of(
                FxRateService.dayRate(rows(), D1).orElseThrow(),
                FxRateService.dayRate(rows(), D2).orElseThrow(),
                FxRateService.dayRate(rows(), D3).orElseThrow()); // carried from D2
        Map<LocalDate, BigDecimal> vol = Map.of(D1, new BigDecimal("1000"), D2, new BigDecimal("3000"));
        FxRateService.Effective e = FxRateService.weighted("EGP", D1, D3, days, vol);

        // (1000*0.02 + 3000*0.025) / 4000 = 0.02375 ; D3 has no volume and no weight
        assertEquals("VOLUME_WEIGHTED", e.method());
        assertEquals(0, new BigDecimal("0.02375").compareTo(e.usdPerUnit().stripTrailingZeros()));
        assertEquals(3, e.daysInRange());
        assertEquals(2, e.daysExact());
        assertEquals(1, e.daysCarried());
        assertEquals(0, e.daysMissing());
        assertEquals(1, e.maxStaleDays());
        assertEquals(D2, e.lastRateDate());
        // "total × one rate" would have used 0.025 (latest) or 0.02 (first): both wrong.
        assertNotEquals(0, new BigDecimal("0.025").compareTo(e.usdPerUnit()));
    }

    @Test
    void noVolumeFallsBackToSimpleAverage_andMissingDaysAreCounted() {
        // Only D2..D3 have rates; D1-1 is before the first row → missing from the series
        LocalDate d0 = D1.minusDays(1);
        List<FxRateService.DayRate> days = List.of(
                FxRateService.dayRate(rows(), D1).orElseThrow(),
                FxRateService.dayRate(rows(), D2).orElseThrow());
        FxRateService.Effective e = FxRateService.weighted("EGP", d0, D2, days, Map.of());
        assertEquals("SIMPLE_AVERAGE", e.method());
        assertEquals(0, new BigDecimal("0.0225").compareTo(e.usdPerUnit().stripTrailingZeros()));
        assertEquals(3, e.daysInRange());
        assertEquals(1, e.daysMissing());
    }

    @Test
    void peggedCurrenciesStayConstants_andEgpIsNotOne() {
        assertNotNull(FxRates.usdPerUnit("BHD"));
        assertNull(FxRates.usdPerUnit("EGP"), "EGP must come from fx_rate_daily, never a constant");
        assertNull(FxRates.toUsd(BigDecimal.TEN, "EGP"));
    }
}
