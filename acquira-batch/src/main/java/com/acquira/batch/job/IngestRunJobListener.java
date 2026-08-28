package com.acquira.batch.job;

import com.acquira.common.ingest.IngestReconciliationService;
import com.acquira.common.ingest.IngestRunRecorder;
import com.acquira.common.ingest.IngestSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Opens and closes the ingestion ledger row around every batch job.
 *
 * WHY A LISTENER RATHER THAN CALL SITES
 * -------------------------------------
 * Putting openRun/closeRun in FileUploadService, the server-file loop and
 * IntegrationPullService would mean three copies of the same bookkeeping, each
 * free to drift, and each able to miss the failure path. A JobExecutionListener
 * sees every launch of every job regardless of who launched it, and afterJob
 * runs on success AND failure. Call sites only have to declare WHERE the file
 * came from, via the optional 'ingestSource' job parameter.
 *
 * The run id is published into the JOB execution context under
 * {@code ingestRunId} so step-scoped beans can bind to it with
 * {@code #{jobExecutionContext['ingestRunId']}} — that is how staging rows get
 * stamped with their run (P0-2) without threading the id through every method.
 *
 * ORDERING: beforeJob runs before any step, so the id is guaranteed to exist by
 * the time the first step-scoped bean is created.
 */
@Component
public class IngestRunJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(IngestRunJobListener.class);

    /** Key under which the run id is published to the job execution context. */
    public static final String CTX_RUN_ID = "ingestRunId";

    private final IngestRunRecorder recorder;
    private final IngestReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    public IngestRunJobListener(IngestRunRecorder recorder, IngestReconciliationService reconciliation,
                                JdbcTemplate jdbc) {
        this.recorder = recorder;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        try {
            Long tenantId = jobExecution.getJobParameters().getLong("tenantId");
            String fullPath = jobExecution.getJobParameters().getString("fullPath");
            String loadMode = jobExecution.getJobParameters().getString("loadMode");
            String sourceRaw = jobExecution.getJobParameters().getString("ingestSource");
            String triggeredBy = jobExecution.getJobParameters().getString("triggeredBy");
            String correlationId = jobExecution.getJobParameters().getString("correlationId");
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = "job#" + jobExecution.getId();
            }

            // A DB pull has no file at all; default it explicitly rather than
            // letting IngestSource.parse fall back to UPLOAD and mislabel it.
            IngestSource source = (sourceRaw == null || sourceRaw.isBlank())
                    ? (fullPath == null || fullPath.isBlank() ? IngestSource.DB_PULL : IngestSource.UPLOAD)
                    : IngestSource.parse(sourceRaw);

            Long runId = recorder.openRun(tenantId, source, jobExecution.getId(),
                    jobExecution.getJobInstance().getJobName(), fullPath, loadMode,
                    triggeredBy, correlationId);

            if (runId != null) {
                jobExecution.getExecutionContext().putLong(CTX_RUN_ID, runId);
            }
        } catch (Exception e) {
            // Never let bookkeeping stop an ingestion.
            log.warn("Could not open ingest ledger row for job {} (non-fatal): {}",
                    jobExecution.getId(), e.toString());
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Long runId = runIdOf(jobExecution);
        if (runId == null) return;
        try {
            Throwable failure = jobExecution.getAllFailureExceptions().isEmpty()
                    ? null : jobExecution.getAllFailureExceptions().get(0);

            // rows_file — the top of the funnel. ExcelSplitterTasklet publishes
            // the row count it split out of the source file; a DB pull has none.
            try {
                long totalReqRows = jobExecution.getExecutionContext().getLong("totalReqRows", 0L);
                if (totalReqRows > 0) {
                    recorder.updateCounts(runId, totalReqRows, null, null, null, null, null,
                            null, null, null, null);
                }
            } catch (Exception fe) {
                log.warn("Could not record file row count for run {} (non-fatal): {}", runId, fe.toString());
            }

            // Coverage first: summaries exist by now, so the per-day rows record
            // real counts rather than the zeros they would have held if
            // stagingToFact had written them mid-pipeline.
            try {
                refreshCoverage(runId);
            } catch (Exception ce) {
                log.warn("Coverage refresh failed for run {} (non-fatal): {}", runId, ce.toString());
            }

            // Reconcile before closing so recon_status is set on the row the
            // board reads, not a beat later.
            try {
                reconciliation.reconcile(runId);
            } catch (Exception re) {
                log.warn("Reconciliation failed for run {} (non-fatal): {}", runId, re.toString());
            }

            recorder.closeRun(runId, jobExecution.getStatus().toString(), failure);
        } catch (Exception e) {
            log.warn("Could not close ingest ledger row {} (non-fatal): {}", runId, e.toString());
        }
    }

    /**
     * Refreshes ingest_day_coverage for the days this run touched.
     *
     * The touched days are re-derived from the run's own min/max transaction
     * dates rather than carried in the execution context: BATCH_JOB_EXECUTION_CONTEXT's
     * SHORT_CONTEXT column is bounded, and a year-wide backfill's date list
     * would truncate. Sparse days inside the range are handled by asking
     * sum_daily_full which dates actually hold data.
     */
    private void refreshCoverage(Long runId) {
        java.util.Map<String, Object> run = jdbc.queryForMap(
            "SELECT tenant_id, min_txn_date, max_txn_date FROM ingest_run WHERE id = ?", runId);
        Object tid = run.get("tenant_id");
        java.sql.Date min = (java.sql.Date) run.get("min_txn_date");
        java.sql.Date max = (java.sql.Date) run.get("max_txn_date");
        if (tid == null || min == null || max == null) return;

        Long tenantId = ((Number) tid).longValue();
        java.util.List<java.time.LocalDate> days = jdbc.queryForList(
            "SELECT DISTINCT business_date FROM sum_daily_full " +
            "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? ORDER BY business_date",
            java.sql.Date.class, tenantId, min, max)
            .stream().map(java.sql.Date::toLocalDate).collect(java.util.stream.Collectors.toList());

        // A load that wiped a day to zero leaves no summary row, so make sure the
        // bounds themselves are always refreshed — otherwise an emptied day keeps
        // showing its old, now-wrong counts on the calendar.
        java.time.LocalDate minD = min.toLocalDate(), maxD = max.toLocalDate();
        if (!days.contains(minD)) days.add(minD);
        if (!days.contains(maxD)) days.add(maxD);

        recorder.upsertDayCoverage(tenantId, runId, days);
    }

    /** Reads the run id back out of the job execution context; null when the ledger was unavailable. */
    public static Long runIdOf(JobExecution jobExecution) {
        try {
            ExecutionContext ctx = jobExecution.getExecutionContext();
            return ctx.containsKey(CTX_RUN_ID) ? ctx.getLong(CTX_RUN_ID) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
