package com.acquira.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseMaintenanceService}.
 *
 * Pure logic + behaviour, no Spring context: the JdbcTemplate is mocked, so
 * these are fast and deterministic. They cover the window math, the three
 * guards (disabled / window / already-ran-today), the batch-idle guard and its
 * override, and the SQL-identifier whitelist that protects the VACUUM string.
 */
@DisplayName("DatabaseMaintenanceService")
class DatabaseMaintenanceServiceTest {

    private JdbcTemplate jdbc;
    private DatabaseMaintenanceService svc;

    private static final String VACUUM_BANK = "VACUUM (ANALYZE) sum_daily_bank";
    private static final String VACUUM_SCHEME = "VACUUM (ANALYZE) sum_daily_scheme";

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        svc = new DatabaseMaintenanceService(jdbc);
    }

    // ─────────────────────────────────────────────────────────────
    // inWindow — package-private static, tested directly
    // ─────────────────────────────────────────────────────────────
    @ParameterizedTest(name = "hour={0} window=[{1},{2}) → {3}")
    @CsvSource({
            // normal window 02:00–05:00
            "3, 2, 5, true",   // inside
            "2, 2, 5, true",   // start inclusive
            "5, 2, 5, false",  // end exclusive
            "1, 2, 5, false",  // before
            "9, 2, 5, false",  // after
            // wrap-past-midnight window 22:00–04:00
            "23, 22, 4, true", // after start
            "2,  22, 4, true", // before end
            "22, 22, 4, true", // start inclusive
            "4,  22, 4, false",// end exclusive
            "12, 22, 4, false",// daytime, outside
            // zero-width window = effectively off
            "3, 3, 3, false",
            "0, 0, 0, false"
    })
    @DisplayName("inWindow handles normal, wrapping and empty windows")
    void inWindow_cases(int hour, int start, int end, boolean expected) {
        assertEquals(expected, DatabaseMaintenanceService.inWindow(hour, start, end));
    }

    // ─────────────────────────────────────────────────────────────
    // runNow — happy path
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("runNow(force) vacuums each configured table and stamps last_run_date")
    void runNow_force_vacuumsEachTable() {
        stubConfig(true, 2, 5, "sum_daily_bank, sum_daily_scheme", null);
        stubBatchRunning(false);
        stubRunInsertReturnsId(1L);

        Map<String, Object> r = svc.runNow(true, false);

        assertEquals("SUCCESS", r.get("status"));
        assertEquals(2, ((Number) r.get("tablesDone")).intValue());
        verify(jdbc).execute(VACUUM_BANK);
        verify(jdbc).execute(VACUUM_SCHEME);
        // last-run stamp uses the no-arg update overload
        verify(jdbc).update(contains("last_run_date = CURRENT_DATE"));
    }

    // ─────────────────────────────────────────────────────────────
    // runNow — SQL identifier whitelist
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("runNow rejects invalid table identifiers and never vacuums them")
    void runNow_skipsUnsafeIdentifiers() {
        stubConfig(true, 2, 5, "sum_daily_bank, bad;drop, sum_daily_scheme", null);
        stubBatchRunning(false);
        stubRunInsertReturnsId(1L);

        Map<String, Object> r = svc.runNow(true, false);

        verify(jdbc).execute(VACUUM_BANK);
        verify(jdbc).execute(VACUUM_SCHEME);
        verify(jdbc, never()).execute(contains("bad"));   // never builds a VACUUM with the bad token
        assertEquals("SUCCESS", r.get("status"));         // at least one table done → SUCCESS
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) r.get("errors");
        assertEquals(1, errors.size(), "the one invalid identifier is recorded as an error");
        assertTrue(errors.get(0).contains("bad;drop"));
    }

    // ─────────────────────────────────────────────────────────────
    // Batch-idle guard
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("runNow refuses while a batch job is running (no override)")
    void runNow_refusesWhenBatchRunning() {
        stubConfig(true, 2, 5, "sum_daily_bank", null);
        stubBatchRunning(true);

        Map<String, Object> r = svc.runNow(true, false);

        assertEquals("SKIPPED", r.get("status"));
        assertTrue(String.valueOf(r.get("reason")).toLowerCase().contains("batch"));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("runNow proceeds while a batch job is running when overrideBatch=true")
    void runNow_overridesBatchGuard() {
        stubConfig(true, 2, 5, "sum_daily_bank", null);
        stubBatchRunning(true);
        stubRunInsertReturnsId(1L);

        Map<String, Object> r = svc.runNow(true, true);

        assertEquals("SUCCESS", r.get("status"));
        verify(jdbc).execute(VACUUM_BANK);
    }

    // ─────────────────────────────────────────────────────────────
    // Non-force guards
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("runNow(non-force) is skipped when maintenance is disabled")
    void runNow_nonForce_skipsWhenDisabled() {
        stubConfig(false, 2, 5, "sum_daily_bank", null);

        Map<String, Object> r = svc.runNow(false, false);

        assertEquals("SKIPPED", r.get("status"));
        assertTrue(String.valueOf(r.get("reason")).toLowerCase().contains("disabled"));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("runNow(non-force) is skipped when it already ran today")
    void runNow_nonForce_skipsWhenAlreadyRanToday() {
        // window spanning the current hour so we reach the already-ran-today check
        int h = LocalTime.now().getHour();
        stubConfig(true, h, (h + 1) % 24, "sum_daily_bank", LocalDate.now());

        Map<String, Object> r = svc.runNow(false, false);

        assertEquals("SKIPPED", r.get("status"));
        assertTrue(String.valueOf(r.get("reason")).toLowerCase().contains("already"));
        verify(jdbc, never()).execute(anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // poll
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("poll does nothing when disabled")
    void poll_noopWhenDisabled() {
        stubConfig(false, 2, 5, null, null);

        svc.poll();

        verify(jdbc, never()).execute(anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // status
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("status returns config, default-table flag and recent runs")
    void status_returnsConfigAndRuns() {
        stubConfig(true, 2, 5, null, LocalDate.of(2026, 6, 26)); // null csv → defaults
        stubBatchRunning(false);
        when(jdbc.queryForList(contains("db_maintenance_run"))).thenReturn(new ArrayList<>());

        Map<String, Object> s = svc.status();

        assertEquals(Boolean.TRUE, s.get("enabled"));
        assertEquals(2, s.get("windowStartHour"));
        assertEquals(5, s.get("windowEndHour"));
        assertEquals(Boolean.TRUE, s.get("usingDefaultTables"));
        assertTrue(s.get("tables") instanceof List);
        assertFalse(((List<?>) s.get("tables")).isEmpty());
        assertNotNull(s.get("recentRuns"));
    }

    // ─────────────────────────────────────────────────────────────
    // Stub helpers
    // ─────────────────────────────────────────────────────────────

    /** Stub the single-row config SELECT by driving the real RowMapper over a mocked ResultSet. */
    @SuppressWarnings("unchecked")
    private void stubConfig(boolean enabled, int start, int end, String tablesCsv, LocalDate lastRun) {
        when(jdbc.queryForObject(contains("db_maintenance_config"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("enabled")).thenReturn(enabled);
                    when(rs.getInt("window_start_hour")).thenReturn(start);
                    when(rs.getInt("window_end_hour")).thenReturn(end);
                    when(rs.getString("tables_csv")).thenReturn(tablesCsv);
                    when(rs.getDate("last_run_date"))
                            .thenReturn(lastRun == null ? null : java.sql.Date.valueOf(lastRun));
                    return rm.mapRow(rs, 0);
                });
    }

    private void stubBatchRunning(boolean running) {
        when(jdbc.queryForObject(contains("batch_job_execution"), eq(Integer.class)))
                .thenReturn(running ? 1 : 0);
    }

    private void stubRunInsertReturnsId(long id) {
        when(jdbc.queryForObject(contains("INSERT INTO db_maintenance_run"), eq(Long.class), any()))
                .thenReturn(id);
    }
}
