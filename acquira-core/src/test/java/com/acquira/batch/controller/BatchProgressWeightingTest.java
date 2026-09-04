package com.acquira.batch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the "Complete! at step 4 of 12" defect (P0-1).
 *
 * The old progress payload summed readCount over EVERY StepExecution on the
 * job. Spring Batch registers each partition worker as its own StepExecution
 * and ALSO folds the workers' counts into the manager step, so the sum reached
 * totalReqRows while eight steps were still to run — and the row-based
 * percentage read 100%.
 *
 * These tests pin both halves of the fix: partition executions are excluded,
 * and completion is stage-weighted rather than row-derived.
 */
class BatchProgressWeightingTest {

    private static final String JOB = "transactionLoadJob";

    private JobExecution jobExecution(String status) {
        JobExecution exec = new JobExecution(1L);
        exec.setJobInstance(new JobInstance(1L, JOB));
        exec.setStatus(BatchStatus.valueOf(status));
        return exec;
    }

    /**
     * createStepExecution (not the StepExecution constructor) is what registers
     * the step against the job — weightedProgress walks getStepExecutions().
     */
    private StepExecution step(JobExecution job, String name, String status, int read) {
        StepExecution s = job.createStepExecution(name);
        s.setStatus(BatchStatus.valueOf(status));
        s.setReadCount(read);
        s.setStartTime(LocalDateTime.now());
        return s;
    }

    // ── isPartitionWorker ───────────────────────────────────────────────────

    @Test
    @DisplayName("partition worker executions are identified by the colon Spring Batch inserts")
    void identifiesPartitionWorkers() {
        JobExecution job = jobExecution("STARTED");
        assertTrue(BatchProgressController.isPartitionWorker(
                step(job, "csvWorkerStep:partition_part_003.csv", "COMPLETED", 50_000)));
        assertFalse(BatchProgressController.isPartitionWorker(
                step(job, "masterIngestStep", "COMPLETED", 50_000)));
        assertFalse(BatchProgressController.isPartitionWorker(
                step(job, "populateSummaryStep", "STARTED", 0)));
    }

    // ── weightedProgress ────────────────────────────────────────────────────

    @Test
    @DisplayName("THE BUG: staging fully read is not 100% — eight steps still to run")
    void stagingCompleteIsNotJobComplete() {
        JobExecution job = jobExecution("STARTED");
        step(job, "ensurePartitionsStep", "COMPLETED", 0);
        step(job, "splitExcelStep", "COMPLETED", 0);
        step(job, "cleanTargetDayStep", "COMPLETED", 0);
        step(job, "masterIngestStep", "COMPLETED", 1_000_000);
        // 20 partition workers, each already carrying the full aggregate share.
        for (int i = 0; i < 20; i++) {
            step(job, "csvWorkerStep:partition_part_" + i + ".csv", "COMPLETED", 50_000);
        }

        long pct = BatchProgressController.weightedProgress(job, 1_000_000, 1_000_000);

        // Old behaviour would have been 100. Weights say 1+5+1+30 of 100.
        assertEquals(37L, pct, "four of twelve stages done should read ~37%, not 100%");
        assertTrue(pct < 100, "must never report complete while steps remain");
    }

    @Test
    @DisplayName("a run is only 100% when Spring Batch says the job COMPLETED")
    void hundredOnlyOnCompletion() {
        JobExecution running = jobExecution("STARTED");
        for (String s : new String[]{
                "ensurePartitionsStep", "splitExcelStep", "cleanTargetDayStep", "masterIngestStep",
                "analyzeStagingStep", "autoCreateDimensionsStep", "stagingToFactStep", "populateSummaryStep",
                "calculateBusinessMetricsStep", "scoreMlStep", "computeSegmentsStep",
                "calculateDailyDashboardMetricsStep"}) {
            step(running, s, "COMPLETED", 0);
        }
        assertEquals(99L, BatchProgressController.weightedProgress(running, 0, 0),
                "every stage done but the job still open must cap at 99");

        JobExecution done = jobExecution("COMPLETED");
        step(done, "ensurePartitionsStep", "COMPLETED", 0);
        assertEquals(100L, BatchProgressController.weightedProgress(done, 0, 0));
    }

    @Test
    @DisplayName("row progress moves the bar only inside the ingest stage")
    void rowProgressScopedToIngestStage() {
        JobExecution job = jobExecution("STARTED");
        step(job, "ensurePartitionsStep", "COMPLETED", 0);   // weight 1
        step(job, "splitExcelStep", "COMPLETED", 0);         // weight 5
        step(job, "cleanTargetDayStep", "COMPLETED", 0);     // weight 1
        step(job, "masterIngestStep", "STARTED", 500_000);   // weight 30, half read

        // 1 + 5 + 1 + (30 * 0.5) = 22 of 100
        assertEquals(22L, BatchProgressController.weightedProgress(job, 500_000, 1_000_000));
    }

    @Test
    @DisplayName("a failed stage earns nothing — a broken run must not look nearly done")
    void failedStageEarnsNothing() {
        JobExecution job = jobExecution("FAILED");
        step(job, "ensurePartitionsStep", "COMPLETED", 0);   // 1
        step(job, "splitExcelStep", "COMPLETED", 0);         // 5
        step(job, "cleanTargetDayStep", "COMPLETED", 0);     // 1
        step(job, "masterIngestStep", "FAILED", 900_000);    // 0

        assertEquals(7L, BatchProgressController.weightedProgress(job, 900_000, 1_000_000));
    }

    @Test
    @DisplayName("dbPullTransactionJob is weighted over its own nine stages")
    void dbPullJobUsesItsOwnStageList() {
        JobExecution job = new JobExecution(2L);
        job.setJobInstance(new JobInstance(2L, "dbPullTransactionJob"));
        job.setStatus(BatchStatus.STARTED);
        job.createStepExecution("ensurePartitionsStep").setStatus(BatchStatus.COMPLETED);

        // dbPull total weight = 1+3+3+25+20+5+3+2+2 = 64; ensurePartitions = 1.
        assertEquals(2L, BatchProgressController.weightedProgress(job, 0, 0));
    }

    @Test
    @DisplayName("an unknown job falls back to rows rather than reporting zero forever")
    void unknownJobFallsBackToRows() {
        JobExecution job = new JobExecution(3L);
        job.setJobInstance(new JobInstance(3L, "merchantMasterJob"));
        job.setStatus(BatchStatus.STARTED);

        assertEquals(50L, BatchProgressController.weightedProgress(job, 500, 1000));
        assertEquals(0L, BatchProgressController.weightedProgress(job, 0, 0));
        assertEquals(-1L, BatchProgressController.weightedProgress(job, 10, 0),
                "rows read but no total known is indeterminate, not zero");
    }
}
