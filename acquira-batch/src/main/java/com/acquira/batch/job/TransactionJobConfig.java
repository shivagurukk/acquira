package com.acquira.batch.job;

import com.acquira.common.model.StagingTransaction;
import com.acquira.common.service.MerchantMetricCalculator;
import com.acquira.common.repository.SumDailyMerchantRepository;
import com.acquira.common.repository.SumMonthlyMerchantMetricsRepository;
import com.acquira.common.model.SumDailyMerchant;
import com.acquira.common.model.SumMonthlyMerchantMetrics;

import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HIGH-PERFORMANCE Transaction Job
 *
 * IMPORTANT - currency-amount semantics:
 *   - txn_currency_amount        = amount in CARDHOLDER currency (mixed: AED, USD, EUR, KES, IQD)
 *   - store_base_currency_amount = amount in MERCHANT settlement currency (single currency)
 *
 * Any aggregation that produces a single-currency total MUST use store_base_currency_amount.
 * AS OF 2026-07-06 every summary total_volume (bank/merchant/mcc/scheme/channel/terminal/
 * finance/insight) and merchant_activity_summary value is aggregated from
 * store_base_currency_amount — txn_currency_amount is stored on the fact row for
 * reference/Explorer only and is never summed into a display total.
 * Using txn_currency_amount produces wildly inflated totals when foreign-currency
 * intl transactions are present (e.g. an IQD/KES txn whose raw amount is 100x-1000x the AED).
 *
 * IMPORTANT - DCC flag parsing:
 *   The DCC column has appeared as 'Y'/'Yes' (older feeds) AND 'TRUE'/'FALSE' (newer feeds).
 *   All DCC parsing goes through parseDccFlag(...) which accepts Y/YES/TRUE/T/1.
 */
@Configuration
public class TransactionJobConfig {

    private static final Logger log = LoggerFactory.getLogger(TransactionJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final MerchantMetricCalculator merchantMetricCalculator;
    private final SumDailyMerchantRepository dailyMerchantRepo;
    private final SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final com.acquira.batch.service.PartitionMaintenanceService partitionMaintenanceService;
    private final com.acquira.common.service.ChurnScoringService churnScoringService;
    private final com.acquira.common.service.MerchantSegmentationService merchantSegmentationService;

    // MDC context listener — populates tenant/job/step on the batch worker thread
    // for every step so parallel batch log lines are attributable. Field-injected
    // (not constructor) to keep the existing constructor signature untouched.
    @org.springframework.beans.factory.annotation.Autowired
    private MdcStepListener mdcStepListener;

    // Clears the Caffeine report caches when an ingest finishes, so dashboards
    // pick up new data immediately instead of waiting out the cache TTL.
    @org.springframework.beans.factory.annotation.Autowired
    private CacheEvictionJobListener cacheEvictionJobListener;

    // Ingestion ledger (ingest_run / ingest_run_stage). The job listener opens
    // and closes the run and publishes its id into the job execution context;
    // the step listener records one row per stage. Both are best-effort and can
    // never fail a job — see IngestRunRecorder.
    @org.springframework.beans.factory.annotation.Autowired
    private IngestRunStepListener ingestRunStepListener;

    @org.springframework.beans.factory.annotation.Autowired
    private IngestRunJobListener ingestRunJobListener;

    // Records row counts and destructive-delete volumes onto the ledger row as
    // the pipeline discovers them.
    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.ingest.IngestRunRecorder ingestRunRecorder;

    /**
     * REPLACE guard (P0-3). A REPLACE load deletes fact rows by whole DATE, so a
     * partial-day resend can destroy a full day. When the incoming row count for
     * the batch is below this fraction of what already exists, the load is
     * refused instead of silently destroying data.
     *
     * Set acquira.replace.guard.enabled=false to restore the old behaviour, or
     * raise/lower the ratio per environment. Some tenants legitimately resend
     * small days, which is why this is configurable rather than hardcoded.
     */
    @org.springframework.beans.factory.annotation.Value("${acquira.replace.guard.enabled:true}")
    private boolean replaceGuardEnabled;

    @org.springframework.beans.factory.annotation.Value("${acquira.replace.guard.min-ratio:0.5}")
    private double replaceGuardMinRatio;

    // Shared bulk rebuild of sum_monthly_merchant_metrics (also used by
    // BulkMigrationService and BackfillIngestionService, which previously each
    // carried their own per-merchant N+1 copy of this logic).
    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.service.MonthlyMetricsRebuilder monthlyMetricsRebuilder;

    // Shared fee engine + summary population (extracted 2026-08-28) — the same
    // beans BulkMigrationService and BackfillIngestionService now use, so all
    // fact-writing paths price and summarize identically.
    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.batch.service.FeeComputationService feeComputationService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.batch.service.SummaryPopulationService summaryPopulationService;

    public TransactionJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DataSource dataSource, JdbcTemplate jdbcTemplate,
            MerchantMetricCalculator merchantMetricCalculator,
            SumDailyMerchantRepository dailyMerchantRepo,
            SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
            com.acquira.batch.service.PartitionMaintenanceService partitionMaintenanceService,
            com.acquira.common.service.ChurnScoringService churnScoringService,
            com.acquira.common.service.MerchantSegmentationService merchantSegmentationService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.merchantMetricCalculator = merchantMetricCalculator;
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.partitionMaintenanceService = partitionMaintenanceService;
        this.churnScoringService = churnScoringService;
        this.merchantSegmentationService = merchantSegmentationService;
    }

    private static final String NUMERIC_ONLY_REGEX = "'^[0-9.]+$'";

    // ── Ingest ledger helpers ───────────────────────────────────────────────
    // Tasklets are lambdas with only a ChunkContext to reach the job execution,
    // so these two pull the run id out of it and record against the ledger
    // without any tasklet needing to know how the plumbing works.

    /** Run id published by IngestRunJobListener.beforeJob; null when the ledger is unavailable. */
    private static Long ingestRunIdOf(org.springframework.batch.core.scope.context.ChunkContext chunkContext) {
        try {
            org.springframework.batch.item.ExecutionContext ctx = chunkContext.getStepContext()
                .getStepExecution().getJobExecution().getExecutionContext();
            return ctx.containsKey(IngestRunJobListener.CTX_RUN_ID)
                ? ctx.getLong(IngestRunJobListener.CTX_RUN_ID) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Records how many existing fact rows a load destroyed before writing its own. */
    private void recordDeleted(org.springframework.batch.core.scope.context.ChunkContext chunkContext, Long deleted) {
        try {
            Long runId = ingestRunIdOf(chunkContext);
            if (runId != null) {
                ingestRunRecorder.updateCounts(runId, null, null, null, null, null, deleted,
                    null, null, null, null);
            }
        } catch (Exception e) {
            log.warn("Could not record deleted-row count on the ingest ledger (non-fatal): {}", e.toString());
        }
    }

    // PERF FIX: compiled once at class-load time, not per buildSafeDateInList() call.
    private static final java.util.regex.Pattern ISO_DATE_PATTERN =
        java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * Sargable partition-pruning range over the RAW payment_date column, ready to
     * be prepended to a {@code DATE(payment_date) IN (...)} predicate.
     *
     * WHY BOTH: fact_transaction is RANGE-partitioned on payment_date (monthly).
     * {@code DATE(payment_date)} wraps the partition key in a function, so
     * Postgres can neither prune partitions nor range-scan
     * idx_fact_txn_tenant_date — every such query scanned the tenant's ENTIRE
     * history, which meant ingest cost grew with every month of data retained
     * rather than staying proportional to the day being loaded. The raw-column
     * range prunes to the affected partitions; the exact IN list is kept because
     * the dates inside the range may be sparse.
     *
     * @param alias table alias including the dot ("f.", "ft.") or "" for none
     * @return e.g. {@code " f.payment_date >= DATE '2026-08-01' AND f.payment_date < DATE '2026-08-03' + INTERVAL '1 day' AND "}
     *         — note the trailing AND: callers splice this immediately before
     *         the DATE(...) IN predicate.
     */
    private static String dateRangeClause(java.util.List<java.sql.Date> sortedDates, String alias) {
        // Canonical implementation lives in IngestScopes (shared with the
        // backfill / bulk-migration rebuild paths since 2026-08-28).
        return com.acquira.batch.service.IngestScopes.rangeClause(sortedDates, alias);
    }

    private static Boolean parseDccFlag(String raw) {
        if (raw == null) return Boolean.FALSE;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return Boolean.FALSE;
        return ("Y".equals(s) || "YES".equals(s)
             || "TRUE".equals(s) || "T".equals(s)
             || "1".equals(s)) ? Boolean.TRUE : Boolean.FALSE;
    }

    private static String networkNameFromCardTypeToken(String rawCardType) {
        if (rawCardType == null) return null;
        String s = rawCardType.trim().toUpperCase();
        if (s.isEmpty()) return null;
        switch (s) {
            case "JCB":                       return "JCB";
            case "AMEX": case "AMERICAN EXPRESS": return "American Express";
            case "DINERS": case "DINERS CLUB":   return "Diners Club";
            case "DISCOVER":                  return "Discover";
            default:                          return null;
        }
    }

    @Bean
    public Job transactionLoadJob(
            @org.springframework.beans.factory.annotation.Qualifier("ensurePartitionsStep") Step ensurePartitionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("splitExcelStep") Step splitExcelStep,
            @org.springframework.beans.factory.annotation.Qualifier("cleanTargetDayStep") Step cleanTargetDayStep,
            @org.springframework.beans.factory.annotation.Qualifier("masterIngestStep") Step masterIngestStep,
            @org.springframework.beans.factory.annotation.Qualifier("analyzeStagingStep") Step analyzeStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("autoCreateDimensionsStep") Step autoCreateDimensionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("clearRunStagingStep") Step clearRunStagingStep) {
        return new JobBuilder("transactionLoadJob", jobRepository)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .start(ensurePartitionsStep).next(splitExcelStep).next(cleanTargetDayStep)
                .next(masterIngestStep).next(analyzeStagingStep).next(autoCreateDimensionsStep)
                .next(stagingToFactStep).next(populateSummaryStep)
                .next(calculateBusinessMetricsStep).next(scoreMlStep).next(computeSegmentsStep)
                .next(calculateDailyDashboardMetricsStep).next(clearRunStagingStep).build();
    }

    /**
     * DB-pull processing job — the EXACT same post-ingestion pipeline as
     * transactionLoadJob, minus the file-specific steps (splitExcel,
     * cleanTargetDay, masterIngest). IntegrationPullService populates
     * stg_trnx_raw itself (staging is cleared + batch-inserted there), then
     * launches this job so DB pulls get full parity with file uploads:
     * dimension auto-create, stagingToFact with fee computation, ALL summary
     * tables, business metrics, ML scoring, segments, and dashboard metrics.
     * Job params: tenantId (Long), loadMode (String), startedAt (Long, uniqueness).
     *
     * adoptStagingStep MUST run first — see its javadoc; without it the
     * run-scoped staging reads see zero rows and the whole pipeline no-ops
     * green. clearRunStagingStep at the end mirrors transactionLoadJob's
     * cleanup so pulled staging rows don't linger until the next upload.
     */
    @Bean
    public Job dbPullTransactionJob(
            @org.springframework.beans.factory.annotation.Qualifier("adoptStagingStep") Step adoptStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("ensurePartitionsStep") Step ensurePartitionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("analyzeStagingStep") Step analyzeStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("autoCreateDimensionsStep") Step autoCreateDimensionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("clearRunStagingStep") Step clearRunStagingStep) {
        return new JobBuilder("dbPullTransactionJob", jobRepository)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .start(adoptStagingStep)
                .next(ensurePartitionsStep).next(analyzeStagingStep).next(autoCreateDimensionsStep)
                .next(stagingToFactStep).next(populateSummaryStep)
                .next(calculateBusinessMetricsStep).next(scoreMlStep).next(computeSegmentsStep)
                .next(calculateDailyDashboardMetricsStep).next(clearRunStagingStep).build();
    }

    /**
     * FIRST step of dbPullTransactionJob ONLY (2026-09-05).
     *
     * IntegrationPullService fills stg_trnx_raw BEFORE this job exists, so its
     * rows carry no ingest_run_id — while IngestRunJobListener DOES open a
     * ledger run for this job (source DB_PULL), which switches every downstream
     * staging read to `AND ingest_run_id = <run>` (the 2026-08-29 run-scope
     * fix). Without adoption those reads matched ZERO rows: stagingToFact saw
     * "staging is empty", the job COMPLETED green, and the pull reported
     * SUCCESS having loaded nothing. Adopting the tenant's untagged rows into
     * THIS run makes the pull genuinely run-scoped — full parity with the file
     * path, including cleanTargetDay's protection of RUNNING siblings.
     *
     * Only untagged (ingest_run_id IS NULL) rows are adopted, so a concurrent
     * file upload's tagged rows are never stolen.
     */
    @Bean
    public Step adoptStagingStep(Tasklet adoptStagingTasklet) {
        return new StepBuilder("adoptStagingStep", jobRepository)
            .tasklet(adoptStagingTasklet, transactionManager).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet adoptStagingTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            Long ingestRunId = ingestRunIdOf(chunkContext);
            if (ingestRunId == null) {
                // Ledger unavailable — downstream reads fall back to tenant
                // scope, which is safe because the pull wiped staging first.
                log.warn("adoptStaging: no ingest run id available — staging reads fall back to tenant scope");
                return RepeatStatus.FINISHED;
            }
            int rows = jdbcTemplate.update(
                "UPDATE stg_trnx_raw SET ingest_run_id = ? WHERE tenant_id = ? AND ingest_run_id IS NULL",
                ingestRunId, tenantId);
            log.info("adoptStaging: tagged {} staged row(s) with run {}", rows, ingestRunId);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The final pass of a sequential server-folder load: just the TENANT-WIDE
     * reporting steps (churn scoring + segmentation) that each file's job
     * skipped under deferReporting=true. Runs once per tenant over the complete
     * data — the 21-file BH backfill executed these 23 times overnight with
     * every run but the last overwritten. businessMetrics and dashboard
     * snapshots are NOT here: they are scoped to each file's dates/months and
     * must stay per-file. Job params: tenantId (Long), startedAt (uniqueness).
     */
    @Bean
    public Job reportingOnlyJob(
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep) {
        return new JobBuilder("reportingOnlyJob", jobRepository)
                .listener(cacheEvictionJobListener)
                .start(scoreMlStep).next(computeSegmentsStep).build();
    }

    @Bean
    public Step autoCreateDimensionsStep(Tasklet autoCreateDimensionsTasklet) {
        return new StepBuilder("autoCreateDimensionsStep", jobRepository)
            .tasklet(autoCreateDimensionsTasklet, transactionManager).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet autoCreateDimensionsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();
            // Run-scope every staging read (2026-08-29) so a sequential folder load
            // does not re-scan every prior file's accumulated staging rows for each
            // new file. Dimension creation is idempotent, but the EXISTS/INSERT scans
            // over a growing staging table were O(n^2) across a 50-file batch.
            final Long ingestRunId = ingestRunIdOf(chunkContext);
            final String stgRunWhereS = ingestRunId != null ? " AND s.ingest_run_id = " + ingestRunId : "";

            int orphansRemoved = jdbcTemplate.update(
                "DELETE FROM dim_merchant m " +
                "WHERE m.tenant_id = ? " +
                "  AND m.internal_id LIKE 'AUTO_SID_%' " +
                "  AND (m.name IS NULL OR TRIM(m.name) = '') " +
                "  AND NOT EXISTS (SELECT 1 FROM fact_transaction f " +
                "    WHERE f.tenant_id = m.tenant_id AND f.merchant_id = m.merchant_id LIMIT 1) " +
                "  AND NOT EXISTS (SELECT 1 FROM sum_daily_merchant s " +
                "    WHERE s.tenant_id = m.tenant_id AND s.merchant_id = m.merchant_id LIMIT 1)",
                tenantId);
            if (orphansRemoved > 0) {
                log.info("  cleanup: removed {} orphan auto-created merchant placeholder(s)", orphansRemoved);
            }

            int merchantsAdded = 0;
            Boolean hasUnmappedMerchants = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw s " +
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL" + stgRunWhereS + " " +
                "AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                "AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                "LIMIT 1)", Boolean.class, tenantId);

            if (Boolean.TRUE.equals(hasUnmappedMerchants)) {
                merchantsAdded = jdbcTemplate.update(
                    "INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, status, created_date) " +
                    "SELECT s.tenant_id, " +
                    "  'AUTO_SID_' || TRIM(s.sid), " +
                    "  COALESCE(NULLIF(TRIM(MAX(s.mid)), ''), 'AUTO_MID_' || TRIM(s.sid)), " +
                    "  COALESCE(" +
                    "    MAX(CASE WHEN s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '' " +
                    "             AND s.merchant_name !~ " + NUMERIC_ONLY_REGEX + " THEN s.merchant_name END), " +
                    "    MAX(NULLIF(TRIM(s.merchant_store_legal_name), '')), " +
                    "    MAX(NULLIF(TRIM(s.store_name), '')), " +
                    "    'Merchant ' || TRIM(s.sid)), " +
                    "  'ACTIVE', NOW() " +
                    "FROM stg_trnx_raw s " +
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL" + stgRunWhereS + " " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                    "GROUP BY s.tenant_id, TRIM(s.sid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            int storesAdded = 0;
            // PERF (2026-08-29): the store gate is the SAME EXISTS as the merchant
            // gate above (same "staged sid with no dim_store AND no dim_terminal"
            // predicate) — re-running it was a second full staging scan for one
            // answer. The merchant INSERT only adds dim_merchant rows, never
            // dim_store/dim_terminal, so the gate's truth value is unchanged by it;
            // reuse the earlier result.
            Boolean hasUnmappedStores = hasUnmappedMerchants;

            if (Boolean.TRUE.equals(hasUnmappedStores)) {
                storesAdded = jdbcTemplate.update(
                    "INSERT INTO dim_store (tenant_id, internal_id, merchant_id, sid, name, status, created_date) " +
                    "SELECT s.tenant_id, " +
                    "  'AUTO_STORE_SID_' || TRIM(s.sid), " +
                    "  m.merchant_id, " +
                    "  TRIM(s.sid), " +
                    "  COALESCE(MAX(NULLIF(TRIM(s.store_name), '')), " +
                    "           MAX(NULLIF(TRIM(s.merchant_store_legal_name), '')), " +
                    "           MAX(NULLIF(TRIM(s.merchant_name), '')), " +
                    "           'Store ' || TRIM(s.sid)), " +
                    "  'ACTIVE', NOW() " +
                    "FROM stg_trnx_raw s " +
                    // PERF (2026-08-29): resolve the owning merchant via a LATERAL
                    // of two SINGLE-index probes instead of a disjunctive join
                    // `ON (m.mid = ... OR m.internal_id = ...)`. The OR cannot use
                    // either index (idx_dim_merchant_mid / the unique
                    // (tenant_id, internal_id)), so it degraded to a hash/nested
                    // join scanning ~every staged row x the tenant's merchants.
                    // COALESCE prefers the real MID match, falling back to the
                    // auto-created AUTO_SID_<sid> merchant — the same two arms the
                    // OR expressed. dim_store's unique internal_id (AUTO_STORE_SID_
                    // <sid>) already collapses any duplicate to one row, so picking
                    // one merchant per sid is the identical end state.
                    "JOIN LATERAL (SELECT COALESCE(" +
                    "    (SELECT m1.merchant_id FROM dim_merchant m1 " +
                    "       WHERE m1.tenant_id = s.tenant_id AND m1.mid = NULLIF(TRIM(s.mid), '') LIMIT 1), " +
                    "    (SELECT m2.merchant_id FROM dim_merchant m2 " +
                    "       WHERE m2.tenant_id = s.tenant_id AND m2.internal_id = 'AUTO_SID_' || TRIM(s.sid) LIMIT 1) " +
                    "  ) AS merchant_id) m ON m.merchant_id IS NOT NULL " +
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL" + stgRunWhereS + " " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_store ds " +
                    "    WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt " +
                    "    WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                    "GROUP BY s.tenant_id, m.merchant_id, TRIM(s.sid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            int terminalsAdded = 0;
            Boolean hasUnmappedTerminals = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw s " +
                "JOIN dim_store ds ON ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid) " +
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.tid), '') IS NOT NULL" + stgRunWhereS + " " +
                "AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id " +
                "  AND dt.store_id = ds.store_id AND dt.tid = TRIM(s.tid)) " +
                "LIMIT 1)", Boolean.class, tenantId);

            if (Boolean.TRUE.equals(hasUnmappedTerminals)) {
                terminalsAdded = jdbcTemplate.update(
                    "INSERT INTO dim_terminal (tenant_id, internal_id, store_id, tid, status, created_date) " +
                    "SELECT s.tenant_id, " +
                    "  'AUTO_TERM_' || TRIM(s.sid) || '_' || TRIM(s.tid), " +
                    "  ds.store_id, " +
                    "  TRIM(s.tid), " +
                    "  'ACTIVE', NOW() " +
                    "FROM stg_trnx_raw s " +
                    "JOIN dim_store ds ON ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid) " +
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.tid), '') IS NOT NULL" + stgRunWhereS + " " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id " +
                    "    AND dt.store_id = ds.store_id AND dt.tid = TRIM(s.tid)) " +
                    "GROUP BY s.tenant_id, ds.store_id, TRIM(s.sid), TRIM(s.tid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            log.info(String.format("autoCreateDimensions: +%d merchants, +%d stores, +%d terminals in %.1fs (skipped: %s%s%s)",
                merchantsAdded, storesAdded, terminalsAdded,
                (System.currentTimeMillis() - start) / 1000.0,
                Boolean.TRUE.equals(hasUnmappedMerchants) ? "" : "merchants ",
                Boolean.TRUE.equals(hasUnmappedStores) ? "" : "stores ",
                Boolean.TRUE.equals(hasUnmappedTerminals) ? "" : "terminals"));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step ensurePartitionsStep(Tasklet ensurePartitionsTasklet) {
        return new StepBuilder("ensurePartitionsStep", jobRepository).tasklet(ensurePartitionsTasklet, transactionManager).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }
    @Bean public Tasklet ensurePartitionsTasklet() {
        return (contribution, chunkContext) -> {
            long t = System.currentTimeMillis();
            partitionMaintenanceService.ensurePartitionsForCurrentAndNextYear();
            log.info(String.format("ensurePartitions completed in %.1fs", (System.currentTimeMillis() - t) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step splitExcelStep(ExcelSplitterTasklet excelSplitterTasklet) {
        return new StepBuilder("splitExcelStep", jobRepository).tasklet(excelSplitterTasklet, transactionManager).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    /**
     * PERF: refresh planner statistics on the freshly-loaded staging table.
     *
     * stg_trnx_raw is emptied and re-filled on every single upload, so by the time
     * the joins below run, PostgreSQL's stats for it describe the PREVIOUS load (or
     * an empty table on a cold start). The planner then estimates a handful of rows
     * where there are millions and picks nested loops for the stagingToFact insert,
     * the two ID fix-ups, and the fee update — each of which degrades from a hash
     * join into a per-row index probe. That is the classic "ingestion was fine last
     * week and crawls today" shape.
     *
     * One ANALYZE costs a few seconds and lets every downstream step plan against
     * the real row count. It runs outside a transaction and after staging is filled.
     */
    @Bean public Step analyzeStagingStep(Tasklet analyzeStagingTasklet) {
        return new StepBuilder("analyzeStagingStep", jobRepository)
            .tasklet(analyzeStagingTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .listener(mdcStepListener).listener(ingestRunStepListener).build();
    }
    @Bean @StepScope public Tasklet analyzeStagingTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            long t = System.currentTimeMillis();
            try {
                jdbcTemplate.execute("ANALYZE stg_trnx_raw");
                log.info(String.format("analyzeStaging completed in %.1fs",
                    (System.currentTimeMillis() - t) / 1000.0));
            } catch (Exception e) {
                // Stats are an optimisation, never a correctness requirement — a
                // permissions failure here must not fail the whole ingestion.
                log.warn("ANALYZE stg_trnx_raw failed (non-fatal, ingestion continues): {}", e.getMessage());
            }

            // PARTITION COVERAGE for the years this file actually contains.
            //
            // ensurePartitionsStep runs FIRST — before the file has even been read — so it
            // can only guess, and it guesses `current year + next year`. Any backdated
            // upload (a historical backload, or a multi-month file reaching into a prior
            // year) therefore had no matching monthly partition and every such row fell
            // into fact_transaction_default. No error, but those rows lose partition
            // pruning permanently, and the default partition grows without bound.
            //
            // Here staging IS loaded, so the real range is known. Provisioning is
            // idempotent (CREATE TABLE IF NOT EXISTS) and the service caches verified
            // years, so re-provisioning the current year costs nothing.
            if (tenantId != null) {
                try {
                    java.util.List<Integer> years = jdbcTemplate.queryForList(
                        "SELECT DISTINCT EXTRACT(YEAR FROM payment_date)::int AS y FROM stg_trnx_raw " +
                        "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY y",
                        Integer.class, tenantId);
                    for (Integer y : years) {
                        if (y != null) partitionMaintenanceService.ensurePartitionsForYear(y);
                    }
                    if (!years.isEmpty()) {
                        log.info("ensurePartitions(staging range): provisioned year(s) {}", years);
                    }
                } catch (Exception e) {
                    log.warn("Partition provisioning for staging range failed (non-fatal): {}", e.getMessage());
                }
            }
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step cleanTargetDayStep(Tasklet cleanTargetDayTasklet) {
        return new StepBuilder("cleanTargetDayStep", jobRepository).tasklet(cleanTargetDayTasklet, transactionManager).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }
    /**
     * Clears staging ahead of this run's ingest.
     *
     * P0-2 (INGEST TRUST): this used to be an unqualified
     * {@code DELETE FROM stg_trnx_raw WHERE tenant_id = ?} — no day scope and no
     * run scope, despite the name. Two uploads for the same tenant at once meant
     * the second wiped the first's staging mid-flight, and the first then
     * "succeeded" having written a fraction of its rows.
     *
     * Now it clears only what is NOT this run's: rows belonging to runs that have
     * already reached a terminal state, plus untagged legacy rows. A concurrently
     * RUNNING sibling's rows are left alone.
     *
     * NOTE ON SCOPE — read this before "finishing the job" by scoping more reads.
     * Everything downstream (stagingToFactTasklet and friends) still reads
     * staging by tenant_id alone. Leaving a sibling's rows in place is therefore
     * only SAFE because FileUploadService now refuses to start a second ingest
     * while one is RUNNING for the same tenant (see assertNoRunningIngest). The
     * defect is fixed by preventing the concurrency, not by supporting it —
     * supporting it would mean run-scoping ~20 downstream queries, and a
     * half-scoped pipeline silently mixes two uploads into one fact load.
     */
    @Bean @StepScope public Tasklet cleanTargetDayTasklet(
            @Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobExecutionContext['ingestRunId']}") Long ingestRunId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long t = System.currentTimeMillis();
            int rows;
            if (ingestRunId == null) {
                // Ledger unavailable — fall back to the old behaviour rather than
                // leaving staging dirty, but say so, because concurrency safety
                // is not available on this path.
                rows = jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);
                log.warn("cleanTargetDay: no ingest run id available, cleared ALL staging for tenant {} "
                    + "(concurrent uploads are not safe on this path)", tenantId);
            } else {
                rows = jdbcTemplate.update(
                    "DELETE FROM stg_trnx_raw s WHERE s.tenant_id = ? AND " +
                    "(s.ingest_run_id IS NULL OR s.ingest_run_id <> ?) AND " +
                    "(s.ingest_run_id IS NULL OR NOT EXISTS (" +
                    "   SELECT 1 FROM ingest_run r WHERE r.id = s.ingest_run_id AND r.status = 'RUNNING'))",
                    tenantId, ingestRunId);
            }
            log.info(String.format("cleanTargetDay completed in %.1fs (deleted %d staging rows, run=%s)",
                (System.currentTimeMillis() - t) / 1000.0, rows, String.valueOf(ingestRunId)));
            return RepeatStatus.FINISHED;
        };
    }

    // RUN STAGING CLEANUP (2026-08-29). The LAST step of the job: delete this
    // run's own staging rows once every reader (stagingToFact, summaries, business
    // + dashboard metrics) has consumed them. Without it, stg_trnx_raw grew by one
    // file per file in a sequential folder load — a 50-file batch left ~50 files'
    // rows piled up (disk + ANALYZE cost), and cleanTargetDay's guarded delete had
    // been leaving them (prior runs still protected). Deletes ONLY this run's rows,
    // so it is safe under concurrency. If the job crashes earlier, the next job's
    // cleanTargetDay removes the leftover (the crashed run is no longer RUNNING).
    @Bean public Step clearRunStagingStep(Tasklet clearRunStagingTasklet) {
        return new StepBuilder("clearRunStagingStep", jobRepository)
            .tasklet(clearRunStagingTasklet, transactionManager)
            .listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet clearRunStagingTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long t = System.currentTimeMillis();
            Long ingestRunId = ingestRunIdOf(chunkContext);
            int rows = (ingestRunId != null)
                ? jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ? AND ingest_run_id = ?", tenantId, ingestRunId)
                : jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);
            log.info(String.format("clearRunStaging: deleted %d staging row(s) for run %s in %.1fs",
                rows, String.valueOf(ingestRunId), (System.currentTimeMillis() - t) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step masterIngestStep(Step csvWorkerStep, CsvPartitioner partitioner,
            @org.springframework.beans.factory.annotation.Qualifier("transactionPartitionExecutor")
            org.springframework.core.task.TaskExecutor partitionExecutor) {
        // NOTE: gridSize is INERT here — CsvPartitioner.partition(int) ignores its
        // argument and returns one partition per part_NNN.csv on disk. The real
        // concurrency limit is transactionPartitionExecutor's pool size (8); the
        // partition COUNT is (rows / ExcelSplitterTasklet.CHUNK_SIZE). Left at 8
        // only because Spring Batch requires a value.
        return new StepBuilder("masterIngestStep", jobRepository).partitioner("csvWorkerStep", partitioner)
                .step(csvWorkerStep).taskExecutor(partitionExecutor).gridSize(8).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    // PERF FIX: ThreadPoolTaskExecutor instead of SimpleAsyncTaskExecutor.
    // SimpleAsyncTaskExecutor has no reject policy. ThreadPoolTaskExecutor is
    // bounded, keeps-alive, and shuts down gracefully with Spring lifecycle.
    @Bean("transactionPartitionExecutor")
    public org.springframework.core.task.TaskExecutor transactionPartitionExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
            new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        // CRITICAL: the queue must hold EVERY partition, not zero.
        // CsvPartitioner creates one partition per part_NNN.csv (50k rows each,
        // see ExcelSplitterTasklet.CHUNK_SIZE), so a 1M-row file produces ~20
        // partitions — gridSize(8) below is ignored by that partitioner. With
        // queueCapacity=0 the 9th and later partitions were REJECTED by the pool
        // and run by CallerRunsPolicy inline on the partition-manager thread, one
        // at a time, while the manager was then too busy to feed workers that had
        // gone idle: parallelism decayed toward serial past 400k rows.
        // 256 covers a ~12.8M-row file; beyond that CallerRunsPolicy still keeps
        // the job correct (just slower) rather than failing it.
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("batch-ingest-");
        // Propagate the manager thread's MDC (tenant/job/step/correlationId set by
        // MdcStepListener on masterIngestStep) onto each partition worker thread,
        // so parallel csvWorkerStep partition logs are attributable instead of
        // showing empty context. Snapshot at submit time, install for the task,
        // restore afterwards so pooled threads don't leak context between tasks.
        executor.setTaskDecorator(runnable -> {
            java.util.Map<String, String> parent = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
                if (parent != null) org.slf4j.MDC.setContextMap(parent); else org.slf4j.MDC.clear();
                try {
                    runnable.run();
                } finally {
                    if (previous != null) org.slf4j.MDC.setContextMap(previous); else org.slf4j.MDC.clear();
                }
            };
        });
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean @StepScope public CsvPartitioner csvPartitioner(@Value("#{jobExecutionContext['partitionDirectory']}") String dir) {
        CsvPartitioner partitioner = new CsvPartitioner(); partitioner.setPartitionDirectory(dir); return partitioner;
    }

    @Bean public Step csvWorkerStep(
            org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> csvTransactionReader,
            ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor,
            ItemWriter<StagingTransaction> highPerfTransactionWriter) {
        return new StepBuilder("csvWorkerStep", jobRepository)
                .<StagingTransaction, StagingTransaction>chunk(10_000, transactionManager)
                .reader(csvTransactionReader).processor(transactionTenantProcessor).writer(highPerfTransactionWriter).build();
    }

    @Bean @StepScope
    public org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> csvTransactionReader(
            @Value("#{stepExecutionContext['fileName']}") String fileName) {
        org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> reader = new org.springframework.batch.item.file.FlatFileItemReader<>();
        if (fileName != null) reader.setResource(new FileSystemResource(fileName));
        reader.setLinesToSkip(1);
        reader.setLineMapper(new org.springframework.batch.item.file.mapping.DefaultLineMapper<>() {{
            setLineTokenizer(new org.springframework.batch.item.file.transform.DelimitedLineTokenizer() {{
                setDelimiter(","); setQuoteCharacter('"');
                setNames("Entity Name", "Aggregator Internal Id", "Aggregator Name", "AggregatorCode",
                        "MID", "Merchant Internal Id", "Merchant Name",
                        "SID", "Merchant Store Internal Id", "CMM Merchant Store Internal Id",
                        "Merchant Store Legal Name", "Store Name",
                        "TerminalID", "ARN", "RRN Number", "CardNumber", "Auth Code",
                        "Payment Date", "Transaction Date", "Transaction Time", "BatchNumber", "Transaction Type", "CardScheme",
                        "Card Type", "DCC", "Txn Currency", "Txn Currency Amount", "Store Base Currency",
                        "Store Base Currency Amount", "MSF", "VAT", "Total Amount Settled", "Interchange Fee", "Destination");
            }});
            setFieldSetMapper(fieldSet -> {
                StagingTransaction t = new StagingTransaction();
                t.setEntityName(fieldSet.readString("Entity Name"));
                t.setAggregatorInternalId(fieldSet.readString("Aggregator Internal Id"));
                t.setAggregatorName(fieldSet.readString("Aggregator Name"));
                t.setAggregatorCode(fieldSet.readString("AggregatorCode"));
                t.setMid(MerchantMasterJobConfig.normalizeSid(fieldSet.readString("MID")));
                t.setMerchantInternalId(fieldSet.readString("Merchant Internal Id"));
                t.setMerchantName(fieldSet.readString("Merchant Name"));
                t.setSid(MerchantMasterJobConfig.normalizeSid(fieldSet.readString("SID")));
                t.setMerchantStoreInternalId(fieldSet.readString("Merchant Store Internal Id"));
                t.setCmmMerchantStoreInternalId(fieldSet.readString("CMM Merchant Store Internal Id"));
                t.setMerchantStoreLegalName(fieldSet.readString("Merchant Store Legal Name"));
                t.setStoreName(fieldSet.readString("Store Name"));
                t.setTid(fieldSet.readString("TerminalID"));
                t.setArn(fieldSet.readString("ARN"));
                t.setRrnNumber(fieldSet.readString("RRN Number"));
                t.setCardNumber(fieldSet.readString("CardNumber"));
                t.setAuthCode(fieldSet.readString("Auth Code"));
                t.setPaymentDate(parseDate(fieldSet.readString("Payment Date")));
                String txnDateStr = fieldSet.readString("Transaction Date");
                String txnTimeStr = fieldSet.readString("Transaction Time");
                t.setTransactionDate(parseDateWithTime(txnDateStr, txnTimeStr));
                t.setBatchNumber(fieldSet.readString("BatchNumber"));
                t.setTransactionType(fieldSet.readString("Transaction Type"));
                t.setCardScheme(fieldSet.readString("CardScheme"));
                String cardTypeRaw = fieldSet.readString("Card Type");
                t.setCardType(cardTypeRaw);
                t.setCardProductCode(cardTypeRaw); // preserve granular code (VIPM/MCPM/...) for tier resolution
                t.setDcc(parseDccFlag(fieldSet.readString("DCC")));
                t.setTxnCurrency(fieldSet.readString("Txn Currency"));
                t.setTxnCurrencyAmount(parseDecimal(fieldSet.readString("Txn Currency Amount")));
                t.setStoreBaseCurrency(fieldSet.readString("Store Base Currency"));
                t.setStoreBaseCurrencyAmount(parseDecimal(fieldSet.readString("Store Base Currency Amount")));
                t.setMsf(parseDecimal(fieldSet.readString("MSF")));
                t.setVat(parseDecimal(fieldSet.readString("VAT")));
                t.setTotalAmountSettled(parseDecimal(fieldSet.readString("Total Amount Settled")));
                t.setInterchangeFee(parseDecimal(fieldSet.readString("Interchange Fee")));
                t.setDestination(fieldSet.readString("Destination"));
                return t;
            });
        }});
        return reader;
    }

    // Ref-table cache shared across ALL partition workers. Loaded ONCE; all 8 workers reuse it.
    private static volatile RefTableCache REF_CACHE = null;
    private static final Object REF_CACHE_LOCK = new Object();

    private static class RefTableCache {
        final java.util.Map<String, String> cardSchemeToType;
        final java.util.Map<String, String> isoNumericToCurrencyCode;
        final java.util.Map<String, Integer> currencyCodeToDecimal;
        RefTableCache(java.util.Map<String, String> a, java.util.Map<String, String> b, java.util.Map<String, Integer> c) {
            this.cardSchemeToType = a; this.isoNumericToCurrencyCode = b; this.currencyCodeToDecimal = c;
        }
    }

    private RefTableCache loadOrGetRefTables() {
        RefTableCache cached = REF_CACHE;
        if (cached != null) return cached;
        synchronized (REF_CACHE_LOCK) {
            if (REF_CACHE != null) return REF_CACHE;
            long t = System.currentTimeMillis();
            java.util.Map<String, String> cardSchemeToType = new java.util.HashMap<>();
            java.util.Map<String, String> isoNumericToCurrencyCode = new java.util.HashMap<>();
            java.util.Map<String, Integer> currencyCodeToDecimal = new java.util.HashMap<>();
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                boolean hasCardScheme = false;
                try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "ref_card_scheme", null)) {
                    hasCardScheme = rs.next();
                }
                if (hasCardScheme) {
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery("SELECT code, card_type FROM ref_card_scheme")) {
                        while (rs.next()) {
                            int ct = rs.getInt("card_type");
                            String label = switch (ct) {
                                case 2, 4 -> "DEBIT";
                                case 0, 1 -> "CREDIT";
                                case 3 -> "PREPAID";
                                default -> "UNKNOWN";
                            };
                            cardSchemeToType.put(rs.getString("code"), label);
                        }
                    }
                }
                boolean hasRefCountry = false;
                try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "ref_country", null)) {
                    hasRefCountry = rs.next();
                }
                if (hasRefCountry) {
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery(
                             "SELECT iso_numeric, currency_code, decimal_notation_value FROM ref_country WHERE iso_numeric IS NOT NULL")) {
                        while (rs.next()) {
                            String isoNum = rs.getString("iso_numeric");
                            String curCode = rs.getString("currency_code");
                            int decVal = rs.getInt("decimal_notation_value");
                            if (isoNum != null && curCode != null) {
                                isoNumericToCurrencyCode.put(isoNum.trim(), curCode.trim());
                                currencyCodeToDecimal.put(curCode.trim(), decVal > 0 ? decVal : 100);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("Could not load ref tables (non-fatal): {}", e.getMessage());
            }
            REF_CACHE = new RefTableCache(cardSchemeToType, isoNumericToCurrencyCode, currencyCodeToDecimal);
            log.info(String.format("Ref tables loaded ONCE in %.2fs (card_scheme=%d, currency=%d)",
                (System.currentTimeMillis() - t) / 1000.0, cardSchemeToType.size(), isoNumericToCurrencyCode.size()));
            return REF_CACHE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIN typing cache (card_type_source='BIN', V2026_08_08_06).
    // ref_bin (operator-uploaded exact 6-digit mapping — checked first, acts
    // as an override) + ref_bin_range (scheme files: Visa BIN list + promoted
    // Mastercard T068; 19-char zero-padded bounds, binary-searched).
    // Shared across the 8 partition workers like REF_CACHE, but with a
    // freshness window: BIN uploads happen in the core JVM, so a long-lived
    // batch JVM must not serve last week's ranges forever. All partitions of
    // one job start within seconds, so one reload per run in practice.
    // ─────────────────────────────────────────────────────────────────────
    private static volatile BinCache BIN_CACHE = null;
    private static final Object BIN_CACHE_LOCK = new Object();
    private static final long BIN_CACHE_TTL_MS = 10 * 60 * 1000L;

    /**
     * Immutable lookup result: [0]=cardType, [1]=productCode, [2]=issuerCountry,
     * [3]=source — "M" for a manual ref_bin row, null for a scheme range file.
     * The source matters because only manual rows carry product codes in the
     * rate-card vocabulary; scheme-file product codes are a different alphabet.
     */
    private static final class BinCache {
        final long loadedAt;
        final java.util.Map<String, String[]> exactBin6;
        final String[] lows; final String[] highs; final String[][] vals;
        BinCache(long loadedAt, java.util.Map<String, String[]> exactBin6,
                 String[] lows, String[] highs, String[][] vals) {
            this.loadedAt = loadedAt; this.exactBin6 = exactBin6;
            this.lows = lows; this.highs = highs; this.vals = vals;
        }
        /** prefix = leading clear digits of the PAN (>= 6). */
        String[] lookup(String prefix) {
            String[] exact = exactBin6.get(prefix.substring(0, 6));
            if (exact != null) return exact;
            if (lows.length == 0) return null;
            // Zero-pad to the fixed 19-char width — lexicographic == numeric.
            String pan19 = (prefix + "0000000000000000000").substring(0, 19);
            int lo = 0, hi = lows.length - 1, floor = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lows[mid].compareTo(pan19) <= 0) { floor = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (floor >= 0 && highs[floor].compareTo(pan19) >= 0) return vals[floor];
            return null;
        }
    }

    private BinCache loadOrGetBinCache() {
        BinCache cached = BIN_CACHE;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < BIN_CACHE_TTL_MS) return cached;
        synchronized (BIN_CACHE_LOCK) {
            if (BIN_CACHE != null && now - BIN_CACHE.loadedAt < BIN_CACHE_TTL_MS) return BIN_CACHE;
            long t = System.currentTimeMillis();
            java.util.Map<String, String[]> exact = new java.util.HashMap<>();
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            int skippedBin8 = 0;
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                boolean hasRefBin = false, hasRanges = false;
                try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "ref_bin", null)) { hasRefBin = rs.next(); }
                try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "ref_bin_range", null)) { hasRanges = rs.next(); }
                if (hasRefBin) {
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery(
                             "SELECT bin, card_type, product_code, issuer_country FROM ref_bin")) {
                        while (rs.next()) {
                            String bin = rs.getString("bin");
                            if (bin == null) continue;
                            bin = bin.trim();
                            // Feeds carry only 6 clear digits (first-6 + masked + last-4),
                            // so 8-digit operator rows cannot be matched — skipped, counted.
                            if (bin.length() != 6) { skippedBin8++; continue; }
                            exact.put(bin, new String[]{
                                nzTrim(rs.getString("card_type")),
                                nzTrim(rs.getString("product_code")),
                                nzTrim(rs.getString("issuer_country")), "M"});
                        }
                    }
                }
                if (hasRanges) {
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery(
                             // Same invariant BinManagementController asserts: only fixed-width
                             // 19-char bounds compare correctly as strings.
                             "SELECT range_low, range_high, card_type, product_code, issuer_country " +
                             "FROM ref_bin_range WHERE LENGTH(range_low) = 19 AND LENGTH(range_high) = 19 " +
                             "ORDER BY range_low, range_high")) {
                        String prevLow = null, prevHigh = null;
                        while (rs.next()) {
                            String low = rs.getString("range_low"), high = rs.getString("range_high");
                            // Mastercard splits one range across product codes (uq on
                            // range_low+product_code) — card typing needs one row per range;
                            // duplicates agree on funding-derived card_type in practice.
                            if (low.equals(prevLow) && high.equals(prevHigh)) continue;
                            prevLow = low; prevHigh = high;
                            rows.add(new String[]{low, high,
                                nzTrim(rs.getString("card_type")),
                                nzTrim(rs.getString("product_code")),
                                nzTrim(rs.getString("issuer_country"))});
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not load BIN reference tables (non-fatal, BIN typing degrades to FILE): {}", e.getMessage());
            }
            String[] lows = new String[rows.size()];
            String[] highs = new String[rows.size()];
            String[][] vals = new String[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                lows[i] = r[0]; highs[i] = r[1];
                vals[i] = new String[]{r[2], r[3], r[4], null};
            }
            if (skippedBin8 > 0) {
                log.warn("BIN cache: skipped {} ref_bin rows whose bin is not exactly 6 digits (feeds carry only 6 clear digits)", skippedBin8);
            }
            BIN_CACHE = new BinCache(System.currentTimeMillis(), exact, lows, highs, vals);
            log.info(String.format("BIN cache loaded in %.2fs (exact=%d, ranges=%d)",
                (System.currentTimeMillis() - t) / 1000.0, exact.size(), lows.length));
            return BIN_CACHE;
        }
    }

    private static String nzTrim(String s) {
        if (s == null) return null;
        // Card types / country codes repeat across ~800K rows — intern the few
        // distinct values instead of holding 800K duplicate strings.
        String v = s.trim();
        return v.isEmpty() ? null : v.intern();
    }

    /** Leading clear digits of the PAN (feeds: first-6 clear + mask + last-4). Null if fewer than 6. */
    private static String clearPanPrefix(String cardNumber) {
        if (cardNumber == null) return null;
        int n = 0;
        while (n < cardNumber.length() && Character.isDigit(cardNumber.charAt(n))) n++;
        return n >= 6 ? cardNumber.substring(0, n) : null;
    }

    private static final java.util.Set<String> WARNED_MISSING_CURRENCIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Static constant - never allocates per row.
    private static final java.math.BigDecimal BD_10000 = new java.math.BigDecimal("10000");

    // PERF FIX: pre-cache BigDecimal divisors - avoids new BigDecimal(decVal) on every row.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.math.BigDecimal> BD_DECIMAL_CACHE
        = new java.util.concurrent.ConcurrentHashMap<>();
    static {
        BD_DECIMAL_CACHE.put(100,  new java.math.BigDecimal("100"));
        BD_DECIMAL_CACHE.put(1000, new java.math.BigDecimal("1000"));
    }
    private static java.math.BigDecimal decimalDivisor(int decVal) {
        return BD_DECIMAL_CACHE.computeIfAbsent(decVal, k -> new java.math.BigDecimal(k.toString()));
    }

    private static String resolveCurrencyCode(String raw,
            java.util.Map<String, String> isoNumericToCode,
            java.util.Map<String, Integer> codeToDecimal) {
        if (raw == null || raw.isBlank()) return null;
        String c = raw.trim();
        String code = isoNumericToCode.get(c);
        if (code == null && c.matches("\\d{1,2}")) {
            code = isoNumericToCode.get(String.format("%03d", Integer.parseInt(c)));
        }
        if (code == null && codeToDecimal.containsKey(c.toUpperCase())) {
            code = c.toUpperCase();
        }
        return code;
    }

    /**
     * Decimal PLACES for a minor-unit divisor. The divisor was always
     * currency-aware; the rounding scale next to it was hardcoded to 2, which
     * is why every BHD amount lost its third decimal (fils) at the very first
     * touch — verified: 100.505 -> 100.51, 99.999 -> 100.00.
     */
    private static int scaleFor(int decVal) {
        switch (decVal) {
            case 1:     return 0;
            case 10:    return 1;
            case 100:   return 2;
            case 1000:  return 3;
            case 10000: return 4;
            default:
                if (WARNED_MISSING_CURRENCIES.add("SCALE:" + decVal)) {
                    log.warn("decimal_notation_value={} is not a power of ten - falling back to scale 2", decVal);
                }
                return 2;
        }
    }

    /** Unit mode for one feed column, per the feed_amount_contract table. */
    private static final String UNIT_MINOR = "MINOR";
    private static final String UNIT_BASIS_10000 = "BASIS_10000";

    /**
     * Resolve the per-column unit contract for a tenant (global default ->
     * country -> tenant, most specific wins). Loaded once per processor build.
     */
    private java.util.Map<String, String> loadAmountContract(Long tenantId) {
        java.util.Map<String, String> modes = new java.util.HashMap<>();
        try {
            jdbcTemplate.query(
                "SELECT c.column_name, c.unit_mode FROM feed_amount_contract c " +
                "LEFT JOIN tenant t ON t.tenant_id = ? " +
                "WHERE c.tenant_id IS NULL AND c.country_code IS NULL " +
                "   OR c.country_code = t.home_country_code " +
                "   OR c.tenant_id = ? " +
                "ORDER BY (c.tenant_id IS NOT NULL) ASC, (c.country_code IS NOT NULL) ASC",
                rs -> { modes.put(rs.getString("column_name"), rs.getString("unit_mode")); },
                tenantId, tenantId);
        } catch (Exception e) {
            log.warn("feed_amount_contract unavailable ({}) - using legacy defaults", e.getMessage());
        }
        modes.putIfAbsent("txn_currency_amount", UNIT_MINOR);
        modes.putIfAbsent("store_base_currency_amount", UNIT_MINOR);
        modes.putIfAbsent("total_amount_settled", UNIT_MINOR);
        modes.putIfAbsent("msf", "MAJOR");
        modes.putIfAbsent("vat", "MAJOR");
        modes.putIfAbsent("interchange_fee", UNIT_BASIS_10000);
        return modes;
    }

    private static int resolveDecimal(String raw,
            java.util.Map<String, String> isoNumericToCode,
            java.util.Map<String, Integer> codeToDecimal,
            String ccyLabel) {
        String code = resolveCurrencyCode(raw, isoNumericToCode, codeToDecimal);
        Integer dec = (code != null) ? codeToDecimal.get(code) : null;
        if (dec == null && WARNED_MISSING_CURRENCIES.add("DEC:" + ccyLabel + ":" + (raw == null ? "" : raw.trim()))) {
            log.warn("{} currency '{}' not resolved - defaulting to 100 (2dp).", ccyLabel, raw);
        }
        return dec != null ? dec : 100;
    }

    @Bean @StepScope public ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['inputType']}") String inputType) {
        final RefTableCache refs = loadOrGetRefTables();
        final java.util.Map<String, String> cardSchemeToType = refs.cardSchemeToType;
        final java.util.Map<String, String> isoNumericToCurrencyCode = refs.isoNumericToCurrencyCode;
        final java.util.Map<String, Integer> currencyCodeToDecimal = refs.currencyCodeToDecimal;

        // AMS input files already carry FINAL decimal amounts (txn, store-base, interchange),
        // so the decimal-scaling divisions must be skipped for them. CMM (default / null)
        // keeps the existing behaviour unchanged. Computed ONCE per processor build, not per row.
        final boolean rawAmounts = "AMS".equalsIgnoreCase(inputType);
        if (rawAmounts) {
            log.info("transactionTenantProcessor: AMS input - skipping amount divisions (txn, store-base, interchange).");
        }

        // Per-column unit contract (V2026_08_10_03). Defaults reproduce the legacy
        // behaviour exactly; a feed whose MSF/VAT genuinely arrive in minor units is
        // now a configuration row rather than a silent 100x/1000x revenue error.
        final java.util.Map<String, String> unitModes = loadAmountContract(tenantId);
        log.info("transactionTenantProcessor: amount unit contract = {}", unitModes);

        // BIN typing (card_type_source='BIN', tenant-level opt-in). Applied ONLY
        // to domestically-issued cards: BIN issuer country == tenant home country.
        // International cards ALWAYS keep the feed's card type — the BIN's issuer
        // country is stored as metadata on every matched row, but typing is never
        // touched for them. card_product_code is overwritten ONLY when the hit is
        // a manual ref_bin row (bank-authored, expected in the rate-card product
        // vocabulary) AND the card is local; scheme range files never touch it
        // (their product codes are a different alphabet than the rate cards).
        // Destination/fee inputs are untouched by design.
        String binSource = "FILE"; String binHomeCountry = null;
        try {
            java.util.Map<String, Object> trow = jdbcTemplate.queryForMap(
                "SELECT card_type_source, home_country_code FROM tenant WHERE tenant_id = ?", tenantId);
            binSource = String.valueOf(trow.getOrDefault("card_type_source", "FILE"));
            Object hc = trow.get("home_country_code");
            binHomeCountry = hc != null ? hc.toString().trim() : null;
        } catch (Exception e) {
            log.warn("Could not read tenant card_type_source ({}) - BIN typing disabled for this run", e.getMessage());
        }
        final boolean binTyping = "BIN".equalsIgnoreCase(binSource)
                && binHomeCountry != null && !binHomeCountry.isBlank();
        final BinCache binCache = binTyping ? loadOrGetBinCache() : null;
        final String homeCountry = binHomeCountry;
        final java.util.concurrent.atomic.AtomicLong binTyped = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong binProductTyped = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong binIntlSkipped = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong binNoMatch = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong binShortPrefix = new java.util.concurrent.atomic.AtomicLong();
        if (binTyping) {
            log.info("transactionTenantProcessor: BIN typing ACTIVE for tenant {} (home country {})", tenantId, homeCountry);
        }

        return item -> {
            item.setTenantId(tenantId);

            String rawSchemeTok = item.getCardScheme();
            boolean schemeMissing = rawSchemeTok == null
                    || rawSchemeTok.trim().isEmpty()
                    || "NULL".equalsIgnoreCase(rawSchemeTok.trim());
            if (schemeMissing) {
                String netName = networkNameFromCardTypeToken(item.getCardType());
                if (netName != null) item.setCardScheme(netName);
            }

            String rawCardType = item.getCardType();
            // Preserve the granular product code (VIPM/MCPM/MCDB...) BEFORE card_type
            // is coarsened to DEBIT/CREDIT/PREPAID. The reader already sets this, but
            // guard here so it is populated regardless of the reader path.
            if (item.getCardProductCode() == null && rawCardType != null && !rawCardType.isBlank()) {
                item.setCardProductCode(rawCardType.trim());
            }
            if (rawCardType != null && !rawCardType.isBlank()) {
                String resolved = cardSchemeToType.get(rawCardType.trim());
                if (resolved != null) item.setCardType(resolved);
            }

            // BIN typing — local cards only. Runs AFTER the feed-based coarsening
            // so a local BIN hit overrides it and everything else keeps it.
            if (binTyping) {
                String prefix = clearPanPrefix(item.getCardNumber());
                if (prefix == null) {
                    binShortPrefix.incrementAndGet();
                } else {
                    String[] hit = binCache.lookup(prefix);
                    if (hit == null) {
                        binNoMatch.incrementAndGet();
                    } else {
                        // Issuer country is metadata on every match, local or not.
                        if (hit[2] != null) item.setIssuerCountry(hit[2]);
                        if (hit[2] != null && hit[2].equalsIgnoreCase(homeCountry)) {
                            if (hit[0] != null) item.setCardType(hit[0]);
                            // Manual ref_bin rows are the bank's own product mapping —
                            // for local cards their product code replaces the feed's,
                            // so tier resolution (Standard/Premium) prices off the BIN.
                            if ("M".equals(hit[3]) && hit[1] != null) {
                                item.setCardProductCode(hit[1]);
                                binProductTyped.incrementAndGet();
                            }
                            long n = binTyped.incrementAndGet();
                            if (n % 250_000 == 0) {
                                log.info("BIN typing: {} local rows typed so far (productTyped={}, intlSkipped={}, noMatch={}, shortPrefix={})",
                                    n, binProductTyped.get(), binIntlSkipped.get(), binNoMatch.get(), binShortPrefix.get());
                            }
                        } else {
                            binIntlSkipped.incrementAndGet();
                        }
                    }
                }
            }

            // BLANK-TYPE FALLBACK (2026-08-24, user rule, BIN-typed tenants only).
            // The BH feed sends Card Type EMPTY on every row, which left the
            // card-type dashboard and debit/credit splits empty. Rule confirmed
            // by the business: type locals from the BIN table (the block above),
            // and when the BIN is not found default to premium. Applied ONLY
            // when the feed value is still blank — a feed-supplied or BIN-typed
            // value is never overridden, and FILE-sourced tenants (UAE) keep
            // their existing blank-stays-blank behaviour.
            //   1. Benefit / Benefit QR ('No Interchange') scheme => DEBIT —
            //      the Benefit switch is Bahrain's domestic DEBIT network by
            //      definition (ref_card_scheme maps both card_type=2), and
            //      Benefit-only cards never appear in the Visa/MC range files,
            //      so the premium default would mistype the largest local slice.
            //   2. Anything else => CREDIT, which resolves the Premium tier
            //      (card_subtype != 1) — i.e. priced and reported as premium.
            if (binTyping && (item.getCardType() == null || item.getCardType().isBlank())) {
                String schemeTok = item.getCardScheme();
                String schemeNorm = schemeTok == null ? "" : schemeTok.trim().toUpperCase().replace(" ", "");
                if (schemeNorm.equals("BENEFIT") || schemeNorm.equals("BENEFITQR") || schemeNorm.equals("NOINTERCHANGE")) {
                    item.setCardType("DEBIT");
                } else {
                    item.setCardType("CREDIT");
                }
            }

            // PERF FIX: decimalDivisor() - cached BigDecimal, no per-row allocation.
            // NOTE: currency-CODE resolution (ISO-numeric -> 'AED' etc.) still runs for BOTH
            // CMM and AMS so the stored currency label is correct. Only the numeric DIVISION
            // is conditional: CMM divides by the currency's decimal_notation_value; AMS does not.
            String rawTxnCcy = item.getTxnCurrency();
            if (rawTxnCcy != null && !rawTxnCcy.isBlank()) {
                int txnDecVal = resolveDecimal(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal, "Txn");
                String txnCode = resolveCurrencyCode(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal);
                if (txnCode != null) item.setTxnCurrency(txnCode);
                if (!rawAmounts && item.getTxnCurrencyAmount() != null
                        && UNIT_MINOR.equals(unitModes.get("txn_currency_amount"))) {
                    // Scale now comes from the SAME currency fact as the divisor.
                    item.setTxnCurrencyAmount(item.getTxnCurrencyAmount()
                        .divide(decimalDivisor(txnDecVal), scaleFor(txnDecVal), java.math.RoundingMode.HALF_UP));
                }
            }

            int stlDecVal = resolveDecimal(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal, "Store base");
            String stlCode = resolveCurrencyCode(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal);
            if (stlCode != null) item.setStoreBaseCurrency(stlCode);
            final int stlScale = scaleFor(stlDecVal);
            if (!rawAmounts && item.getStoreBaseCurrencyAmount() != null
                    && UNIT_MINOR.equals(unitModes.get("store_base_currency_amount"))) {
                // THE most damaging of the old hardcoded scales: this is the basis of
                // every fee computation and every volume rollup, so rounding it to 2dp
                // rounded the whole warehouse to 2dp for a 3-decimal currency.
                item.setStoreBaseCurrencyAmount(item.getStoreBaseCurrencyAmount()
                    .divide(decimalDivisor(stlDecVal), stlScale, java.math.RoundingMode.HALF_UP));
            }

            // TOTAL AMOUNT SETTLED: previously thrown away unconditionally
            // (setTotalAmountSettled(null)) — a financial field from the feed silently
            // discarded. Now retained, with the same unit contract as the amounts.
            if (item.getTotalAmountSettled() != null
                    && !rawAmounts && UNIT_MINOR.equals(unitModes.get("total_amount_settled"))) {
                item.setTotalAmountSettled(item.getTotalAmountSettled()
                    .divide(decimalDivisor(stlDecVal), stlScale, java.math.RoundingMode.HALF_UP));
            }

            // MSF / VAT: honour the contract. Legacy default is MAJOR (untouched), so
            // this is a no-op until a feed is explicitly configured as MINOR. Fees keep
            // 4dp because the columns are DECIMAL(19,4) and fee arithmetic needs the
            // headroom below the currency's own precision.
            if (!rawAmounts && item.getMsf() != null && UNIT_MINOR.equals(unitModes.get("msf"))) {
                item.setMsf(item.getMsf().divide(decimalDivisor(stlDecVal), 4, java.math.RoundingMode.HALF_UP));
            }
            if (!rawAmounts && item.getVat() != null && UNIT_MINOR.equals(unitModes.get("vat"))) {
                item.setVat(item.getVat().divide(decimalDivisor(stlDecVal), 4, java.math.RoundingMode.HALF_UP));
            }

            if (!rawAmounts && item.getInterchangeFee() != null) {
                String icMode = unitModes.get("interchange_fee");
                if (UNIT_BASIS_10000.equals(icMode)) {
                    item.setInterchangeFee(item.getInterchangeFee().divide(BD_10000, 4, java.math.RoundingMode.HALF_UP));
                } else if (UNIT_MINOR.equals(icMode)) {
                    item.setInterchangeFee(item.getInterchangeFee()
                        .divide(decimalDivisor(stlDecVal), 4, java.math.RoundingMode.HALF_UP));
                }
            }

            return item;
        };
    }

    // PERF FIX: jdbcTemplate.batchUpdate() uses Hikari properly.
    // Old code called dataSource.getConnection() directly, bypassing the pool entirely.
    //
    // P0-2 (INGEST TRUST): every staging row is stamped with the run that wrote
    // it. Before this, staging had no run scope at all, so cleanTargetDayTasklet
    // could only clear the WHOLE tenant — two concurrent uploads for one tenant
    // meant the second wiped the first's staging mid-flight. The stamp is also
    // what makes a truthful rows_staged possible for the reconciliation funnel.
    //
    // @StepScope so the bean can bind the run id the job listener published into
    // the job execution context before the first step ran. Null when the ledger
    // was unavailable — the column simply stays NULL and ingestion is unaffected.
    @Bean @StepScope
    public ItemWriter<StagingTransaction> highPerfTransactionWriter(
            @Value("#{jobExecutionContext['ingestRunId']}") Long ingestRunId) {
        final String sql = "INSERT INTO stg_trnx_raw (ingest_run_id, entity_name, aggregator_internal_id, aggregator_name, aggregator_code, " +
            "mid, merchant_internal_id, merchant_name, sid, merchant_store_internal_id, cmm_merchant_store_internal_id, " +
            "merchant_store_legal_name, store_name, tid, arn, rrn_number, card_number, auth_code, payment_date, " +
            "transaction_date, batch_number, transaction_type, card_scheme, card_type, card_product_code, dcc, txn_currency, " +
            "txn_currency_amount, store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, " +
            "interchange_fee, destination, issuer_country, tenant_id, load_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        return items -> jdbcTemplate.batchUpdate(sql,
            new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override public int getBatchSize() { return items.size(); }
                @Override public void setValues(PreparedStatement ps, int idx) throws java.sql.SQLException {
                    StagingTransaction t = items.getItems().get(idx);
                    int i = 1;
                    if (ingestRunId != null) ps.setLong(i++, ingestRunId); else ps.setNull(i++, java.sql.Types.BIGINT);
                    ps.setString(i++, t.getEntityName()); ps.setString(i++, t.getAggregatorInternalId());
                    ps.setString(i++, t.getAggregatorName()); ps.setString(i++, t.getAggregatorCode());
                    ps.setString(i++, t.getMid()); ps.setString(i++, t.getMerchantInternalId());
                    ps.setString(i++, t.getMerchantName()); ps.setString(i++, t.getSid());
                    ps.setString(i++, t.getMerchantStoreInternalId()); ps.setString(i++, t.getCmmMerchantStoreInternalId());
                    ps.setString(i++, t.getMerchantStoreLegalName()); ps.setString(i++, t.getStoreName());
                    ps.setString(i++, t.getTid()); ps.setString(i++, t.getArn());
                    ps.setString(i++, t.getRrnNumber()); ps.setString(i++, t.getCardNumber());
                    ps.setString(i++, t.getAuthCode());
                    ps.setTimestamp(i++, t.getPaymentDate() != null ? java.sql.Timestamp.valueOf(t.getPaymentDate()) : null);
                    ps.setTimestamp(i++, t.getTransactionDate() != null ? java.sql.Timestamp.valueOf(t.getTransactionDate()) : null);
                    ps.setString(i++, t.getBatchNumber()); ps.setString(i++, t.getTransactionType());
                    ps.setString(i++, t.getCardScheme()); ps.setString(i++, t.getCardType());
                    ps.setString(i++, t.getCardProductCode());
                    if (t.getDcc() != null) ps.setBoolean(i++, t.getDcc()); else ps.setNull(i++, java.sql.Types.BOOLEAN);
                    ps.setString(i++, t.getTxnCurrency());
                    if (t.getTxnCurrencyAmount() != null) ps.setBigDecimal(i++, t.getTxnCurrencyAmount()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    ps.setString(i++, t.getStoreBaseCurrency());
                    if (t.getStoreBaseCurrencyAmount() != null) ps.setBigDecimal(i++, t.getStoreBaseCurrencyAmount()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    if (t.getMsf() != null) ps.setBigDecimal(i++, t.getMsf()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    if (t.getVat() != null) ps.setBigDecimal(i++, t.getVat()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    if (t.getTotalAmountSettled() != null) ps.setBigDecimal(i++, t.getTotalAmountSettled()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    if (t.getInterchangeFee() != null) ps.setBigDecimal(i++, t.getInterchangeFee()); else ps.setNull(i++, java.sql.Types.NUMERIC);
                    ps.setString(i++, t.getDestination());
                    ps.setString(i++, t.getIssuerCountry());
                    if (t.getTenantId() != null) ps.setLong(i++, t.getTenantId()); else ps.setNull(i++, java.sql.Types.BIGINT);
                }
            });
    }

    @Bean public Step stagingToFactStep(Tasklet stagingToFactTasklet) {
        return new StepBuilder("stagingToFactStep", jobRepository)
            .tasklet(stagingToFactTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .listener(mdcStepListener).listener(ingestRunStepListener)
            .build();
    }

    @Bean @StepScope
    public Tasklet stagingToFactTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['loadMode']}") String loadMode) {
        return (contribution, chunkContext) -> {
            long start = System.currentTimeMillis();
            final boolean appendMode = "APPEND".equalsIgnoreCase(loadMode);

            // RUN-SCOPE STAGING (2026-08-29). stg_trnx_raw is shared per tenant. In a
            // sequential folder load every file appends to it, and cleanTargetDay's
            // guarded delete keeps rows belonging to still-RUNNING runs — so without a
            // run filter, stagingToFact reprocessed the WHOLE accumulated table each
            // file: O(n^2) work and cumulative/duplicate fact (seen live: the per-file
            // batch grew 3.0M -> 11.9M across six files). Scoping every staging read to
            // THIS run's ingest_run_id makes each file's job process only its own rows —
            // correct and safe for sequential AND concurrent loads, with no destructive
            // clear. DB pulls also carry a run id: IngestRunJobListener opens a DB_PULL
            // run for dbPullTransactionJob and adoptStagingStep tags the pulled rows
            // with it. The null fallback (tenant-only scope) remains only for the
            // ledger-unavailable case, where cleanTargetDay wiped staging wholesale.
            final Long ingestRunId = ingestRunIdOf(chunkContext);
            final String stgRunWhere        = ingestRunId != null ? " AND ingest_run_id = " + ingestRunId : "";       // unaliased
            final String stgRunWhereAliased = ingestRunId != null ? " AND stg.ingest_run_id = " + ingestRunId : "";   // stg.
            final String stgRunWhereS       = ingestRunId != null ? " AND s.ingest_run_id = " + ingestRunId : "";     // s.

            // PERF FIX: nullDateCount full-scan removed. IS NOT NULL filter below handles skipping.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL" + stgRunWhere + " ORDER BY d",
                java.sql.Date.class, tenantId);
            String dateScope;
            if (distinctDates.isEmpty()) {
                // DATA-QUALITY GATE. "No usable dates" has two very different causes and
                // they must not look the same to an operator:
                //   (a) staging is genuinely empty -> nothing was uploaded, benign skip.
                //   (b) staging has rows but EVERY payment_date is NULL -> the file's date
                //       column was missing, renamed, or in a format parseDate() does not
                //       support (it accepts 12 patterns; e.g. "2026/08/03" and "03-Aug-2026"
                //       both yield null). Previously this returned FINISHED, so the job went
                //       green having loaded ZERO transactions — indistinguishable from a
                //       successful upload. Fail loudly instead; a totally unparseable file is
                //       an error, not a no-op.
                Integer stagedRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ?" + stgRunWhere, Integer.class, tenantId);
                if (stagedRows != null && stagedRows > 0) {
                    throw new IllegalStateException(
                        "Upload rejected: " + stagedRows + " row(s) reached staging but NONE has a usable "
                        + "payment_date. The date column is missing, renamed, or in an unsupported format. "
                        + "No transactions were written. Check the file's date column and re-upload.");
                }
                log.info("stagingToFact: staging is empty - nothing to do");
                return RepeatStatus.FINISHED;
            } else {
                dateScope = buildSafeDateInList(distinctDates);
            }

            // PERF: sargable partition-pruning range over the RAW payment_date column.
            // The fee UPDATEs previously filtered on DATE(payment_date) IN (...), which
            // wraps the partition key in a function and defeats BOTH partition pruning
            // and the (tenant_id, payment_date) index -> full scan of every partition.
            // distinctDates is sorted ASC, so min..max+1day bounds every date in the
            // batch. We keep the exact DATE(...) IN (...) filter too (dates may be
            // sparse within the range) — the range prunes partitions, the IN keeps it
            // exact. `dateRange` is prefixed with the correct table alias per query.
            final String rngBare = dateRangeClause(distinctDates, "");
            final String rngF = dateRangeClause(distinctDates, "f.");
            final String rngFt = dateRangeClause(distinctDates, "ft.");

            String updateNameSql = "UPDATE dim_merchant m SET name = sub.merchant_name " +
                "FROM (SELECT DISTINCT s.mid AS staging_mid, s.merchant_name FROM stg_trnx_raw s " +
                "WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> ''" + stgRunWhereS + ") sub " +
                "WHERE m.tenant_id = ? " +
                "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") " +
                "AND sub.merchant_name !~ " + NUMERIC_ONLY_REGEX + " " +
                "AND m.mid = sub.staging_mid";

            Boolean hasMissingNames = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.tenant_id = ? " +
                "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") LIMIT 1)",
                Boolean.class, tenantId);

            int namesUpdated = 0;
            if (Boolean.TRUE.equals(hasMissingNames)) {
                namesUpdated = jdbcTemplate.update(updateNameSql, tenantId, tenantId);
            }
            log.info(String.format("Auto-populated %d merchant names in %.1fs%s",
                namesUpdated, (System.currentTimeMillis() - start) / 1000.0,
                Boolean.TRUE.equals(hasMissingNames) ? "" : " [skipped: all names good]"));

            if (Boolean.TRUE.equals(hasMissingNames)) {
                // PERF (2026-08-29): the prefix-match pass below is a bidirectional
                // `col LIKE othercol || '%'` cross join — non-sargable in both
                // directions, so its cost is (unnamed merchants) x (distinct staged
                // mids). For a tenant whose merchant names are genuinely numeric
                // (the BH feed this heuristic targets) the gate never closes, so it
                // ran full-cross every upload (5k x 20k ~ 100M string compares).
                // The exact m.mid = staging_mid pass already ran above; this is a
                // best-effort fuzzy fallback, so cap it: skip when the unnamed set
                // is large, where it is both most expensive and least reliable.
                Integer stillMissingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dim_merchant m WHERE m.tenant_id = ? " +
                    "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ")",
                    Integer.class, tenantId);
                final int PREFIX_MATCH_CAP = 2000;
                if (stillMissingCount != null && stillMissingCount > PREFIX_MATCH_CAP) {
                    log.info("Skipping prefix-match name pass: {} unnamed merchants exceeds cap {} " +
                        "(exact-match pass already applied; fuzzy prefix match not worth a {}-row cross join)",
                        stillMissingCount, PREFIX_MATCH_CAP, stillMissingCount);
                } else if (stillMissingCount != null && stillMissingCount > 0) {
                    long t06 = System.currentTimeMillis();
                    int prefixUpdated = jdbcTemplate.update(
                        "UPDATE dim_merchant m SET name = sub.merchant_name " +
                        "FROM (SELECT DISTINCT s.mid AS staging_mid, s.merchant_name FROM stg_trnx_raw s " +
                        "WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '' " +
                        "AND s.merchant_name !~ " + NUMERIC_ONLY_REGEX + stgRunWhereS + ") sub " +
                        "WHERE m.tenant_id = ? " +
                        "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") " +
                        "AND m.mid <> sub.staging_mid " +
                        "AND (m.mid LIKE sub.staging_mid || '%' OR sub.staging_mid LIKE m.mid || '%')",
                        tenantId, tenantId);
                    if (prefixUpdated > 0) {
                        log.info(String.format("Auto-populated %d additional merchant names (prefix-match) in %.1fs",
                            prefixUpdated, (System.currentTimeMillis() - t06) / 1000.0));
                    }
                }
            }

            // SCHEME NORMALIZATION AWARENESS (2026-08-24): the fact INSERT below maps
            // the BH feed token 'No Interchange' to canonical 'Benefit QR'. This list
            // is compared against FACT card_scheme values in the APPEND delete, so it
            // must apply the SAME mapping — otherwise a re-upload of a file containing
            // 'No Interchange' rows would never delete the previously inserted
            // 'Benefit QR' rows and would duplicate them.
            java.util.List<String> uploadSchemes = jdbcTemplate.queryForList(
                "SELECT DISTINCT CASE WHEN REPLACE(UPPER(TRIM(card_scheme)),' ','') = 'NOINTERCHANGE' " +
                "THEN 'BENEFIT QR' ELSE UPPER(TRIM(card_scheme)) END FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND NULLIF(TRIM(card_scheme), '') IS NOT NULL" + stgRunWhere,
                String.class, tenantId);

            // CONNECTION PINNING (2026-08-29, root cause of UAT job#1004): this
            // step runs with PROPAGATION_NEVER, so without an explicit transaction
            // every jdbcTemplate call borrows its own pooled connection — but the
            // session temp tables (tmp_fact_batch below, tmp_fee_resolve inside
            // FeeComputationService) exist on ONE connection only. It held together
            // while Hikari happened to hand back the same idle connection; any
            // concurrent borrower (the REQUIRES_NEW ledger writes, web traffic, a
            // cron tick) reshuffles the pool and a later statement lands on a
            // session where the temp table does not exist — seen live 2026-08-29
            // 02:12 UAT as "relation tmp_fact_batch does not exist" AFTER 2.54M
            // rows had staged cleanly. Backfill and bulk-migration already wrap
            // this same sequence in a TransactionTemplate; this tasklet was the
            // one caller that did not. One programmatic transaction from the fact
            // delete through the final flush pins every statement to one
            // connection — and makes delete+reload atomic, so a failed load can
            // no longer leave the day emptied.
            org.springframework.transaction.TransactionStatus factTxn =
                transactionManager.getTransaction(
                    new org.springframework.transaction.support.DefaultTransactionDefinition());
            try {

            long tDel = System.currentTimeMillis();
            if (appendMode) {
                // IDEMPOTENCY: the delete below must cover EXACTLY the set of rows the
                // INSERT further down will add, or a re-upload duplicates whatever it
                // missed. fact_transaction has no unique constraint on any business key
                // (PK is the surrogate transaction_id + payment_date), so this delete is
                // the ONLY thing preventing duplicates — the database will not catch them.
                //
                // uploadSchemes is built with `NULLIF(TRIM(card_scheme),'') IS NOT NULL`,
                // so it silently omits staging rows whose card_scheme is NULL or blank.
                // Those rows were still INSERTed, but never deleted — so every re-upload
                // of a file containing any blank-scheme row duplicated those rows, and a
                // file with NO scheme values at all duplicated in full (the old
                // "skipping fact delete" branch). Delete blank-scheme rows explicitly.
                int deleted = 0;
                if (!uploadSchemes.isEmpty()) {
                    String placeholders = uploadSchemes.stream().map(x -> "?")
                        .collect(java.util.stream.Collectors.joining(","));
                    Object[] args = new Object[uploadSchemes.size() + 1];
                    args[0] = tenantId;
                    for (int i = 0; i < uploadSchemes.size(); i++) args[i + 1] = uploadSchemes.get(i);
                    deleted += jdbcTemplate.update(
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                        " AND UPPER(TRIM(card_scheme)) IN (" + placeholders + ")", args);
                }
                Boolean stagingHasBlankScheme = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw WHERE tenant_id = ? " +
                    "AND payment_date IS NOT NULL AND NULLIF(TRIM(card_scheme), '') IS NULL" + stgRunWhere + " LIMIT 1)",
                    Boolean.class, tenantId);
                if (Boolean.TRUE.equals(stagingHasBlankScheme)) {
                    deleted += jdbcTemplate.update(
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                        " AND NULLIF(TRIM(card_scheme), '') IS NULL", tenantId);
                }
                if (uploadSchemes.isEmpty() && !Boolean.TRUE.equals(stagingHasBlankScheme)) {
                    log.warn("APPEND mode: staging has no rows in scope - nothing to delete.");
                } else {
                    recordDeleted(chunkContext, (long) deleted);
                    log.info(String.format("APPEND mode: deleted %d fact rows for scheme(s) %s%s in %.1fs",
                        deleted, uploadSchemes,
                        Boolean.TRUE.equals(stagingHasBlankScheme) ? " + blank-scheme rows" : "",
                        (System.currentTimeMillis() - tDel) / 1000.0));
                }
            } else {
                // P0-3 (INGEST TRUST): REPLACE deletes fact rows by WHOLE DATE, so a
                // 200-row resend of one acquirer's slice destroys the entire day.
                // Count first, so (a) the ledger can show what was destroyed and
                // (b) the guard below can refuse an obviously partial replacement.
                Long existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? AND " + rngBare
                        + "DATE(payment_date) IN " + dateScope,
                    Long.class, tenantId);
                long existingRows = existing == null ? 0L : existing;

                Long incoming = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL" + stgRunWhere,
                    Long.class, tenantId);
                long incomingRows = incoming == null ? 0L : incoming;

                if (replaceGuardEnabled && existingRows > 0
                        && incomingRows < existingRows * replaceGuardMinRatio) {
                    throw new IllegalStateException(String.format(
                        "REPLACE refused: this file carries %d row(s) for %d date(s) that already hold "
                        + "%d row(s). Replacing would delete %d row(s) and write back only %d — a partial-day "
                        + "file replacing a fuller day. Re-upload the complete day, use APPEND mode, or lower "
                        + "acquira.replace.guard.min-ratio (currently %.2f) if this is intentional.",
                        incomingRows, distinctDates.size(), existingRows,
                        existingRows - incomingRows, incomingRows, replaceGuardMinRatio));
                }

                int factDeleted = jdbcTemplate.update(
                    "DELETE FROM fact_transaction WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope,
                    tenantId);
                recordDeleted(chunkContext, (long) factDeleted);
                log.info(String.format("Deleted %d existing fact rows in %.1fs",
                    factDeleted, (System.currentTimeMillis() - tDel) / 1000.0));
            }

            // =================================================================
            // APPEND-ONLY FACT WRITE (2026-08-28, REPLACE mode only).
            //
            // Every fact row used to be written TWICE: once by the INSERT below,
            // then re-written by the store/terminal fix-ups and the fee-apply
            // UPDATE — and a Postgres UPDATE is a delete+insert (new row version,
            // every index maintained again, dead tuples for autovacuum). On the
            // BH UAT re-ingest that second write was the dominant cost even after
            // the two-phase split (resolve SELECT ~28s/day vs hours of UPDATE).
            //
            // REPLACE deletes the whole date scope first, so the incoming batch
            // IS the entire scope. Stage it in a session temp table shaped
            // exactly like fact_transaction, run the SAME fix-up and fee SQL
            // against the temp table (cheap: no partitions, no fact indexes, no
            // MVCC bloat that survives the job), then flush with a single
            // INSERT INTO fact_transaction — each fact row is written exactly
            // once, fees and statuses already populated.
            //
            // APPEND keeps the direct path verbatim: its delete is
            // scheme-scoped, so pre-existing rows of OTHER schemes share the
            // date scope and the fee pass legitimately re-prices them; pricing
            // only the batch would change that behaviour.
            //
            // LIKE ... INCLUDING DEFAULTS copies the live column order and the
            // BIGSERIAL default, so transaction_ids draw from the real sequence
            // and the final `INSERT ... SELECT *` aligns positionally.
            final boolean stageViaBatchTable = !appendMode;
            final String factTarget = stageViaBatchTable ? "tmp_fact_batch" : "fact_transaction";
            if (stageViaBatchTable) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fact_batch");
                jdbcTemplate.execute(
                    "CREATE TEMP TABLE tmp_fact_batch (LIKE fact_transaction INCLUDING DEFAULTS)");
            }

            long tIns = System.currentTimeMillis();
            // The INSERT ... SELECT (dim resolution + fan-out guard + txn-type/
            // scheme/destination normalization + signed refund volume/MSF +
            // DOMESTIC txn-currency fallback) was extracted VERBATIM to
            // IngestSql.stagingToFactInsertSql (2026-08-28) so backfill writes
            // fact rows with the SAME SQL. All the inline documentation moved
            // with it.
            String sql = com.acquira.batch.service.IngestSql.stagingToFactInsertSql(factTarget, stgRunWhereAliased);
            int inserted = jdbcTemplate.update(sql, tenantId);
            log.info(String.format("Inserted %d %s rows in %.1fs", inserted,
                stageViaBatchTable ? "batch (pre-fact)" : "fact", (System.currentTimeMillis() - tIns) / 1000.0));
            if (stageViaBatchTable) {
                // Temp tables have no stats until analyzed; the fix-up and fee
                // joins below need row counts to pick hash joins.
                jdbcTemplate.execute("ANALYZE tmp_fact_batch");
            }

            // RECONCILIATION: staging rows with a usable date must equal fact rows inserted.
            // The INSERT filters on `payment_date IS NOT NULL` AND excludes
            // pre-authorization rows (2026-08-24), so this expected count applies the
            // SAME two filters; a gap means rows were silently lost (or the
            // LEFT JOINs to dim_store/dim_terminal fanned out and DUPLICATED rows, which is
            // possible because those joins are not guaranteed one-to-one and there is no
            // unique constraint on fact_transaction to catch it). Either way the operator
            // must know — this used to be invisible.
            Integer stagedUsable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL " +
                "AND REPLACE(UPPER(TRIM(COALESCE(transaction_type,''))),' ','') " +
                "    NOT IN ('PRE-AUTHORIZATION','PREAUTHORIZATION','PRE-AUTH','PREAUTH')" + stgRunWhere,
                Integer.class, tenantId);
            if (stagedUsable != null && stagedUsable != inserted) {
                String detail = String.format(
                    "Row-count mismatch: %d staging row(s) with a usable payment_date produced %d fact row(s) (delta %+d).",
                    stagedUsable, inserted, inserted - stagedUsable);
                if (inserted > stagedUsable) {
                    // Fan-out duplicates corrupt every downstream summary. Never let this pass.
                    throw new IllegalStateException(detail
                        + " More rows were written than staged, which means a dimension join duplicated rows. "
                        + "Summaries would be inflated, so the load has been failed deliberately.");
                }
                log.warn("[RECONCILE] {} Rows were dropped between staging and fact.", detail);
            } else {
                log.info("[RECONCILE] staging({}) == fact({}) - row counts reconcile", stagedUsable, inserted);
            }

            // PERF: one pass with FILTER instead of two separate COUNT(*) scans over
            // the same date range — these are diagnostics, they shouldn't cost 2 scans.
            Map<String, Object> counts = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total, " +
                "COUNT(*) FILTER (WHERE merchant_id IS NOT NULL) AS matched " +
                "FROM " + factTarget + " WHERE tenant_id = ? " +
                "AND " + rngBare + "DATE(payment_date) IN " + dateScope, tenantId);
            Integer total   = counts.get("total")   == null ? 0 : ((Number) counts.get("total")).intValue();
            Integer matched = counts.get("matched") == null ? 0 : ((Number) counts.get("matched")).intValue();
            if (total != null && total > 0) {
                int unmatched = total - (matched != null ? matched : 0);
                if (unmatched > 0) {
                    log.warn(String.format("%d/%d fact rows have NULL merchant_id.", unmatched, total));
                } else {
                    log.info("All {} fact rows resolved to a merchant.", total);
                }
            }

            try {
                org.springframework.batch.item.ExecutionContext jobCtx =
                    chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
                int dqTotal = total != null ? total : 0;
                int dqUnresolved = Math.max(0, dqTotal - (matched != null ? matched : 0));
                jobCtx.putInt("dq.total", dqTotal);
                jobCtx.putInt("dq.unresolvedMerchant", dqUnresolved);
                jobCtx.putInt("dq.dates", distinctDates.size());
                jobCtx.putString("dq.schemes", String.join(",", uploadSchemes));
                jobCtx.putString("dq.loadMode", appendMode ? "APPEND" : "REPLACE");
                // PERF (2026-08-29): publish the ISO dates this upload actually
                // loaded so the async reporting step (ManualIngestionService)
                // can scope to them instead of rescanning + re-aggregating the
                // tenant's WHOLE fact history. The DB-pull path already scopes
                // (it reads staging before the job wipes it); the file path had
                // no such handle once staging was cleared, so it passed no scope
                // and processed every month the tenant ever loaded. Kept as a
                // compact CSV of yyyy-MM-dd; readers split on ','.
                jobCtx.putString("dq.loadedDates", distinctDates.stream()
                    .map(d -> d.toLocalDate().toString())
                    .collect(java.util.stream.Collectors.joining(",")));
            } catch (Exception dqe) {
                log.warn("Could not record data-quality summary (non-fatal): {}", dqe.getMessage());
            }

            // INGEST TRUST: publish the funnel's middle two tiers onto the ledger
            // row. rows_staged counts only THIS run's staging rows when the stamp
            // is available (P0-2); rows_facted is what actually landed. The
            // reconciliation service compares them after the job closes.
            try {
                Long runId = ingestRunIdOf(chunkContext);
                if (runId != null) {
                    Long stagedForRun = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ? AND ingest_run_id = ?",
                        Long.class, tenantId, runId);
                    // DB pulls populate staging themselves and never carry the stamp,
                    // so fall back to the tenant-wide count rather than reporting 0.
                    if (stagedForRun == null || stagedForRun == 0) {
                        stagedForRun = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ?", Long.class, tenantId);
                    }
                    ingestRunRecorder.updateCounts(runId, null, stagedForRun,
                        (long) inserted, null, null, null,
                        Math.max(0, (total == null ? 0 : total) - (matched == null ? 0 : matched)),
                        distinctDates.get(0).toLocalDate(),
                        distinctDates.get(distinctDates.size() - 1).toLocalDate(),
                        distinctDates.size());

                    // Day coverage is refreshed by IngestRunJobListener AFTER the
                    // job finishes, not here: populateSummaryStep has not run yet,
                    // so a coverage row written now would record a summary count of
                    // zero. The listener re-derives the touched days from
                    // min_txn_date/max_txn_date written just above — deliberately
                    // not from a date list in the execution context, whose
                    // SHORT_CONTEXT column is bounded and would truncate on a
                    // year-wide backfill.
                }
            } catch (Exception le) {
                log.warn("Could not update ingest ledger counts (non-fatal): {}", le.toString());
            }

            long tFix = System.currentTimeMillis();
            // PERF: both fix-ups join the whole date range of fact_transaction against
            // the whole staging table on (payment_date, arn). That join is paid in full
            // even when it updates ZERO rows — and it usually does update zero, because
            // the INSERT above already resolves store_id/terminal_id for well-formed
            // feeds. Gate each one on a cheap EXISTS that stops at the first NULL,
            // served by the (tenant_id, payment_date) index. Same idiom as the
            // hasMissingNames / hasUnmappedMerchants guards above.
            int storeFixed = 0, termFixed = 0;
            Boolean anyNullStore = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + factTarget + " f WHERE f.tenant_id = ? " +
                "AND f.store_id IS NULL AND f.merchant_id IS NOT NULL " +
                "AND " + rngF + "DATE(f.payment_date) IN " + dateScope + " LIMIT 1)", Boolean.class, tenantId);
            if (Boolean.TRUE.equals(anyNullStore)) {
                storeFixed = jdbcTemplate.update(
                    "UPDATE " + factTarget + " f SET store_id = s.store_id " +
                    "FROM dim_store s, stg_trnx_raw stg " +
                    "WHERE f.tenant_id = ? AND s.tenant_id = ? AND stg.tenant_id = ?" + stgRunWhereAliased + " " +
                    "AND f.store_id IS NULL AND f.merchant_id IS NOT NULL " +
                    "AND s.merchant_id = f.merchant_id " +
                    "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                    "AND s.internal_id = CONCAT('STORE_', stg.mid) " +
                    "AND " + rngF + "DATE(f.payment_date) IN " + dateScope,
                    tenantId, tenantId, tenantId);
            }
            Boolean anyNullTerm = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + factTarget + " f WHERE f.tenant_id = ? " +
                "AND f.terminal_id IS NULL AND f.store_id IS NOT NULL " +
                "AND " + rngF + "DATE(f.payment_date) IN " + dateScope + " LIMIT 1)", Boolean.class, tenantId);
            if (Boolean.TRUE.equals(anyNullTerm)) {
                termFixed = jdbcTemplate.update(
                    "UPDATE " + factTarget + " f SET terminal_id = t.terminal_id " +
                    "FROM dim_terminal t, stg_trnx_raw stg " +
                    "WHERE f.tenant_id = ? AND t.tenant_id = ? AND stg.tenant_id = ?" + stgRunWhereAliased + " " +
                    "AND f.terminal_id IS NULL AND f.store_id IS NOT NULL " +
                    "AND t.store_id = f.store_id " +
                    "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                    "AND t.internal_id = CONCAT('TERM_', stg.mid) " +
                    "AND " + rngF + "DATE(f.payment_date) IN " + dateScope,
                    tenantId, tenantId, tenantId);
            }
            if (storeFixed + termFixed > 0) {
                log.info(String.format("Fix-up: %d store_ids, %d terminal_ids in %.1fs",
                    storeFixed, termFixed, (System.currentTimeMillis() - tFix) / 1000.0));
            } else {
                log.info(String.format("Fix-up: nothing to resolve, skipped in %.1fs",
                    (System.currentTimeMillis() - tFix) / 1000.0));
            }

            // =================================================================
            // =================================================================
            // FEE COMPUTATION — extracted VERBATIM to FeeComputationService
            // (2026-08-28) so BackfillIngestionService and BulkMigrationService
            // price rows with the SAME SQL as this job. The full rate-resolution
            // documentation (refund rule, effective dating, two-phase apply,
            // gap report) lives on that class now.
            // =================================================================
            feeComputationService.computeFees(tenantId, factTarget, rngFt, rngF, rngBare, dateScope);

            // FLUSH (append-only path): the batch — dims fixed up, fees and
            // statuses populated — lands in fact_transaction in ONE insert.
            // SELECT * is positionally safe because tmp_fact_batch was created
            // with LIKE fact_transaction in this same session. Rows route to
            // their monthly partitions exactly as the old direct INSERT did.
            if (stageViaBatchTable) {
                long tFlush = System.currentTimeMillis();
                int flushed = jdbcTemplate.update(
                    "INSERT INTO fact_transaction SELECT * FROM tmp_fact_batch");
                jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fact_batch");
                if (flushed != inserted) {
                    // Should be impossible (nothing else writes the temp table);
                    // loud rather than silent if it ever isn't.
                    log.warn("[RECONCILE] batch flush wrote {} fact row(s) but {} were staged in tmp_fact_batch",
                        flushed, inserted);
                }
                log.info(String.format("Fact flush (append-only, single write): %d rows in %.1fs",
                    flushed, (System.currentTimeMillis() - tFlush) / 1000.0));
            }

            transactionManager.commit(factTxn);
            } catch (Exception txe) {
                // Roll back rather than leak an open transaction back to the pool
                // (and back to this PROPAGATION_NEVER step's thread).
                if (!factTxn.isCompleted()) {
                    transactionManager.rollback(factTxn);
                }
                throw txe;
            }

            // PLAN STABILITY (2026-08-28): refresh statistics on the fact
            // partitions this load rewrote, for BOTH paths (APPEND's in-place
            // fee UPDATE stales stats exactly the same way). Nothing else
            // analyzes fact before the wide-window steps downstream, and the
            // planner's view of the touched partition drifts further with
            // every same-day re-upload — seen live in UAT (2026-08-28) as two
            // IDENTICAL uploads two hours apart: businessMetrics 208s -> 811s,
            // churn 33s -> 49s, dashboards 51s -> 86s, while the date-scoped
            // steps stayed flat. ANALYZE is sampled (seconds per partition)
            // and valid inside the tasklet transaction; failures are non-fatal
            // because statistics are an optimisation, never correctness.
            {
                long tAnalyze = System.currentTimeMillis();
                java.util.Set<String> parts = new java.util.LinkedHashSet<>();
                for (java.sql.Date d : distinctDates) {
                    java.time.LocalDate ld = d.toLocalDate();
                    parts.add(String.format("fact_transaction_y%04dm%02d", ld.getYear(), ld.getMonthValue()));
                }
                parts.add("fact_transaction_default"); // rows without a monthly partition land here
                int analyzed = 0;
                for (String part : parts) {
                    try {
                        jdbcTemplate.execute("ANALYZE " + part);
                        analyzed++;
                    } catch (Exception ae) {
                        log.debug("ANALYZE {} skipped: {}", part, ae.getMessage());
                    }
                }
                log.info(String.format("Analyzed %d fact partition(s) in %.1fs",
                    analyzed, (System.currentTimeMillis() - tAnalyze) / 1000.0));
            }

            log.info(String.format("stagingToFact completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step populateSummaryStep(Tasklet populateSummaryTasklet) {
        return new StepBuilder("populateSummaryStep", jobRepository)
            .tasklet(populateSummaryTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .listener(mdcStepListener).listener(ingestRunStepListener)
            .build();
    }

    @Bean @StepScope
    public Tasklet populateSummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        // Body extracted VERBATIM to SummaryPopulationService (2026-08-28) so
        // BulkMigrationService and BackfillIngestionService aggregate with the
        // SAME SQL instead of hand-maintained mirrors. This tasklet only feeds
        // it the upload's staged dates; the advisory lock, clean-slate deletes,
        // parallel phases and sargable scopes all live on the service now.
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            // Run-scope (2026-08-29): only THIS file's staged dates — see stagingToFact.
            final Long ingestRunId = ingestRunIdOf(chunkContext);
            final String stgRunWhere = ingestRunId != null ? " AND ingest_run_id = " + ingestRunId : "";
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL" + stgRunWhere + " ORDER BY d",
                java.sql.Date.class, tenantId);
            summaryPopulationService.populateForDates(tenantId, distinctDates);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step calculateBusinessMetricsStep(Tasklet calculateBusinessMetricsTasklet) {
        return new StepBuilder("calculateBusinessMetricsStep", jobRepository)
            .tasklet(calculateBusinessMetricsTasklet, transactionManager)
            .transactionAttribute(noTxn()).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet calculateBusinessMetricsTasklet(
            @Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("${acquira.retention.snapshot-days:90}") int snapshotRetentionDays) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();
            // Run-scope (2026-08-29): only THIS file's staged dates — see stagingToFact.
            final Long ingestRunId = ingestRunIdOf(chunkContext);
            final String stgRunWhere = ingestRunId != null ? " AND ingest_run_id = " + ingestRunId : "";
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL" + stgRunWhere + " ORDER BY d",
                java.sql.Date.class, tenantId);
            // RETENTION SHORT-CIRCUIT (2026-08-29): the prune at the end of this
            // tasklet deletes every snapshot with calc_date < CURRENT_DATE -
            // snapshotRetentionDays — so computing a snapshot for an older date is
            // insert-then-delete of the same rows within one run. On a historical
            // backfill that was the ENTIRE step: the 21-file BH re-ingest spent an
            // average 196s/file building Jan–Jul snapshots the same tasklet
            // immediately pruned. Filter those dates out up front; the surviving
            // rows are byte-identical to the old behaviour's end state.
            if (snapshotRetentionDays > 0 && !distinctDates.isEmpty()) {
                // Cutoff from the DATABASE clock — the prune below compares against
                // CURRENT_DATE, so deriving this from the JVM clock could disagree
                // by a day across a timezone skew and drop a boundary snapshot.
                java.time.LocalDate cutoff = jdbcTemplate.queryForObject(
                    "SELECT CURRENT_DATE - ?", java.time.LocalDate.class, snapshotRetentionDays);
                int before = distinctDates.size();
                distinctDates.removeIf(d -> d.toLocalDate().isBefore(cutoff));
                if (distinctDates.size() < before) {
                    log.info("businessMetrics: skipped {} date(s) older than the {}-day snapshot retention "
                        + "window (their snapshots would be pruned by this same run)",
                        before - distinctDates.size(), snapshotRetentionDays);
                }
            }
            if (distinctDates.isEmpty()) {
                log.info("businessMetrics: no dates to process - skipping");
                return RepeatStatus.FINISHED;
            }
            String dateScope = buildSafeDateInList(distinctDates);
            // Sargable companion for the fact_transaction scan below — see dateRangeClause().
            final String rngBare = dateRangeClause(distinctDates, "");

            // FIX: clean-slate the affected calc_dates before re-inserting. These two
            // tables are ON CONFLICT (tenant, merchant, calc_date) DO UPDATE and only
            // include merchants that transacted in THIS upload's dates. A merchant that
            // had a row for one of these calc_dates from an earlier upload but is absent
            // now would keep a stale row -> orphan drift on dashboard active/dormant/new
            // counts and opportunity scores. Delete-then-rebuild for calc_date IN dateScope.
            {
                int delAct = jdbcTemplate.update(
                    "DELETE FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date IN " + dateScope, tenantId);
                int delScore = jdbcTemplate.update(
                    "DELETE FROM merchant_opportunity_score WHERE tenant_id = ? AND calc_date IN " + dateScope, tenantId);
                log.warn("  [businessMetrics] clean-slate activity {} + score {} rows", delAct, delScore);
            }

            // PERF (2026-08-29): a CONSTANT payment_date envelope for the fact join
            // below. The per-row bounds `f.payment_date >= d.target_date - 60d`
            // reference an inline VALUES column, which the planner cannot use for
            // partition pruning — so on a hash join the WHOLE tenant history of
            // fact_transaction is scanned (the step's own comment records a
            // 208s -> 811s regression). distinctDates is sorted ASC; the widest
            // window any target_date needs is [min-60d, max+1d), and adding it as
            // a constant predicate prunes to just those month partitions. It never
            // changes the result: every row the per-row bounds admit lies inside it.
            final String actWindow =
                " AND f.payment_date >= DATE '" + distinctDates.get(0).toLocalDate().minusDays(60) + "' " +
                " AND f.payment_date <  DATE '" + distinctDates.get(distinctDates.size() - 1).toLocalDate().plusDays(1) + "' ";
            jdbcTemplate.update("INSERT INTO merchant_activity_summary (tenant_id, merchant_id, calc_date, " +
                "first_txn_date, last_txn_date, last_7d_cnt, last_7d_value, last_30d_cnt, last_30d_value, status, status_change_date) " +
                "SELECT m.tenant_id, m.merchant_id, d.target_date, MIN(f.payment_date), MAX(f.payment_date), " +
                "COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN 1 END), 0), " +
                "COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN f.store_base_currency_amount ELSE 0 END), 0), " +
                "COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN 1 END), 0), " +
                "COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN f.store_base_currency_amount ELSE 0 END), 0), " +
                "CASE WHEN MAX(f.payment_date) >= d.target_date - INTERVAL '30 days' THEN 'ACTIVE' " +
                "WHEN MAX(f.payment_date) < d.target_date - INTERVAL '30 days' THEN 'DORMANT' ELSE 'ONBOARDED' END, d.target_date " +
                "FROM dim_merchant m " +
                "JOIN (VALUES " + distinctDates.stream().map(d -> "(DATE '" + d + "')").collect(java.util.stream.Collectors.joining(",")) + ") d(target_date) ON TRUE " +
                "LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id AND f.tenant_id = m.tenant_id " +
                // The window must be relative to d.target_date, NOT CURRENT_DATE.
                // Every aggregate above already measures from d.target_date (7d/30d
                // windows, ACTIVE/DORMANT cutoff), but this join used to be pinned to
                // `CURRENT_DATE - INTERVAL '60 days'`. For any BACKDATED upload — a
                // historical backload, or a multi-month file whose earlier dates are
                // more than 60 days old — the join matched nothing, so every merchant
                // was written with last_7d/last_30d = 0, NULL first/last txn dates and
                // status 'ONBOARDED' (the CASE falls through to ELSE when MAX is NULL).
                // The upper bound matters too: without it a backdated snapshot pulled in
                // transactions that happened AFTER its own calc_date, so last_txn_date
                // and status were computed from the future.
                "  AND f.payment_date >= d.target_date - INTERVAL '60 days' " +
                "  AND f.payment_date <  d.target_date + INTERVAL '1 day' " +
                actWindow +
                "WHERE m.tenant_id = ? " +
                "AND m.merchant_id IN (SELECT DISTINCT merchant_id FROM fact_transaction WHERE tenant_id = ? " +
                "  AND merchant_id IS NOT NULL AND " + rngBare + "DATE(payment_date) IN " + dateScope + ") " +
                "GROUP BY m.tenant_id, m.merchant_id, d.target_date " +
                "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET " +
                "first_txn_date=EXCLUDED.first_txn_date, last_txn_date=EXCLUDED.last_txn_date, " +
                "last_7d_cnt=EXCLUDED.last_7d_cnt, last_7d_value=EXCLUDED.last_7d_value, " +
                "last_30d_cnt=EXCLUDED.last_30d_cnt, last_30d_value=EXCLUDED.last_30d_value, " +
                "status=EXCLUDED.status, status_change_date=EXCLUDED.status_change_date",
                tenantId, tenantId);

            // Fresh stats for the score SELECT below and for the attrition/
            // dashboard reads — this table was just mass-deleted + reinserted.
            try { jdbcTemplate.execute("ANALYZE merchant_activity_summary"); } catch (Exception ignore) {}

            jdbcTemplate.update("INSERT INTO merchant_opportunity_score (tenant_id, merchant_id, score, reason_tags, calc_date) " +
                "SELECT tenant_id, merchant_id, CASE WHEN last_30d_value > 1000 THEN 80 ELSE 40 END, 'Automated Score', calc_date " +
                "FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date IN " + dateScope +
                " ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET score=EXCLUDED.score, reason_tags=EXCLUDED.reason_tags",
                tenantId);

            if (snapshotRetentionDays > 0) {
                long tPrune = System.currentTimeMillis();
                int prunedActivity = jdbcTemplate.update(
                    "DELETE FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date < CURRENT_DATE - ?",
                    tenantId, snapshotRetentionDays);
                int prunedScore = jdbcTemplate.update(
                    "DELETE FROM merchant_opportunity_score WHERE tenant_id = ? AND calc_date < CURRENT_DATE - ?",
                    tenantId, snapshotRetentionDays);
                if (prunedActivity > 0 || prunedScore > 0) {
                    log.info(String.format("Pruned %d activity + %d score rows older than %d days in %.1fs",
                        prunedActivity, prunedScore, snapshotRetentionDays,
                        (System.currentTimeMillis() - tPrune) / 1000.0));
                }
            }
            log.info(String.format("businessMetrics completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * ML churn-risk scoring step. Runs AFTER calculateBusinessMetricsStep so that
     * merchant_activity_summary (labels) and sum_daily_merchant (features) are both
     * fresh for this tenant. CRITICAL: this step must NEVER fail the ingestion job —
     * the entire body is exception-isolated and always returns FINISHED. A model
     * failure at worst leaves churn scores stale; ingestion is unaffected.
     */
    @Bean public Step scoreMlStep(Tasklet scoreMlTasklet) {
        return new StepBuilder("scoreMlStep", jobRepository)
            .tasklet(scoreMlTasklet, transactionManager)
            .transactionAttribute(noTxn()).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet scoreMlTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['deferReporting']}") String deferReporting) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            // DEFERRED REPORTING (2026-08-29): churn scoring is TENANT-WIDE — it
            // reads the whole merchant base, so in a sequential server-folder run
            // each file's execution is overwritten by the next file's ~25 minutes
            // later and only the final one matters. The folder path sets
            // deferReporting=true per file and runs reportingOnlyJob once at the
            // end over the complete data.
            if ("true".equals(deferReporting)) {
                log.info("scoreMl: deferred (sequential folder load) — will run once in reportingOnlyJob");
                return RepeatStatus.FINISHED;
            }
            long start = System.currentTimeMillis();
            try {
                int scored = churnScoringService.trainAndScore(tenantId);
                log.info(String.format("scoreMl (churn) completed in %.1fs (scored %d merchants)",
                    (System.currentTimeMillis() - start) / 1000.0, scored));
            } catch (Exception e) {
                // Never fail the ingestion job because of ML. Log and move on.
                log.warn("scoreMl (churn) failed (non-fatal, ingestion unaffected): {}", e.toString());
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Merchant segmentation step. Runs AFTER scoreMlStep so sum_daily_merchant,
     * merchant_activity_summary, and the churn score are all fresh. Assigns each
     * merchant a primary segment + secondary tags from trailing-90d metrics and
     * per-tenant percentiles. Exception-isolated — can never fail ingestion.
     */
    @Bean public Step computeSegmentsStep(Tasklet computeSegmentsTasklet) {
        return new StepBuilder("computeSegmentsStep", jobRepository)
            .tasklet(computeSegmentsTasklet, transactionManager)
            .transactionAttribute(noTxn()).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet computeSegmentsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['deferReporting']}") String deferReporting) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            // Tenant-wide as-of-latest-date computation — same deferral rationale
            // as scoreMlTasklet above.
            if ("true".equals(deferReporting)) {
                log.info("computeSegments: deferred (sequential folder load) — will run once in reportingOnlyJob");
                return RepeatStatus.FINISHED;
            }
            long start = System.currentTimeMillis();
            try {
                int n = merchantSegmentationService.computeForTenant(tenantId);
                log.info(String.format("computeSegments completed in %.1fs (segmented %d merchants)",
                    (System.currentTimeMillis() - start) / 1000.0, n));
            } catch (Exception e) {
                log.warn("computeSegments failed (non-fatal, ingestion unaffected): {}", e.toString());
            }
            return RepeatStatus.FINISHED;
        };
    }

    private static org.springframework.transaction.interceptor.DefaultTransactionAttribute noTxn() {
        org.springframework.transaction.interceptor.DefaultTransactionAttribute attr =
            new org.springframework.transaction.interceptor.DefaultTransactionAttribute(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NEVER);
        return attr;
    }

    @Bean public Step calculateDailyDashboardMetricsStep(Tasklet calculateDailyDashboardMetricsTasklet) {
        return new StepBuilder("calculateDailyDashboardMetricsStep", jobRepository)
            .tasklet(calculateDailyDashboardMetricsTasklet, transactionManager)
            .transactionAttribute(noTxn()).listener(mdcStepListener).listener(ingestRunStepListener).build();
    }

    @Bean @StepScope
    public Tasklet calculateDailyDashboardMetricsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantIdObj) {
        return (contribution, chunkContext) -> {
            if (tenantIdObj == null) return RepeatStatus.FINISHED;
            Integer tenantId = tenantIdObj.intValue();
            long start = System.currentTimeMillis();

            // PERF FIX: derive months from distinctDates, avoiding a second stg_trnx_raw scan.
            // Run-scope (2026-08-29): only THIS file's staged dates — see stagingToFact.
            final Long ingestRunId = ingestRunIdOf(chunkContext);
            final String stgRunWhere = ingestRunId != null ? " AND ingest_run_id = " + ingestRunId : "";
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL" + stgRunWhere + " ORDER BY d",
                java.sql.Date.class, tenantId);
            java.util.Set<String> monthSet = new java.util.LinkedHashSet<>();
            for (java.sql.Date d : distinctDates) {
                java.time.LocalDate ld = d.toLocalDate();
                monthSet.add(String.format("%04d-%02d", ld.getYear(), ld.getMonthValue()));
            }

            int totalSaved = 0;
            for (String monthYear : monthSet) {
                // FIX: clean-slate this month's monthly-merchant-metrics before rebuild.
                // sum_daily_merchant was just cleanly rebuilt in populateSummary, so
                // deleting the month here and re-deriving guarantees no orphan merchant
                // rows survive from an earlier upload that touched a different day of
                // the same month. month_year is the YYYY-MM VARCHAR key.
                // Passed as the beforeWrite hook so it still fires ONLY when the month
                // has daily rows to rebuild from — deleting for an empty month would
                // discard good metrics rather than refresh them.
                totalSaved += monthlyMetricsRebuilder.rebuildMonth(tenantId, monthYear, () -> {
                    int delMonthly = jdbcTemplate.update(
                        "DELETE FROM sum_monthly_merchant_metrics WHERE tenant_id = ? AND month_year = ?",
                        tenantId, monthYear);
                    if (delMonthly > 0) log.warn("  [dashboardMetrics] clean-slate {} monthly rows for {}", delMonthly, monthYear);
                });
            }
            log.info(String.format("dashboardMetrics completed in %.1fs (saved %d rows across %d months)",
                (System.currentTimeMillis() - start) / 1000.0, totalSaved, monthSet.size()));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean @StepScope
    public org.springframework.batch.item.support.SynchronizedItemStreamReader<StagingTransaction> transactionExcelReader(
            @Value("#{jobParameters['fullPath']}") String fullPath) {
        ExcelItemReader<StagingTransaction> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(fullPath));
        reader.setLinesToSkip(1);
        reader.setRowMapper((row, rowNum) -> {
            StagingTransaction t = new StagingTransaction();
            t.setEntityName(reader.getCellValue(row, "Entity Name"));
            t.setMid(reader.getCellValue(row, "MID"));
            t.setMerchantName(reader.getCellValue(row, "Merchant Name"));
            t.setPaymentDate(parseDate(reader.getCellValue(row, "Payment Date")));
            t.setTxnCurrencyAmount(parseDecimal(reader.getCellValue(row, "Txn Currency Amount")));
            return t;
        });
        org.springframework.batch.item.support.SynchronizedItemStreamReader<StagingTransaction> sync =
            new org.springframework.batch.item.support.SynchronizedItemStreamReader<>();
        sync.setDelegate(reader); return sync;
    }

    // Canonical implementation lives in IngestScopes (shared with the
    // backfill / bulk-migration rebuild paths since 2026-08-28).
    private static String buildSafeDateInList(java.util.List<java.sql.Date> dates) {
        return com.acquira.batch.service.IngestScopes.dateInList(dates);
    }

    private java.math.BigDecimal parseDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try { return new java.math.BigDecimal(val.replaceAll(",", "").trim()); } catch (Exception e) { return null; }
    }

    // PERF FIX: static DateTimeFormatter arrays - avoids allocating formatters per row.
    private static final java.time.format.DateTimeFormatter[] DT_FORMATTERS = {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
        java.time.format.DateTimeFormatter.ofPattern("M/d/yy H:mm"),
        java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
    };
    private static final java.time.format.DateTimeFormatter[] D_FORMATTERS = {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("M/d/yy"),
        // BH feed (2026-08-24): dates arrive as '01-AUG-26' / '31-JUL-26'.
        // Month names must parse CASE-INSENSITIVELY ('AUG' vs the default 'Aug')
        // and in ENGLISH regardless of the server's default locale — a pattern
        // formatter alone would silently NULL every payment_date, and the fact
        // insert's `payment_date IS NOT NULL` filter would then drop 100% of
        // rows. yy pivots to 2000-2099 (SmartResolverStyle default base 2000).
        new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yy").toFormatter(java.util.Locale.ENGLISH),
        new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy").toFormatter(java.util.Locale.ENGLISH),
        new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d-MMM-yy").toFormatter(java.util.Locale.ENGLISH),
        new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d-MMM-yyyy").toFormatter(java.util.Locale.ENGLISH),
    };

    private java.time.LocalDateTime parseDate(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try {
            String v = val.trim();
            if (v.matches("-?\\d+(\\.\\d+)?")) {
                double serial = Double.parseDouble(v);
                long days = (long) serial;
                double fraction = serial - days;
                java.time.LocalDateTime base = java.time.LocalDateTime.of(1899, 12, 30, 0, 0).plusDays(days);
                if (fraction > 0) base = base.plusSeconds(Math.round(fraction * 86400));
                return base;
            }
            // ISO-8601 fast path. Guarded try: '21-OCT-25' also contains a 'T'
            // (the only month abbreviation that does), and before 2026-09-02 it
            // was routed here, threw, and NULLed every payment_date in every
            // October BH file. On failure fall through to the pattern lists.
            if (v.contains("T")) {
                try { return java.time.LocalDateTime.parse(v); } catch (Exception ignored) {}
            }
            if (v.contains(" ")) {
                for (java.time.format.DateTimeFormatter fmt : DT_FORMATTERS) {
                    try { return java.time.LocalDateTime.parse(v, fmt); } catch (Exception ignored) {}
                }
            }
            for (java.time.format.DateTimeFormatter fmt : D_FORMATTERS) {
                try { return java.time.LocalDate.parse(v, fmt).atStartOfDay(); } catch (Exception ignored) {}
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private java.time.LocalDateTime parseDateWithTime(String dateStr, String timeStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        java.time.LocalDateTime datePart = parseDate(dateStr);
        if (datePart == null) return null;
        if (timeStr == null || timeStr.trim().isEmpty()) return datePart;
        try {
            String tv = timeStr.trim(); int hh, mm, ss;
            if (tv.matches("\\d+\\.\\d+")) {
                double frac = Double.parseDouble(tv); int totalSecs = (int) Math.round(frac * 86400);
                hh = totalSecs / 3600; mm = (totalSecs % 3600) / 60; ss = totalSecs % 60;
            } else if (tv.contains(":")) {
                String[] parts = tv.split(":"); hh = Integer.parseInt(parts[0]);
                mm = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                ss = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            } else { return datePart; }
            return datePart.toLocalDate().atTime(hh, mm, ss);
        } catch (Exception e) { return datePart; }
    }
}
