package com.acquira.core.service;

import com.acquira.pdf.service.PlaywrightPdfService;
import com.acquira.pdf.service.PlaywrightPdfService.BatchJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the PDF engine's non-rendering surface.
 *
 * The heavy path (Chromium/Playwright) is exercised only by @PostConstruct
 * init(), which a plain constructor does NOT trigger — so we can safely test
 * the job-registry accessors and the BatchJobStatus progress math without
 * launching a browser.
 */
class PlaywrightPdfServiceTest {

    /** Constructor only assigns fields; init() (which launches Chromium) is not called. */
    private PlaywrightPdfService service() {
        return new PlaywrightPdfService(null, null);
    }

    // ---- service accessors without init ------------------------------------

    @Test
    @DisplayName("engine is not ready until init() runs")
    void engineNotReady() {
        assertFalse(service().isEngineReady());
    }

    @Test
    @DisplayName("getJobStatus for an unknown id returns null")
    void unknownJobStatusNull() {
        assertNull(service().getJobStatus("does-not-exist"));
    }

    @Test
    @DisplayName("cancelJob for an unknown id returns false")
    void cancelUnknownJobFalse() {
        assertFalse(service().cancelJob("does-not-exist"));
    }

    @Test
    @DisplayName("getActiveJobs starts empty and is unmodifiable")
    void activeJobsEmptyUnmodifiable() {
        Map<String, BatchJobStatus> jobs = service().getActiveJobs();
        assertTrue(jobs.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> jobs.put("x", new BatchJobStatus("x", 1)));
    }

    // ---- BatchJobStatus: construction & defaults ---------------------------

    @Test
    @DisplayName("new status carries id, total, zero counters and INITIALIZING phase")
    void statusDefaults() {
        BatchJobStatus s = new BatchJobStatus("job-1", 10);
        assertEquals("job-1", s.jobId);
        assertEquals(10, s.totalMerchants);
        assertEquals(0, s.completed.get());
        assertEquals(0, s.succeeded.get());
        assertEquals(0, s.failed.get());
        assertEquals("INITIALIZING", s.phase);
        assertFalse(s.cancelled);
    }

    // ---- BatchJobStatus: progress math -------------------------------------

    @Test
    @DisplayName("progressPercent is 100 when there are zero merchants (avoids divide-by-zero)")
    void progressZeroTotal() {
        assertEquals(100.0, new BatchJobStatus("j", 0).progressPercent(), 0.0001);
    }

    @Test
    @DisplayName("progressPercent tracks completed/total")
    void progressHalf() {
        BatchJobStatus s = new BatchJobStatus("j", 10);
        for (int i = 0; i < 5; i++) s.completed.incrementAndGet();
        assertEquals(50.0, s.progressPercent(), 0.0001);
    }

    @Test
    @DisplayName("estimatedRemainingMs is -1 before any completion")
    void estimatedRemainingUnknown() {
        assertEquals(-1.0, new BatchJobStatus("j", 5).estimatedRemainingMs(), 0.0001);
    }

    // ---- BatchJobStatus: toMap ---------------------------------------------

    @Test
    @DisplayName("toMap exposes the documented progress keys")
    void toMapKeys() {
        BatchJobStatus s = new BatchJobStatus("job-9", 4);
        s.succeeded.incrementAndGet();
        s.completed.incrementAndGet();
        Map<String, Object> m = s.toMap();

        assertEquals("job-9", m.get("jobId"));
        assertEquals("INITIALIZING", m.get("phase"));
        assertEquals(4, m.get("totalMerchants"));
        assertEquals(1, m.get("completed"));
        assertEquals(1, m.get("succeeded"));
        assertEquals(0, m.get("failed"));
        assertEquals(false, m.get("cancelled"));
        assertTrue(m.containsKey("progressPercent"));
        assertTrue(m.containsKey("errorCount"));
        assertTrue(m.containsKey("errors"));
    }

    @Test
    @DisplayName("toMap rounds progressPercent to one decimal place")
    void toMapRoundsProgress() {
        BatchJobStatus s = new BatchJobStatus("j", 3);
        s.completed.incrementAndGet(); // 1/3 = 33.333...
        assertEquals(33.3, (Double) s.toMap().get("progressPercent"), 0.0001);
    }

    @Test
    @DisplayName("toMap caps the errors list at 20 but reports the true errorCount")
    void toMapTruncatesErrors() {
        BatchJobStatus s = new BatchJobStatus("j", 100);
        for (int i = 0; i < 25; i++) s.errors.add("err-" + i);
        Map<String, Object> m = s.toMap();
        assertEquals(20, ((java.util.List<?>) m.get("errors")).size());
        assertEquals(25, m.get("errorCount"));
    }

    @Test
    @DisplayName("a cancelled flag is reflected in toMap")
    void toMapCancelled() {
        BatchJobStatus s = new BatchJobStatus("j", 2);
        s.cancelled = true;
        assertEquals(true, s.toMap().get("cancelled"));
    }
}
