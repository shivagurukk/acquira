package com.acquira.batch.job;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

/**
 * The ONE date parser for the ancillary feeds (rental, DCC revenue).
 *
 * WHY THIS CLASS EXISTS
 * ---------------------
 * RentalJobConfig and DccRevenueJobConfig each carried their own copy of a
 * parseDate built from plain {@code DateTimeFormatter.ofPattern(...)}. That
 * form is case-SENSITIVE on month names and has no two-digit-year pattern, so
 * the AFS Bahrain export format — '08-MAY-26' — failed on BOTH counts:
 *
 *   ofPattern("dd-MMM-yyyy").parse("08-MAY-26")   -> fails (yy vs yyyy)
 *   ofPattern("dd-MMM-yyyy").parse("08-MAY-2026") -> fails ('MAY' vs 'May')
 *
 * A failed parse returns null, the apply step then rejects the row as
 * "Missing rental amount or payment date", and a whole BH file lands as 100%
 * REJECTED with nothing crashing — Net Spread silently shows no rental or DCC
 * revenue. TransactionJobConfig already solved this for the same bank's
 * transaction feed (parseCaseInsensitive + yy patterns + Locale.ENGLISH,
 * see its D_FORMATTERS); the ancillary feeds now share that coverage from
 * here instead of re-deriving it a third time.
 *
 * Locale.ENGLISH is explicit so month names parse the same way regardless of
 * the server's default locale. {@code yy} pivots to 2000-2099 under the
 * SMART resolver's default base of 2000.
 */
public final class FeedDateParser {

    private FeedDateParser() {}

    /** Case-insensitive, English, ordered most- to least-specific. */
    private static final DateTimeFormatter[] FORMATS = {
        ci("yyyy-MM-dd"),
        ci("yyyy/MM/dd"),
        ci("dd/MM/yyyy"),
        ci("MM/dd/yyyy"),
        ci("dd-MM-yyyy"),
        // All-numeric two-digit year ('03-09-26'), seen in a CMM rental
        // sample. DAY-FIRST like dd-MM-yyyy above — a US-style MM-dd-yy cell
        // parses without error but lands on the wrong date, so a feed known to
        // be month-first must be converted before upload, not sent as-is.
        ci("dd-MM-yy"),
        // BH feed: '08-MAY-26' / '01-AUG-26'. Both the two-digit year and the
        // upper-case month are load-bearing — see the class javadoc.
        ci("dd-MMM-yy"),
        ci("dd-MMM-yyyy"),
        ci("d-MMM-yy"),
        ci("d-MMM-yyyy"),
        ci("dd MMM yyyy"),
        ci("dd MMM yy"),
    };

    private static DateTimeFormatter ci(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    /**
     * Parses a feed date cell, or returns null if no format matches (the
     * caller stages null and the apply step rejects the row with a reason).
     *
     * Handles Excel serial numbers, and drops the time part of a datetime
     * string — the ancillary feeds are day-grain.
     */
    public static LocalDate parse(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        String s = val.trim();

        // Excel serial date (ExcelItemReader hands numerics through as strings).
        if (s.matches("\\d+(?:\\.\\d+)?")) {
            try {
                double serial = Double.parseDouble(s);
                if (serial > 1 && serial < 92000) {
                    return LocalDate.of(1899, 12, 30).plusDays((long) serial);
                }
            } catch (NumberFormatException ignored) {}
        }

        // Datetime strings: keep the date part. Split on the separator rather
        // than a fixed offset, so a short date with a time ('8-MAY-26 13:04')
        // is handled as well as a long one.
        int sp = s.indexOf(' ');
        if (sp > 0 && s.indexOf(':') > sp) s = s.substring(0, sp);

        for (DateTimeFormatter f : FORMATS) {
            try { return LocalDate.parse(s, f); } catch (Exception ignored) {}
        }
        return null;
    }
}
