package com.acquira.batch.job;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the ancillary-feed (rental + DCC) date coverage.
 *
 * The case that motivated the class: an AFS Bahrain rental file carrying
 * '08-MAY-26'. The previous per-job parsers used plain ofPattern(...), which
 * is case-sensitive on month names and had no two-digit-year pattern, so every
 * row of such a file staged with a NULL date and was rejected as "Missing
 * rental amount or payment date" — a whole feed lost with nothing thrown.
 */
class FeedDateParserTest {

    private static final LocalDate MAY_8 = LocalDate.of(2026, 5, 8);

    @Test
    void parsesBahrainExportFormat() {
        // The exact failing cell.
        assertEquals(MAY_8, FeedDateParser.parse("08-MAY-26"));
    }

    @Test
    void bahrainFormatToleratesMonthCaseAndDayAndYearWidth() {
        for (String s : new String[]{
                "08-MAY-26", "08-May-26", "08-may-26",
                "8-MAY-26", "8-May-26",
                "08-MAY-2026", "08-May-2026", "8-MAY-2026"}) {
            assertEquals(MAY_8, FeedDateParser.parse(s), "should parse " + s);
        }
    }

    @Test
    void parsesTheOtherAcceptedFormats() {
        assertEquals(MAY_8, FeedDateParser.parse("2026-05-08"));
        assertEquals(MAY_8, FeedDateParser.parse("2026/05/08"));
        assertEquals(MAY_8, FeedDateParser.parse("08/05/2026"));
        assertEquals(MAY_8, FeedDateParser.parse("08-05-2026"));
        assertEquals(MAY_8, FeedDateParser.parse("08 MAY 2026"));
    }

    @Test
    void keepsTheDatePartOfADatetime() {
        assertEquals(MAY_8, FeedDateParser.parse("2026-05-08 13:04:22"));
        // Short date + time: the old fixed charAt(10) offset could not do this.
        assertEquals(MAY_8, FeedDateParser.parse("8-MAY-26 13:04"));
    }

    @Test
    void readsExcelSerialDates() {
        // 1899-12-30 + 46150 days = 2026-05-08
        assertEquals(MAY_8, FeedDateParser.parse("46150"));
    }

    @Test
    void returnsNullForBlankAndUnparseable() {
        assertNull(FeedDateParser.parse(null));
        assertNull(FeedDateParser.parse(""));
        assertNull(FeedDateParser.parse("   "));
        assertNull(FeedDateParser.parse("not a date"));
        assertNull(FeedDateParser.parse("08-XYZ-26"));
    }

    @Test
    void parsesAllNumericTwoDigitYearDayFirst() {
        // CMM rental sample cell '03-09-26' = 3 September 2026 (day-first,
        // consistent with dd-MM-yyyy above).
        assertEquals(LocalDate.of(2026, 9, 3), FeedDateParser.parse("03-09-26"));
        assertEquals(MAY_8, FeedDateParser.parse("08-05-26"));
    }

    @Test
    void twoDigitYearPivotsTo2000s() {
        assertEquals(LocalDate.of(2025, 10, 21), FeedDateParser.parse("21-OCT-25"));
        assertEquals(LocalDate.of(2026, 8, 1), FeedDateParser.parse("01-AUG-26"));
    }
}
