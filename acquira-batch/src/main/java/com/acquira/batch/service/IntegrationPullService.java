package com.acquira.batch.service;

import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import com.acquira.common.service.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core DB Pull service — fetches data from external databases (Oracle /
 * Postgres / MSSQL) and inserts into the SAME staging tables used by file
 * upload, then launches the SAME Spring Batch post-processing pipeline:
 *
 *   MERCHANT type    → dbPullMerchantJob    (upsertAndSummarizeStep — identical
 *                      dimension upsert logic to the file path, no hand-rolled SQL)
 *   TRANSACTION type → dbPullTransactionJob (ensurePartitions → autoCreateDimensions
 *                      → stagingToFact w/ FEE COMPUTATION → ALL summary tables →
 *                      business metrics → ML scoring → segments → dashboard metrics)
 *
 * This replaced the previous hand-rolled post-processing which silently skipped
 * fee computation and every sum_daily_* / sum_monthly_* table — DB pulls now have
 * full parity with file uploads.
 */
@Service
@Slf4j
public class IntegrationPullService {

    private final IntegrationRunLogRepository runLogRepo;
    private final IntegrationScheduleRepository scheduleRepo;
    private final JdbcTemplate jdbcTemplate;
    private final ManualIngestionService manualIngestionService;
    // P0 fix: decrypt the stored connection password (was stored & used as plaintext)
    private final CryptoService cryptoService;

    private final JobLauncher jobLauncher;          // async (TaskExecutorJobLauncher, see BatchConfig)
    private final JobExplorer jobExplorer;          // used to poll async job completion
    private final Job dbPullTransactionJob;
    private final Job dbPullMerchantJob;
    private final Job dbPullRentalJob;
    private final Job dbPullDccJob;
    private final TaskScheduler taskScheduler;      // retries — replaces the leaked java.util.Timer-per-retry

    // Explicit constructor (NOT @RequiredArgsConstructor): the two Job
    // dependencies need @Qualifier, and without a lombok.config enabling
    // copyableAnnotations Lombok drops @Qualifier from the generated
    // constructor — which fails at startup because multiple Job beans exist
    // (transactionLoadJob, merchantMasterJob, dbPull*).
    public IntegrationPullService(IntegrationRunLogRepository runLogRepo,
                                  IntegrationScheduleRepository scheduleRepo,
                                  JdbcTemplate jdbcTemplate,
                                  ManualIngestionService manualIngestionService,
                                  CryptoService cryptoService,
                                  JobLauncher jobLauncher,
                                  JobExplorer jobExplorer,
                                  @Qualifier("dbPullTransactionJob") Job dbPullTransactionJob,
                                  @Qualifier("dbPullMerchantJob") Job dbPullMerchantJob,
                                  @Qualifier("dbPullRentalJob") Job dbPullRentalJob,
                                  @Qualifier("dbPullDccJob") Job dbPullDccJob,
                                  TaskScheduler taskScheduler) {
        this.runLogRepo = runLogRepo;
        this.scheduleRepo = scheduleRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.manualIngestionService = manualIngestionService;
        this.cryptoService = cryptoService;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.dbPullTransactionJob = dbPullTransactionJob;
        this.dbPullMerchantJob = dbPullMerchantJob;
        this.dbPullRentalJob = dbPullRentalJob;
        this.dbPullDccJob = dbPullDccJob;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Tenant on/off switch. Field-injected (not constructor) so the explicit
     * @Qualifier constructor above — and every test that calls it — stays
     * untouched. Nullable-checked at use for tests that construct the service
     * directly.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.common.service.TenantStatusService tenantStatusService;

    /**
     * Failure-alert seam: publishes IntegrationRunFailedEvent on a FINAL failed
     * attempt; the core module's listener emails the schedule's recipients.
     * Field-injected (required=false) for the same reason as tenantStatusService
     * — the explicit @Qualifier constructor and direct-construction tests stay
     * untouched.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * Self-reference through the Spring proxy, used ONLY by scheduleRetry.
     *
     * WHY: executePull is @Async, but calling it as this.executePull(...) from
     * inside this class bypasses the proxy entirely — the retry then ran
     * SYNCHRONOUSLY on the shared 'integration-cron-' TaskScheduler thread
     * (pool size 5), holding it for the whole pull plus up to 2h of batch
     * polling. A few concurrent retries would starve every @Scheduled bean in
     * the application (email queue, report runner, DB maintenance, alerts).
     * Going through the proxy puts the retry back on the async executor.
     * @Lazy breaks the self-referential construction cycle.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private IntegrationPullService self;

    /**
     * Fallback lookback for a caller that passes no window. Mirrors
     * DynamicSchedulerService.lookbackDays (same property) — scheduled runs
     * always pass an explicit window, so this only covers stray callers.
     */
    @org.springframework.beans.factory.annotation.Value("${acquira.integration.lookback-days:3}")
    private int lookbackDays;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Hard ceiling so a runaway report query can't materialise unbounded rows in
     *  memory and OOM the app. High enough not to truncate legitimate pulls; if a
     *  pull ever hits it we log a warning rather than failing silently. */
    private static final int MAX_PULL_ROWS = 2_000_000;

    /** Staging insert batch size (jdbcTemplate.batchUpdate chunk). */
    private static final int INSERT_BATCH_SIZE = 2_000;

    /** External cursor fetch size for streamed pulls — keeps heap flat on huge
     *  result sets (Oracle's default is 10, which would make a 1M-row pull crawl;
     *  Postgres needs an explicit value with autoCommit off to stream at all). */
    private static final int FETCH_SIZE = 2_000;

    /** Max time to wait for the async Spring Batch pipeline to finish. */
    private static final long JOB_POLL_TIMEOUT_MS = 2 * 60 * 60 * 1000L; // 2h
    private static final long JOB_POLL_INTERVAL_MS = 2_000L;

    /**
     * Per-tenant serialization. Two overlapping pulls for the same tenant both
     * DELETE + re-fill the shared staging tables and would corrupt each other
     * mid-flight. The app deliberately runs as a single replica (schedulers +
     * batch in one process), so an in-JVM lock is sufficient. tryLock (no wait):
     * an overlapping pull FAILS FAST with a clear message instead of queueing —
     * queued pulls against the same staging table are exactly the race we're
     * preventing.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    /**
     * Execute a DB pull for a given report configuration.
     * This is the main entry point — called by Run Now, Scheduler, and Retry.
     */
    // Named executor on purpose — see BatchConfig.integrationPullExecutor.
    // A bare @Async here resolved to an unbounded SimpleAsyncTaskExecutor.
    @Async("integrationPullExecutor")
    public void executePull(IntegrationReport report, IntegrationSchedule schedule,
                            IntegrationRunLog.TriggerType triggerType,
                            LocalDate dateFrom, LocalDate dateTo,
                            int attemptNumber) {

        Long tenantId = report.getTenantId();
        IntegrationConnection conn = report.getConnection();

        // Establish MDC context for this async pull so every [Integration] line —
        // and the log lines from the batch pipeline it launches — carries tenant
        // and a stable correlation id. Cleared in finally.
        org.slf4j.MDC.put("correlationId", "pull#" + report.getId() + "-" + System.currentTimeMillis() % 100000);
        org.slf4j.MDC.put("tenantId", String.valueOf(tenantId));
        org.slf4j.MDC.put("job", "integrationPull:" + report.getReportType());

        // 1. Create run log
        IntegrationRunLog runLog = new IntegrationRunLog();
        runLog.setTenantId(tenantId);
        runLog.setReport(report);
        runLog.setSchedule(schedule);
        runLog.setTriggerType(triggerType);
        runLog.setStatus(IntegrationRunLog.Status.RUNNING);
        runLog.setAttemptNumber(attemptNumber);
        runLog.setMaxRetries(conn.getMaxRetries() != null ? conn.getMaxRetries() : 3);
        runLog.setStartTime(LocalDateTime.now());
        runLog.setDateRangeFrom(dateFrom);
        runLog.setDateRangeTo(dateTo);
        runLogRepo.save(runLog);

        long startMs = System.currentTimeMillis();

        // SEPARATION OF DUTIES: a report's sqlText executes against the
        // CUSTOMER's production database with the stored service credentials,
        // and setReadOnly is a no-op on the Oracle and MSSQL drivers — so the
        // approval recorded by a SUPER_ADMIN is the control that decides which
        // statements may ever run there. Enforced HERE, not only in the
        // controller, so the scheduler, Run Now and retries are all covered.
        // Editing sqlText clears the approval, so an approved-then-swapped
        // query cannot slip through.
        // TENANT OFF-SWITCH: enforced HERE (not in the scheduler) so scheduled
        // runs, Run Now AND retries are all covered without reloading schedules
        // — deactivating a tenant stops its pulls at the next fire, full stop.
        if (tenantStatusService != null && tenantStatusService.isInactive(tenantId)) {
            log.warn("[Integration] Pull '{}' (tenant {}) SKIPPED — tenant is not active.",
                    report.getName(), tenantId);
            runLog.setStatus(IntegrationRunLog.Status.FAILED);
            runLog.setErrorMessage("Tenant " + tenantId + " is not active — pull suppressed. "
                    + "Reactivate the tenant (Tenant Management > Status) to resume scheduled pulls.");
            finishRunLog(runLog, startMs);
            org.slf4j.MDC.clear();
            return;
        }

        if (!report.isApproved()) {
            log.warn("[Integration] Pull '{}' (tenant {}) BLOCKED — report SQL is not approved.",
                    report.getName(), tenantId);
            runLog.setStatus(IntegrationRunLog.Status.FAILED);
            runLog.setErrorMessage("Report SQL is not approved. A SUPER_ADMIN must approve this report "
                    + "(Integration Hub > Report Configs > Approve) before it can run against the source database. "
                    + "Note that editing the SQL revokes an existing approval.");
            finishRunLog(runLog, startMs);
            org.slf4j.MDC.clear();
            return;
        }

        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, t -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("[Integration] Pull '{}' for tenant {} rejected — another pull for this tenant is in progress.",
                    report.getName(), tenantId);
            runLog.setStatus(IntegrationRunLog.Status.FAILED);
            runLog.setErrorMessage("Another pull for this tenant is already in progress — staging tables are shared per tenant. Re-run once it completes.");
            finishRunLog(runLog, startMs);
            org.slf4j.MDC.clear();
            return;
        }

        try {
            // 2. Build params
            Map<String, Object> params = buildParams(dateFrom, dateTo);

            // Record the RESOLVED window (a caller may have passed nulls), so the
            // run log always shows the period this run actually covered.
            runLog.setDateRangeFrom((LocalDate) params.get("dateFrom"));
            runLog.setDateRangeTo((LocalDate) params.get("dateTo"));
            warnIfMonthGranular(report, (LocalDate) params.get("dateFrom"), (LocalDate) params.get("dateTo"));

            // 2b. Optional upstream-readiness gate: run the schedule's precondition
            //     query against the SAME external connection and only pull when it
            //     returns a truthy first cell — i.e. the upstream batch has
            //     completed. Not ready => defer via the normal retry backoff so we
            //     keep polling instead of ingesting a half-loaded day. Manual
            //     "Run Now" bypasses the gate (the operator is forcing the pull).
            if (triggerType != IntegrationRunLog.TriggerType.MANUAL
                    && schedule != null
                    && Boolean.TRUE.equals(schedule.getPreconditionEnabled())
                    && schedule.getPreconditionSql() != null
                    && !schedule.getPreconditionSql().isBlank()) {
                List<Map<String, Object>> check =
                        executeExternalQuery(conn, schedule.getPreconditionSql(), params);
                Object cell = check.isEmpty() ? null
                        : check.get(0).values().stream().findFirst().orElse(null);
                if (!isTruthy(cell)) {
                    log.info("[Integration] Precondition NOT met for '{}' (tenant {}) — upstream returned {} (attempt {}/{})",
                            report.getName(), tenantId, cell, attemptNumber, runLog.getMaxRetries());
                    if (attemptNumber < runLog.getMaxRetries()) {
                        runLog.setStatus(IntegrationRunLog.Status.RETRYING);
                        runLog.setErrorMessage("Upstream batch not complete yet (precondition returned "
                                + cell + ") — pull deferred, will re-check.");
                        // Resolved window, so a defer that crosses midnight still
                        // pulls the period this run was scheduled for.
                        scheduleRetry(report, schedule,
                                runLog.getDateRangeFrom(), runLog.getDateRangeTo(), attemptNumber + 1);
                    } else {
                        runLog.setStatus(IntegrationRunLog.Status.FAILED);
                        runLog.setErrorMessage("Upstream batch never reported complete after "
                                + attemptNumber + " checks (precondition returned " + cell + ").");
                    }
                    return; // finally unlocks, persists the run log, and clears MDC
                }
                log.info("[Integration] Precondition met for '{}' (tenant {}) — proceeding with pull.",
                        report.getName(), tenantId);
            }

            // 3. Stream from the external DB straight into staging (batched).
            // PERF/PARITY (2026-09-05): the result set is no longer materialised
            // in heap first — a 1M-row pull used to build a List of 1M
            // LinkedHashMaps (multi-GB) before writing a single staging row.
            // The file path streams (split + chunked ingest), so the pull now
            // does too: fetchSize on the external cursor, INSERT_BATCH_SIZE
            // flushes into staging, memory stays flat regardless of window size.
            log.info("[Integration] Pulling {} report '{}' for tenant {} (attempt {}/{})",
                    report.getReportType(), report.getName(), tenantId, attemptNumber, runLog.getMaxRetries());

            // CONCURRENCY (2026-09-05): staging is shared per tenant, and the
            // wipe inside pullToStaging would destroy a RUNNING file/server-folder
            // load's rows mid-flight. FileUploadService.assertNoRunningIngest
            // already blocks uploads while a pull is RUNNING; this closes the
            // reverse direction. Throws -> the normal retry backoff kicks in.
            assertNoRunningIngestForTenant(tenantId);

            // 4/5. Parse column mapping, wipe staging, stream query -> staging.
            Map<String, String> columnMap = parseColumnMapping(report.getColumnMapping());
            SkipTracker skips = new SkipTracker();
            PullResult pulled = pullToStaging(report, conn, params, columnMap, tenantId, skips);
            runLog.setRowsFetched((int) Math.min(pulled.fetched(), Integer.MAX_VALUE));

            if (pulled.fetched() == 0) {
                log.warn("[Integration] No rows returned for report '{}', tenant {}", report.getName(), tenantId);
                runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
                runLog.setRowsProcessed(0);
                return; // finally persists the run log — calling finishRunLog here too saved it twice
            }

            int processed = pulled.processed();
            if (report.getReportType() != IntegrationReport.ReportType.MERCHANT
                    && report.getReportType() != IntegrationReport.ReportType.DCC
                    && report.getReportType() != IntegrationReport.ReportType.RENTAL) {
                // 5b. Normalize staged rows to match what the file-path ItemProcessor
                //     produces: granular card_product_code preserved, card_type
                //     coarsened to DEBIT/CREDIT/PREPAID, ISO-numeric currency tokens
                //     translated to codes, optional minor-unit division.
                // TENANT-DRIVEN amount format (2026-08-08): tenant.input_format is the
                // primary switch (CMM = minor units -> divide, AMS = final decimals ->
                // no division), same rule as the file path. The per-report
                // amounts_minor_units flag, when explicitly set, still overrides —
                // existing configured reports keep their behaviour exactly.
                boolean minorUnits = report.getAmountsMinorUnits() != null
                        ? report.getAmountsMinorUnits()
                        : isCmmTenant(tenantId);
                normalizeStagedTransactions(tenantId, minorUnits);
            }

            runLog.setRowsProcessed(processed);
            runLog.setRowsFailed((int) Math.min(pulled.fetched() - processed, Integer.MAX_VALUE));
            if (skips.total > 0) {
                runLog.setErrorMessage(skips.summary());
            }

            // 6. Trigger the SAME Spring Batch pipeline as file upload and wait
            //    for it — the JobLauncher is async (returns in STARTING state).
            List<LocalDate> stagedDates = (report.getReportType() == IntegrationReport.ReportType.TRANSACTION)
                    ? stagedTransactionDates(tenantId) : List.of();
            runBatchPipeline(report.getReportType(), tenantId);

            // 7. Legacy per-merchant metrics (same as the file path does after its
            //    job) — date-scoped so a one-day pull doesn't re-aggregate the
            //    tenant's entire history.
            if (report.getReportType() == IntegrationReport.ReportType.TRANSACTION && !stagedDates.isEmpty()) {
                manualIngestionService.processManualUpload(tenantId, stagedDates);
            }

            // 8. Success
            runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
            log.info("[Integration] SUCCESS — '{}' for tenant {}: {} rows fetched, {} processed{}",
                    report.getName(), tenantId, pulled.fetched(), processed,
                    skips.total > 0 ? " (" + skips.total + " skipped)" : "");

        } catch (Exception e) {
            log.error("[Integration] FAILED — '{}' for tenant {}: {}", report.getName(), tenantId, e.getMessage(), e);
            runLog.setStatus(IntegrationRunLog.Status.FAILED);
            runLog.setErrorMessage(e.getMessage());

            // Schedule retry if attempts remaining. Retry the RESOLVED window, not
            // the caller's raw arguments: a retry can fire up to 30 minutes later
            // and possibly past midnight, so re-deriving the window from the clock
            // would silently retry a DIFFERENT period than the run that failed.
            if (attemptNumber < runLog.getMaxRetries()) {
                scheduleRetry(report, schedule,
                        runLog.getDateRangeFrom(), runLog.getDateRangeTo(), attemptNumber + 1);
                runLog.setStatus(IntegrationRunLog.Status.RETRYING);
            }
        } finally {
            lock.unlock();
            finishRunLog(runLog, startMs);

            // Bookkeeping MUST be in the finally block. It used to sit after the
            // try/finally, so every early `return` inside the try — empty result
            // set, precondition not met — skipped it: lastRunAt went stale (which
            // matters now that it is the operator's evidence that a schedule is
            // alive) and the MDC context leaked onto the next task to reuse this
            // pool thread, mislabelling its log lines with this tenant.
            if (schedule != null) {
                try {
                    schedule.setLastRunAt(LocalDateTime.now());
                    scheduleRepo.save(schedule);
                } catch (Exception e) {
                    log.warn("[Integration] Could not update lastRunAt for schedule #{}: {}",
                            schedule.getId(), e.getMessage());
                }
            }
            org.slf4j.MDC.clear();
        }
    }

    // ─── External query execution ─────────────────────────────

    /**
     * Execute query against external database.
     */
    private List<Map<String, Object>> executeExternalQuery(IntegrationConnection config, String sql, Map<String, Object> params) {
        String url = config.getJdbcUrl();
        int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 30;

        log.info("[Integration] Connecting to {} ({}) at {}:{}", config.getName(), config.getDbType(), config.getHost(), config.getPort());

        // Defense-in-depth: reject stacked (';'-separated) statements before the SQL
        // reaches the driver. The durable control is still a read-only,
        // least-privilege DB account on this connection.
        assertSingleStatement(sql);

        Properties props = new Properties();
        props.setProperty("user", config.getUsername());
        // P0 fix: decrypt before handing to the JDBC driver. The column is
        // named encryptedPassword but historical data may be plaintext — the
        // CryptoService handles both transparently (logs a warning on legacy
        // plaintext rows so they get re-encrypted on next edit).
        props.setProperty("password", cryptoService.decrypt(config.getEncryptedPassword()));
        props.setProperty("loginTimeout", String.valueOf(timeout));

        // The executor backing setNetworkTimeout MUST be shut down or it leaks a
        // thread (and the executor) on every single pull. Scope it here, close it
        // in finally.
        java.util.concurrent.ExecutorService netTimeoutExec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection conn = DriverManager.getConnection(url, props)) {
            conn.setReadOnly(true); // Safety: prevent accidental writes to external DB
            try {
                conn.setNetworkTimeout(netTimeoutExec, timeout * 1000);
            } catch (SQLException | RuntimeException ignored) {
                // Not all drivers implement setNetworkTimeout; queryTimeout below still applies.
            }

            // SECURITY: bind ":name" params via NamedParamBinder (parses once, binds by
            // name in correct order). The old inline sql.replace(":"+key,"?") over a
            // HashMap had no word boundaries and undefined order → values could bind
            // to the wrong "?" slot.
            try (PreparedStatement ps = NamedParamBinder.prepare(conn, sql, params)) {
                ps.setQueryTimeout(timeout);
                ps.setMaxRows(MAX_PULL_ROWS);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = mapResultSet(rs);
                    if (rows.size() >= MAX_PULL_ROWS) {
                        log.warn("[Integration] Pull for '{}' hit the {}-row safety ceiling — result may be truncated.",
                                config.getName(), MAX_PULL_ROWS);
                    }
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("External DB query failed for '" + config.getName() + "': " + e.getMessage(), e);
        } finally {
            netTimeoutExec.shutdownNow();
        }
    }

    /**
     * Reject multi-statement (stacked) SQL. Strips one optional trailing semicolon,
     * then fails if any ';' remains. This is a pragmatic guard, not a full SQL
     * parser (a ';' inside a string literal would be a false positive — rare for a
     * read query) — defense-in-depth on top of the read-only least-privilege account.
     */
    private void assertSingleStatement(String sql) {
        if (sql == null) return;
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException(
                "Report SQL must be a single statement (stacked ';'-separated statements are not allowed).");
        }
    }

    // ─── Staging inserts (batched) ────────────────────────────

    /** Tracks per-reason skip counts so the run log can show WHY rows were dropped. */
    private static final class SkipTracker {
        int total = 0;
        final Map<String, Integer> reasons = new LinkedHashMap<>();
        void skip(String reason) {
            total++;
            if (reason == null) reason = "unknown";
            if (reason.length() > 160) reason = reason.substring(0, 160);
            if (reasons.size() < 5 || reasons.containsKey(reason)) {
                reasons.merge(reason, 1, Integer::sum);
            }
        }
        String summary() {
            StringBuilder sb = new StringBuilder(total + " row(s) skipped. ");
            reasons.forEach((r, c) -> sb.append("[x").append(c).append("] ").append(r).append("; "));
            return sb.toString();
        }
    }

    /** Result of a streamed pull: rows read from the source vs rows written to staging. */
    private record PullResult(long fetched, int processed) {}

    /**
     * Refuse to start while any ingest (file upload, server folder) is RUNNING
     * for this tenant — mirrors FileUploadService.assertNoRunningIngest, which
     * covers the opposite direction (upload blocked while a pull runs). The
     * pull's own ledger run only opens when the batch job launches, AFTER the
     * staging fill, so this check never sees itself. Ledger unavailable ->
     * proceed rather than block the pull over a bookkeeping query, same policy
     * as the upload path.
     */
    private void assertNoRunningIngestForTenant(Long tenantId) {
        try {
            Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingest_run WHERE tenant_id = ? AND status = 'RUNNING' " +
                "AND started_at > CURRENT_TIMESTAMP - INTERVAL '6 hours'",
                Integer.class, tenantId);
            if (running != null && running > 0) {
                throw new IllegalStateException(
                    "An ingestion is already running for this tenant — staging tables are shared per tenant, "
                    + "and starting the pull now would wipe the running load's staging mid-flight. "
                    + "The pull retries automatically; check /ops/ingest-trust for the running load.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("[Integration] Ingest ledger unavailable for running-ingest check (non-fatal): {}", e.toString());
        }
    }

    /**
     * Wipe this tenant's staging for the report type, then stream the report
     * query straight into it — the pull-side equivalent of the file path's
     * clean-staging + ingest steps. The per-tenant wipe is the same convention
     * all four feeds use; the REPLACE semantics live downstream in the shared
     * apply (stagingToFact REPLACE wipes + reloads the pulled dates,
     * applyRental/applyDcc replace by date, merchant is an upsert), so
     * re-pulling a day replaces that day exactly like re-uploading its file.
     */
    private PullResult pullToStaging(IntegrationReport report, IntegrationConnection config,
                                     Map<String, Object> params, Map<String, String> columnMap,
                                     Long tenantId, SkipTracker skips) {
        final String stagingTable;
        final String insertSql;
        final java.util.function.Function<Map<String, Object>, Object[]> mapper;
        switch (report.getReportType()) {
            case MERCHANT -> {
                stagingTable = "stg_merchant_master_raw";
                insertSql = MERCHANT_STAGING_INSERT;
                mapper = row -> mapMerchantArgs(row, columnMap, tenantId, skips);
            }
            case DCC -> {
                // Dedicated DCC revenue feed — applied by dbPullDccJob with
                // replace-by-date semantics. Amounts are tenant base currency,
                // major units.
                stagingTable = "stg_dcc_revenue_raw";
                insertSql = DCC_STAGING_INSERT;
                mapper = row -> mapDccArgs(row, columnMap, tenantId, skips);
            }
            case RENTAL -> {
                // Dedicated rental feed — applied by dbPullRentalJob. No unit
                // normalization: rental amounts are tenant base currency, major
                // units, for BOTH input formats (decision 2026-08-29).
                stagingTable = "stg_rental_raw";
                insertSql = RENTAL_STAGING_INSERT;
                mapper = row -> mapRentalArgs(row, columnMap, tenantId, skips);
            }
            default -> {
                stagingTable = "stg_trnx_raw";
                insertSql = TRANSACTION_STAGING_INSERT;
                mapper = row -> mapTransactionArgs(row, columnMap, tenantId, skips);
            }
        }

        // Clear existing staging for this tenant (same convention as the file
        // path's clean-staging step; safe against concurrent uploads thanks to
        // assertNoRunningIngestForTenant in executePull).
        jdbcTemplate.update("DELETE FROM " + stagingTable + " WHERE tenant_id = ?", tenantId);

        PullResult r = streamQueryToStaging(config, report.getSqlText(), params, insertSql, mapper);
        if (skips.total > 0) {
            log.warn("[Integration] {} staging: {}", report.getReportType(), skips.summary());
        }
        return r;
    }

    /**
     * Stream the report query into staging without materialising the result
     * set: rows are read cursor-wise (FETCH_SIZE) and flushed to staging in
     * INSERT_BATCH_SIZE batches, so heap stays flat for any pull size — the
     * old path built the ENTIRE result as a List of Maps first, which for a
     * 1M+ row transaction window was a multi-GB allocation. Connection setup
     * mirrors executeExternalQuery (which remains for the small precondition /
     * validation queries).
     */
    private PullResult streamQueryToStaging(IntegrationConnection config, String sql, Map<String, Object> params,
                                            String insertSql,
                                            java.util.function.Function<Map<String, Object>, Object[]> mapper) {
        String url = config.getJdbcUrl();
        int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 30;

        log.info("[Integration] Connecting to {} ({}) at {}:{}", config.getName(), config.getDbType(), config.getHost(), config.getPort());
        assertSingleStatement(sql);

        Properties props = new Properties();
        props.setProperty("user", config.getUsername());
        props.setProperty("password", cryptoService.decrypt(config.getEncryptedPassword()));
        props.setProperty("loginTimeout", String.valueOf(timeout));

        java.util.concurrent.ExecutorService netTimeoutExec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection extConn = DriverManager.getConnection(url, props)) {
            extConn.setReadOnly(true); // Safety: prevent accidental writes to external DB
            // Postgres only streams with autoCommit off (otherwise the driver
            // buffers the full result client-side, defeating the point); Oracle
            // and MSSQL honour fetchSize regardless. Read-only, so close()
            // simply discards the transaction.
            try { extConn.setAutoCommit(false); } catch (SQLException | RuntimeException ignored) {}
            try {
                extConn.setNetworkTimeout(netTimeoutExec, timeout * 1000);
            } catch (SQLException | RuntimeException ignored) {
                // Not all drivers implement setNetworkTimeout; queryTimeout below still applies.
            }

            try (PreparedStatement ps = NamedParamBinder.prepare(extConn, sql, params)) {
                ps.setQueryTimeout(timeout);
                ps.setFetchSize(FETCH_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    // Labels are lower-cased ONCE here, not per row. Lookup keys
                    // (staging field names and parsed column-mapping values) are
                    // already lower case, so every getMapped now hits the map's
                    // direct get. Without this, Oracle and MSSQL return UPPERCASE
                    // labels, every one of the ~33 transaction fields missed and
                    // fell through to getIgnoreCase's linear scan over all ~33
                    // entries — ~1e9 string comparisons on a 1M-row pull.
                    String[] labels = new String[colCount];
                    for (int i = 1; i <= colCount; i++) {
                        labels[i - 1] = meta.getColumnLabel(i) == null
                                ? "" : meta.getColumnLabel(i).trim().toLowerCase();
                    }

                    List<Object[]> batch = new ArrayList<>(INSERT_BATCH_SIZE);
                    long fetched = 0;
                    int processed = 0;
                    while (rs.next()) {
                        // A pull that hits the row ceiling is a PARTIAL extract.
                        // Applying it would silently publish an incomplete
                        // day/month (REPLACE deletes the real rows first) with
                        // the run recorded SUCCESS — the worst combination.
                        // Fail loudly; the partial staging rows are never
                        // applied (the batch job is not launched) and the next
                        // pull's wipe removes them.
                        if (++fetched > MAX_PULL_ROWS) {
                            throw new IllegalStateException(
                                "Source query returned more than " + MAX_PULL_ROWS + " rows and was STOPPED at the safety cap. "
                                + "Nothing was applied, because a partial extract would overwrite complete data. "
                                + "Narrow the report's date window (or split the schedule) and re-run.");
                        }
                        Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
                        // putIfAbsent, not put: two source columns whose labels
                        // differ only by case now collapse to one key, and the
                        // first non-null wins — the same column the old
                        // case-insensitive scan would have found.
                        for (int i = 1; i <= colCount; i++) row.putIfAbsent(labels[i - 1], rs.getObject(i));
                        Object[] args = mapper.apply(row);
                        if (args != null) {
                            batch.add(args);
                            processed++;
                        }
                        if (batch.size() >= INSERT_BATCH_SIZE) {
                            jdbcTemplate.batchUpdate(insertSql, batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) jdbcTemplate.batchUpdate(insertSql, batch);
                    return new PullResult(fetched, processed);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("External DB query failed for '" + config.getName() + "': "
                    + e.getMessage() + timeoutHint(e, timeout), e);
        } finally {
            netTimeoutExec.shutdownNow();
        }
    }

    /**
     * Appended to a pull failure that looks like the query timeout firing, so the
     * run log names the setting instead of showing a bare driver error. The
     * connection's timeout covers the WHOLE source query, and a high-volume feed
     * routinely needs longer than the 30s default — without this the pull just
     * retries into the same wall.
     */
    private String timeoutHint(SQLException e, int timeoutSeconds) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        boolean looksLikeTimeout = e instanceof SQLTimeoutException
                || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("canceling statement")          // PostgreSQL
                || msg.contains("ora-01013")                    // Oracle: user requested cancel
                || msg.contains("cancel of current operation"); // Oracle (text form)
        if (!looksLikeTimeout) return "";
        return " — this looks like the connection's " + timeoutSeconds + "s timeout firing. It applies to the"
                + " whole source query, not just connecting; high-volume feeds usually need 300-600s."
                + " Raise Timeout (seconds) on the connection, or narrow the report's date window.";
    }

    private static final String MERCHANT_STAGING_INSERT = """
            INSERT INTO stg_merchant_master_raw (
                tenant_id, institution_code, institution_name, entity_internal_id, entity_name, entity_code,
                aggregator_internal_id, aggregator_name, aggregator_code,
                merchant_internal_id, mid, merchant_name, merchant_status,
                merchant_store_internal_id, sid, store_legal_name, store_name, store_status,
                business_type, business_mcc, vat_number,
                primary_contact_person, primary_contact_number, primary_contact_email,
                address, city, state, postal_code,
                risk_level, product, date_of_onboarding,
                load_time
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

    /** Maps one source row to stg_merchant_master_raw insert args; null = skipped (recorded in skips). */
    private Object[] mapMerchantArgs(Map<String, Object> row, Map<String, String> columnMap,
                                     Long tenantId, SkipTracker skips) {
        try {
            return new Object[]{
                tenantId,
                str(getMapped(row, columnMap, "institution_code")),
                str(getMapped(row, columnMap, "institution_name")),
                str(getMapped(row, columnMap, "entity_internal_id")),
                str(getMapped(row, columnMap, "entity_name")),
                str(getMapped(row, columnMap, "entity_code")),
                str(getMapped(row, columnMap, "aggregator_internal_id")),
                str(getMapped(row, columnMap, "aggregator_name")),
                str(getMapped(row, columnMap, "aggregator_code")),
                str(getMapped(row, columnMap, "merchant_internal_id")),
                str(getMapped(row, columnMap, "mid")),
                str(getMapped(row, columnMap, "merchant_name")),
                str(getMapped(row, columnMap, "merchant_status")),
                str(getMapped(row, columnMap, "merchant_store_internal_id")),
                str(getMapped(row, columnMap, "sid")),
                str(getMapped(row, columnMap, "store_legal_name")),
                str(getMapped(row, columnMap, "store_name")),
                str(getMapped(row, columnMap, "store_status")),
                str(getMapped(row, columnMap, "business_type")),
                str(getMapped(row, columnMap, "business_mcc")),
                str(getMapped(row, columnMap, "vat_number")),
                str(getMapped(row, columnMap, "primary_contact_person")),
                str(getMapped(row, columnMap, "primary_contact_number")),
                str(getMapped(row, columnMap, "primary_contact_email")),
                str(getMapped(row, columnMap, "address")),
                str(getMapped(row, columnMap, "city")),
                str(getMapped(row, columnMap, "state")),
                str(getMapped(row, columnMap, "postal_code")),
                str(getMapped(row, columnMap, "risk_level")),
                str(getMapped(row, columnMap, "product")),
                str(getMapped(row, columnMap, "date_of_onboarding"))
            };
        } catch (Exception e) {
            skips.skip(e.getMessage());
            return null;
        }
    }

    private static final String DCC_STAGING_INSERT = """
            INSERT INTO stg_dcc_revenue_raw (
                tenant_id, sid, file_tenant_id, merchant_share, acquirer_share, payment_date, load_time
            ) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

    /**
     * Maps one source row to stg_dcc_revenue_raw insert args; null = skipped.
     * Mapped staging fields: sid, merchant_share, acquirer_share, payment_date
     * (file_tenant_id optional — validated, never used for routing). The apply
     * step (DccRevenueJobConfig.applyDccTasklet) then does the same validation
     * and replace-by-date apply as the file path.
     */
    private Object[] mapDccArgs(Map<String, Object> row, Map<String, String> columnMap,
                                Long tenantId, SkipTracker skips) {
        try {
            BigDecimal merchantShare = toBigDecimal(getMapped(row, columnMap, "merchant_share"));
            BigDecimal acquirerShare = toBigDecimal(getMapped(row, columnMap, "acquirer_share"));
            Timestamp paymentDate = toTimestamp(getMapped(row, columnMap, "payment_date"));
            if ((merchantShare == null && acquirerShare == null) || paymentDate == null) {
                skips.skip("merchant_share/acquirer_share or payment_date missing/unparseable");
                return null;
            }
            return new Object[]{
                tenantId,
                str(getMapped(row, columnMap, "sid")),
                str(getMapped(row, columnMap, "file_tenant_id")),
                merchantShare,
                acquirerShare,
                new java.sql.Date(paymentDate.getTime())
            };
        } catch (Exception e) {
            skips.skip(e.getMessage());
            return null;
        }
    }

    private static final String RENTAL_STAGING_INSERT = """
            INSERT INTO stg_rental_raw (
                tenant_id, entity_name, mid, sid, tid, rental_amount, payment_date, load_time
            ) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

    /**
     * Maps one source row to stg_rental_raw insert args; null = skipped.
     * Mapped staging fields: mid, sid, tid, rental_amount, payment_date
     * (entity_name optional). Level is NOT mapped —
     * RentalJobConfig.applyRentalTasklet derives it from which ids are
     * present, identically to the file path.
     */
    private Object[] mapRentalArgs(Map<String, Object> row, Map<String, String> columnMap,
                                   Long tenantId, SkipTracker skips) {
        try {
            BigDecimal amount = toBigDecimal(getMapped(row, columnMap, "rental_amount"));
            Timestamp paymentDate = toTimestamp(getMapped(row, columnMap, "payment_date"));
            if (amount == null || paymentDate == null) {
                skips.skip("rental_amount or payment_date missing/unparseable");
                return null;
            }
            return new Object[]{
                tenantId,
                str(getMapped(row, columnMap, "entity_name")),
                str(getMapped(row, columnMap, "mid")),
                str(getMapped(row, columnMap, "sid")),
                str(getMapped(row, columnMap, "tid")),
                amount,
                new java.sql.Date(paymentDate.getTime())
            };
        } catch (Exception e) {
            skips.skip(e.getMessage());
            return null;
        }
    }

    private static final String TRANSACTION_STAGING_INSERT = """
            INSERT INTO stg_trnx_raw (
                tenant_id, entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
                mid, merchant_internal_id, merchant_name,
                sid, merchant_store_internal_id, store_name,
                tid, arn, rrn_number, card_number, auth_code,
                payment_date, transaction_date, batch_number, transaction_type,
                card_scheme, card_type, card_product_code, dcc,
                txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                msf, vat, total_amount_settled, interchange_fee, destination,
                load_time
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

    /**
     * Maps one source row to stg_trnx_raw insert args; null = skipped.
     * Includes card_product_code (granular feed 'Card Type' — VIPM/MCPM/MCDB…)
     * which tier resolution needs; defaults to the raw card_type value when not
     * mapped separately. ingest_run_id is deliberately NOT set here — the run
     * only exists once dbPullTransactionJob launches; its adoptStagingStep tags
     * these rows with the run id before any downstream read.
     */
    private Object[] mapTransactionArgs(Map<String, Object> row, Map<String, String> columnMap,
                                        Long tenantId, SkipTracker skips) {
        try {
            Timestamp paymentDate = toTimestamp(getMapped(row, columnMap, "payment_date"));
            if (paymentDate == null) {
                // Without payment_date the row can't be partition-routed or
                // date-scoped anywhere downstream — skip loudly, not silently.
                skips.skip("payment_date missing/unparseable");
                return null;
            }
            String rawCardType = str(getMapped(row, columnMap, "card_type"));
            String productCode = str(getMapped(row, columnMap, "card_product_code"));
            if (productCode == null || productCode.isBlank()) {
                // Preserve the granular product code BEFORE card_type is
                // coarsened to DEBIT/CREDIT/PREPAID (same rule as the file path).
                productCode = rawCardType;
            }
            return new Object[]{
                tenantId,
                str(getMapped(row, columnMap, "entity_name")),
                str(getMapped(row, columnMap, "aggregator_internal_id")),
                str(getMapped(row, columnMap, "aggregator_name")),
                str(getMapped(row, columnMap, "aggregator_code")),
                str(getMapped(row, columnMap, "mid")),
                str(getMapped(row, columnMap, "merchant_internal_id")),
                str(getMapped(row, columnMap, "merchant_name")),
                str(getMapped(row, columnMap, "sid")),
                str(getMapped(row, columnMap, "merchant_store_internal_id")),
                str(getMapped(row, columnMap, "store_name")),
                str(getMapped(row, columnMap, "tid")),
                str(getMapped(row, columnMap, "arn")),
                str(getMapped(row, columnMap, "rrn_number")),
                str(getMapped(row, columnMap, "card_number")),
                str(getMapped(row, columnMap, "auth_code")),
                paymentDate,
                toTimestamp(getMapped(row, columnMap, "transaction_date")),
                str(getMapped(row, columnMap, "batch_number")),
                str(getMapped(row, columnMap, "transaction_type")),
                str(getMapped(row, columnMap, "card_scheme")),
                rawCardType,
                productCode,
                toBoolean(getMapped(row, columnMap, "dcc")),
                str(getMapped(row, columnMap, "txn_currency")),
                toBigDecimal(getMapped(row, columnMap, "txn_currency_amount")),
                str(getMapped(row, columnMap, "store_base_currency")),
                toBigDecimal(getMapped(row, columnMap, "store_base_currency_amount")),
                toBigDecimal(getMapped(row, columnMap, "msf")),
                toBigDecimal(getMapped(row, columnMap, "vat")),
                toBigDecimal(getMapped(row, columnMap, "total_amount_settled")),
                toBigDecimal(getMapped(row, columnMap, "interchange_fee")),
                str(getMapped(row, columnMap, "destination"))
            };
        } catch (Exception e) {
            skips.skip(e.getMessage());
            return null;
        }
    }

    /**
     * Normalize staged transaction rows to match what the file path's
     * transactionTenantProcessor produces, so the shared batch pipeline sees
     * identical data regardless of source:
     *  1. card_product_code backfilled from card_type where missing;
     *  2. card_type coarsened to DEBIT/CREDIT/PREPAID via ref_card_scheme
     *     (exact code match, same as the in-memory map lookup);
     *  3. ISO-numeric currency tokens ('784') translated to codes ('AED')
     *     via ref_country for both txn and store-base currency;
     *  4. when amountsMinorUnits: divide txn/store-base amounts by the
     *     currency's decimal_notation_value (default 100 when unknown, same
     *     as the file path) and interchange by 10000 — mirrors CMM handling.
     */
    /**
     * tenant.input_format = 'CMM' (or anything but 'AMS') -> amounts are minor
     * units and need the decimal_notation_value division. 'AMS' -> final
     * decimals, no division. Mirrors FileUploadService.inputTypeForTenant.
     */
    private boolean isCmmTenant(Long tenantId) {
        try {
            String fmt = jdbcTemplate.queryForObject(
                "SELECT input_format FROM tenant WHERE tenant_id = ?", String.class, tenantId);
            return !"AMS".equalsIgnoreCase(fmt == null ? "" : fmt.trim());
        } catch (Exception e) {
            return true; // legacy default: CMM (divide)
        }
    }

    private void normalizeStagedTransactions(Long tenantId, boolean minorUnits) {
        long t0 = System.currentTimeMillis();

        int pc = jdbcTemplate.update(
            "UPDATE stg_trnx_raw SET card_product_code = NULLIF(TRIM(card_type), '') " +
            "WHERE tenant_id = ? AND (card_product_code IS NULL OR TRIM(card_product_code) = '') AND card_type IS NOT NULL",
            tenantId);

        int ct = jdbcTemplate.update(
            "UPDATE stg_trnx_raw s SET card_type = CASE rcs.card_type " +
            "  WHEN 2 THEN 'DEBIT' WHEN 4 THEN 'DEBIT' " +
            "  WHEN 0 THEN 'CREDIT' WHEN 1 THEN 'CREDIT' " +
            "  WHEN 3 THEN 'PREPAID' ELSE s.card_type END " +
            "FROM ref_card_scheme rcs " +
            "WHERE s.tenant_id = ? AND s.card_type IS NOT NULL AND rcs.code = TRIM(s.card_type)",
            tenantId);

        int cc1 = jdbcTemplate.update(
            "UPDATE stg_trnx_raw s SET txn_currency = TRIM(rc.currency_code) FROM ref_country rc " +
            "WHERE s.tenant_id = ? AND rc.iso_numeric IS NOT NULL AND rc.currency_code IS NOT NULL " +
            "AND TRIM(s.txn_currency) = TRIM(rc.iso_numeric)",
            tenantId);
        int cc2 = jdbcTemplate.update(
            "UPDATE stg_trnx_raw s SET store_base_currency = TRIM(rc.currency_code) FROM ref_country rc " +
            "WHERE s.tenant_id = ? AND rc.iso_numeric IS NOT NULL AND rc.currency_code IS NOT NULL " +
            "AND TRIM(s.store_base_currency) = TRIM(rc.iso_numeric)",
            tenantId);

        int divided = 0;
        if (minorUnits) {
            // CURRENCY-AWARE SCALE (2026-08-10). The divisor was always resolved from
            // ref_country, but the ROUND() scale next to it was hardcoded to 2 — so a
            // BHD amount was divided by 1000 and then immediately rounded back to 2dp,
            // destroying the fils this code had just correctly recovered. LOG10 of the
            // divisor gives the currency's real precision (1000 -> 3, 100 -> 2).
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET txn_currency_amount = ROUND(s.txn_currency_amount / d.div, " +
                "  CAST(ROUND(LOG(10, d.div)) AS INT)) " +
                "FROM LATERAL (SELECT COALESCE((SELECT MAX(CASE WHEN rc.decimal_notation_value > 0 " +
                "                THEN rc.decimal_notation_value ELSE 100 END) FROM ref_country rc " +
                "                WHERE TRIM(rc.currency_code) = TRIM(s.txn_currency)), 100)::NUMERIC AS div) d " +
                "WHERE s.tenant_id = ? AND s.txn_currency_amount IS NOT NULL",
                tenantId);
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET store_base_currency_amount = ROUND(s.store_base_currency_amount / d.div, " +
                "  CAST(ROUND(LOG(10, d.div)) AS INT)) " +
                "FROM LATERAL (SELECT COALESCE((SELECT MAX(CASE WHEN rc.decimal_notation_value > 0 " +
                "                THEN rc.decimal_notation_value ELSE 100 END) FROM ref_country rc " +
                "                WHERE TRIM(rc.currency_code) = TRIM(s.store_base_currency)), 100)::NUMERIC AS div) d " +
                "WHERE s.tenant_id = ? AND s.store_base_currency_amount IS NOT NULL",
                tenantId);
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw SET interchange_fee = ROUND(interchange_fee / 10000, 4) " +
                "WHERE tenant_id = ? AND interchange_fee IS NOT NULL",
                tenantId);
        }

        log.info("[Integration] Staging normalization for tenant {} in {}ms — productCode={}, cardTypeCoarsened={}, currencyCodes={}/{}, minorUnitDivisions={}",
                tenantId, System.currentTimeMillis() - t0, pc, ct, cc1, cc2, divided);
    }

    /** Distinct payment dates currently staged for this tenant (for date-scoped metrics). */
    private List<LocalDate> stagedTransactionDates(Long tenantId) {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL",
            LocalDate.class, tenantId);
    }

    // ─── Batch pipeline launch (parity with file upload) ──────

    /**
     * Launch the SAME Spring Batch post-processing pipeline the file path uses
     * and WAIT for completion (the primary JobLauncher is async — run() returns
     * a JobExecution in STARTING state). Throws on job failure so the run log
     * reflects it and retries kick in.
     */
    private void runBatchPipeline(IntegrationReport.ReportType reportType, Long tenantId) throws Exception {
        Job job = switch (reportType) {
            case MERCHANT -> dbPullMerchantJob;
            case RENTAL -> dbPullRentalJob;
            case DCC -> dbPullDccJob;
            default -> dbPullTransactionJob;
        };

        JobParametersBuilder pb = new JobParametersBuilder()
                .addLong("tenantId", tenantId)
                .addLong("startedAt", System.currentTimeMillis()); // uniqueness per run
        if (reportType == IntegrationReport.ReportType.TRANSACTION) {
            // DB pulls are replace-by-date, never additive: stagingToFact deletes
            // the pulled dates from fact + summaries before re-inserting.
            pb.addString("loadMode", "REPLACE");
        }
        JobParameters jobParameters = pb.toJobParameters();

        log.info("[Integration] Launching {} for tenant {}", job.getName(), tenantId);
        JobExecution execution = jobLauncher.run(job, jobParameters);
        Long executionId = execution.getId();

        long waited = 0;
        while (waited < JOB_POLL_TIMEOUT_MS) {
            JobExecution current = jobExplorer.getJobExecution(executionId);
            if (current != null && !current.isRunning()) {
                if (current.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED) {
                    log.info("[Integration] {} COMPLETED for tenant {} in {}s", job.getName(), tenantId, waited / 1000);
                    return;
                }
                String failure = current.getAllFailureExceptions().isEmpty()
                        ? String.valueOf(current.getStatus())
                        : current.getAllFailureExceptions().get(0).getMessage();
                throw new RuntimeException("Batch pipeline " + job.getName() + " ended with status "
                        + current.getStatus() + ": " + failure);
            }
            Thread.sleep(JOB_POLL_INTERVAL_MS);
            waited += JOB_POLL_INTERVAL_MS;
        }
        throw new RuntimeException("Batch pipeline " + job.getName() + " did not finish within "
                + (JOB_POLL_TIMEOUT_MS / 60000) + " minutes (executionId=" + executionId + ") — check Batch Logs.");
    }

    // ─── Retry ────────────────────────────────────────────────

    /**
     * Schedule a retry with exponential backoff via the shared TaskScheduler.
     * (The previous java.util.Timer-per-retry leaked a non-daemon thread on
     * every retry and its tasks died silently with the pod.)
     */
    private void scheduleRetry(IntegrationReport report, IntegrationSchedule schedule,
                                LocalDate dateFrom, LocalDate dateTo, int nextAttempt) {
        long delayMs = (long) Math.pow(5, nextAttempt) * 60_000L; // 5min, 25min, 125min
        delayMs = Math.min(delayMs, 30 * 60_000L); // Cap at 30 minutes

        log.info("[Integration] Scheduling retry #{} for '{}' in {}ms", nextAttempt, report.getName(), delayMs);

        // Through the proxy (self), NOT this.executePull — see the `self` field.
        taskScheduler.schedule(
                () -> self.executePull(report, schedule, IntegrationRunLog.TriggerType.RETRY, dateFrom, dateTo, nextAttempt),
                Instant.now().plusMillis(delayMs));
    }

    // ─── Helpers ──────────────────────────────────────────────

    /**
     * Truthiness for the precondition gate's single result cell. Upstream batch
     * trackers signal completion in many shapes — boolean flags, row counts,
     * status strings — so accept the common ones rather than forcing one schema.
     */
    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0;
        String s = v.toString().trim().toUpperCase();
        return s.equals("TRUE") || s.equals("T") || s.equals("Y") || s.equals("YES")
                || s.equals("1") || s.equals("COMPLETED") || s.equals("COMPLETE")
                || s.equals("SUCCESS") || s.equals("DONE");
    }

    /**
     * Build the bind values offered to the report SQL.
     *
     * The window is the caller's — SCHEDULED runs pass a rolling lookback window
     * computed in the SCHEDULE's timezone (DynamicSchedulerService), MANUAL runs
     * pass the operator's dates, and RETRY reuses the original run's window so a
     * retry re-pulls the same days rather than a window that has since shifted.
     *
     * ':year'/':month'/':today' are derived from the window's END, not from
     * LocalDate.now(): deriving them from the wall clock made a retry (which can
     * fire hours later, possibly past midnight) silently pull a different period
     * than the run it was retrying. ':yearFrom'/':monthFrom' expose the window's
     * START so a month-granular report can cover a window that spans a month
     * boundary — see warnIfMonthGranular.
     *
     * Extra entries are harmless: NamedParamBinder binds only the placeholders
     * that actually appear in the SQL.
     */
    private Map<String, Object> buildParams(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();
        // Fallback only — every real caller passes a window. Kept as a rolling
        // lookback (NOT the old 1st-of-month default, which never re-pulled the
        // previous month's last day).
        LocalDate from = dateFrom != null ? dateFrom : to.minusDays(Math.max(0, lookbackDays));

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                "Invalid pull window: dateFrom (" + from + ") is after dateTo (" + to + ").");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("year", to.getYear());
        params.put("month", to.getMonthValue());
        params.put("yearFrom", from.getYear());
        params.put("monthFrom", from.getMonthValue());
        params.put("today", to);
        params.put("dateFrom", from);
        params.put("dateTo", to);
        return params;
    }

    /**
     * Warn when a report filters by month but the run's window spans two months.
     *
     * A report written as "WHERE YEAR(d) = :year AND MONTH(d) = :month" ignores
     * :dateFrom/:dateTo entirely, so the rolling lookback cannot pull the tail of
     * the previous month for it — the month-end gap the lookback exists to close
     * stays open. Such a report should filter on ":dateFrom AND :dateTo", or
     * additionally accept ":yearFrom"/":monthFrom".
     */
    private void warnIfMonthGranular(IntegrationReport report, LocalDate from, LocalDate to) {
        String sql = report.getSqlText();
        if (sql == null || from == null || to == null) return;
        boolean sameMonth = from.getYear() == to.getYear() && from.getMonthValue() == to.getMonthValue();
        if (sameMonth) return;

        String lower = sql.toLowerCase();
        boolean usesMonth = lower.contains(":month");
        boolean usesWindow = lower.contains(":datefrom") || lower.contains(":dateto")
                || lower.contains(":monthfrom");
        if (usesMonth && !usesWindow) {
            log.warn("[Integration] Report '{}' filters on :month but the pull window {}..{} spans two months. "
                    + "The earlier month's days will NOT be pulled. Rewrite the report to filter between "
                    + ":dateFrom and :dateTo (or also accept :yearFrom/:monthFrom).",
                    report.getName(), from, to);
        }
    }

    /**
     * Parse the column-mapping JSON with a real JSON parser (Jackson).
     * The previous hand-rolled split(",")/split(":") broke on any ',' or ':'
     * inside a key/value. Format: {"SQL_COL":"staging_field", ...} — stored
     * inverted as staging_field -> sql_column for lookup.
     */
    private Map<String, String> parseColumnMapping(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, String> raw = JSON.readValue(json, new TypeReference<Map<String, String>>() {});
            Map<String, String> map = new HashMap<>();
            raw.forEach((sqlCol, stagingField) -> {
                if (sqlCol != null && stagingField != null) {
                    map.put(stagingField.trim().toLowerCase(), sqlCol.trim().toLowerCase());
                }
            });
            return map;
        } catch (Exception e) {
            log.warn("Failed to parse column mapping: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Get RAW value from row using column mapping — returns the JDBC object
     * (Timestamp, BigDecimal, String, …) so type fidelity is preserved. The
     * previous version stringified everything, which nulled every date column:
     * java.sql.Timestamp.toString() is '2026-07-14 10:30:00.0' (space-separated)
     * and the old parser only accepted ISO-8601.
     */
    private Object getMapped(Map<String, Object> row, Map<String, String> columnMap, String stagingField) {
        // 1. Check mapping
        String sqlCol = columnMap.get(stagingField);
        if (sqlCol != null) {
            return getIgnoreCase(row, sqlCol);
        }
        // 2. Direct match (case-insensitive)
        return getIgnoreCase(row, stagingField);
    }

    /**
     * On the streamed pull path the row keys are already lower-cased (see
     * streamQueryToStaging), and both lookup key sources — the staging field
     * names and parseColumnMapping's values — are lower case too, so the direct
     * get hits and the scan below never runs. The scan is kept as a safety net
     * for any caller whose keys are not normalized; it is O(columns) per miss,
     * so it must not become the hot path again.
     */
    private Object getIgnoreCase(Map<String, Object> row, String key) {
        Object direct = row.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String str(Object val) {
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Cache of {@code timestampValue()} per driver value class (Oracle's Datum
     * subclasses), so the per-row reflective lookup in toTimestamp resolves once
     * per class instead of once per column per row. Empty = the class has no such
     * method. Bounded by the number of JDBC value types the drivers return.
     */
    private static final Map<Class<?>, Optional<java.lang.reflect.Method>> TIMESTAMP_VALUE_METHODS =
            new ConcurrentHashMap<>();

    /** Accepts 'yyyy-MM-dd HH:mm:ss[.fraction]' (JDBC toString), ISO-8601, and bare dates. */
    private static final DateTimeFormatter JDBC_TS_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    /**
     * Type-faithful timestamp conversion — handles native JDBC temporal types
     * from all three supported drivers first, then common string formats.
     */
    private Timestamp toTimestamp(Object val) {
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts;
        if (val instanceof java.sql.Date d) return new Timestamp(d.getTime());
        if (val instanceof java.util.Date d) return new Timestamp(d.getTime());
        if (val instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
        if (val instanceof LocalDate ld) return Timestamp.valueOf(ld.atStartOfDay());
        if (val instanceof java.time.OffsetDateTime odt) return Timestamp.valueOf(odt.toLocalDateTime());
        if (val instanceof java.time.ZonedDateTime zdt) return Timestamp.valueOf(zdt.toLocalDateTime());
        if (val instanceof java.time.Instant inst) return Timestamp.from(inst);

        // ORACLE: oracle.sql.TIMESTAMP / oracle.sql.DATE are Datum subclasses —
        // NOT java.util.Date — and their toString() ("2026-8-21 0:0:0.0") matches
        // none of the parsers below, so payment_date came back null and
        // insertTransactionStaging SILENTLY DROPPED the row. Every Oracle-sourced
        // transaction pull could therefore ingest zero rows while reporting
        // SUCCESS. Reflection keeps this driver-agnostic (no compile-time
        // dependency on ojdbc, and it also covers other vendors' Datum types).
        // Method lookup is CACHED per class: Class.getMethod scans and copies the
        // declared-method array on every call, and this runs for payment_date AND
        // transaction_date on every row — two million reflective lookups on a
        // 1M-row Oracle pull. The resolution itself is unchanged.
        java.lang.reflect.Method m = TIMESTAMP_VALUE_METHODS
                .computeIfAbsent(val.getClass(), c -> {
                    try {
                        return Optional.of(c.getMethod("timestampValue"));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
        if (m != null) {
            try {
                Object ts = m.invoke(val);
                if (ts instanceof Timestamp t) return t;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Oracle TIMESTAMPTZ, whose timestampValue() needs a Connection —
                // fall through to the string parsers.
            }
        }

        String s = val.toString().trim();
        if (s.isEmpty()) return null;
        try { return Timestamp.valueOf(LocalDateTime.parse(s)); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(s, JDBC_TS_FORMAT)); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")).atStartOfDay()); } catch (Exception ignored) {}
        return null;
    }

    private Boolean toBoolean(Object val) {
        if (val == null) return null;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        String s = val.toString().trim().toUpperCase();
        return "Y".equals(s) || "YES".equals(s) || "TRUE".equals(s) || "1".equals(s);
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return new BigDecimal(n.toString());
        String s = val.toString().replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private void finishRunLog(IntegrationRunLog runLog, long startMs) {
        runLog.setEndTime(LocalDateTime.now());
        runLog.setDurationMs(System.currentTimeMillis() - startMs);
        runLogRepo.save(runLog);
        publishFailureAlert(runLog);
    }

    /**
     * Fire the failure-alert event for a FINAL failure only. RETRYING runs stay
     * silent (another attempt is coming); runs without a schedule (ad-hoc from
     * the UI, where the operator is watching) stay silent too. Alerting must
     * never break the pull bookkeeping — everything is caught.
     */
    private void publishFailureAlert(IntegrationRunLog runLog) {
        try {
            if (eventPublisher == null) return;
            if (runLog.getStatus() != IntegrationRunLog.Status.FAILED) return;
            IntegrationSchedule schedule = runLog.getSchedule();
            if (schedule == null) return;
            if (Boolean.FALSE.equals(schedule.getAlertOnFailure())) return;
            String recipients = schedule.getAlertEmails();
            if (recipients == null || recipients.isBlank()) return;

            IntegrationReport report = runLog.getReport();
            eventPublisher.publishEvent(new com.acquira.common.event.IntegrationRunFailedEvent(
                    runLog.getTenantId(),
                    runLog.getId(),
                    schedule.getId(),
                    report != null ? report.getName() : "Unknown report",
                    report != null && report.getReportType() != null ? report.getReportType().name() : null,
                    report != null && report.getConnection() != null ? report.getConnection().getName() : null,
                    runLog.getTriggerType() != null ? runLog.getTriggerType().name() : null,
                    runLog.getErrorMessage(),
                    runLog.getAttemptNumber() != null ? runLog.getAttemptNumber() : 1,
                    runLog.getMaxRetries() != null ? runLog.getMaxRetries() : 1,
                    runLog.getDateRangeFrom(),
                    runLog.getDateRangeTo(),
                    recipients));
        } catch (Exception e) {
            log.warn("[Integration] Could not publish failure alert for run #{}: {}",
                    runLog.getId(), e.getMessage());
        }
    }

    // ─── Public test connection ───────────────────────────────

    public boolean testConnection(IntegrationConnection config) {
        return testConnectionError(config) == null;
    }

    /**
     * Returns null when the connection opens and is valid, otherwise the
     * driver/validator error message — for the ad-hoc pre-save test in the
     * connection editor, where "failed" alone isn't actionable.
     */
    public String testConnectionError(IntegrationConnection config) {
        int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 5;
        // loginTimeout goes through the per-connection property rather than the
        // JVM-global DriverManager.setLoginTimeout, which would race other pulls.
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", config.getUsername() != null ? config.getUsername() : "");
        props.setProperty("password", cryptoService.decrypt(config.getEncryptedPassword()));
        props.setProperty("loginTimeout", String.valueOf(timeout));
        try (Connection conn = DriverManager.getConnection(config.getJdbcUrl(), props)) {
            return conn.isValid(timeout) ? null : "The driver reported the connection as not valid.";
        } catch (SQLException | RuntimeException e) {
            log.error("Test connection failed for '{}': {}", config.getName(), e.getMessage());
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    /**
     * Validate SQL by running with LIMIT/ROWNUM/TOP 5.
     */
    public List<Map<String, Object>> validateQuery(IntegrationConnection config, String sql) {
        String limitedSql = "SELECT * FROM (" + sql.replaceAll(";$", "") + ") _vq LIMIT 5";
        if (config.getDbType() == IntegrationConnection.DbType.ORACLE) {
            limitedSql = "SELECT * FROM (" + sql.replaceAll(";$", "") + ") WHERE ROWNUM <= 5";
        } else if (config.getDbType() == IntegrationConnection.DbType.MSSQL) {
            limitedSql = "SELECT TOP 5 * FROM (" + sql.replaceAll(";$", "") + ") AS _vq";
        }

        Map<String, Object> params = buildParams(LocalDate.now().withDayOfMonth(1), LocalDate.now());
        try {
            return executeExternalQuery(config, limitedSql, params);
        } catch (RuntimeException e) {
            // MSSQL rejects ORDER BY inside a derived table unless TOP/OFFSET is
            // also present — surface a usable hint instead of the raw driver error.
            if (config.getDbType() == IntegrationConnection.DbType.MSSQL
                    && e.getMessage() != null && e.getMessage().toUpperCase().contains("ORDER BY")) {
                throw new RuntimeException(e.getMessage()
                    + " — Hint: SQL Server does not allow ORDER BY inside a subquery. "
                    + "Remove the ORDER BY from the report SQL (row order does not matter for ingestion).", e);
            }
            throw e;
        }
    }
}
