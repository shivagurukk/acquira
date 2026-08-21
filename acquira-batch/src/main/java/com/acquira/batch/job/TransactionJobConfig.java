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

    // PERF FIX: compiled once at class-load time, not per buildSafeDateInList() call.
    private static final java.util.regex.Pattern ISO_DATE_PATTERN =
        java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

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
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {
        return new JobBuilder("transactionLoadJob", jobRepository)
                .listener(cacheEvictionJobListener)
                .start(ensurePartitionsStep).next(splitExcelStep).next(cleanTargetDayStep)
                .next(masterIngestStep).next(analyzeStagingStep).next(autoCreateDimensionsStep)
                .next(stagingToFactStep).next(populateSummaryStep)
                .next(calculateBusinessMetricsStep).next(scoreMlStep).next(computeSegmentsStep)
                .next(calculateDailyDashboardMetricsStep).build();
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
     */
    @Bean
    public Job dbPullTransactionJob(
            @org.springframework.beans.factory.annotation.Qualifier("ensurePartitionsStep") Step ensurePartitionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("analyzeStagingStep") Step analyzeStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("autoCreateDimensionsStep") Step autoCreateDimensionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {
        return new JobBuilder("dbPullTransactionJob", jobRepository)
                .listener(cacheEvictionJobListener)
                .start(ensurePartitionsStep).next(analyzeStagingStep).next(autoCreateDimensionsStep)
                .next(stagingToFactStep).next(populateSummaryStep)
                .next(calculateBusinessMetricsStep).next(scoreMlStep).next(computeSegmentsStep)
                .next(calculateDailyDashboardMetricsStep).build();
    }

    @Bean
    public Step autoCreateDimensionsStep(Tasklet autoCreateDimensionsTasklet) {
        return new StepBuilder("autoCreateDimensionsStep", jobRepository)
            .tasklet(autoCreateDimensionsTasklet, transactionManager).listener(mdcStepListener).build();
    }

    @Bean @StepScope
    public Tasklet autoCreateDimensionsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();

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
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
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
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                    "GROUP BY s.tenant_id, TRIM(s.sid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            int storesAdded = 0;
            Boolean hasUnmappedStores = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw s " +
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                "AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                "LIMIT 1)", Boolean.class, tenantId);

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
                    "JOIN dim_merchant m ON m.tenant_id = s.tenant_id " +
                    "  AND (m.mid = NULLIF(TRIM(s.mid), '') " +
                    "    OR m.internal_id = 'AUTO_SID_' || TRIM(s.sid)) " +
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
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
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.tid), '') IS NOT NULL " +
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
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.tid), '') IS NOT NULL " +
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
        return new StepBuilder("ensurePartitionsStep", jobRepository).tasklet(ensurePartitionsTasklet, transactionManager).listener(mdcStepListener).build();
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
        return new StepBuilder("splitExcelStep", jobRepository).tasklet(excelSplitterTasklet, transactionManager).listener(mdcStepListener).build();
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
            .listener(mdcStepListener).build();
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
        return new StepBuilder("cleanTargetDayStep", jobRepository).tasklet(cleanTargetDayTasklet, transactionManager).listener(mdcStepListener).build();
    }
    @Bean @StepScope public Tasklet cleanTargetDayTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long t = System.currentTimeMillis();
            int rows = jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);
            log.info(String.format("cleanTargetDay completed in %.1fs (deleted %d staging rows)",
                (System.currentTimeMillis() - t) / 1000.0, rows));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step masterIngestStep(Step csvWorkerStep, CsvPartitioner partitioner,
            @org.springframework.beans.factory.annotation.Qualifier("transactionPartitionExecutor")
            org.springframework.core.task.TaskExecutor partitionExecutor) {
        return new StepBuilder("masterIngestStep", jobRepository).partitioner("csvWorkerStep", partitioner)
                .step(csvWorkerStep).taskExecutor(partitionExecutor).gridSize(8).listener(mdcStepListener).build();
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
        executor.setQueueCapacity(0);
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
    @Bean public ItemWriter<StagingTransaction> highPerfTransactionWriter() {
        final String sql = "INSERT INTO stg_trnx_raw (entity_name, aggregator_internal_id, aggregator_name, aggregator_code, " +
            "mid, merchant_internal_id, merchant_name, sid, merchant_store_internal_id, cmm_merchant_store_internal_id, " +
            "merchant_store_legal_name, store_name, tid, arn, rrn_number, card_number, auth_code, payment_date, " +
            "transaction_date, batch_number, transaction_type, card_scheme, card_type, card_product_code, dcc, txn_currency, " +
            "txn_currency_amount, store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, " +
            "interchange_fee, destination, issuer_country, tenant_id, load_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        return items -> jdbcTemplate.batchUpdate(sql,
            new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override public int getBatchSize() { return items.size(); }
                @Override public void setValues(PreparedStatement ps, int idx) throws java.sql.SQLException {
                    StagingTransaction t = items.getItems().get(idx);
                    int i = 1;
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
            .listener(mdcStepListener)
            .build();
    }

    @Bean @StepScope
    public Tasklet stagingToFactTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['loadMode']}") String loadMode) {
        return (contribution, chunkContext) -> {
            long start = System.currentTimeMillis();
            final boolean appendMode = "APPEND".equalsIgnoreCase(loadMode);

            // PERF FIX: nullDateCount full-scan removed. IS NOT NULL filter below handles skipping.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
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
                    "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ?", Integer.class, tenantId);
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
            final String firstDate = distinctDates.get(0).toString();
            final String lastDate = distinctDates.get(distinctDates.size() - 1).toString();
            final String dateRangeF = " f.payment_date >= DATE '" + firstDate + "' AND f.payment_date < DATE '" + lastDate + "' + INTERVAL '1 day' ";
            final String dateRangeFt = " ft.payment_date >= DATE '" + firstDate + "' AND ft.payment_date < DATE '" + lastDate + "' + INTERVAL '1 day' ";

            String updateNameSql = "UPDATE dim_merchant m SET name = sub.merchant_name " +
                "FROM (SELECT DISTINCT s.mid AS staging_mid, s.merchant_name FROM stg_trnx_raw s " +
                "WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '') sub " +
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
                Boolean stillMissing = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.tenant_id = ? " +
                    "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") LIMIT 1)",
                    Boolean.class, tenantId);
                if (Boolean.TRUE.equals(stillMissing)) {
                    long t06 = System.currentTimeMillis();
                    int prefixUpdated = jdbcTemplate.update(
                        "UPDATE dim_merchant m SET name = sub.merchant_name " +
                        "FROM (SELECT DISTINCT s.mid AS staging_mid, s.merchant_name FROM stg_trnx_raw s " +
                        "WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '' " +
                        "AND s.merchant_name !~ " + NUMERIC_ONLY_REGEX + ") sub " +
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

            java.util.List<String> uploadSchemes = jdbcTemplate.queryForList(
                "SELECT DISTINCT UPPER(TRIM(card_scheme)) FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND NULLIF(TRIM(card_scheme), '') IS NOT NULL",
                String.class, tenantId);

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
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " AND UPPER(TRIM(card_scheme)) IN (" + placeholders + ")", args);
                }
                Boolean stagingHasBlankScheme = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw WHERE tenant_id = ? " +
                    "AND payment_date IS NOT NULL AND NULLIF(TRIM(card_scheme), '') IS NULL LIMIT 1)",
                    Boolean.class, tenantId);
                if (Boolean.TRUE.equals(stagingHasBlankScheme)) {
                    deleted += jdbcTemplate.update(
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " AND NULLIF(TRIM(card_scheme), '') IS NULL", tenantId);
                }
                if (uploadSchemes.isEmpty() && !Boolean.TRUE.equals(stagingHasBlankScheme)) {
                    log.warn("APPEND mode: staging has no rows in scope - nothing to delete.");
                } else {
                    log.info(String.format("APPEND mode: deleted %d fact rows for scheme(s) %s%s in %.1fs",
                        deleted, uploadSchemes,
                        Boolean.TRUE.equals(stagingHasBlankScheme) ? " + blank-scheme rows" : "",
                        (System.currentTimeMillis() - tDel) / 1000.0));
                }
            } else {
                jdbcTemplate.update(
                    "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope,
                    tenantId);
                log.info(String.format("Deleted existing fact rows in %.1fs", (System.currentTimeMillis() - tDel) / 1000.0));
            }

            long tIns = System.currentTimeMillis();
            String sql = "INSERT INTO fact_transaction (tenant_id, merchant_id, store_id, terminal_id, " +
                "arn, rrn_number, card_number, auth_code, payment_date, transaction_date, batch_number, " +
                "transaction_type, card_scheme, card_type, card_product_code, dcc, txn_currency, txn_currency_amount, " +
                "store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, interchange_fee, " +
                "destination, destination_raw, issuer_country) " +
                "SELECT stg.tenant_id, " +
                "COALESCE(s.merchant_id, m.merchant_id, s2.merchant_id) AS merchant_id, " +
                "COALESCE(s.store_id, s2.store_id) AS store_id, t.terminal_id, " +
                "stg.arn, stg.rrn_number, stg.card_number, stg.auth_code, " +
                "stg.payment_date, stg.transaction_date, stg.batch_number, stg.transaction_type, " +
                // SIGNED VOLUME (2026-07-18, reverses 2026-07-08 option B): refunds (RFND)
                // are stored NEGATIVE so fact + all summaries net refunds out of volume,
                // matching the raw feed / MIS reconciliation basis. Sign is forced from
                // transaction_type (not trusted from the feed): purchases +ABS, refunds -ABS.
                //
                // SIGNED MSF (2026-08-07): MSF follows the SAME sign rule as volume.
                // Verified against July 2026 (10,180,989 rows): file signed sum
                // 16,566,159.6713 == finance == fact netted; the old ABS basis ran
                // exactly 2x refund MSF higher (16,583,044.4293). Refund fees must
                // net out to reconcile with the raw feed / finance pivot.
                // vat/interchange remain ABS; total_amount_settled stays raw SIGNED.
                // TXN CURRENCY FALLBACK (2026-08-14, user rule): feeds mask the PAN
                // as first-6-clear + masked + last-4-clear, so only a 6-digit BIN is
                // extractable and BIN -> issuer-country -> cardholder-currency cannot
                // resolve yet. For rows the feed leaves blank AND that map to
                // DOMESTIC, the cardholder is local by definition, so take the
                // tenant's home currency (base_currency, else the home country's
                // ref_country currency). Blank INTERNATIONAL rows stay NULL — a
                // guessed foreign currency would poison the by-country rollups.
                // A feed-supplied currency is never overridden.
                "stg.card_scheme, stg.card_type, stg.card_product_code, stg.dcc, " +
                "COALESCE(NULLIF(TRIM(stg.txn_currency),''), " +
                "  CASE WHEN dtm.dest = 'DOMESTIC' THEN NULLIF(TRIM(COALESCE(tn.base_currency, rchome.currency_code)),'') END), " +
                "CASE WHEN UPPER(TRIM(COALESCE(stg.transaction_type,''))) IN ('RFND','REFUND') " +
                "     THEN -ABS(stg.txn_currency_amount) ELSE ABS(stg.txn_currency_amount) END, " +
                "stg.store_base_currency, " +
                "CASE WHEN UPPER(TRIM(COALESCE(stg.transaction_type,''))) IN ('RFND','REFUND') " +
                "     THEN -ABS(stg.store_base_currency_amount) ELSE ABS(stg.store_base_currency_amount) END, " +
                "CASE WHEN UPPER(TRIM(COALESCE(stg.transaction_type,''))) IN ('RFND','REFUND') " +
                "     THEN -ABS(stg.msf) ELSE ABS(stg.msf) END, " +
                "ABS(stg.vat), stg.total_amount_settled, ABS(stg.interchange_fee), " +
                // DESTINATION NORMALIZATION (2026-08-10). The feed's own vocabulary is
                // mapped to the engine's canonical DOMESTIC/INTERNATIONAL exactly once,
                // here, so fact + fee engine + every rollup all see the same value.
                // Previously the raw token was copied verbatim and the fee engine
                // exact-matched it, so a Bahraini or Egyptian feed saying 'LOCAL'
                // matched no rate row and silently took a 1.85% UAE fallback.
                // An UNMAPPED token deliberately lands as NULL rather than being
                // guessed as INTERNATIONAL — the fee engine reports it as
                // UNMAPPED_DESTINATION and prices nothing. destination_raw always
                // keeps the original token for audit and for mapping gaps analysis.
                "dtm.dest, NULLIF(TRIM(stg.destination),''), stg.issuer_country " +
                "FROM stg_trnx_raw stg " +
                "LEFT JOIN tenant tn ON tn.tenant_id = stg.tenant_id " +
                // home-currency source for the DOMESTIC txn_currency fallback above;
                // country_code is ref_country's key, so this can never fan out rows.
                "LEFT JOIN ref_country rchome ON rchome.country_code = COALESCE(tn.home_country_code,'AE') " +
                "LEFT JOIN LATERAL ( " +
                "  SELECT d.dest FROM destination_token_map d " +
                "  WHERE d.country_code = COALESCE(tn.home_country_code,'AE') " +
                "    AND (d.tenant_id IS NULL OR d.tenant_id = stg.tenant_id) " +
                "    AND d.raw_token = UPPER(TRIM(COALESCE(stg.destination,''))) " +
                "  ORDER BY (d.tenant_id IS NOT NULL) DESC LIMIT 1 " +
                ") dtm ON TRUE " +
                "LEFT JOIN dim_store s ON s.tenant_id = stg.tenant_id AND s.sid = NULLIF(TRIM(stg.sid), '') " +
                "LEFT JOIN dim_merchant m ON m.tenant_id = stg.tenant_id AND m.mid = NULLIF(TRIM(stg.mid), '') " +
                "LEFT JOIN dim_terminal t ON t.tenant_id = stg.tenant_id " +
                "  AND t.tid = NULLIF(TRIM(stg.tid), '') AND (t.store_id = s.store_id OR s.store_id IS NULL) " +
                "LEFT JOIN dim_store s2 ON s2.tenant_id = stg.tenant_id AND s2.store_id = t.store_id " +
                "WHERE stg.tenant_id = ? AND stg.payment_date IS NOT NULL";
            int inserted = jdbcTemplate.update(sql, tenantId);
            log.info(String.format("Inserted %d fact rows in %.1fs", inserted, (System.currentTimeMillis() - tIns) / 1000.0));

            // RECONCILIATION: staging rows with a usable date must equal fact rows inserted.
            // The INSERT's only filter is `payment_date IS NOT NULL`, so these two numbers
            // are expected to match exactly; a gap means rows were silently lost (or the
            // LEFT JOINs to dim_store/dim_terminal fanned out and DUPLICATED rows, which is
            // possible because those joins are not guaranteed one-to-one and there is no
            // unique constraint on fact_transaction to catch it). Either way the operator
            // must know — this used to be invisible.
            Integer stagedUsable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL",
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
                "FROM fact_transaction WHERE tenant_id = ? " +
                "AND DATE(payment_date) IN " + dateScope, tenantId);
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
            } catch (Exception dqe) {
                log.warn("Could not record data-quality summary (non-fatal): {}", dqe.getMessage());
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
                "SELECT EXISTS (SELECT 1 FROM fact_transaction f WHERE f.tenant_id = ? " +
                "AND f.store_id IS NULL AND f.merchant_id IS NOT NULL " +
                "AND DATE(f.payment_date) IN " + dateScope + " LIMIT 1)", Boolean.class, tenantId);
            if (Boolean.TRUE.equals(anyNullStore)) {
                storeFixed = jdbcTemplate.update(
                    "UPDATE fact_transaction f SET store_id = s.store_id " +
                    "FROM dim_store s, stg_trnx_raw stg " +
                    "WHERE f.tenant_id = ? AND s.tenant_id = ? AND stg.tenant_id = ? " +
                    "AND f.store_id IS NULL AND f.merchant_id IS NOT NULL " +
                    "AND s.merchant_id = f.merchant_id " +
                    "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                    "AND s.internal_id = CONCAT('STORE_', stg.mid) " +
                    "AND DATE(f.payment_date) IN " + dateScope,
                    tenantId, tenantId, tenantId);
            }
            Boolean anyNullTerm = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM fact_transaction f WHERE f.tenant_id = ? " +
                "AND f.terminal_id IS NULL AND f.store_id IS NOT NULL " +
                "AND DATE(f.payment_date) IN " + dateScope + " LIMIT 1)", Boolean.class, tenantId);
            if (Boolean.TRUE.equals(anyNullTerm)) {
                termFixed = jdbcTemplate.update(
                    "UPDATE fact_transaction f SET terminal_id = t.terminal_id " +
                    "FROM dim_terminal t, stg_trnx_raw stg " +
                    "WHERE f.tenant_id = ? AND t.tenant_id = ? AND stg.tenant_id = ? " +
                    "AND f.terminal_id IS NULL AND f.store_id IS NOT NULL " +
                    "AND t.store_id = f.store_id " +
                    "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                    "AND t.internal_id = CONCAT('TERM_', stg.mid) " +
                    "AND DATE(f.payment_date) IN " + dateScope,
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
            // FEE COMPUTATION (V2026_07_05_01): interchange + scheme fee are
            // computed by US, not trusted from the feed. Both off the
            // SETTLEMENT amount (store_base_currency_amount) — never the
            // cardholder amount. Rows without a matching rate row (e.g. an
            // unseeded tenant) keep the feed interchange value untouched, so
            // this can never break ingestion.
            //
            // Interchange: highest-priority matching interchange_rate_local
            // row (NULL column = wildcard). Scheme via ref_card_scheme by
            // CODE or NAME; tier via card_subtype (1=Standard else Premium);
            // channel via dim_terminal.type exact ECOM whitelist; MCC sector
            // via mcc_sector_map; ticket thresholds vs settlement amount;
            // debit cap via LEAST(). Scheme fee: dest x channel percentage.
            // =================================================================
            long tFee = System.currentTimeMillis();
            // =================================================================
            // SINGLE-PASS FEE COMPUTATION (PERF, 2026-07-06c)
            //
            // Previously interchange, scheme fee, and ecom fee were THREE separate
            // UPDATEs, each re-scanning the same fact rows for the date range and
            // re-joining dim_terminal / ref_card_scheme. Scheme fee even re-derived
            // the ECOM channel via a correlated dim_terminal subquery that the
            // interchange join had already computed. That's 3x the scan + redundant
            // joins.
            //
            // Now ONE UPDATE:
            //   - joins dim_store / dim_terminal / ref_card_scheme ONCE
            //   - derives `channel` (POS/ECOM) ONCE in the sub-select
            //   - one LATERAL for the interchange rate, one for the scheme rate
            //   - ecom_fee is a CASE on the shared channel (no extra pass/subquery)
            //
            // Correctness is identical to the three separate statements: same rate
            // resolution, same ABS(settlement) basis, same fallbacks. Rows with no
            // matching rate keep the feed interchange value and get scheme/ecom
            // 0/NULL exactly as before.
            //
            // PERF: filters on the RAW payment_date range (partition pruning +
            // index) AND the exact DATE(...) IN (...) set. Fees off SETTLEMENT
            // amount (store_base_currency_amount), never cardholder amount.
            // =================================================================
            int feeRows = jdbcTemplate.update(
                "UPDATE fact_transaction f SET " +
                "  interchange_fee = r.computed_ic, " +
                "  scheme_fee      = r.computed_scheme, " +
                "  ecom_fee        = r.computed_ecom, " +
                "  channel                  = r.channel, " +
                "  fee_resolution_status    = r.status, " +
                "  scheme_fee_status        = r.sf_status, " +
                "  interchange_rule_id      = r.ic_rule_id, " +
                "  scheme_fee_rule_id       = r.sf_rule_id, " +
                "  interchange_pct_applied  = r.ic_pct, " +
                "  interchange_flat_applied = r.ic_flat, " +
                "  interchange_cap_applied  = r.ic_cap " +
                "FROM ( " +
                "  SELECT ft.transaction_id, ft.payment_date, ch.channel, " +
                // REFUND RULE (2026-07-08, business-confirmed): refunds carry ZERO
                // interchange and ZERO scheme fee. Feed transaction_type = 'RFND'.
                // Ecom flat fee untouched.
                // interchange: refund => 0; else matched rate (+cap) else flat 1.85% fallback
                // INTERCHANGE (rewritten 2026-08-10).
                //
                // The old version ended `WHEN lr.interchange_pct IS NULL THEN
                // 0.018500 * amount` — any transaction that matched no rate row was
                // silently charged the UAE cross-border rate, in the tenant's own
                // currency, with a NULL (=0) scheme fee. It was indistinguishable
                // from a correctly priced row. That fallback is GONE: an unmatched
                // transaction now yields NULL and an explicit fee_resolution_status.
                //
                // FORMULA: cap bounds the PERCENTAGE component, then the flat fee is
                // added. Both live cases confirm that ordering —
                //   BENEFIT petrol : LEAST(0.6% x 45.750, 0.085) + 0    = 0.085
                //   BENEFIT intl   : LEAST(1.1% x 100.000, inf) + 0.100 = 1.200
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN NULL " +
                "         WHEN lr.id IS NULL OR lr.rate_status <> 'APPROVED' THEN NULL " +
                "         ELSE LEAST(lr.interchange_pct * ABS(COALESCE(ft.store_base_currency_amount,0)), " +
                "                    COALESCE(lr.cap_amount, 999999999999)) + COALESCE(lr.flat_fee,0) END AS computed_ic, " +
                // scheme fee: same discipline — an approved rate or nothing at all.
                // BH/EG scheme-fee grids are verbatim UAE copies (flagged PLACEHOLDER
                // in V2026_08_10_01), so they resolve to NULL + PLACEHOLDER_RATE until
                // real country figures are supplied rather than quietly billing UAE
                // economics to Bahraini and Egyptian merchants.
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN NULL " +
                "         WHEN sfr.id IS NULL OR sfr.rate_status <> 'APPROVED' THEN NULL " +
                "         ELSE (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))) " +
                "              + COALESCE(sfr.flat_fee,0) END AS computed_scheme, " +
                // ---- provenance + resolution status -------------------------------
                "    lr.id AS ic_rule_id, sfr.id AS sf_rule_id, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.interchange_pct END AS ic_pct, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.flat_fee END AS ic_flat, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.cap_amount END AS ic_cap, " +
                "    CASE WHEN rf.is_refund                      THEN 'RESOLVED' " +
                "         WHEN ft.destination IS NULL            THEN 'UNMAPPED_DESTINATION' " +
                "         WHEN ch.channel IS NULL                THEN 'UNMAPPED_CHANNEL' " +
                "         WHEN lr.id IS NULL                     THEN 'NO_RATE_FOUND' " +
                "         WHEN lr.rate_status <> 'APPROVED'      THEN 'PLACEHOLDER_RATE' " +
                // The scheme token did not resolve to a known network, so pricing came
                // from the country's any-scheme row. Legitimate (this is how Amex and
                // unmapped tokens have always priced) but it must be visible, not
                // silently indistinguishable from a scheme-specific match.
                "         WHEN rcs.group_name IS NULL            THEN 'RESOLVED_SCHEME_WILDCARD' " +
                "         ELSE 'RESOLVED' END AS status, " +
                "    CASE WHEN rf.is_refund                       THEN 'RESOLVED' " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN 'UNRESOLVED' " +
                "         WHEN sfr.id IS NULL                     THEN 'NO_RATE_FOUND' " +
                "         WHEN sfr.rate_status <> 'APPROVED'      THEN 'PLACEHOLDER_RATE' " +
                "         ELSE 'RESOLVED' END AS sf_status, " +
                // ecom flat fee (V2026_07_31_06): per-country config (ecom_flat_fee)
                // resolved by home_country_code, NOT a hardcoded 0.18. On ECOM channel
                // use the resolved fee (COALESCE to 0 when a country has no configured
                // row, e.g. BH/OM/EG today); NULL off ECOM. AE keeps 0.18 via its seed.
                "    CASE WHEN ch.channel = 'ECOM' THEN COALESCE(eff.fee_amount, 0) ELSE NULL END AS computed_ecom " +
                "  FROM fact_transaction ft " +
                // COUNTRY RESOLUTION (V2026_07_31_02, Phase 2 multi-region): a rate
                // card is COUNTRY-LEVEL, not tenant-level. Resolve the transaction's
                // country from its tenant's home_country_code; every rate LATERAL
                // below then matches country_code = this value (default 'AE' if a
                // tenant has no home country set, preserving legacy UAE behaviour).
                "  LEFT JOIN tenant tn ON tn.tenant_id = ft.tenant_id " +
                "  LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id " +
                "  LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id " +
                // SCHEME RESOLUTION FIX (2026-07-07): space-insensitive match so feed
                // variants like 'MASTER CARD' resolve to ref_card_scheme 'MasterCard'.
                // Without this, ~42% of rows (MASTER CARD) got group_name NULL -> wrong
                // interchange AND zero scheme fee. Strips spaces on BOTH sides.
                // SCHEME RESOLUTION, two-tier (fixed 2026-08-10). The product code is
                // tried FIRST because it carries the Premium/Standard tier signal, then
                // the network name is tried as a fallback.
                //
                // The previous single-expression join used
                //   COALESCE(NULLIF(card_product_code,''), card_scheme)
                // which falls back only when the product code is EMPTY — never when it
                // is present but unrecognised. A feed that puts a generic word like
                // 'DEBIT' or 'CREDIT' in its Card Type column therefore resolved
                // group_name = NULL for EVERY row, so scheme-specific pricing became
                // unreachable and everything silently took the country's any-scheme
                // wildcard. Verified on real Bahraini and Egyptian ingestion: BENEFIT
                // and Meeza transactions were being priced at the generic 1.75%
                // instead of their own rate cards. UAE is unaffected — its product
                // codes (VIPM/MCPM/MCDB...) still match on the first tier.
                "  CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_product_code,''))),' ','') AS v) pc " +
                "  CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_scheme,''))),' ','') AS v) sc " +
                "  LEFT JOIN LATERAL ( " +
                "    SELECT r.*, CASE WHEN pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = pc.v " +
                "                       OR REPLACE(UPPER(TRIM(r.name)),' ','') = pc.v) THEN 1 ELSE 0 END AS by_product " +
                "    FROM ref_card_scheme r " +
                "    WHERE (pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = pc.v " +
                "                        OR REPLACE(UPPER(TRIM(r.name)),' ','') = pc.v)) " +
                "       OR (sc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = sc.v " +
                "                        OR REPLACE(UPPER(TRIM(r.name)),' ','') = sc.v)) " +
                "    ORDER BY by_product DESC, r.id ASC LIMIT 1 " +
                "  ) rcs ON TRUE " +
                // derive channel ONCE, reused by both rate LATERALs and the ecom CASE
                // CHANNEL RESOLUTION (config-driven since 2026-08-10). This used to be
                // a hardcoded four-string UAE whitelist with an implicit `ELSE 'POS'`,
                // so ANY other processor's e-commerce silently priced as POS — cheaper
                // interchange and a cheaper scheme fee, i.e. an error that flatters the
                // P&L and never trips an alarm. Now: exact terminal-type match, then
                // the country's '*' wildcard. AE seeds '*' -> POS so its behaviour is
                // unchanged; BH/EG have no wildcard, so an unrecognised terminal type
                // surfaces as UNMAPPED_CHANNEL until the real feed values are mapped.
                "  CROSS JOIN LATERAL ( " +
                "    SELECT COALESCE( " +
                "      (SELECT t1.channel FROM terminal_channel_map t1 " +
                "         WHERE t1.country_code = COALESCE(tn.home_country_code,'AE') " +
                "           AND (t1.tenant_id IS NULL OR t1.tenant_id = ft.tenant_id) " +
                "           AND t1.raw_type = UPPER(TRIM(COALESCE(dt.type,''))) " +
                "         ORDER BY (t1.tenant_id IS NOT NULL) DESC LIMIT 1), " +
                "      (SELECT t2.channel FROM terminal_channel_map t2 " +
                "         WHERE t2.country_code = COALESCE(tn.home_country_code,'AE') " +
                "           AND (t2.tenant_id IS NULL OR t2.tenant_id = ft.tenant_id) " +
                "           AND t2.raw_type = '*' " +
                "         ORDER BY (t2.tenant_id IS NOT NULL) DESC LIMIT 1) " +
                "    ) AS channel " +
                "  ) ch " +
                // derive refund flag ONCE, reused by both computed_ic and computed_scheme.
                // MUST match the volume-signing set IN ('RFND','REFUND') used in
                // stagingToFact (2026-07-18) — previously this checked only 'RFND', so a
                // row typed 'REFUND' was signed as negative volume yet still charged
                // interchange + scheme fee. Kept in sync here.
                "  CROSS JOIN LATERAL (SELECT (UPPER(TRIM(COALESCE(ft.transaction_type,''))) IN ('RFND','REFUND')) AS is_refund) rf " +
                // derive mcc sector ONCE (was a correlated subquery inside the LATERAL).
                // COUNTRY-LEVEL (V2026_07_31_02): match the tenant's country card;
                // tenant_id IS NULL is the country default, a non-null tenant_id is a
                // per-tenant override which wins via the (tenant_id IS NOT NULL) DESC
                // tiebreak. LATERAL+LIMIT 1 so an override never multiplies rows.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT m.sector FROM mcc_sector_map m " +
                "    WHERE m.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (m.tenant_id IS NULL OR m.tenant_id = ft.tenant_id) " +
                "      AND m.mcc = ds.mcc " +
                "    ORDER BY (m.tenant_id IS NOT NULL) DESC LIMIT 1 " +
                "  ) msm ON TRUE " +
                // PERF (2026-07-14): lateral split into MCC-keyed + wildcard branches so
                // the planner drives each via an index instead of scanning all ~365
                // candidate rows per transaction (was 9.4M heap blocks / ~270s per window).
                // Branch 1 uses idx_interchange_rate_local_mcc (tenant_id, mcc);
                // Branch 2 uses idx_interchange_rate_local_generic (partial, mcc IS NULL).
                // Same candidate set, same priority pick - semantics unchanged.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT ilr.id, ilr.interchange_pct, ilr.flat_fee, ilr.cap_amount, ilr.rate_status FROM ( " +
                // COUNTRY-LEVEL lookup (V2026_07_31_02): match country_code =
                // tenant's home country (not tenant_id) so all tenants in a country
                // share its card; tenant_id IS NULL = country default, non-null =
                // per-tenant override (preferred in the ORDER BY below).
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.country_code = COALESCE(tn.home_country_code,'AE') " +
                "        AND (i.tenant_id IS NULL OR i.tenant_id = ft.tenant_id) " +
                "        AND i.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "        AND i.mcc = ds.mcc " +
                "      UNION ALL " +
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.country_code = COALESCE(tn.home_country_code,'AE') " +
                "        AND (i.tenant_id IS NULL OR i.tenant_id = ft.tenant_id) " +
                "        AND i.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "        AND i.mcc IS NULL " +
                "    ) ilr " +
                "    WHERE (ilr.channel IS NULL OR ilr.channel = ch.channel) " +
                "      AND (ilr.scheme_group IS NULL OR ilr.scheme_group = COALESCE(rcs.group_name,'')) " +
                // CARD-TYPE FOR PRICING (2026-07-07, business-confirmed): credit-prepaid
                // products (rcs.card_type=3, i.e. MCCP) are PRICED as CREDIT (-> Premium
                // tier below), NOT at the debit/prepaid rate. ft.card_type stays 'PREPAID'
                // for reporting/splits; only this rate lookup remaps. Debit-prepaid
                // (rcs.card_type=4, MCDP) stays on the local debit rate via 'DEBIT'.
                "      AND (ilr.card_type IS NULL OR ilr.card_type = CASE WHEN rcs.card_type = 3 THEN 'CREDIT' ELSE UPPER(TRIM(COALESCE(ft.card_type,''))) END) " +
                // TIER (2026-07-07, business-confirmed mapping): ONLY explicit Standard
                // products (card_subtype=1: MCSD/VISD) resolve Standard. EVERYTHING else
                // - AMEX/JCB/UPI/VICR/MCCR/MCCP/MCPM/VIPM/VICP, generic VISA/MCRD, and
                // unmatched codes - resolves Premium. (JCB/UPI still hit their priority-11
                // flat 1.75 rows, which are tier-wildcard, so tier is moot for them.)
                "      AND (ilr.tier IS NULL OR ilr.tier = CASE WHEN rcs.card_subtype = 1 THEN 'Standard' ELSE 'Premium' END) " +
                // MCC-KEYED RATE CARD (2026-07-07): mcc match/wildcard now enforced by the
                // UNION ALL branches above (most-specific still wins via priority DESC).
                "      AND (ilr.mcc_sector IS NULL OR ilr.mcc_sector = msm.sector) " +
                "      AND (ilr.min_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= ilr.min_ticket) " +
                "      AND (ilr.max_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) <  ilr.max_ticket) " +
                // EFFECTIVE DATING (2026-08-10): resolve against the rate that was in
                // force on the PAYMENT date, not today's. Without this, re-ingesting a
                // historical month reprices it at current rates. Needed imminently
                // because the Egypt Meeza figure is an explicit interim rate.
                "      AND (ilr.effective_from IS NULL OR ilr.effective_from <= DATE(ft.payment_date)) " +
                "      AND (ilr.effective_to   IS NULL OR ilr.effective_to   >= DATE(ft.payment_date)) " +
                // An APPROVED row always beats a PLACEHOLDER one; a placeholder is only
                // ever returned so the status column can say WHY nothing priced.
                // Then: tenant override over country default, then priority, then id.
                "    ORDER BY (ilr.rate_status = 'APPROVED') DESC, (ilr.tenant_id IS NOT NULL) DESC, " +
                "             ilr.priority DESC, ilr.id ASC LIMIT 1 " +
                "  ) lr ON TRUE " +
                // SCHEME FEE: match dest x channel; prefer scheme-specific row, then the
                // scheme_group IS NULL wildcard (seeded 2026-07-07) so EVERY scheme -
                // incl. Amex / MASTER CARD / unmapped - gets a rate instead of 0.
                "  LEFT JOIN LATERAL ( " +
                // COUNTRY-LEVEL (V2026_07_31_02): match the tenant's country card.
                // Prefer a per-tenant override (tenant_id NOT NULL), then a
                // scheme-specific row over the scheme_group IS NULL wildcard.
                "    SELECT s.id, s.fee_pct, s.flat_fee, s.rate_status FROM scheme_fee_rate s " +
                "    WHERE s.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (s.tenant_id IS NULL OR s.tenant_id = ft.tenant_id) " +
                "      AND s.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "      AND s.channel = ch.channel " +
                "      AND (s.scheme_group IS NULL OR s.scheme_group = COALESCE(rcs.group_name,'')) " +
                "      AND (s.effective_from IS NULL OR s.effective_from <= DATE(ft.payment_date)) " +
                "      AND (s.effective_to   IS NULL OR s.effective_to   >= DATE(ft.payment_date)) " +
                "    ORDER BY (s.rate_status = 'APPROVED') DESC, (s.tenant_id IS NOT NULL) DESC, " +
                "             (s.scheme_group IS NOT NULL) DESC LIMIT 1 " +
                "  ) sfr ON TRUE " +
                // ECOM FLAT FEE (V2026_07_31_06): resolve the per-country flat fee the
                // same country-level way (tenant override preferred over country default).
                // No row for a country => eff.fee_amount NULL => COALESCE'd to 0 above.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT e.fee_amount FROM ecom_flat_fee e " +
                "    WHERE e.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (e.tenant_id IS NULL OR e.tenant_id = ft.tenant_id) " +
                "    ORDER BY (e.tenant_id IS NOT NULL) DESC LIMIT 1 " +
                "  ) eff ON TRUE " +
                "  WHERE ft.tenant_id = ? AND " + dateRangeFt + " AND DATE(ft.payment_date) IN " + dateScope +
                " ) r " +
                "WHERE f.tenant_id = ? AND f.transaction_id = r.transaction_id AND f.payment_date = r.payment_date",
                tenantId, tenantId);
            log.info(String.format("Fee computation (single-pass): %d rows in %.1fs",
                feeRows, (System.currentTimeMillis() - tFee) / 1000.0));

            // FEE RESOLUTION REPORT. The whole point of removing the 1.85% fallback is
            // that a pricing gap must be LOUD. Every non-RESOLVED status is a
            // configuration gap that leaves money uncosted, so surface it per run
            // instead of leaving it to be discovered in a month-end reconciliation.
            try {
                java.util.List<java.util.Map<String, Object>> byStatus = jdbcTemplate.queryForList(
                    "SELECT fee_resolution_status AS st, COUNT(*) AS n FROM fact_transaction " +
                    "WHERE tenant_id = ? AND " + dateRangeFt + " AND DATE(payment_date) IN " + dateScope +
                    " GROUP BY 1 ORDER BY 2 DESC", tenantId);
                long unresolved = 0;
                StringBuilder sb = new StringBuilder();
                for (java.util.Map<String, Object> row : byStatus) {
                    String st = String.valueOf(row.get("st"));
                    long n = ((Number) row.get("n")).longValue();
                    sb.append(st).append('=').append(n).append(' ');
                    if (!"RESOLVED".equals(st) && !"RESOLVED_SCHEME_WILDCARD".equals(st)) {
                        unresolved += n;
                    }
                }
                if (unresolved > 0) {
                    log.warn("FEE RESOLUTION GAPS for tenant {}: {} row(s) not priced -> {}",
                        tenantId, unresolved, sb.toString().trim());
                    log.warn("  Unmapped destination tokens seen: {}", jdbcTemplate.queryForList(
                        "SELECT DISTINCT destination_raw FROM fact_transaction WHERE tenant_id = ? " +
                        "AND fee_resolution_status = 'UNMAPPED_DESTINATION' AND " + dateRangeFt +
                        " LIMIT 20", String.class, tenantId));
                } else {
                    log.info("Fee resolution: {}", sb.toString().trim());
                }
            } catch (Exception e) {
                log.warn("Fee resolution report failed (non-fatal): {}", e.getMessage());
            }

            log.info(String.format("stagingToFact completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean public Step populateSummaryStep(Tasklet populateSummaryTasklet) {
        return new StepBuilder("populateSummaryStep", jobRepository)
            .tasklet(populateSummaryTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .listener(mdcStepListener)
            .build();
    }

    @Bean @StepScope
    public Tasklet populateSummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();

            final long lockKey = 11_000_000L + tenantId;
            java.sql.Connection lockConn = null;
            try {
                lockConn = dataSource.getConnection();
                lockConn.setAutoCommit(true);
                long lockWaitStart = System.currentTimeMillis();
                try (java.sql.PreparedStatement ps = lockConn.prepareStatement("SELECT pg_advisory_lock(?)")) {
                    ps.setLong(1, lockKey);
                    try (java.sql.ResultSet rs = ps.executeQuery()) { rs.next(); }
                }
                long lockWaitMs = System.currentTimeMillis() - lockWaitStart;
                if (lockWaitMs > 1000) {
                    log.warn("populateSummary: waited {}ms for tenant lock", lockWaitMs);
                }
            } catch (Exception e) {
                if (lockConn != null) try { lockConn.close(); } catch (Exception ignore) {}
                throw new RuntimeException("Failed to acquire advisory lock for tenant " + tenantId, e);
            }
            final java.sql.Connection lockConnFinal = lockConn;

            try {
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            if (distinctDates.isEmpty()) {
                log.info("populateSummary: no dates to process - skipping");
                return RepeatStatus.FINISHED;
            }
            final String dateScope = buildSafeDateInList(distinctDates);
            java.util.Set<Integer> monthSet = new java.util.LinkedHashSet<>();
            for (java.sql.Date d : distinctDates) {
                java.time.LocalDate ld = d.toLocalDate();
                monthSet.add(ld.getYear() * 100 + ld.getMonthValue());
            }
            final String monthScope = "(" + monthSet.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + ")";
            log.info("populateSummary: {} dates, {} months in scope", distinctDates.size(), monthSet.size());

            java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(4,
                    r -> { Thread t = new Thread(r, "summary-agg-"); t.setDaemon(true); return t; });
            try {
                // ---------------------------------------------------------------
                // FIX: clean-slate the affected grain BEFORE re-aggregating.
                // The rollups below are ON CONFLICT DO UPDATE, which refreshes a
                // (grain) tuple only when it reappears in this upload. A merchant/
                // day/scheme tuple that transacted in an EARLIER upload but not in
                // this one is never touched -> orphan rows accumulate across the
                // many uploads per month, and per-day sums drift from fact in both
                // directions (and can go negative when stale rows collide with a
                // fact re-insert on the same day). fact_transaction is already
                // DELETE+reinserted per upload date upstream, so deleting the
                // summary rows for the SAME dates (daily) / months (monthly) and
                // rebuilding from fact makes summary reconcile exactly with fact.
                // Daily tables: delete by business_date IN dateScope.
                // Monthly tables: delete by month_key IN monthScope (they are
                // rebuilt from the freshly-cleaned daily tables covering the whole
                // month, so a whole-month delete+rebuild is correct).
                // ---------------------------------------------------------------
                for (String dailyTbl : new String[]{
                        "sum_daily_bank", "sum_daily_merchant", "sum_daily_mcc",
                        "sum_daily_scheme", "sum_daily_channel", "sum_daily_terminal",
                        "sum_daily_finance", "sum_daily_insight", "sum_daily_full",
                        "sum_daily_explorer", "sum_daily_merchant_destination",
                        "sum_daily_local_debit_bin",
                        "sum_daily_merchant_attribute"}) {
                    int del = jdbcTemplate.update(
                        "DELETE FROM " + dailyTbl +
                        " WHERE tenant_id = ? AND business_date IN " + dateScope, tenantId);
                    log.warn("  [populateSummary] delete {} {} rows", String.format("%-25s", dailyTbl), del);
                }
                for (String monthlyTbl : new String[]{
                        "sum_monthly_bank", "sum_monthly_insight", "sum_monthly_card"}) {
                    int del = jdbcTemplate.update(
                        "DELETE FROM " + monthlyTbl +
                        " WHERE tenant_id = ? AND month_key IN " + monthScope, tenantId);
                    log.warn("  [populateSummary] delete {} {} rows", String.format("%-25s", monthlyTbl), del);
                }

                java.util.List<java.util.concurrent.CompletableFuture<Void>> phase1 = new java.util.ArrayList<>();

                phase1.add(runAsync(exec, "sum_daily_bank", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_bank (tenant_id, business_date, total_txns, total_volume, total_base_volume, total_msf, " +
                        "total_interchange, total_scheme_fee, total_ecom_fee, total_vat, total_net_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), COUNT(*), SUM(store_base_currency_amount), SUM(store_base_currency_amount), SUM(msf), " +
                        "SUM(interchange_fee), SUM(COALESCE(scheme_fee,0)), SUM(COALESCE(ecom_fee,0)), SUM(vat), " +
                        "SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - COALESCE(scheme_fee,0) - COALESCE(ecom_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, DATE(payment_date) " +
                        "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, total_ecom_fee=EXCLUDED.total_ecom_fee, " +
                        "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_merchant", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id, " +
                        "total_txns, total_volume, total_base_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, total_margin, " +
                        "total_debit_prepaid_volume, total_credit_volume, sales_user_id, unique_customer_count, " +
                        "dcc_eligible_volume, dcc_optin_volume, dcc_optout_volume, dcc_eligible_count, dcc_optin_count) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, COUNT(*), " +
                        "SUM(f.store_base_currency_amount), SUM(f.store_base_currency_amount), SUM(f.msf), SUM(f.interchange_fee), " +
                        "SUM(COALESCE(f.scheme_fee,0)), SUM(COALESCE(f.ecom_fee,0)), " +
                        "SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - COALESCE(f.scheme_fee,0) - COALESCE(f.ecom_fee,0)), " +
                        "SUM(CASE WHEN UPPER(f.card_type) IN ('DEBIT','PREPAID') THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.card_type) = 'CREDIT' THEN f.store_base_currency_amount ELSE 0 END), " +
                        "m.sales_user_id, COUNT(DISTINCT f.card_number), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND (f.dcc IS FALSE OR f.dcc IS NULL) THEN f.store_base_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN 1 END), " +
                        "COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN 1 END) " +
                        "FROM fact_transaction f JOIN dim_merchant m ON f.merchant_id = m.merchant_id AND m.tenant_id = f.tenant_id " +
                        "WHERE f.tenant_id = ? AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, " +
                        "total_msf=EXCLUDED.total_msf, total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_ecom_fee=EXCLUDED.total_ecom_fee, " +
                        "total_margin=EXCLUDED.total_margin, total_debit_prepaid_volume=EXCLUDED.total_debit_prepaid_volume, " +
                        "total_credit_volume=EXCLUDED.total_credit_volume, sales_user_id=EXCLUDED.sales_user_id, " +
                        "unique_customer_count=EXCLUDED.unique_customer_count, " +
                        "dcc_eligible_volume=EXCLUDED.dcc_eligible_volume, dcc_optin_volume=EXCLUDED.dcc_optin_volume, " +
                        "dcc_optout_volume=EXCLUDED.dcc_optout_volume, dcc_eligible_count=EXCLUDED.dcc_eligible_count, " +
                        "dcc_optin_count=EXCLUDED.dcc_optin_count", tenantId)));

                // Merchant x destination with REAL fees (V2026_07_10_03 — the table's
                // promised population, previously never written). Settlement currency,
                // straight off fact with no dim_merchant join so bank-level totals
                // reconcile exactly with fact (unmatched-merchant rows keep NULL
                // merchant_id; the clean-slate DELETE above makes that safe under the
                // plain UNIQUE). NULL destination lands as DOMESTIC per the table's
                // documented convention. MIRRORED in BulkMigrationService.rebuildSummaries
                // — keep both in sync (summary-rebuild-drift rule).
                phase1.add(runAsync(exec, "sum_daily_merchant_destination", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_merchant_destination (tenant_id, business_date, merchant_id, destination, " +
                        "total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN UPPER(COALESCE(f.destination,'DOMESTIC'))='INTERNATIONAL' THEN 'INTERNATIONAL' ELSE 'DOMESTIC' END, " +
                        "COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), SUM(f.interchange_fee), " +
                        "SUM(COALESCE(f.scheme_fee,0)), SUM(COALESCE(f.ecom_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f " +
                        "WHERE f.tenant_id = ? AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN UPPER(COALESCE(f.destination,'DOMESTIC'))='INTERNATIONAL' THEN 'INTERNATIONAL' ELSE 'DOMESTIC' END " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, destination) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_ecom_fee=EXCLUDED.total_ecom_fee, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_mcc", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), " +
                        "SUM(COALESCE(f.scheme_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id AND s.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme " +
                        "ON CONFLICT (tenant_id, business_date, mcc, card_scheme) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_scheme_fee=EXCLUDED.total_scheme_fee, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_scheme", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_scheme (tenant_id, business_date, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), " +
                        "  CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
                        "       THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') " +
                        "       ELSE card_scheme END, " +
                        "COUNT(*), SUM(store_base_currency_amount), SUM(msf), " +
                        "SUM(interchange_fee), SUM(COALESCE(scheme_fee,0)), " +
                        "SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)-COALESCE(scheme_fee,0)-COALESCE(ecom_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, DATE(payment_date), " +
                        "  CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
                        "       THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') ELSE card_scheme END " +
                        "HAVING SUM(store_base_currency_amount) > 0 " +
                        "ON CONFLICT (tenant_id, business_date, card_scheme) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_channel", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_channel (tenant_id, business_date, channel, total_txns, " +
                        "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS'), COUNT(*), SUM(f.store_base_currency_amount), " +
                        "SUM(f.msf), SUM(f.interchange_fee), SUM(COALESCE(f.scheme_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS') " +
                        "ON CONFLICT (tenant_id, business_date, channel) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_terminal", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
                        "total_txns, total_volume, total_base_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, total_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id, COUNT(*), SUM(store_base_currency_amount), " +
                        "SUM(store_base_currency_amount), SUM(msf), SUM(COALESCE(interchange_fee,0)), SUM(COALESCE(scheme_fee,0)), SUM(COALESCE(ecom_fee,0)), " +
                        "SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)-COALESCE(scheme_fee,0)-COALESCE(ecom_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, " +
                        "total_msf=EXCLUDED.total_msf, total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_ecom_fee=EXCLUDED.total_ecom_fee, total_revenue=EXCLUDED.total_revenue",
                        tenantId)));

                phase1.add(runAsync(exec, "sum_daily_finance", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_finance (tenant_id, business_date, " +
                        "dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin, " +
                        "dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin, " +
                        "int_cnt, int_vol, int_msf, int_optin, total_vol, total_msf) " +
                        "SELECT tenant_id, DATE(payment_date), " +
                        "COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') AND dcc IS TRUE THEN store_base_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' AND dcc IS TRUE THEN store_base_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' AND dcc IS TRUE THEN store_base_currency_amount ELSE 0 END), " +
                        "SUM(store_base_currency_amount), SUM(msf) " +
                        "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, DATE(payment_date) " +
                        "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
                        "dom_debit_cnt=EXCLUDED.dom_debit_cnt, dom_debit_vol=EXCLUDED.dom_debit_vol, " +
                        "dom_debit_msf=EXCLUDED.dom_debit_msf, dom_debit_optin=EXCLUDED.dom_debit_optin, " +
                        "dom_credit_cnt=EXCLUDED.dom_credit_cnt, dom_credit_vol=EXCLUDED.dom_credit_vol, " +
                        "dom_credit_msf=EXCLUDED.dom_credit_msf, dom_credit_optin=EXCLUDED.dom_credit_optin, " +
                        "int_cnt=EXCLUDED.int_cnt, int_vol=EXCLUDED.int_vol, int_msf=EXCLUDED.int_msf, int_optin=EXCLUDED.int_optin, " +
                        "total_vol=EXCLUDED.total_vol, total_msf=EXCLUDED.total_msf", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_insight", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_insight (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
                        "card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') " +
                        "     ELSE f.card_scheme END, " +
                        "f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf) " +
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
                        "f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf",
                        tenantId)));

                // sum_daily_full — the fully-dimensional daily SETTLEMENT pre-aggregate
                // WITH real fees. Same fact scan as sum_daily_insight but:
                //   - volume is store_base_currency_amount (settlement), not cardholder
                //   - carries interchange / scheme / ecom / net fee columns
                //   - adds mcc (dim_store) to the grain
                // Grain: day x merchant x store x mcc x channel x destination x scheme
                //        x card_type x is_opt_in (dcc). channel from dim_terminal.type
                //        (COALESCE 'POS'); card_scheme normalized exactly like insight.
                // Fees come straight from fact_transaction (populated by the fee UPDATE
                // in stagingToFactStep, which runs BEFORE this step).
                phase1.add(runAsync(exec, "sum_daily_full", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_full (tenant_id, business_date, merchant_id, store_id, mcc, " +
                        "channel, destination, card_scheme, card_type, is_opt_in, " +
                        "total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, " +
                        "total_net_revenue, dcc_optin_count) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, st.mcc, " +
                        "COALESCE(t.type,'POS'), f.destination, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') " +
                        "     ELSE f.card_scheme END, " +
                        "f.card_type, f.dcc, " +
                        "COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), " +
                        "SUM(COALESCE(f.interchange_fee,0)), SUM(COALESCE(f.scheme_fee,0)), SUM(COALESCE(f.ecom_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)), " +
                        "COUNT(CASE WHEN f.dcc IS TRUE THEN 1 END) " +
                        "FROM fact_transaction f " +
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "LEFT JOIN dim_store st ON f.store_id=st.store_id AND st.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, st.mcc, " +
                        "COALESCE(t.type,'POS'), f.destination, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
                        "f.card_type, f.dcc " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_ecom_fee=EXCLUDED.total_ecom_fee, total_net_revenue=EXCLUDED.total_net_revenue, " +
                        "dcc_optin_count=EXCLUDED.dcc_optin_count",
                        tenantId)));

                // sum_daily_local_debit_bin — the Local Debit Bank Dashboard
                // pre-aggregate: day x merchant x 6-digit BIN, restricted to
                // DOMESTIC DEBIT rows only. Same source, merchant rule, signed
                // settlement volume/msf, and card_type normalization as
                // sum_daily_full, so matched banks + the query-time "Other
                // Banks" bucket reconcile exactly with that table's
                // DOMESTIC x DEBIT cell. Strict destination='DOMESTIC' —
                // NULL/UNMAPPED tokens must not silently count as local.
                // Bank names are NOT stored here: the dashboard joins
                // ref_tenant_bin_bank at query time, so a BIN re-upload
                // re-labels all history with no rebuild. PANs not starting
                // with 6 clear digits land in the visible '??????' bucket.
                // Any change here MUST be mirrored in
                // BulkMigrationService.rebuildSummaries and
                // BackfillIngestionService.
                phase1.add(runAsync(exec, "sum_daily_local_debit_bin", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_local_debit_bin (tenant_id, business_date, merchant_id, bin6, " +
                        "total_txns, total_volume, total_msf) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN f.card_number ~ '^[0-9]{6}' THEN LEFT(f.card_number,6) ELSE '??????' END, " +
                        "COUNT(*), SUM(f.store_base_currency_amount), SUM(COALESCE(f.msf,0)) " +
                        "FROM fact_transaction f " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL " +
                        "AND UPPER(COALESCE(NULLIF(TRIM(f.card_type),''),'')) = 'DEBIT' " +
                        "AND f.destination = 'DOMESTIC' " +
                        "AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN f.card_number ~ '^[0-9]{6}' THEN LEFT(f.card_number,6) ELSE '??????' END " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, bin6) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, " +
                        "total_msf=EXCLUDED.total_msf",
                        tenantId)));

                // sum_daily_explorer — the Data Explorer history pre-aggregate.
                // Same fact scan as sum_daily_full but at the EXPLORER grain:
                //   day x merchant x store x terminal x transaction_type x scheme
                //     x card_type x destination x channel x txn_currency x is_opt_in
                // Carries BOTH amount bases (cardholder txn_currency_amount and
                // settlement store_base_currency_amount) plus msf/vat/settled/
                // interchange/scheme_fee so the Data Explorer can serve every
                // measure it previously read from staging — but historically.
                // Row-level identifiers (arn/rrn/card_number) are deliberately
                // NOT here; the Transactions page owns row grain. Clean-slate
                // DELETE above covers this table, so the ON CONFLICT clause is a
                // belt-and-braces no-op in practice (NULL dim values never match
                // in a UNIQUE constraint — same accepted behavior as
                // sum_daily_full, made safe by the preceding DELETE).
                phase1.add(runAsync(exec, "sum_daily_explorer", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_explorer (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
                        "transaction_type, card_scheme, card_type, destination, channel, txn_currency, store_base_currency, is_opt_in, " +
                        "total_txns, total_txn_currency_amount, total_base_volume, total_msf, total_vat, total_settled, " +
                        "total_interchange, total_scheme_fee) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "f.transaction_type, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') " +
                        "     ELSE f.card_scheme END, " +
                        "f.card_type, f.destination, COALESCE(t.type,'POS'), f.txn_currency, f.store_base_currency, f.dcc, " +
                        "COUNT(*), SUM(COALESCE(f.txn_currency_amount,0)), SUM(COALESCE(f.store_base_currency_amount,0)), " +
                        "SUM(COALESCE(f.msf,0)), SUM(COALESCE(f.vat,0)), SUM(COALESCE(f.total_amount_settled,0)), " +
                        "SUM(COALESCE(f.interchange_fee,0)), SUM(COALESCE(f.scheme_fee,0)) " +
                        "FROM fact_transaction f " +
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "f.transaction_type, " +
                        "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                        "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
                        "f.card_type, f.destination, COALESCE(t.type,'POS'), f.txn_currency, f.store_base_currency, f.dcc " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_txn_currency_amount=EXCLUDED.total_txn_currency_amount, " +
                        "total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, total_vat=EXCLUDED.total_vat, " +
                        "total_settled=EXCLUDED.total_settled, total_interchange=EXCLUDED.total_interchange, " +
                        "total_scheme_fee=EXCLUDED.total_scheme_fee, store_base_currency=EXCLUDED.store_base_currency",
                        tenantId)));

                // Merchant attributes serialized into one task to prevent B-tree deadlocks
                phase1.add(runAsync(exec, "attr-ALL", () -> {
                    int totalRows = 0;
                    final String schemeExpr =
                        "UPPER(CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
                        "          THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') " +
                        "          ELSE card_scheme END)";
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'CARD_SCHEME', " + schemeExpr + ", COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), " + schemeExpr +
                        " ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    for (String ac : new String[]{"CARD_TYPE:card_type","DESTINATION:destination","TRANSACTION_TYPE:transaction_type"}) {
                        String[] parts = ac.split(":");
                        totalRows += jdbcTemplate.update(String.format(
                            "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                            "SELECT tenant_id, merchant_id, DATE(payment_date), '%s', UPPER(COALESCE(%s,'UNKNOWN')), COUNT(*), SUM(store_base_currency_amount) " +
                            "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN %s " +
                            "GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(COALESCE(%s,'UNKNOWN')) " +
                            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
                            parts[0], parts[1], dateScope, parts[1]), tenantId);
                    }
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND transaction_date IS NOT NULL AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date) " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    // Clear the whole TXN_SIZE_BUCKET slice for this date scope before
                    // reinserting. Previously only the legacy '1K+' label was deleted,
                    // which was enough while the band labels were fixed constants. Now
                    // that bands are configurable per country, a relabelled or retuned
                    // band would otherwise leave its old rows behind and double-count
                    // (e.g. a BH tenant's historical '< 50' alongside its new '< 5').
                    jdbcTemplate.update(
                        "DELETE FROM sum_daily_merchant_attribute WHERE tenant_id=? AND business_date IN " + dateScope +
                        " AND attribute_type='TXN_SIZE_BUCKET'", tenantId);
                    // TICKET-SIZE BUCKETS (config-driven since 2026-08-11). These were
                    // the hardcoded constants 50/100/250/500/1000/5000 compared raw
                    // against the settlement amount — AED-shaped numbers. 50 BHD is a
                    // large ticket and 50 EGP is a trivial one, so the same band meant
                    // three different things across three tenants and the distribution
                    // was not comparable to anything. Bands now come from
                    // ticket_size_bucket, per country (AE keeps its historical values,
                    // so the UAE tenant is unchanged), with a per-tenant override.
                    // A transaction whose amount matches no band is skipped rather
                    // than dumped into a catch-all, so a gap in the configuration is
                    // visible as a missing row instead of a wrong one.
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT ft.tenant_id, ft.merchant_id, DATE(ft.payment_date), 'TXN_SIZE_BUCKET', tb.label, " +
                        "       COUNT(*), SUM(ft.store_base_currency_amount) " +
                        "FROM fact_transaction ft " +
                        "LEFT JOIN tenant tn ON tn.tenant_id = ft.tenant_id " +
                        "CROSS JOIN LATERAL ( " +
                        "  SELECT b.label FROM ticket_size_bucket b " +
                        "  WHERE b.country_code = COALESCE(tn.home_country_code,'AE') " +
                        "    AND (b.tenant_id IS NULL OR b.tenant_id = ft.tenant_id) " +
                        "    AND (b.min_amount IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= b.min_amount) " +
                        "    AND (b.max_amount IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) <  b.max_amount) " +
                        "  ORDER BY (b.tenant_id IS NOT NULL) DESC, b.seq ASC LIMIT 1 " +
                        ") tb " +
                        "WHERE ft.tenant_id=? AND ft.merchant_id IS NOT NULL AND DATE(ft.payment_date) IN " + dateScope +
                        " GROUP BY ft.tenant_id, ft.merchant_id, DATE(ft.payment_date), tb.label " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'COUNTRY', UPPER(TRIM(txn_currency)), COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope +
                        " AND UPPER(destination) = 'INTERNATIONAL' AND NULLIF(TRIM(txn_currency), '') IS NOT NULL " +
                        "GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(TRIM(txn_currency)) HAVING COUNT(*) > 0 " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    return totalRows;
                }));

                phase1.add(runAsync(exec, "sum_monthly_card", () ->
                    jdbcTemplate.update("INSERT INTO sum_monthly_card (tenant_id, merchant_id, month_key, card_number, visit_count, total_spend) " +
                        "SELECT tenant_id, merchant_id, CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER), card_number, COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER) IN " + monthScope +
                        " GROUP BY tenant_id, merchant_id, TO_CHAR(payment_date,'YYYYMM'), card_number " +
                        "ON CONFLICT (tenant_id, merchant_id, month_key, card_number) DO UPDATE SET " +
                        "visit_count=EXCLUDED.visit_count, total_spend=EXCLUDED.total_spend", tenantId)));

                java.util.concurrent.CompletableFuture.allOf(phase1.toArray(new java.util.concurrent.CompletableFuture[0])).join();

                java.util.List<java.util.concurrent.CompletableFuture<Void>> phase2 = new java.util.ArrayList<>();
                phase2.add(runAsync(exec, "sum_monthly_bank", () ->
                    jdbcTemplate.update("INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_base_volume, total_msf, " +
                        "total_interchange, total_scheme_fee, total_ecom_fee, total_vat, total_net_revenue) " +
                        "SELECT tenant_id, CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER), SUM(total_txns), SUM(total_volume), SUM(COALESCE(total_base_volume,0)), " +
                        "SUM(total_msf), SUM(total_interchange), SUM(total_scheme_fee), SUM(COALESCE(total_ecom_fee,0)), SUM(total_vat), SUM(total_net_revenue) " +
                        "FROM sum_daily_bank WHERE tenant_id=? AND CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER) IN " + monthScope +
                        " GROUP BY tenant_id, TO_CHAR(business_date,'YYYYMM') " +
                        "ON CONFLICT (tenant_id, month_key) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, total_ecom_fee=EXCLUDED.total_ecom_fee, " +
                        "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));
                // sum_monthly_insight — month-grain rollup of sum_daily_insight (phase1).
                // Powers WIDE-range Explorer/Business queries: a year reads ~12 month
                // rows per dimensional combo instead of 365 day rows. Additive SUMs, so
                // monthly = SUM(daily) reconciles exactly. Mirrors the daily grain with
                // business_date replaced by month_key (YYYYMM).
                phase2.add(runAsync(exec, "sum_monthly_insight", () ->
                    jdbcTemplate.update("INSERT INTO sum_monthly_insight (tenant_id, month_key, merchant_id, store_id, terminal_id, " +
                        "card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf) " +
                        "SELECT tenant_id, CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER), merchant_id, store_id, terminal_id, " +
                        "card_scheme, card_type, destination, channel, is_opt_in, " +
                        "SUM(total_txns), SUM(total_volume), SUM(total_msf) " +
                        "FROM sum_daily_insight WHERE tenant_id=? AND CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER) IN " + monthScope +
                        " GROUP BY tenant_id, TO_CHAR(business_date,'YYYYMM'), merchant_id, store_id, terminal_id, " +
                        "card_scheme, card_type, destination, channel, is_opt_in " +
                        "ON CONFLICT (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf",
                        tenantId)));
                phase2.add(runAsync(exec, "top_spending_customer", () ->
                    jdbcTemplate.update("WITH DailyCustSpend AS (SELECT tenant_id, merchant_id, DATE(payment_date) as b_date, card_number, " +
                        "SUM(store_base_currency_amount) as total_spend FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number), " +
                        "Ranked AS (SELECT *, ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn FROM DailyCustSpend) " +
                        "UPDATE sum_daily_merchant s SET top_spending_customer_id=r.card_number, top_spending_amount=r.total_spend " +
                        "FROM Ranked r WHERE s.tenant_id=r.tenant_id AND s.merchant_id=r.merchant_id AND s.business_date=r.b_date AND r.rn=1 AND s.tenant_id = ?",
                        tenantId, tenantId)));
                java.util.concurrent.CompletableFuture.allOf(phase2.toArray(new java.util.concurrent.CompletableFuture[0])).join();

            } finally { exec.shutdown(); }

            log.info(String.format("populateSummary completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
            } finally {
                if (lockConnFinal != null) {
                    try (java.sql.PreparedStatement ps = lockConnFinal.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, lockKey);
                        try (java.sql.ResultSet rs = ps.executeQuery()) { rs.next(); }
                    } catch (Exception unlockErr) {
                        log.warn("pg_advisory_unlock failed (non-fatal): {}", unlockErr.getMessage());
                    }
                    try { lockConnFinal.close(); } catch (Exception ignore) {}
                }
            }
        };
    }

    private static java.util.concurrent.CompletableFuture<Void> runAsync(
            java.util.concurrent.ExecutorService exec, String name,
            java.util.function.Supplier<Integer> work) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            long t = System.currentTimeMillis();
            try {
                int rows = work.get();
                org.slf4j.LoggerFactory.getLogger(TransactionJobConfig.class).warn(
                    "  [populateSummary] {} {} rows in {}s",
                    String.format("%-25s", name), rows,
                    String.format("%.2f", (System.currentTimeMillis() - t) / 1000.0));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(TransactionJobConfig.class).error(
                    "  [populateSummary] {} FAILED in {}s: {}",
                    String.format("%-25s", name),
                    String.format("%.2f", (System.currentTimeMillis() - t) / 1000.0),
                    e.getMessage());
                throw e;
            }
        }, exec);
    }

    @Bean public Step calculateBusinessMetricsStep(Tasklet calculateBusinessMetricsTasklet) {
        return new StepBuilder("calculateBusinessMetricsStep", jobRepository)
            .tasklet(calculateBusinessMetricsTasklet, transactionManager)
            .transactionAttribute(noTxn()).listener(mdcStepListener).build();
    }

    @Bean @StepScope
    public Tasklet calculateBusinessMetricsTasklet(
            @Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("${acquira.retention.snapshot-days:90}") int snapshotRetentionDays) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            if (distinctDates.isEmpty()) {
                log.info("businessMetrics: no dates to process - skipping");
                return RepeatStatus.FINISHED;
            }
            String dateScope = buildSafeDateInList(distinctDates);

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
                "WHERE m.tenant_id = ? " +
                "AND m.merchant_id IN (SELECT DISTINCT merchant_id FROM fact_transaction WHERE tenant_id = ? " +
                "  AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope + ") " +
                "GROUP BY m.tenant_id, m.merchant_id, d.target_date " +
                "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET " +
                "first_txn_date=EXCLUDED.first_txn_date, last_txn_date=EXCLUDED.last_txn_date, " +
                "last_7d_cnt=EXCLUDED.last_7d_cnt, last_7d_value=EXCLUDED.last_7d_value, " +
                "last_30d_cnt=EXCLUDED.last_30d_cnt, last_30d_value=EXCLUDED.last_30d_value, " +
                "status=EXCLUDED.status, status_change_date=EXCLUDED.status_change_date",
                tenantId, tenantId);

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
            .transactionAttribute(noTxn()).listener(mdcStepListener).build();
    }

    @Bean @StepScope
    public Tasklet scoreMlTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
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
            .transactionAttribute(noTxn()).listener(mdcStepListener).build();
    }

    @Bean @StepScope
    public Tasklet computeSegmentsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
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
            .transactionAttribute(noTxn()).listener(mdcStepListener).build();
    }

    @Bean @StepScope
    public Tasklet calculateDailyDashboardMetricsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantIdObj) {
        return (contribution, chunkContext) -> {
            if (tenantIdObj == null) return RepeatStatus.FINISHED;
            Integer tenantId = tenantIdObj.intValue();
            long start = System.currentTimeMillis();

            // PERF FIX: derive months from distinctDates, avoiding a second stg_trnx_raw scan.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            java.util.Set<String> monthSet = new java.util.LinkedHashSet<>();
            for (java.sql.Date d : distinctDates) {
                java.time.LocalDate ld = d.toLocalDate();
                monthSet.add(String.format("%04d-%02d", ld.getYear(), ld.getMonthValue()));
            }

            int totalSaved = 0;
            for (String monthYear : monthSet) {
                String[] parts = monthYear.split("-");
                int year = Integer.parseInt(parts[0]); int month = Integer.parseInt(parts[1]);
                LocalDate monthStart = LocalDate.of(year, month, 1);
                LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

                List<SumDailyMerchant> dailyRecs = dailyMerchantRepo.findByTenantIdAndDateRange(tenantId, monthStart, monthEnd);
                if (dailyRecs.isEmpty()) continue;

                // FIX: clean-slate this month's monthly-merchant-metrics before rebuild.
                // sum_daily_merchant was just cleanly rebuilt in populateSummary, so
                // deleting the month here and re-deriving guarantees no orphan merchant
                // rows survive from an earlier upload that touched a different day of
                // the same month. month_year is the YYYY-MM VARCHAR key.
                int delMonthly = jdbcTemplate.update(
                    "DELETE FROM sum_monthly_merchant_metrics WHERE tenant_id = ? AND month_year = ?",
                    tenantId, monthYear);
                if (delMonthly > 0) log.warn("  [dashboardMetrics] clean-slate {} monthly rows for {}", delMonthly, monthYear);

                java.util.Map<Long, List<SumDailyMerchant>> grouped = dailyRecs.stream()
                        .collect(java.util.stream.Collectors.groupingBy(SumDailyMerchant::getMerchantId));

                java.util.Map<Long, SumMonthlyMerchantMetrics> existingByMerchant = new java.util.HashMap<>();
                try {
                    java.util.List<SumMonthlyMerchantMetrics> existingRows = monthlyMetricsRepo.findAllByTenantAndMonth(tenantId, monthYear);
                    for (SumMonthlyMerchantMetrics e : existingRows) existingByMerchant.put(e.getMerchantId(), e);
                } catch (Exception ex) {
                    log.warn("bulk fetch of monthly metrics failed, falling back: {}", ex.getMessage());
                    for (Long mId : grouped.keySet()) {
                        monthlyMetricsRepo.findByMerchantAndMonth(tenantId, mId, monthYear)
                            .ifPresent(e -> existingByMerchant.put(mId, e));
                    }
                }

                java.util.List<SumMonthlyMerchantMetrics> toSave = new java.util.ArrayList<>(grouped.size());
                for (java.util.Map.Entry<Long, List<SumDailyMerchant>> entry : grouped.entrySet()) {
                    Long merchantId = entry.getKey();
                    SumMonthlyMerchantMetrics newMetrics = merchantMetricCalculator.calculateMetrics(
                        entry.getValue(), tenantId, merchantId, monthYear);
                    SumMonthlyMerchantMetrics existing = existingByMerchant.get(merchantId);
                    if (existing != null) {
                        newMetrics.setMetricId(existing.getMetricId());
                        newMetrics.setCreatedAt(existing.getCreatedAt());
                    }
                    toSave.add(newMetrics);
                }
                if (!toSave.isEmpty()) {
                    monthlyMetricsRepo.saveAll(toSave);
                    totalSaved += toSave.size();
                }
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

    // PERF FIX: uses static ISO_DATE_PATTERN - no Pattern.compile() per call.
    private static String buildSafeDateInList(java.util.List<java.sql.Date> dates) {
        if (dates == null || dates.isEmpty()) return "(NULL)";
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (java.sql.Date d : dates) {
            if (d == null) continue;
            String s = d.toString();
            if (!ISO_DATE_PATTERN.matcher(s).matches()) {
                throw new IllegalStateException("Refusing to inline non-ISO date: '" + s + "'");
            }
            if (!first) sb.append(',');
            sb.append("DATE '").append(s).append("'");
            first = false;
        }
        sb.append(")");
        return sb.toString();
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
            if (v.contains("T")) return java.time.LocalDateTime.parse(v);
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
