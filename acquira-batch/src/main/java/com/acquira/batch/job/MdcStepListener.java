package com.acquira.batch.job;

import org.slf4j.MDC;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.stereotype.Component;

/**
 * Populates SLF4J MDC for the duration of every batch step so that log lines
 * emitted on batch worker threads (batch-job-*, integration-cron-*, and the
 * parallel CSV partition threads) carry the same tenant / job / step context
 * that HTTP request logs get from CorrelationIdFilter.
 *
 * WHY THIS EXISTS
 * ---------------
 * Batch steps run on pool threads that never pass through the servlet filter
 * chain, so MDC (correlationId / tenantId / job / step) was empty for the
 * entire pipeline — every line showed [no-ctx] / [tenant=] and, with several
 * jobs/partitions running at once, interleaved lines were impossible to
 * attribute. This listener sets the keys on beforeStep and clears them on
 * afterStep so the logback patterns render:
 *
 *   [tenant=42] [job=dbPullTransactionJob] [step=populateSummaryStep]
 *
 * MDC keys used (matching CorrelationIdFilter / logback-spring.xml):
 *   correlationId — synthesized as "job#<jobExecutionId>" when absent
 *   tenantId      — from the 'tenantId' job parameter
 *   job           — job name
 *   step          — step name
 *
 * Registered on every step in TransactionJobConfig / MerchantMasterJobConfig
 * via .listener(mdcStepListener).
 */
@Component
public class MdcStepListener implements StepExecutionListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MdcStepListener.class);

    private static final String MDC_CID = "correlationId";
    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_JOB = "job";
    private static final String MDC_STEP = "step";

    /** Wall-clock stamp for the STEP START/END audit lines. */
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Start instant per running step execution. Keyed by step-execution id (not a
     * ThreadLocal) because partitioned steps run worker executions on different
     * threads than the manager.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.time.Instant> stepStart =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void beforeStep(StepExecution stepExecution) {
        String jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
        Long jobExecId = stepExecution.getJobExecution().getId();

        // correlationId: reuse one already threaded in via job params if present,
        // otherwise synthesize a stable per-job-run id so all steps of one run
        // share the same correlation token.
        String cid = stepExecution.getJobParameters().getString("correlationId");
        if (cid == null || cid.isBlank()) {
            cid = "job#" + jobExecId;
        }
        MDC.put(MDC_CID, cid);

        Long tenantId = stepExecution.getJobParameters().getLong("tenantId");
        if (tenantId != null) {
            MDC.put(MDC_TENANT, String.valueOf(tenantId));
        }
        MDC.put(MDC_JOB, jobName);
        MDC.put(MDC_STEP, stepExecution.getStepName());

        java.time.Instant now = java.time.Instant.now();
        stepStart.put(stepExecution.getId(), now);
        log.info("[STEP START] {} | start={}",
                stepExecution.getStepName(),
                TS.format(java.time.LocalDateTime.ofInstant(now, java.time.ZoneId.systemDefault())));
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        java.time.Instant end = java.time.Instant.now();
        java.time.Instant start = stepStart.remove(stepExecution.getId());
        String startTs = start == null ? "?"
                : TS.format(java.time.LocalDateTime.ofInstant(start, java.time.ZoneId.systemDefault()));
        String tookS = start == null ? "?"
                : String.format("%.1f", java.time.Duration.between(start, end).toMillis() / 1000.0);
        // Row counts are only populated for chunk-oriented steps (the Excel/CSV
        // staging readers); tasklet steps report 0/0 and the counts are omitted.
        String counts = (stepExecution.getReadCount() > 0 || stepExecution.getWriteCount() > 0)
                ? String.format(" | read=%d written=%d skipped=%d",
                        stepExecution.getReadCount(), stepExecution.getWriteCount(),
                        stepExecution.getSkipCount())
                : "";
        log.info("[STEP END]   {} | status={} | start={} | end={} | took {}s{}",
                stepExecution.getStepName(),
                stepExecution.getExitStatus().getExitCode(),
                startTs,
                TS.format(java.time.LocalDateTime.ofInstant(end, java.time.ZoneId.systemDefault())),
                tookS, counts);

        // Clear only the keys we set — leave any pre-existing MDC (e.g. an HTTP
        // request thread that launched the job synchronously) untouched.
        MDC.remove(MDC_STEP);
        MDC.remove(MDC_JOB);
        MDC.remove(MDC_TENANT);
        MDC.remove(MDC_CID);
        return stepExecution.getExitStatus();
    }
}
