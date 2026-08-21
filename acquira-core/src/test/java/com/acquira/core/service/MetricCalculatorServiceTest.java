package com.acquira.core.service;

import com.acquira.common.model.MerchantDailyMetrics;
import com.acquira.common.service.MetricCalculatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link MetricCalculatorService#computeMetrics}.
 *
 * The service derives "today"/"yesterday"/avg-7-day from {@link LocalDate#now()},
 * so the calendar-coupled branches (trend crash, volume spike, flatline) are
 * guarded with JUnit Assumptions and skipped on days of the month where their
 * pre-condition cannot hold — the suite stays green every day while still
 * exercising the branch whenever the date allows.
 */
class MetricCalculatorServiceTest {

    private final MetricCalculatorService svc = new MetricCalculatorService();
    private static final int TODAY = LocalDate.now().getDayOfMonth();
    private static final LocalDate REPORT = LocalDate.now().withDayOfMonth(1);

    private MerchantDailyMetrics compute(Map<Integer, Double> daily) {
        return svc.computeMetrics("M1", "MID1", "Acme", daily,
                REPORT, MerchantDailyMetrics.SourceType.FILE_UPLOAD);
    }

    private Map<Integer, Double> map(Object... kv) {
        Map<Integer, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (Double) kv[i + 1]);
        return m;
    }

    // ---- totals & provenance ------------------------------------------------

    @Test
    @DisplayName("totalMtd is the sum of all daily volumes")
    void totalMtdSum() {
        MerchantDailyMetrics m = compute(map(1, 100.0, 2, 200.0, 3, 300.0));
        assertEquals(600.0, m.getTotalMtd(), 0.0001);
        assertEquals("M1", m.getMerchantId());
        assertEquals("MID1", m.getMid());
        assertEquals("Acme", m.getMerchantName());
        assertEquals(MerchantDailyMetrics.SourceType.FILE_UPLOAD, m.getSourceType());
        assertEquals(REPORT, m.getReportDate());
    }

    @Test
    @DisplayName("empty map -> zero totals, Low volatility, base risk 10, Stable, JSON populated")
    void emptyMap() {
        MerchantDailyMetrics m = compute(new HashMap<>());
        assertEquals(0.0, m.getTotalMtd(), 0.0001);
        assertEquals(0.0, m.getTodayVolume(), 0.0001);
        assertEquals(0.0, m.getYesterdayVolume(), 0.0001);
        assertEquals(0.0, m.getTrendPct(), 0.0001);
        assertEquals("Low", m.getVolatility());
        assertEquals(10, m.getRiskScore());
        assertEquals("Stable", m.getUiStatus());
        assertNotNull(m.getDailyVolumesJson());
        assertNotNull(m.getSparklineDataJson());
        assertTrue(m.getSparklineDataJson().startsWith("["));
        assertTrue(m.getDailyVolumesJson().startsWith("{"));
    }

    // ---- today / yesterday / trend -----------------------------------------

    @Test
    @DisplayName("today and yesterday volumes are read from the day-of-month keys")
    void todayYesterdayExtraction() {
        assumeTrue(TODAY >= 2, "needs a 'yesterday' slot");
        MerchantDailyMetrics m = compute(map(TODAY, 300.0, TODAY - 1, 100.0));
        assertEquals(300.0, m.getTodayVolume(), 0.0001);
        assertEquals(100.0, m.getYesterdayVolume(), 0.0001);
        assertEquals(200.0, m.getTrendPct(), 0.0001); // (300-100)/100 * 100
    }

    @Test
    @DisplayName("trend is +100% when yesterday is zero and today is positive")
    void trendYesterdayZero() {
        Map<Integer, Double> daily = map(TODAY, 50.0);
        if (TODAY >= 2) daily.remove(TODAY - 1); // ensure no yesterday value
        MerchantDailyMetrics m = compute(daily);
        assertEquals(50.0, m.getTodayVolume(), 0.0001);
        assertEquals(0.0, m.getYesterdayVolume(), 0.0001);
        assertEquals(100.0, m.getTrendPct(), 0.0001);
    }

    @Test
    @DisplayName("trend is zero when both today and yesterday are zero")
    void trendBothZero() {
        Map<Integer, Double> daily = map(TODAY, 0.0);
        if (TODAY >= 2) daily.put(TODAY - 1, 0.0);
        MerchantDailyMetrics m = compute(daily);
        assertEquals(0.0, m.getTrendPct(), 0.0001);
    }

    // ---- volatility (coefficient of variation) ------------------------------

    @Test
    @DisplayName("fewer than 2 active days -> Low volatility")
    void volatilityLowSingleActive() {
        assertEquals("Low", compute(map(1, 500.0)).getVolatility());
    }

    @Test
    @DisplayName("equal active volumes -> Low volatility (cv 0)")
    void volatilityLowEqual() {
        assertEquals("Low", compute(map(1, 100.0, 2, 100.0, 3, 100.0)).getVolatility());
    }

    @Test
    @DisplayName("skewed volumes (cv ~1.2) -> Medium volatility")
    void volatilityMedium() {
        assertEquals("Medium", compute(map(1, 100.0, 2, 100.0, 3, 100.0, 4, 1000.0)).getVolatility());
    }

    @Test
    @DisplayName("heavily skewed volumes (cv ~2.0) -> High volatility")
    void volatilityHigh() {
        assertEquals("High", compute(map(1, 1.0, 2, 1.0, 3, 1.0, 4, 1.0, 5, 1000.0)).getVolatility());
    }

    // ---- avg 7 day ----------------------------------------------------------

    @Test
    @DisplayName("avg-7-day averages today's value over the trailing in-month window")
    void avg7Day() {
        MerchantDailyMetrics m = compute(map(TODAY, 700.0));
        int n = Math.min(7, TODAY);
        assertEquals(700.0 / n, m.getAvg7Day(), 0.0001);
    }

    // ---- sparkline JSON -----------------------------------------------------

    @Test
    @DisplayName("sparkline JSON carries the day's value")
    void sparklineContainsValue() {
        MerchantDailyMetrics m = compute(map(5, 1234.5));
        assertTrue(m.getSparklineDataJson().contains("1234.5"));
    }

    // ---- risk score & ui status (calendar-guarded) --------------------------

    @Test
    @DisplayName("a same-day spike (>3x avg7 and >1000) forces risk score 90 -> Risk")
    void spikeForcesRisk() {
        assumeTrue(TODAY >= 4, "spike needs >=4 days in the avg-7 window");
        MerchantDailyMetrics m = compute(map(TODAY, 5000.0));
        assertEquals(90, m.getRiskScore());
        assertEquals("Risk", m.getUiStatus());
    }

    @Test
    @DisplayName("a trend crash (<-50%) with medium volatility -> risk 50 -> Watch")
    void trendCrashWatch() {
        assumeTrue(TODAY >= 2, "needs a 'yesterday' slot");
        MerchantDailyMetrics m = compute(map(TODAY - 1, 1000.0, TODAY, 100.0));
        assertEquals(50, m.getRiskScore());  // base 10 + medium 15 + crash 25
        assertEquals("Watch", m.getUiStatus());
    }

    @Test
    @DisplayName("a flatline (today 0, avg7 > 500) raises risk via the flatline rule")
    void flatlineRaisesRisk() {
        assumeTrue(TODAY >= 2, "needs prior days to build avg7 > 500");
        Map<Integer, Double> daily = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            int d = TODAY - i;
            if (d > 0) daily.put(d, 2000.0);
        }
        // today intentionally absent -> 0
        MerchantDailyMetrics m = compute(daily);
        assertEquals(75, m.getRiskScore()); // base 10 + crash 25 + flatline 40
        assertEquals("Watch", m.getUiStatus());
    }

    // ---- ui status thresholds ----------------------------------------------

    @Test
    @DisplayName("base risk (10) maps to Stable ui status")
    void baseStable() {
        assertEquals("Stable", compute(map(15, 250.0)).getUiStatus());
    }
}
