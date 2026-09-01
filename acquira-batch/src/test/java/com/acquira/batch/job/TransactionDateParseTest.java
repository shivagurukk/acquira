package com.acquira.batch.job;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the October date bug (2026-09-01).
 *
 * parseDate() had an ISO-8601 fast path gated on v.contains("T"). "OCT" is the
 * only month abbreviation containing a 'T', so the BH feed's "21-OCT-25" was
 * routed to LocalDateTime.parse(), threw, and the outer catch returned null --
 * NULLing every payment_date in every October file. The staging-to-fact
 * data-quality gate then (correctly) rejected the whole upload:
 * "N row(s) reached staging but NONE has a usable payment_date."
 *
 * Every other month loaded fine, which is what made it look like an
 * October-file problem rather than a parser problem.
 */
class TransactionDateParseTest {

    private static LocalDateTime parse(String value) throws Exception {
        Constructor<TransactionJobConfig> ctor =
                TransactionJobConfig.class.getDeclaredConstructor(
                        org.springframework.batch.core.repository.JobRepository.class,
                        org.springframework.transaction.PlatformTransactionManager.class,
                        javax.sql.DataSource.class,
                        org.springframework.jdbc.core.JdbcTemplate.class,
                        com.acquira.common.service.MerchantMetricCalculator.class,
                        com.acquira.common.repository.SumDailyMerchantRepository.class,
                        com.acquira.common.repository.SumMonthlyMerchantMetricsRepository.class,
                        com.acquira.batch.service.PartitionMaintenanceService.class,
                        com.acquira.common.service.ChurnScoringService.class,
                        com.acquira.common.service.MerchantSegmentationService.class);
        ctor.setAccessible(true);
        TransactionJobConfig cfg = ctor.newInstance(
                null, null, null, null, null, null, null, null, null, null);
        Method m = TransactionJobConfig.class.getDeclaredMethod("parseDate", String.class);
        m.setAccessible(true);
        return (LocalDateTime) m.invoke(cfg, value);
    }

    @Test
    void octoberMonthNameDatesParse() throws Exception {
        assertEquals(LocalDateTime.of(2025, 10, 21, 0, 0), parse("21-OCT-25"));
        assertEquals(LocalDateTime.of(2025, 10, 1, 0, 0), parse("01-OCT-25"));
        assertEquals(LocalDateTime.of(2026, 10, 5, 0, 0), parse("5-Oct-2026"));
    }

    @Test
    void everyMonthAbbreviationParses() throws Exception {
        String[] months = {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
        for (int i = 0; i < months.length; i++) {
            assertEquals(LocalDateTime.of(2026, i + 1, 15, 0, 0),
                    parse("15-" + months[i] + "-26"), "month " + months[i]);
        }
    }

    @Test
    void isoTimestampsStillParse() throws Exception {
        assertEquals(LocalDateTime.of(2026, 8, 1, 9, 15, 30), parse("2026-08-01T09:15:30"));
    }

    @Test
    void existingFormatsUnchanged() throws Exception {
        assertEquals(LocalDateTime.of(2026, 8, 31, 0, 0), parse("31-AUG-26"));
        assertEquals(LocalDateTime.of(2026, 8, 31, 0, 0), parse("31/08/2026"));
        assertEquals(LocalDateTime.of(2026, 8, 31, 0, 0), parse("2026-08-31"));
        assertEquals(LocalDateTime.of(2026, 8, 31, 14, 5, 0), parse("31/08/2026 14:05"));
        assertNull(parse("not a date"));
        assertNull(parse(""));
        assertNull(parse(null));
    }
}
