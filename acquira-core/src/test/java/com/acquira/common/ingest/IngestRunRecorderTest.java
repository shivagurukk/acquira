package com.acquira.common.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorder's contract is narrow but absolute: it must never fail a job, and
 * it must never persist a client-supplied path verbatim.
 */
class IngestRunRecorderTest {

    @Test
    @DisplayName("only the basename is stored — a client-supplied path is not persisted verbatim")
    void sanitisesFileNames() {
        assertEquals("AMS_20260827.xlsx",
                IngestRunRecorder.sanitiseFileName("C:\\Users\\ops\\Desktop\\AMS_20260827.xlsx"));
        assertEquals("AMS_20260827.xlsx",
                IngestRunRecorder.sanitiseFileName("/opt/acquira/uploads/AMS_20260827.xlsx"));
        assertEquals("evil.xlsx",
                IngestRunRecorder.sanitiseFileName("../../../../etc/passwd/evil.xlsx"));
        assertNull(IngestRunRecorder.sanitiseFileName(null));
        assertNull(IngestRunRecorder.sanitiseFileName("   "));
    }

    @Test
    @DisplayName("control characters are stripped so the name is safe to render")
    void stripsControlCharacters() {
        assertEquals("report.xlsx", IngestRunRecorder.sanitiseFileName("rep\u0000ort\u001b.xlsx"));
    }

    @Test
    @DisplayName("a very long name is capped to the column width rather than failing the insert")
    void capsLength() {
        String longName = "a".repeat(900) + ".xlsx";
        assertEquals(512, IngestRunRecorder.sanitiseFileName(longName).length());
    }

    @Test
    @DisplayName("a dead ledger returns null instead of throwing — ingestion must continue")
    void openRunNeverThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new RuntimeException("relation \"ingest_run\" does not exist"));

        IngestRunRecorder recorder = new IngestRunRecorder(jdbc);

        Long id = assertDoesNotThrow(() ->
                recorder.openRun(1L, IngestSource.UPLOAD, 99L, "transactionLoadJob",
                        "/tmp/x.xlsx", "REPLACE", "ops", "cid"));
        assertNull(id, "a failed open must return null, which callers treat as 'no ledger'");
    }

    @Test
    @DisplayName("every write path swallows its errors")
    void writesNeverThrow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("down"));
        IngestRunRecorder recorder = new IngestRunRecorder(jdbc);

        assertDoesNotThrow(() -> recorder.recordStage(1L, "stagingToFactStep", 7, "COMPLETED",
                java.time.Instant.now(), java.time.Instant.now(), 1L, 1L, 0L, null));
        assertDoesNotThrow(() -> recorder.closeRun(1L, "COMPLETED", null));
        assertDoesNotThrow(() -> recorder.closeRunFailed(1L, "boom"));
        assertDoesNotThrow(() -> recorder.setReconResult(1L, "OK", null, 99.0));
        assertDoesNotThrow(() -> recorder.upsertDayCoverage(1L, 1L,
                java.util.List.of(java.time.LocalDate.of(2026, 8, 27))));
    }

    @Test
    @DisplayName("a null run id short-circuits every write — no wasted queries")
    void nullRunIdIsANoOp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IngestRunRecorder recorder = new IngestRunRecorder(jdbc);

        recorder.recordStage(null, "s", 1, "COMPLETED", null, null, null, null, null, null);
        recorder.closeRun(null, "COMPLETED", null);
        recorder.setReconResult(null, "OK", null, null);
        recorder.updateCounts(null, 1L, 1L, 1L, 1L, 1L, 1L, 1, null, null, 1);

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("unknown ingest sources degrade to UPLOAD rather than blowing up")
    void sourceParsingIsLenient() {
        assertEquals(IngestSource.UPLOAD, IngestSource.parse(null));
        assertEquals(IngestSource.UPLOAD, IngestSource.parse("  "));
        assertEquals(IngestSource.UPLOAD, IngestSource.parse("nonsense"));
        assertEquals(IngestSource.SERVER_FILE, IngestSource.parse("server_file"));
        assertEquals(IngestSource.BACKFILL, IngestSource.parse(" Backfill "));
    }
}
