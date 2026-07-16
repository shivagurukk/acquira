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
        this.taskScheduler = taskScheduler;
    }

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
    @Async
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

            // 3. Fetch from external DB
            log.info("[Integration] Pulling {} report '{}' for tenant {} (attempt {}/{})",
                    report.getReportType(), report.getName(), tenantId, attemptNumber, runLog.getMaxRetries());

            List<Map<String, Object>> rawRows = executeExternalQuery(conn, report.getSqlText(), params);
            runLog.setRowsFetched(rawRows.size());

            if (rawRows.isEmpty()) {
                log.warn("[Integration] No rows returned for report '{}', tenant {}", report.getName(), tenantId);
                runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
                runLog.setRowsProcessed(0);
                finishRunLog(runLog, startMs);
                return;
            }

            // 4. Parse column mapping
            Map<String, String> columnMap = parseColumnMapping(report.getColumnMapping());

            // 5. Insert into staging table based on report type (batched)
            SkipTracker skips = new SkipTracker();
            int processed;
            if (report.getReportType() == IntegrationReport.ReportType.MERCHANT) {
                processed = insertMerchantStaging(rawRows, columnMap, tenantId, skips);
            } else {
                processed = insertTransactionStaging(rawRows, columnMap, tenantId, skips);
                // 5b. Normalize staged rows to match what the file-path ItemProcessor
                //     produces: granular card_product_code preserved, card_type
                //     coarsened to DEBIT/CREDIT/PREPAID, ISO-numeric currency tokens
                //     translated to codes, optional minor-unit division.
                normalizeStagedTransactions(tenantId, Boolean.TRUE.equals(report.getAmountsMinorUnits()));
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

            // Schedule retry if attempts remaining
            if (attemptNumber < runLog.getMaxRetries()) {
                scheduleRetry(report, schedule, dateFrom, dateTo, attemptNumber + 1);
                runLog.setStatus(IntegrationRunLog.Status.RETRYING);
            }
        } finally {
            lock.unlock();
            finishRunLog(runLog, startMs);
        }

        // Update schedule last run time
        if (schedule != null) {
            schedule.setLastRunAt(LocalDateTime.now());
            scheduleRepo.save(schedule);
        }
        org.slf4j.MDC.clear();
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
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET txn_currency_amount = ROUND(s.txn_currency_amount / " +
                "COALESCE((SELECT MAX(CASE WHEN rc.decimal_notation_value > 0 THEN rc.decimal_notation_value ELSE 100 END) " +
                "          FROM ref_country rc WHERE TRIM(rc.currency_code) = TRIM(s.txn_currency)), 100), 2) " +
                "WHERE s.tenant_id = ? AND s.txn_currency_amount IS NOT NULL",
                tenantId);
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET store_base_currency_amount = ROUND(s.store_base_currency_amount / " +
                "COALESCE((SELECT MAX(CASE WHEN rc.decimal_notation_value > 0 THEN rc.decimal_notation_value ELSE 100 END) " +
                "          FROM ref_country rc WHERE TRIM(rc.currency_code) = TRIM(s.store_base_currency)), 100), 2) " +
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
        Job job = (reportType == IntegrationReport.ReportType.MERCHANT) ? dbPullMerchantJob : dbPullTransactionJob;

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

        taskScheduler.schedule(
                () -> executePull(report, schedule, IntegrationRunLog.TriggerType.RETRY, dateFrom, dateTo, nextAttempt),
                Instant.now().plusMillis(delayMs));
    }

    // ─── Helpers ──────────────────────────────────────────────

    private Map<String, Object> buildParams(LocalDate dateFrom, LocalDate dateTo) {
        Map<String, Object> params = new HashMap<>();
        LocalDate now = LocalDate.now();
        params.put("year", now.getYear());
        params.put("month", now.getMonthValue());
        params.put("today", now);
        params.put("dateFrom", dateFrom != null ? dateFrom : now.withDayOfMonth(1));
        params.put("dateTo", dateTo != null ? dateTo : now);
        return params;
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
