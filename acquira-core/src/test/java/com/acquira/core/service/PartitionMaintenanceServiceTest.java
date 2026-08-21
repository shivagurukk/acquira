package com.acquira.core.service;

import com.acquira.batch.service.PartitionMaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PartitionMaintenanceService} with a mocked JdbcTemplate.
 *
 * Covers: monthly + yearly partition naming (incl. the
 * sum_daily_merchant_attribute -> sum_daily_merch_attr prefix override),
 * the EXISTS short-circuit (no CREATE when the partition already exists),
 * the per-JVM verified-year cache (no repeat work), correct date bounds on
 * CREATE, current+next-year coverage, and fault tolerance (a failing EXISTS
 * check never propagates).
 */
class PartitionMaintenanceServiceTest {

    /** Mock that reports every partition as missing, so CREATE statements fire. */
    private JdbcTemplate jdbcAllMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(false);
        return jdbc;
    }

    private List<String> executedSql(JdbcTemplate jdbc) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("monthly fact_transaction partitions are named _y<year>m<MM> for all 12 months")
    void monthlyPartitionNaming() {
        JdbcTemplate jdbc = jdbcAllMissing();
        new PartitionMaintenanceService(jdbc).ensurePartitionsForYear(2025);

        List<String> sql = executedSql(jdbc);
        for (int m = 1; m <= 12; m++) {
            String name = String.format("fact_transaction_y2025m%02d", m);
            assertTrue(sql.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS " + name)
                            && s.contains("PARTITION OF fact_transaction")),
                    "expected CREATE for " + name);
        }
    }

    @Test
    @DisplayName("yearly summary partitions are named _y<year>, with the attribute-table prefix override")
    void yearlyPartitionNaming() {
        JdbcTemplate jdbc = jdbcAllMissing();
        new PartitionMaintenanceService(jdbc).ensurePartitionsForYear(2025);

        List<String> sql = executedSql(jdbc);
        assertTrue(sql.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS sum_daily_merchant_y2025")));
        assertTrue(sql.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS sum_daily_insight_y2025")));
        // prefix override: sum_daily_merchant_attribute -> sum_daily_merch_attr
        assertTrue(sql.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS sum_daily_merch_attr_y2025")));
        assertTrue(sql.stream().noneMatch(s -> s.contains("sum_daily_merchant_attribute_y2025")));
    }

    @Test
    @DisplayName("CREATE carries the correct half-open month date range")
    void createUsesCorrectDateRange() {
        JdbcTemplate jdbc = jdbcAllMissing();
        new PartitionMaintenanceService(jdbc)
                .createPartitionIfNotExists("fact_transaction", "_y2030m03",
                        LocalDate.of(2030, 3, 1), LocalDate.of(2030, 4, 1));

        List<String> sql = executedSql(jdbc);
        assertTrue(sql.stream().anyMatch(s ->
                s.contains("fact_transaction_y2030m03")
                        && s.contains("FOR VALUES FROM ('2030-03-01') TO ('2030-04-01')")));
    }

    @Test
    @DisplayName("an existing partition is not re-created (EXISTS short-circuit)")
    void existingPartitionNotRecreated() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true); // all exist
        // Pin the partition-strategy detection to legacy so this test keeps
        // exercising the shared-RANGE path (the generic true stub above would
        // otherwise flip it onto the tenant-wise path).
        when(jdbc.queryForObject(contains("partstrat"), eq(Boolean.class), any())).thenReturn(false);

        new PartitionMaintenanceService(jdbc).ensurePartitionsForYear(2025);

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("verified years are cached — a second call for the same year does no DB work")
    void verifiedYearCache() {
        JdbcTemplate jdbc = jdbcAllMissing();
        PartitionMaintenanceService svc = new PartitionMaintenanceService(jdbc);

        svc.ensurePartitionsForYear(2025);
        svc.ensurePartitionsForYear(2025); // should be a no-op

        // 1 partition-strategy detection (cached per JVM after the first call)
        // + 12 monthly + 12 yearly EXISTS checks = 25, done exactly once.
        // (YEARLY_PARTITIONED_TABLES grew to 12 with sum_daily_local_debit_bin
        // — the Local Debit Bank Dashboard pre-aggregate. Was 11/24 with
        // sum_daily_explorer, 10/23 with sum_daily_full, 9/22 before that, and
        // 8/20 when sum_daily_merchant_destination was added.)
        verify(jdbc, times(25)).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("current+next year covers two distinct years (49 EXISTS checks)")
    void currentAndNextYear() {
        JdbcTemplate jdbc = jdbcAllMissing();
        new PartitionMaintenanceService(jdbc).ensurePartitionsForCurrentAndNextYear();

        // 1 strategy detection (cached after the first call, so counted ONCE
        // across both years) + 24 per year (12 monthly + 12 yearly) x 2 years
        // = 1 + 48 = 49.
        verify(jdbc, times(49)).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("a failing EXISTS check is swallowed, not propagated")
    void existsFailureIsSwallowed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any()))
                .thenThrow(new RuntimeException("connection reset"));

        PartitionMaintenanceService svc = new PartitionMaintenanceService(jdbc);
        assertDoesNotThrow(() -> svc.ensurePartitionsForYear(2025));
        verify(jdbc, never()).execute(anyString());
    }
}
