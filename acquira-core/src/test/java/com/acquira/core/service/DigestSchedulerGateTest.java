package com.acquira.core.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gate is where the business risk sits (Phase 1, I-16): which day may
 * send, what it waits on, and that a day is claimed exactly once. The SQL
 * presence probes are stubbed by fragment so each rule is exercised without a
 * database.
 */
class DigestSchedulerGateTest {

    /** Presence probes answer true when their SQL contains a registered fragment. */
    static class Probe extends DigestScheduler {
        final Set<String> present = new HashSet<>();
        ZoneId zone = ZoneId.of("UTC");

        Probe(JdbcTemplate jdbc) {
            super(jdbc, mock(DigestContentService.class), mock(DigestEmailService.class), mock(EmailService.class));
        }

        @Override
        protected boolean exists(String sql, Object... params) {
            return present.stream().anyMatch(sql::contains);
        }

        @Override
        protected ZoneId tenantZone(long tenantId) {
            return zone;
        }
    }

    static final String MERCHANT = "dim_merchant";
    static final String TRX = "SELECT 1 FROM ingest_day_coverage";
    static final String DCC = "fact_dcc_revenue";
    static final String RENTAL = "fact_rental";
    static final String RUNNING = "status = 'RUNNING'";
    static final String QUIET = "ended_at >";

    private JdbcTemplate jdbc;
    private Probe s;
    private Map<String, Object> cfg;
    private LocalDate yesterday;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        s = new Probe(jdbc);
        cfg = new HashMap<>();
        cfg.put("require_merchant", true);
        cfg.put("require_trx", true);
        cfg.put("require_dcc", true);
        cfg.put("require_rental", true);
        cfg.put("quiet_minutes", 15);
        yesterday = LocalDate.now(s.zone).minusDays(1);
    }

    private String gate(LocalDate date) {
        DigestFeedCoverage cov = s.feedCoverage(1L, date, cfg);
        return s.gate(1L, date, cfg, cov);
    }

    @Test
    @DisplayName("a completed day with every required feed in is ready")
    void readyWhenAllFeedsPresent() {
        s.present.addAll(List.of(MERCHANT, TRX, DCC, RENTAL));
        assertNull(gate(yesterday));
        assertEquals("MERCHANT+TRX+DCC+RENTAL", s.feedCoverage(1L, yesterday, cfg).included());
    }

    @Test
    @DisplayName("every missing REQUIRED feed is named, in gate order")
    void waitsOnMissingRequiredFeeds() {
        s.present.add(TRX);
        assertEquals("MERCHANT+DCC+RENTAL", gate(yesterday));
    }

    @Test
    @DisplayName("an optional feed that never arrives does not hold the day, and the coverage says so")
    void optionalFeedsDoNotBlock() {
        s.present.add(TRX);
        cfg.put("require_merchant", false);
        cfg.put("require_dcc", false);
        cfg.put("require_rental", false);
        assertNull(gate(yesterday));
        DigestFeedCoverage cov = s.feedCoverage(1L, yesterday, cfg);
        assertEquals("TRX", cov.included());
        assertFalse(cov.present(DigestFeedCoverage.DCC));
        assertFalse(cov.get(DigestFeedCoverage.DCC).required());
    }

    @Test
    @DisplayName("the current business day never sends automatically, however complete it looks (I-2)")
    void todayIsHeld() {
        s.present.addAll(List.of(MERCHANT, TRX, DCC, RENTAL));
        assertEquals("TODAY", gate(LocalDate.now(s.zone)));
        assertEquals("TODAY", gate(LocalDate.now(s.zone).plusDays(1)));
    }

    @Test
    @DisplayName("a running ingest, then the quiet period, then the send time hold a ready day")
    void runningQuietSchedule() {
        s.present.addAll(List.of(MERCHANT, TRX, DCC, RENTAL));

        s.present.add(RUNNING);
        assertEquals("RUNNING", gate(yesterday));
        s.present.remove(RUNNING);

        s.present.add(QUIET);
        assertEquals("QUIET", gate(yesterday));
        s.present.remove(QUIET);

        LocalTime now = LocalTime.now(s.zone);
        Assumptions.assumeTrue(now.isBefore(LocalTime.of(23, 50)), "too close to midnight for a stable send-time check");
        cfg.put("send_not_before", java.sql.Time.valueOf(now.plusMinutes(5).withSecond(0).withNano(0)));
        assertEquals("SCHEDULE", gate(yesterday));

        cfg.put("send_not_before", java.sql.Time.valueOf(LocalTime.of(0, 0)));
        assertNull(gate(yesterday));
    }

    @Test
    @DisplayName("a day is claimed by exactly one caller (I-12)")
    void claimIsAtomic() {
        when(jdbc.update(anyString(), eq(7L), eq("PENDING"))).thenReturn(1, 0);
        assertTrue(s.claim(7L, "PENDING"), "first caller wins the row");
        assertFalse(s.claim(7L, "PENDING"), "second caller sees it already claimed");
    }

    @Test
    @DisplayName("restatement: gross must move by the threshold; without gross, any row-count change counts")
    void materialChange() {
        BigDecimal one = new BigDecimal("1.00");
        assertFalse(DigestScheduler.materialChange(100L, new BigDecimal("1000"), 101L, new BigDecimal("1005"), one));
        assertTrue(DigestScheduler.materialChange(100L, new BigDecimal("1000"), 100L, new BigDecimal("1020"), one));
        assertTrue(DigestScheduler.materialChange(100L, new BigDecimal("1000"), 100L, new BigDecimal("980"), one));
        assertTrue(DigestScheduler.materialChange(100L, null, 101L, null, one));
        assertFalse(DigestScheduler.materialChange(100L, null, 100L, null, one));
        assertEquals(2.0, DigestScheduler.pctChange(new BigDecimal("1000"), new BigDecimal("1020")), 0.0001);
        assertNull(DigestScheduler.pctChange(null, new BigDecimal("1")));
    }

    @Test
    @DisplayName("allowed domains: blank = unrestricted; subdomains pass; anything else is named")
    void allowedDomains() {
        List<String> to = List.of("cfo@bank.com", "ops@ops.bank.com", "me@gmail.com", "x@bankcom.io");
        assertTrue(DigestScheduler.recipientsOutsideDomains(to, null).isEmpty());
        assertTrue(DigestScheduler.recipientsOutsideDomains(to, "  ").isEmpty());
        assertEquals(List.of("me@gmail.com", "x@bankcom.io"),
                DigestScheduler.recipientsOutsideDomains(to, "@Bank.com"));
        assertEquals(List.of("x@bankcom.io"),
                DigestScheduler.recipientsOutsideDomains(to, "bank.com, gmail.com"));
        assertEquals(Set.of("bank.com", "gmail.com"), DigestScheduler.parseDomains("@bank.com; GMAIL.COM"));
    }

    @Test
    @DisplayName("recipient parsing splits on commas, semicolons and whitespace, and de-duplicates")
    void parseRecipients() {
        assertEquals(List.of("a@x.com", "b@x.com"),
                DigestScheduler.parseRecipients("a@x.com; b@x.com,a@x.com  not-an-email"));
        assertTrue(DigestScheduler.parseRecipients(null).isEmpty());
    }
}
