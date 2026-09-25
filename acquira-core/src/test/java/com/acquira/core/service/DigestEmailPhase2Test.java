package com.acquira.core.service;

import com.acquira.core.service.DigestContentService.DayPoint;
import com.acquira.core.service.DigestContentService.DigestData;
import com.acquira.core.service.DigestContentService.LossLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 renderer contract: MTD cumulative + straight-line projection, the
 * 14-day trend strip, the DCC opt-in rate and the loss-making card render only
 * when their data exists, and their arithmetic is right.
 */
class DigestEmailPhase2Test {

    private static Map<String, BigDecimal> totals() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("cnt", new BigDecimal("1000"));
        m.put("vol", new BigDecimal("250000.000"));
        m.put("msf", new BigDecimal("3000.000"));
        m.put("icf", new BigDecimal("1500.000"));
        m.put("sf", new BigDecimal("300.000"));
        m.put("pg", new BigDecimal("100.000"));
        m.put("nm", new BigDecimal("1100.000"));
        m.put("dcc", BigDecimal.ZERO);
        m.put("rental", BigDecimal.ZERO);
        m.put("fx", BigDecimal.ZERO);
        m.put("spread", new BigDecimal("1100.000"));
        return m;
    }

    private static DigestData base() {
        DigestData d = new DigestData();
        d.businessDate = LocalDate.of(2026, 9, 15); // day 15 of a 30-day month → factor 2
        d.institution = "AFSB";
        d.currency = "BHD";
        d.totals = totals();
        d.prevWeek = totals();
        return d;
    }

    private final DigestEmailService svc = new DigestEmailService();

    @Test
    @DisplayName("no MTD data, no trend, no DCC, no losses → none of the new cards render")
    void emptyDataRendersNothingNew() {
        String html = svc.render(base());
        assertFalse(html.contains("Month to date"));
        assertFalse(html.contains("Last 14 days"));
        assertFalse(html.contains("DCC opt-in"));
        assertFalse(html.contains("Loss-making merchants"));
    }

    @Test
    @DisplayName("MTD card shows cumulative figures and a straight-line month-end projection")
    void mtdAndProjection() {
        DigestData d = base();
        d.mtdDays = 15;
        d.mtdTotals = new LinkedHashMap<>();
        d.mtdTotals.put("cnt", new BigDecimal("15000"));
        d.mtdTotals.put("vol", new BigDecimal("3600000")); // ×2 → 7.2M
        d.mtdTotals.put("msf", new BigDecimal("45000"));
        d.mtdTotals.put("spread", new BigDecimal("16500")); // ×2 → 33K
        String html = svc.render(d);
        assertTrue(html.contains("Month to date (15 loaded days)"));
        assertTrue(html.contains("BHD 3.60M"), "MTD cumulative volume");
        assertTrue(html.contains("Straight-line month-end (day 15 of 30)"));
        assertTrue(html.contains("BHD 7.20M"), "projected volume = MTD × 30/15");
        assertTrue(html.contains("BHD 33,000.000"), "projected spread = MTD × 30/15");
    }

    @Test
    @DisplayName("on the last day of the month the projection line is dropped")
    void noProjectionOnMonthEnd() {
        DigestData d = base();
        d.businessDate = LocalDate.of(2026, 9, 30);
        d.mtdDays = 30;
        d.mtdTotals = new LinkedHashMap<>(Map.of("vol", new BigDecimal("7200000")));
        String html = svc.render(d);
        assertTrue(html.contains("Month to date (30 loaded days)"));
        assertFalse(html.contains("Straight-line month-end"));
    }

    @Test
    @DisplayName("trend strip renders one bar per loaded day, today bolded")
    void trendStrip() {
        DigestData d = base();
        d.trend14 = List.of(
                new DayPoint(LocalDate.of(2026, 9, 14), new BigDecimal("100000"), new BigDecimal("500")),
                new DayPoint(LocalDate.of(2026, 9, 15), new BigDecimal("250000"), new BigDecimal("1100")));
        String html = svc.render(d);
        assertTrue(html.contains("Last 14 days — volume"));
        assertTrue(html.contains("Mon 14 Sep"));
        assertTrue(html.contains("Tue 15 Sep"));
        // Widest bar (today's 250K = max) is 220px; the 100K day scales to 88px.
        assertTrue(html.contains("width=\"220\""));
        assertTrue(html.contains("width=\"88\""));
    }

    @Test
    @DisplayName("a single loaded day is not a trend — strip suppressed")
    void trendNeedsTwoDays() {
        DigestData d = base();
        d.trend14 = List.of(new DayPoint(LocalDate.of(2026, 9, 15), new BigDecimal("250000"), BigDecimal.ZERO));
        assertFalse(svc.render(d).contains("Last 14 days"));
    }

    @Test
    @DisplayName("DCC opt-in card shows rates by volume and by count")
    void dccOptInRates() {
        DigestData d = base();
        d.dccEligibleVol = new BigDecimal("40000");
        d.dccOptinVol = new BigDecimal("10000");   // 25.0% by volume
        d.dccEligibleCnt = 200;
        d.dccOptinCnt = 90;                        // 45.0% by count
        String html = svc.render(d);
        assertTrue(html.contains("DCC opt-in"));
        assertTrue(html.contains("25.0%"));
        assertTrue(html.contains("45.0% by count"));
    }

    @Test
    @DisplayName("loss-making card names the count and the worst merchants in red")
    void lossMakingCard() {
        DigestData d = base();
        d.lossCount = 7;
        d.lossTop = List.of(
                new LossLine("Corner Cafe", "900000001", new BigDecimal("52000"), new BigDecimal("-310.500")),
                new LossLine("Gift Shop", "900000002", new BigDecimal("11000"), new BigDecimal("-42.250")));
        String html = svc.render(d);
        assertTrue(html.contains("Loss-making merchants (month to date)"));
        assertTrue(html.contains(">7</b> merchants have"));
        assertTrue(html.contains("Corner Cafe"));
        assertTrue(html.contains("BHD -310.500"));
    }
}
