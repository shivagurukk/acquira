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

            // 3. Fetch from external DB
            log.info("[Integration] Pulling {} report '{}' for tenant {} (attempt {}/{})",
                    report.getReportType(), report.getName(), tenantId, attemptNumber, runLog.getMaxRetries());

            List<Map<String, Object>> rawRows = executeExternalQuery(conn, report.getSqlText(), params);
            runLog.setRowsFetched(rawRows.size());

            // A pull that hit the row ceiling is a PARTIAL extract. Loading it
            // would silently publish an incomplete day/month to the warehouse
            // (REPLACE mode deletes the real rows first), and the run would be
            // recorded SUCCESS — the worst possible combination. Fail loudly and
            // make the operator narrow the window instead.
            if (rawRows.size() >= MAX_PULL_ROWS) {
                throw new IllegalStateException(
                    "Source query returned at least " + MAX_PULL_ROWS + " rows and was TRUNCATED at the safety cap. "
                    + "Nothing was loaded, because a partial extract would overwrite complete data. "
                    + "Narrow the report's date window (or split the schedule) and re-run.");
            }

            if (rawRows.isEmpty()) {
                log.warn("[Integration] No rows returned for report '{}', tenant {}", report.getName(), tenantId);
                runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
                runLog.setRowsProcessed(0);
                return; // finally persists the run log — calling finishRunLog here too saved it twice
            }

            // 4. Parse column mapping
            Map<String, String> columnMap = parseColumnMapping(report.getColumnMapping());

            // 5. Insert into staging table based on report type (batched)
            SkipTracker skips = new SkipTracker();
            int processed;
            if (report.getReportType() == IntegrationReport.ReportType.MERCHANT) {
                processed = insertMerchantStaging(rawRows, columnMap, tenantId, skips);
            } else if (report.getReportType() == IntegrationReport.ReportType.RENTAL) {
                // Dedicated rental feed — stg_rental_raw, applied by dbPullRentalJob.
                // No unit normalization: rental amounts are tenant base currency,
                // major units, for BOTH input formats (decision 2026-08-29).
                processed = insertRentalStaging(rawRows, columnMap, tenantId, skips);
            } else {
                processed = insertTransactionStaging(rawRows, columnMap, tenantId, skips);
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
            runLog.setRowsFailed(rawRows.size() - processed);
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
                    report.getName(), tenantId, rawRows.size(), processed,
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

    /**
     * Insert fetched rows into stg_merchant_master_raw (batched).
     */
    private int insertMerchantStaging(List<Map<String, Object>> rows, Map<String, String> columnMap,
                                      Long tenantId, SkipTracker skips) {
        // Clear existing staging for this tenant
        jdbcTemplate.update("DELETE FROM stg_merchant_master_raw WHERE tenant_id = ?", tenantId);

        String sql = """
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

        List<Object[]> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                batch.add(new Object[]{
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
                });
                count++;
            } catch (Exception e) {
                skips.skip(e.getMessage());
            }
            if (batch.size() >= INSERT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
        if (skips.total > 0) log.warn("[Integration] Merchant staging: {}", skips.summary());
        return count;
    }

    /**
     * Insert fetched rows into stg_rental_raw (batched). Mapped staging fields:
     * mid, sid, tid, rental_amount, payment_date (entity_name optional).
     * Level is NOT mapped — RentalJobConfig.applyRentalTasklet derives it from
     * which ids are present, identically to the file path.
     */
    private int insertRentalStaging(List<Map<String, Object>> rows, Map<String, String> columnMap,
                                    Long tenantId, SkipTracker skips) {
        // Clear existing staging for this tenant (same convention as merchant/transaction)
        jdbcTemplate.update("DELETE FROM stg_rental_raw WHERE tenant_id = ?", tenantId);

        String sql = """
            INSERT INTO stg_rental_raw (
                tenant_id, entity_name, mid, sid, tid, rental_amount, payment_date, load_time
            ) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

        List<Object[]> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                BigDecimal amount = toBigDecimal(getMapped(row, columnMap, "rental_amount"));
                Timestamp paymentDate = toTimestamp(getMapped(row, columnMap, "payment_date"));
                if (amount == null || paymentDate == null) {
                    skips.skip("rental_amount or payment_date missing/unparseable");
                    continue;
                }
                batch.add(new Object[]{
                    tenantId,
                    str(getMapped(row, columnMap, "entity_name")),
                    str(getMapped(row, columnMap, "mid")),
                    str(getMapped(row, columnMap, "sid")),
                    str(getMapped(row, columnMap, "tid")),
                    amount,
                    new java.sql.Date(paymentDate.getTime())
                });
                count++;
            } catch (Exception e) {
                skips.skip(e.getMessage());
            }
            if (batch.size() >= INSERT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
        if (skips.total > 0) log.warn("[Integration] Rental staging: {}", skips.summary());
        return count;
    }

    /**
     * Insert fetched rows into stg_trnx_raw (batched). Includes card_product_code
     * (granular feed 'Card Type' — VIPM/MCPM/MCDB…) which tier resolution needs;
     * defaults to the raw card_type value when not mapped separately.
     */
    private int insertTransactionStaging(List<Map<String, Object>> rows, Map<String, String> columnMap,
                                         Long tenantId, SkipTracker skips) {
        // Clear existing staging for this tenant
        jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);

        String sql = """
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

        List<Object[]> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                Timestamp paymentDate = toTimestamp(getMapped(row, columnMap, "payment_date"));
                if (paymentDate == null) {
                    // Without payment_date the row can't be partition-routed or
                    // date-scoped anywhere downstream — skip loudly, not silently.
                    skips.skip("payment_date missing/unparseable");
                    continue;
                }
                String rawCardType = str(getMapped(row, columnMap, "card_type"));
                String productCode = str(getMapped(row, columnMap, "card_product_code"));
                if (productCode == null || productCode.isBlank()) {
                    // Preserve the granular product code BEFORE card_type is
                    // coarsened to DEBIT/CREDIT/PREPAID (same rule as the file path).
                    productCode = rawCardType;
                }
                batch.add(new Object[]{
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
                });
                count++;
            } catch (Exception e) {
                skips.skip(e.getMessage());
            }
            if (batch.size() >= INSERT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
        if (skips.total > 0) log.warn("[Integration] Transaction staging: {}", skips.summary());
        return count;
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
        try {
            java.lang.reflect.Method m = val.getClass().getMethod("timestampValue");
            Object ts = m.invoke(val);
            if (ts instanceof Timestamp t) return t;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not an Oracle Datum (or TIMESTAMPTZ, whose timestampValue needs a
            // Connection) — fall through to the string parsers.
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
    }

    // ─── Public test connection ───────────────────────────────

    public boolean testConnection(IntegrationConnection config) {
        try (Connection conn = DriverManager.getConnection(
                config.getJdbcUrl(), config.getUsername(),
                cryptoService.decrypt(config.getEncryptedPassword()))) {
            return conn.isValid(config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 5);
        } catch (SQLException e) {
            log.error("Test connection failed for '{}': {}", config.getName(), e.getMessage());
            return false;
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
