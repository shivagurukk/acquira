package com.acquira.pdf;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.dto.MerchantInsightsDTO.ChartData;
import com.acquira.common.dto.MerchantInsightsDTO.Kpi;
import com.acquira.pdf.config.ThymeleafConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the Insight PDF pages that were wrong on the Bahrain 2026-09 report
 * (UNITED ELECTRONICS, MID ...29660) against a fixture shaped like that
 * tenant: BHD with 3 decimals, Fri+Sat weekend, Bahrain ticket bands, a
 * one-month quarter at the start of the history, and a 6+ visit tier that
 * rounds to 0% as a whole number.
 */
class InsightPdfTemplatesBahrainTest {

    private static SpringTemplateEngine engine;
    private static MerchantInsightsDTO dto;

    @BeforeAll
    static void setUp() {
        engine = new ThymeleafConfig().pdfTemplateEngine();
        dto = bahrainFixture();
    }

    private static String render(String page, String fragment) {
        Context ctx = new Context();
        ctx.setVariable("dto", dto);
        ctx.setVariable("merchantName", "UNITED ELECTRONICS COMPANY");
        ctx.setVariable("reportPeriod", "September 2026");
        return engine.process("pages/" + page, Set.of(fragment), ctx);
    }

    @Test
    void transactionSizeDistributionShowsEveryBahrainBand() {
        String html = render("p04b-heatmap", "heatmapContent");
        for (String band : List.of("&lt; 5", "5-10", "10-25", "25-50", "50-100", "100-500", "500+")) {
            assertTrue(html.contains(band), "missing band " + band);
        }
    }

    @Test
    void averageTicketByCardTypeKeepsFils() {
        String html = render("p08-card-analytics", "cardAnalyticsContent");
        assertTrue(html.contains("BHD 178.390"), "credit average must keep its fils, got whole-dinar rounding");
        assertFalse(html.contains("BHD 178.000"));
    }

    @Test
    void areasOfChangeIsNeverBlank() {
        String html = render("p01c-exec-summary", "execSummaryContent");
        assertTrue(html.contains("No headline metric declined versus the previous month"));
        assertTrue(html.contains("Weekend = <span>Friday &amp; Saturday</span>"));
    }

    @Test
    void loyaltySegmentsIncludeFrequentBandAndSumTo100() {
        String html = render("p09-customer-intel", "customerIntelContent");
        assertTrue(html.contains("Frequent (6+)"));
        assertTrue(html.contains(">0.3%<"), "6+ share must print even when it rounds to 0 as a whole number");
        assertTrue(html.contains(">1.6%<"));
        assertTrue(html.contains(">7.5%<"));
        assertTrue(html.contains(">90.6%<"), "single-visit share must be the remainder so the column totals 100%");
        assertFalse(html.contains("Loyal (6+)"));
    }

    @Test
    void quarterlyGrowthIsSuppressedAgainstAPartialQuarter() {
        String html = render("p07-growth", "growthContent");
        assertTrue(html.contains("PARTIAL (<span>1</span>/3 mo)"), "one-month Q3 2025 must be flagged partial");
        assertFalse(html.contains("+221.5%"), "growth off a one-month base must not be printed");
        assertTrue(html.contains("<sup>†</sup>"));
        // Q1 2026 vs Q4 2025 are both complete, so that growth still prints.
        assertTrue(html.contains("+10.0%"));
    }

    @Test
    void glossaryNoLongerPromisesAFixedDccShare() {
        String html = render("p13-glossary", "glossaryContent");
        assertFalse(html.contains("approximately 3%"));
        assertTrue(html.contains("merchant agreement"));
        assertTrue(html.contains("Friday &amp; Saturday"));
    }

    // ── fixture ───────────────────────────────────────────────────────────

    private static Kpi kpi(String value, double mom) {
        return new Kpi(new BigDecimal(value), mom, value, mom >= 0 ? "up" : "down");
    }

    private static ChartData cd(String label, String v, String v2, String v3) {
        return ChartData.builder().label(label).value(new BigDecimal(v))
                .value2(v2 == null ? null : new BigDecimal(v2))
                .value3(v3 == null ? null : new BigDecimal(v3)).build();
    }

    private static MerchantInsightsDTO bahrainFixture() {
        MerchantInsightsDTO d = new MerchantInsightsDTO();
        d.setCurrencyCode("BHD");
        d.setCurrencyDecimals(3);

        MerchantInsightsDTO.PeakStats peak = MerchantInsightsDTO.PeakStats.builder()
                .maxDailySales(kpi("52000.000", 4.0))
                .maxTxnsInDay(kpi("410", 2.0))
                .highestTxnValue(kpi("5449.750", 1.0))
                .highestCustomerSpend(kpi("14181.780", 3.0))
                .build();
        d.setOverview(MerchantInsightsDTO.BusinessOverview.builder()
                .sales(kpi("880000.000", 5.2)).transactions(kpi("6140", 3.1))
                .customers(kpi("5278", 2.4)).avgTxnValue(kpi("143.322", 2.0))
                .avgSpendPerCustomer(kpi("166.730", 1.0)).avgTxnsPerCustomer(kpi("1.2", 0.5))
                .prevSales(kpi("836000.000", 0)).prevTransactions(kpi("5955", 0))
                .prevCustomers(kpi("5154", 0)).prevAvgTxnValue(kpi("140.500", 0))
                .peakStats(peak).peakDayName("Saturday")
                .weekdayRevenuePct(new BigDecimal(68)).weekendRevenuePct(new BigDecimal(32))
                .weekendLabel("Friday & Saturday").weekendDayCount(2)
                .salesByDayOfWeek(new ArrayList<>()).transactionsByDayOfWeek(new ArrayList<>())
                .salesByWeekOfMonth(new ArrayList<>()).transactionsByWeekOfMonth(new ArrayList<>())
                .build());

        // Bahrain ticket bands exactly as the batch writes them from ticket_size_bucket.
        List<ChartData> bands = List.of(
                cd("< 5", "351", "5.7", "1000"), cd("5-10", "618", "10.1", "4500"),
                cd("10-25", "1134", "18.5", "19000"), cd("25-50", "1016", "16.5", "37000"),
                cd("50-100", "863", "14.1", "62000"), cd("100-500", "1801", "29.3", "380000"),
                cd("500+", "357", "5.8", "376500"));
        d.setAchievements(MerchantInsightsDTO.BusinessAchievements.builder()
                .txnSizeDistribution(bands).txnSizeDistributionExtended(bands).build());

        MerchantInsightsDTO.ConsumerLoyalty loyalty = MerchantInsightsDTO.ConsumerLoyalty.builder()
                .retentionRate(new BigDecimal(9)).repeatCardPct(new BigDecimal(9))
                .totalUniqueCards(new BigDecimal(5278))
                .visitFrequency(new ArrayList<>()).spendBands(new ArrayList<>())
                .monthlyVisitFreqTrend(new ArrayList<>()).build();
        loyalty.setRepeatTier2Count(397L); loyalty.setRepeatTier35Count(84L); loyalty.setRepeatTier6Count(16L);
        loyalty.setRepeatTier2Pct(new BigDecimal("7.5"));
        loyalty.setRepeatTier35Pct(new BigDecimal("1.6"));
        loyalty.setRepeatTier6Pct(new BigDecimal("0.3"));
        d.setLoyalty(loyalty);

        MerchantInsightsDTO.CustomerDemographics demo = new MerchantInsightsDTO.CustomerDemographics();
        demo.setAvgTicketByCardType(List.of(
                cd("Credit", "178.390", "256170.040", "1436"),
                cd("Debit", "126.845", "423000.000", "3335"),
                cd("Prepaid", "61.250", "22500.000", "367")));
        demo.setCreditPct(new BigDecimal(36)); demo.setCreditVolume(new BigDecimal("256170.040")); demo.setCreditTxnCount(1436L);
        demo.setDebitPct(new BigDecimal(60)); demo.setDebitVolume(new BigDecimal("423000.000")); demo.setDebitTxnCount(3335L);
        demo.setPrepaidPct(new BigDecimal(4)); demo.setPrepaidVolume(new BigDecimal("22500.000")); demo.setPrepaidTxnCount(367L);
        demo.setLocalCardPct(new BigDecimal(95)); demo.setLocalCardVolume(new BigDecimal("836000.000")); demo.setLocalCardCustomers(5852L);
        demo.setInternationalCardPct(new BigDecimal(5)); demo.setInternationalCardVolume(new BigDecimal("44000.000")); demo.setInternationalCardCustomers(288L);
        demo.setCardSchemeValueSplit(new LinkedHashMap<>(Map.of("VISA", new BigDecimal(300000), "MASTERCARD", new BigDecimal(400000))));
        List<ChartData> monthly = new ArrayList<>();
        for (int i = 0; i < 13; i++) monthly.add(cd("M" + i, "100000", null, null));
        demo.setMonthlySales(monthly);
        // History starts in Sep 2025: Q3 2025 has one month, Q4 2025 three (+221.5% off the
        // one-month base — the figure the merchant must not see), Q1 2026 +10%.
        demo.setQuarterlyBreakdown(List.of(
                ChartData.builder().label("Q3 2025").value(new BigDecimal("311000.000")).value2(new BigDecimal(2100)).value3(new BigDecimal("148.095")).value4(new BigDecimal(1)).build(),
                ChartData.builder().label("Q4 2025").value(new BigDecimal("999865.000")).value2(new BigDecimal(7000)).value3(new BigDecimal("142.838")).value4(new BigDecimal(3)).build(),
                ChartData.builder().label("Q1 2026").value(new BigDecimal("1099851.500")).value2(new BigDecimal(7600)).value3(new BigDecimal("144.717")).value4(new BigDecimal(3)).build()));
        demo.setPeakSeason("Q1 2026"); demo.setLowSeason("Q3 2025"); demo.setBestMonth("March 2026");
        d.setDemographics(demo);

        d.setDccPerformance(MerchantInsightsDTO.DccPerformance.builder()
                .dccEligibleVolume(new BigDecimal("44000.000")).dccOptinVolume(new BigDecimal("1800.000"))
                .dccOptoutVolume(new BigDecimal("42200.000")).dccConversionRate(new BigDecimal(4))
                .build());
        return d;
    }
}
