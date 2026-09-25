package com.acquira.core.service;

import com.acquira.core.service.DigestContentService.DigestData;
import com.acquira.core.service.DigestFeedCoverage.FeedState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 renderer contract: a feed that is not loaded is never printed as
 * 0.000 (I-1), a restatement says so before any number (I-2), and the subject
 * carries no figures unless the tenant opts in (I-8).
 */
class DigestEmailCoverageTest {

    private static Map<String, BigDecimal> totals(String dcc, String rental) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("cnt", new BigDecimal("1000"));
        m.put("vol", new BigDecimal("250000.000"));
        m.put("msf", new BigDecimal("3000.000"));
        m.put("icf", new BigDecimal("1500.000"));
        m.put("sf", new BigDecimal("300.000"));
        m.put("pg", new BigDecimal("100.000"));
        m.put("nm", new BigDecimal("1100.000"));
        m.put("dcc", new BigDecimal(dcc));
        m.put("rental", new BigDecimal(rental));
        m.put("fx", BigDecimal.ZERO);
        m.put("spread", new BigDecimal("1100.000").add(new BigDecimal(dcc)).add(new BigDecimal(rental)));
        return m;
    }

    private static DigestData data(String dcc, String rental, boolean dccPresent, boolean rentalPresent) {
        DigestData d = new DigestData();
        d.businessDate = LocalDate.of(2026, 9, 23);
        d.institution = "AFSB";
        d.currency = "BHD";
        d.totals = totals(dcc, rental);
        d.prevWeek = totals(dcc, rental);
        d.coverage = new DigestFeedCoverage(List.of(
                new FeedState(DigestFeedCoverage.MERCHANT, "Merchant master", false, true),
                new FeedState(DigestFeedCoverage.TRX, "Transactions", true, true),
                new FeedState(DigestFeedCoverage.DCC, "DCC revenue", false, dccPresent),
                new FeedState(DigestFeedCoverage.RENTAL, "Rentals", false, rentalPresent)),
                48213L, Timestamp.valueOf(LocalDateTime.of(2026, 9, 24, 7, 12)), false);
        return d;
    }

    private final DigestEmailService svc = new DigestEmailService();

    @Test
    @DisplayName("an absent feed prints 'not loaded', never 0.000, and Net Spread says it is excluded")
    void absentFeedIsNotZero() {
        String html = svc.render(data("0", "0", false, false));
        assertTrue(html.contains("not loaded"), "absent DCC / rental rows must read 'not loaded'");
        assertTrue(html.contains("Net Spread excludes DCC and rentals (not loaded)"));
        assertFalse(html.contains("DCC income (acquirer share)</td><td align=\"right\" style=\"padding:4px 0;font-family:'Courier New',monospace;\">BHD 0.000"),
                "the DCC row must not carry a zero amount when the feed is absent");
        assertTrue(html.contains("Data coverage"));
        assertTrue(html.contains("48,213 rows"));
        assertTrue(html.contains("not tracked for this bank"));
    }

    @Test
    @DisplayName("a loaded feed with a genuine zero still prints the zero")
    void loadedZeroStaysZero() {
        String html = svc.render(data("0", "1875.000", true, true));
        assertFalse(html.contains("not loaded"));
        assertFalse(html.contains("Net Spread excludes"));
        assertTrue(html.contains("BHD 0.000"), "a real zero DCC day shows 0.000");
        assertTrue(html.contains("BHD 1,875.000"));
    }

    @Test
    @DisplayName("a required feed that is missing is flagged as incomplete in the coverage banner")
    void requiredMissingIsFlagged() {
        DigestData d = data("0", "0", false, true);
        d.coverage = new DigestFeedCoverage(List.of(
                new FeedState(DigestFeedCoverage.TRX, "Transactions", true, true),
                new FeedState(DigestFeedCoverage.DCC, "DCC revenue", true, false)), 10L, null, false);
        String html = svc.render(d);
        assertTrue(html.contains("required, figures are incomplete"));
    }

    @Test
    @DisplayName("no coverage information (legacy preview) renders exactly as before")
    void noCoverageRendersLegacy() {
        DigestData d = data("0", "0", false, false);
        d.coverage = DigestFeedCoverage.none();
        String html = svc.render(d);
        assertFalse(html.contains("Data coverage"));
        assertFalse(html.contains("not loaded"));
    }

    @Test
    @DisplayName("a restatement is labelled in the subject and in a banner before the numbers")
    void restatementIsLabelled() {
        DigestData d = data("10", "0", true, true);
        d.restateNo = 2;
        d.originalSentAt = LocalDateTime.of(2026, 9, 24, 8, 5);
        String html = svc.render(d);
        assertTrue(html.contains("RESTATED (revision 2)"));
        assertTrue(html.contains("24 Sep 2026 08:05"));
        assertTrue(html.indexOf("RESTATED") < html.indexOf("Headline"), "banner comes before the KPI cards");
        assertTrue(svc.subject(d).startsWith("[RESTATED] Daily Digest"));
    }

    @Test
    @DisplayName("subject carries figures only when the tenant opts in")
    void subjectPrivacy() {
        DigestData d = data("10", "0", true, true);
        String plain = svc.subject(d);
        assertEquals("Daily Digest — AFSB — 2026-09-23", plain);
        assertFalse(plain.contains("Vol"));
        String withFigures = svc.subject(d, true);
        assertTrue(withFigures.contains("Vol BHD 250.0K"));
        assertTrue(withFigures.contains("Net Spread"));
    }
}
