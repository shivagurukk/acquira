package com.acquira.common.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes the ingestion ledger (ingest_run / ingest_run_stage / ingest_day_coverage).
 *
 * TWO RULES THIS CLASS LIVES BY
 * -----------------------------
 * 1. EVERY WRITE IS REQUIRES_NEW. A failed job must still be able to record
 *    that it failed — if the ledger write joined the job's transaction it
 *    would roll back alongside the failure and we would lose exactly the runs
 *    we most need to see.
 *
 * 2. NOTHING HERE MAY FAIL A JOB. Every public method swallows its exceptions
 *    and logs. An observability layer that can break ingestion is worse than
 *    no observability layer: it converts a reporting outage into a data
 *    outage. Callers get a best-effort result (0 / null) and carry on.
 *
 * Lives in acquira-common because both acquira-batch (writes) and acquira-core
 * (reads, and the backfill/migration paths) need it.
 */
@Service
public class IngestRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(IngestRunRecorder.class);

    /**
     * Files larger than this are not hashed. SHA-256 over a multi-GB file costs
     * more wall-clock than the duplicate-detection is worth, and the run is
     * still fully recorded — file_sha256 just stays NULL and the duplicate
     * check degrades to "unknown" rather than "unique".
     */
    private static final long MAX_HASH_BYTES = 256L * 1024 * 1024;

    private final JdbcTemplate jdbc;

    public IngestRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Open ────────────────────────────────────────────────────────────────

    /**
     * Opens a RUNNING ledger row and returns its id, or null if the ledger is
     * unavailable. A null id must be treated as "no ledger for this run" by
     * callers, never as a failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long openRun(Long tenantId, IngestSource source, Long jobExecutionId, String jobName,
                        String filePath, String loadMode, String triggeredBy, String correlationId) {
        try {
            String fileName = sanitiseFileName(filePath);
            Long bytes = null;
            String sha = null;
            if (filePath != null && !filePath.isBlank()) {
                try {
                    Path p = Path.of(filePath);
                    if (Files.isRegularFile(p)) {
                        bytes = Files.size(p);
                        if (bytes <= MAX_HASH_BYTES) sha = sha256(p);
                    }
                } catch (Exception e) {
                    log.debug("Could not stat/hash {} for the ingest ledger: {}", fileName, e.toString());
                }
            }

            return jdbc.queryForObject(
                "INSERT INTO ingest_run (tenant_id, source, job_execution_id, job_name, file_name, " +
                "file_bytes, file_sha256, load_mode, status, started_at, triggered_by, correlation_id) " +
                "VALUES (?,?,?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP,?,?) RETURNING id",
                Long.class,
                tenantId,
                (source == null ? IngestSource.UPLOAD : source).name(),
                jobExecutionId, jobName, fileName, bytes, sha,
                loadMode == null ? null : loadMode.toUpperCase(),
                triggeredBy, correlationId);
        } catch (Exception e) {
            log.warn("Could not open ingest_run for tenant {} (non-fatal, ingestion continues): {}",
                    tenantId, e.toString());
            return null;
        }
    }

    // ── Stages ──────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStage(Long runId, String stageName, int seq, String status,
                            Instant startedAt, Instant endedAt,
                            Long rowsIn, Long rowsOut, Long rowsSkipped, String note) {
        if (runId == null) return;
        try {
            Long durationMs = (startedAt != null && endedAt != null)
                    ? Duration.between(startedAt, endedAt).toMillis() : null;
            jdbc.update(
                "INSERT INTO ingest_run_stage (run_id, stage_name, seq, status, started_at, ended_at, " +
                "duration_ms, rows_in, rows_out, rows_skipped, note) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                runId, stageName, seq, status,
                startedAt == null ? null : Timestamp.from(startedAt),
                endedAt   == null ? null : Timestamp.from(endedAt),
                durationMs, rowsIn, rowsOut, rowsSkipped, note);
        } catch (Exception e) {
            log.warn("Could not record stage {} for run {} (non-fatal): {}", stageName, runId, e.toString());
        }
    }

    // ── Incremental counters ────────────────────────────────────────────────

    /**
     * Sets whichever counters the caller knows about. Null values are left
     * untouched, so different pipeline stages can each contribute the piece
     * they own without clobbering the others.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateCounts(Long runId, Long rowsFile, Long rowsStaged, Long rowsFacted,
                             Long rowsSummarised, Long rowsRejected, Long factRowsDeleted,
                             Integer unresolvedMerchants, LocalDate minDate, LocalDate maxDate,
                             Integer distinctDays) {
        if (runId == null) return;
        try {
            jdbc.update(
                "UPDATE ingest_run SET " +
                "rows_file            = COALESCE(?, rows_file), " +
                "rows_staged          = COALESCE(?, rows_staged), " +
                "rows_facted          = COALESCE(?, rows_facted), " +
                "rows_summarised      = COALESCE(?, rows_summarised), " +
                "rows_rejected        = COALESCE(?, rows_rejected), " +
                "fact_rows_deleted    = COALESCE(?, fact_rows_deleted), " +
                "unresolved_merchants = COALESCE(?, unresolved_merchants), " +
                "min_txn_date         = COALESCE(?, min_txn_date), " +
                "max_txn_date         = COALESCE(?, max_txn_date), " +
                "distinct_days        = COALESCE(?, distinct_days) " +
                "WHERE id = ?",
                rowsFile, rowsStaged, rowsFacted, rowsSummarised, rowsRejected, factRowsDeleted,
                unresolvedMerchants,
                minDate == null ? null : java.sql.Date.valueOf(minDate),
                maxDate == null ? null : java.sql.Date.valueOf(maxDate),
                distinctDays, runId);
        } catch (Exception e) {
            log.warn("Could not update counts for run {} (non-fatal): {}", runId, e.toString());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setReconResult(Long runId, String reconStatus, String detail, Double feePricedPct) {
        if (runId == null) return;
        try {
            jdbc.update("UPDATE ingest_run SET recon_status = ?, recon_detail = ?, " +
                        "fee_priced_pct = COALESCE(?, fee_priced_pct) WHERE id = ?",
                    reconStatus, detail, feePricedPct, runId);
        } catch (Exception e) {
            log.warn("Could not set recon result for run {} (non-fatal): {}", runId, e.toString());
        }
    }

    // ── Close ───────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeRun(Long runId, String status, Throwable error) {
        if (runId == null) return;
        try {
            String errClass = null, errMsg = null;
            if (error != null) {
                errClass = error.getClass().getName();
                errMsg = truncate(error.getMessage(), 4000);
            }
            jdbc.update(
                "UPDATE ingest_run SET status = ?, ended_at = CURRENT_TIMESTAMP, " +
                "duration_ms = EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000, " +
                "error_class = COALESCE(?, error_class), error_message = COALESCE(?, error_message) " +
                "WHERE id = ?",
                status, errClass, errMsg, runId);
        } catch (Exception e) {
            log.warn("Could not close run {} (non-fatal): {}", runId, e.toString());
        }
    }

    /** Convenience for the non-batch paths (backfill / bulk migration). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeRunFailed(Long runId, String message) {
        if (runId == null) return;
        try {
            jdbc.update(
                "UPDATE ingest_run SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP, " +
                "duration_ms = EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000, " +
                "error_message = ? WHERE id = ?", truncate(message, 4000), runId);
        } catch (Exception e) {
            log.warn("Could not fail-close run {} (non-fatal): {}", runId, e.toString());
        }
    }

    // ── Day coverage ────────────────────────────────────────────────────────

    /**
     * Refreshes ingest_day_coverage for the dates a run touched, straight from
     * fact and summary. load_count increments on every reload of a day — that
     * counter is what turns "someone re-ingested Tuesday four times" from
     * folklore into a number on a screen.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertDayCoverage(Long tenantId, Long runId, List<LocalDate> dates) {
        if (tenantId == null || dates == null || dates.isEmpty()) return;
        // PERF (2026-08-29): was an N+1 — per date, two COUNT(*) scans of
        // fact_transaction (the second, msf<>0, needing heap access) plus two
        // summary reads. A 31-day month meant 62 fact scans in afterJob, on the
        // critical path. Rewritten as ONE bounded, grouped fact scan + ONE
        // grouped summary scan, joined against the target dates. The fact range
        // [min, max+1day) prunes to just the touched partitions; DATE(payment_date)
        // is only in the GROUP BY / output, not a WHERE filter, so pruning holds.
        // load_count semantics preserved: each target date upserts once, so the
        // ON CONFLICT +1 increments exactly as the per-date loop did.
        try {
            java.util.List<LocalDate> sorted = new java.util.ArrayList<>(dates);
            java.util.Collections.sort(sorted);
            LocalDate min = sorted.get(0);
            LocalDate maxExclusive = sorted.get(sorted.size() - 1).plusDays(1);
            String valuesList = sorted.stream()
                .distinct()
                .map(d -> "(DATE '" + d + "')")
                .collect(java.util.stream.Collectors.joining(","));

            jdbc.update(
                "INSERT INTO ingest_day_coverage (tenant_id, txn_date, rows_fact, rows_summary, " +
                "gross_amount, fee_priced_rows, last_run_id, last_loaded_at, load_count) " +
                "SELECT ?, d.txn_date, " +
                "  COALESCE(ff.rows_fact, 0), " +
                "  COALESCE(sf.rows_summary, 0), " +
                "  COALESCE(sf.gross_amount, 0), " +
                "  COALESCE(ff.fee_priced_rows, 0), " +
                "  ?, CURRENT_TIMESTAMP, 1 " +
                "FROM (VALUES " + valuesList + ") d(txn_date) " +
                "LEFT JOIN ( " +
                "   SELECT DATE(f.payment_date) AS bd, COUNT(*) AS rows_fact, " +
                "          COUNT(*) FILTER (WHERE f.msf IS NOT NULL AND f.msf <> 0) AS fee_priced_rows " +
                "   FROM fact_transaction f " +
                "   WHERE f.tenant_id = ? AND f.payment_date >= ? AND f.payment_date < ? " +
                "   GROUP BY DATE(f.payment_date) " +
                ") ff ON ff.bd = d.txn_date " +
                "LEFT JOIN ( " +
                "   SELECT s.business_date AS bd, SUM(s.total_txns) AS rows_summary, " +
                "          SUM(s.total_volume) AS gross_amount " +
                "   FROM sum_daily_full s " +
                "   WHERE s.tenant_id = ? AND s.business_date >= ? AND s.business_date < ? " +
                "   GROUP BY s.business_date " +
                ") sf ON sf.bd = d.txn_date " +
                "ON CONFLICT (tenant_id, txn_date) DO UPDATE SET " +
                "  rows_fact       = EXCLUDED.rows_fact, " +
                "  rows_summary    = EXCLUDED.rows_summary, " +
                "  gross_amount    = EXCLUDED.gross_amount, " +
                "  fee_priced_rows = EXCLUDED.fee_priced_rows, " +
                "  last_run_id     = EXCLUDED.last_run_id, " +
                "  last_loaded_at  = CURRENT_TIMESTAMP, " +
                "  load_count      = ingest_day_coverage.load_count + 1",
                tenantId, runId,
                tenantId, java.sql.Date.valueOf(min), java.sql.Date.valueOf(maxExclusive),
                tenantId, java.sql.Date.valueOf(min), java.sql.Date.valueOf(maxExclusive));
        } catch (Exception e) {
            log.warn("Could not upsert day coverage for tenant {} ({} date(s)) (non-fatal): {}",
                    tenantId, dates.size(), e.toString());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Basename only, length-capped. The ingest audit flagged raw
     * getOriginalFilename() being trusted elsewhere; a client-supplied path is
     * not something to persist verbatim and then render in a browser.
     */
    static String sanitiseFileName(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        String s = filePath.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[\\p{Cntrl}]", "").trim();
        return truncate(s, 512);
    }

    private static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
