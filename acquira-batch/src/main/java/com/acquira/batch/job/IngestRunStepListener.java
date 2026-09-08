package com.acquira.batch.job;

import com.acquira.common.ingest.IngestRunRecorder;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Records one ingest_run_stage row per pipeline stage.
 *
 * PARTITION WORKERS ARE DELIBERATELY SKIPPED
 * ------------------------------------------
 * A partitioned step produces one manager StepExecution (masterIngestStep) plus
 * one worker execution per partition, named "csvWorkerStep:partition7".
 * Spring Batch's DefaultStepExecutionAggregator already folds the workers' read
 * and write counts into the manager execution, so recording both would
 * double-count — which is precisely the bug that made the upload progress bar
 * report "Complete!" at step 4 of 12. Record managers and plain steps only; the
 * worker count is preserved as a note on the manager row.
 *
 * Registered alongside mdcStepListener on every step, so the whole 13-step
 * transactionLoadJob and 11-step dbPullTransactionJob are covered without any
 * per-step code.
 */
@Component
public class IngestRunStepListener implements StepExecutionListener {

    /**
     * Spring Batch names a partition worker execution "<managerStep>:<partitionKey>".
     * CsvPartitioner keys are "partition_part_001.csv", so match on the colon
     * rather than the key prefix — the colon is the part Spring Batch guarantees.
     */
    private static final String PARTITION_MARKER = ":";

    /** The one partitioned step in the transaction pipeline. */
    private static final String MANAGER_STEP = "masterIngestStep";

    private final IngestRunRecorder recorder;

    public IngestRunStepListener(IngestRunRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        // No-op: start time comes from the StepExecution itself in afterStep.
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        try {
            String stepName = stepExecution.getStepName();
            if (stepName != null && stepName.contains(PARTITION_MARKER)) {
                return stepExecution.getExitStatus();   // aggregated into the manager
            }

            Long runId = IngestRunJobListener.runIdOf(stepExecution.getJobExecution());
            if (runId == null) return stepExecution.getExitStatus();

            int seq = seqOf(stepExecution);
            recorder.recordStage(
                    runId,
                    stepName,
                    seq,
                    stepExecution.getStatus().toString(),
                    toInstant(stepExecution.getStartTime()),
                    toInstant(stepExecution.getEndTime()),
                    (long) stepExecution.getReadCount(),
                    (long) stepExecution.getWriteCount(),
                    (long) stepExecution.getSkipCount(),
                    noteFor(stepExecution));
        } catch (Exception ignored) {
            // Observability must never fail an ingestion.
        }
        return stepExecution.getExitStatus();
    }

    /**
     * Position of this step within the run. Counts only non-partition executions
     * so the sequence matches what an operator sees ("step 7 of 12") rather than
     * being inflated by however many CSV parts the file happened to split into.
     */
    private static int seqOf(StepExecution stepExecution) {
        int seq = 0;
        for (StepExecution s : stepExecution.getJobExecution().getStepExecutions()) {
            String n = s.getStepName();
            if (n != null && n.contains(PARTITION_MARKER)) continue;
            seq++;
            if (s.getId() != null && s.getId().equals(stepExecution.getId())) break;
        }
        return seq;
    }

    private static String noteFor(StepExecution stepExecution) {
        // Partition workers are named after the WORKER step ("csvWorkerStep:..."),
        // not the manager, so they cannot be attributed by name prefix. There is
        // exactly one partitioned step per job, so attributing every worker
        // execution to it is unambiguous.
        int partitions = 0;
        if (MANAGER_STEP.equals(stepExecution.getStepName())) {
            for (StepExecution s : stepExecution.getJobExecution().getStepExecutions()) {
                String n = s.getStepName();
                if (n != null && n.contains(PARTITION_MARKER)) partitions++;
            }
        }
        StringBuilder note = new StringBuilder();
        if (partitions > 0) note.append(partitions).append(" partition(s)");
        if (stepExecution.getRollbackCount() > 0) {
            if (note.length() > 0) note.append("; ");
            note.append(stepExecution.getRollbackCount()).append(" rollback(s)");
        }
        if (!stepExecution.getFailureExceptions().isEmpty()) {
            if (note.length() > 0) note.append("; ");
            note.append(stepExecution.getFailureExceptions().get(0).getClass().getSimpleName());
        }
        return note.length() == 0 ? null : note.toString();
    }

    private static java.time.Instant toInstant(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }
}
