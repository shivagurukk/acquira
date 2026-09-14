package com.acquira.common.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconciliation classifies each drop in the file -> staged -> facted ->
 * summarised funnel, and asserts fee coverage and destructive replaces.
 *
 * Each test below corresponds to a defect that actually reached UAT — these are
 * the regression tests for the things the board exists to catch.
 */
class IngestReconciliationServiceTest {

    private JdbcTemplate jdbc;
    private IngestRunRecorder recorder;
    private IngestReconciliationService service;

    private static final Date D1 = Date.valueOf(LocalDate.of(2026, 8, 27));

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        recorder = mock(IngestRunRecorder.class);
        service = new IngestReconciliationService(jdbc, recorder);
    }

    private Map<String, Object> run(long file, long staged, long facted, Long deleted, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", 1L);
        m.put("load_mode", mode);
        m.put("rows_file", file);
        m.put("rows_staged", staged);
        m.put("rows_facted", facted);
        m.put("rows_summarised", null);
        m.put("fact_rows_deleted", deleted);
        m.put("min_txn_date", D1);
        m.put("max_txn_date", D1);
        return m;
    }

    /** Wires the downstream queries: summary count, fact count, then fee coverage. */
    private void stubDownstream(long summarised, long factInRange, long total, long priced) {
        when(jdbc.queryForObject(contains("sum_daily_full"), eq(Long.class), any(), any(), any()))
                .thenReturn(summarised);
        when(jdbc.queryForObject(contains("COUNT(*) FROM fact_transaction"), eq(Long.class), any(), any(), any()))
                .thenReturn(factInRange);
        Map<String, Object> fee = new HashMap<>();
        fee.put("total", total);
        fee.put("priced", priced);
        when(jdbc.queryForMap(contains("FILTER"), any(), any(), any())).thenReturn(fee);
        when(jdbc.queryForObject(contains("fee_coverage_pct"), eq(BigDecimal.class), any()))
                .thenReturn(new BigDecimal("95.00"));
    }

    private String captureDetail() {
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(recorder).setReconResult(eq(1L), status.capture(), detail.capture(), any());
        return status.getValue() + "|" + (detail.getValue() == null ? "" : detail.getValue());
    }

    @Test
    @DisplayName("a clean load reconciles: every tier equal, fees priced, status OK")
    void cleanLoadIsOk() {
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(10_000, 10_000, 10_000, 0L, "REPLACE"));
        stubDownstream(10_000, 10_000, 10_000, 10_000);

        service.reconcile(1L);

        String result = captureDetail();
        assertTrue(result.startsWith("OK|"), "expected OK, got: " + result);
    }

    @Test
    @DisplayName("unresolvable merchants show up as a STAGE_VS_FACT gap of exactly that size")
    void stagedRowsThatNeverBecameFacts() {
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(10_000, 10_000, 9_950, 0L, "REPLACE"));
        stubDownstream(9_950, 9_950, 9_950, 9_950);

        service.reconcile(1L);

        String result = captureDetail();
        assertTrue(result.startsWith("GAP|"), result);
        assertTrue(result.contains("STAGE_VS_FACT"), result);
        assertTrue(result.contains("50 of 10000"), "should name the exact shortfall: " + result);
    }

    @Test
    @DisplayName("summary drift is caught: facts exist that no summary row counts")
    void summaryDriftIsCaught() {
        // The BulkMigrationService / populateSummary mirror problem: fact rows
        // landed but the summary rebuild dropped them.
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(10_000, 10_000, 10_000, 0L, "REPLACE"));
        stubDownstream(8_000, 10_000, 10_000, 10_000);

        service.reconcile(1L);

        String result = captureDetail();
        assertTrue(result.contains("FACT_VS_SUMMARY"), result);
        assertTrue(result.contains("Summary drift"), result);
    }

    @Test
    @DisplayName("a dead rate card is caught at load time: almost nothing priced")
    void deadRateCardIsCaught() {
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(10_000, 10_000, 10_000, 0L, "REPLACE"));
        stubDownstream(10_000, 10_000, 10_000, 0);   // zero rows carry an MSF

        service.reconcile(1L);

        String result = captureDetail();
        assertTrue(result.contains("FEE_COVERAGE_DROP"), result);
        assertTrue(result.contains("0.00%"), "should report the actual coverage: " + result);
    }

    @Test
    @DisplayName("a partial-day REPLACE that destroyed a fuller day is flagged")
    void destructiveReplaceIsFlagged() {
        // 200 rows replacing a 400k-row day — the defect P0-3 guards against.
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(200, 200, 200, 400_000L, "REPLACE"));
        stubDownstream(200, 200, 200, 200);

        service.reconcile(1L);

        String result = captureDetail();
        assertTrue(result.contains("DESTRUCTIVE_REPLACE"), result);
    }

    @Test
    @DisplayName("APPEND deletes by design and is never called destructive")
    void appendDeletesAreNotDestructive() {
        when(jdbc.queryForMap(contains("FROM ingest_run"), eq(1L)))
                .thenReturn(run(200, 200, 200, 400_000L, "APPEND"));
        stubDownstream(200, 200, 200, 200);

        service.reconcile(1L);

        assertFalse(captureDetail().contains("DESTRUCTIVE_REPLACE"));
    }

    @Test
    @DisplayName("reconciliation never throws — a broken check must not fail an ingestion")
    void neverThrows() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("database gone"));

        assertDoesNotThrow(() -> service.reconcile(1L));
        verify(recorder).setReconResult(eq(1L), eq(IngestReconciliationService.UNKNOWN), contains("database gone"), isNull());
    }

    @Test
    @DisplayName("a null run id is a no-op, not a crash")
    void nullRunIdIsSafe() {
        assertDoesNotThrow(() -> service.reconcile(null));
        verifyNoInteractions(jdbc, recorder);
    }
}
