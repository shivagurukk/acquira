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
            @org.springframework.beans.factory.annotation.Qualifier("autoCreateDimensionsStep") Step autoCreateDimensionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {
        return new JobBuilder("transactionLoadJob", jobRepository)
                .start(ensurePartitionsStep).next(splitExcelStep).next(cleanTargetDayStep)
                .next(masterIngestStep).next(autoCreateDimensionsStep)
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
            @org.springframework.beans.factory.annotation.Qualifier("autoCreateDimensionsStep") Step autoCreateDimensionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("scoreMlStep") Step scoreMlStep,
            @org.springframework.beans.factory.annotation.Qualifier("computeSegmentsStep") Step computeSegmentsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {
        return new JobBuilder("dbPullTransactionJob", jobRepository)
                .start(ensurePartitionsStep).next(autoCreateDimensionsStep)
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

            // PERF FIX: decimalDivisor() - cached BigDecimal, no per-row allocation.
            // NOTE: currency-CODE resolution (ISO-numeric -> 'AED' etc.) still runs for BOTH
            // CMM and AMS so the stored currency label is correct. Only the numeric DIVISION
            // is conditional: CMM divides by the currency's decimal_notation_value; AMS does not.
            String rawTxnCcy = item.getTxnCurrency();
            if (rawTxnCcy != null && !rawTxnCcy.isBlank()) {
                int txnDecVal = resolveDecimal(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal, "Txn");
                String txnCode = resolveCurrencyCode(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal);
                if (txnCode != null) item.setTxnCurrency(txnCode);
                if (!rawAmounts && item.getTxnCurrencyAmount() != null) {
                    item.setTxnCurrencyAmount(
                        item.getTxnCurrencyAmount().divide(decimalDivisor(txnDecVal), 2, java.math.RoundingMode.HALF_UP));
                }
            }

            int stlDecVal = resolveDecimal(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal, "Store base");
            String stlCode = resolveCurrencyCode(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal);
            if (stlCode != null) item.setStoreBaseCurrency(stlCode);
            if (!rawAmounts && item.getStoreBaseCurrencyAmount() != null) {
                item.setStoreBaseCurrencyAmount(
                    item.getStoreBaseCurrencyAmount().divide(decimalDivisor(stlDecVal), 2, java.math.RoundingMode.HALF_UP));
            }
            item.setTotalAmountSettled(null);

            if (!rawAmounts && item.getInterchangeFee() != null) {
                item.setInterchangeFee(item.getInterchangeFee().divide(BD_10000, 4, java.math.RoundingMode.HALF_UP));
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
            "interchange_fee, destination, tenant_id, load_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
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
                log.info("stagingToFact: no dates in staging - skipping");
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
                if (uploadSchemes.isEmpty()) {
                    log.warn("APPEND mode: no card_scheme values in staging - skipping fact delete.");
                } else {
                    String placeholders = uploadSchemes.stream().map(x -> "?")
                        .collect(java.util.stream.Collectors.joining(","));
                    Object[] args = new Object[uploadSchemes.size() + 1];
                    args[0] = tenantId;
                    for (int i = 0; i < uploadSchemes.size(); i++) args[i + 1] = uploadSchemes.get(i);
                    int deleted = jdbcTemplate.update(
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " AND UPPER(TRIM(card_scheme)) IN (" + placeholders + ")", args);
                    log.info(String.format("APPEND mode: deleted %d fact rows for scheme(s) %s in %.1fs",
                        deleted, uploadSchemes, (System.currentTimeMillis() - tDel) / 1000.0));
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
                "store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, interchange_fee, destination) " +
                "SELECT stg.tenant_id, " +
                "COALESCE(s.merchant_id, m.merchant_id, s2.merchant_id) AS merchant_id, " +
                "COALESCE(s.store_id, s2.store_id) AS store_id, t.terminal_id, " +
                "stg.arn, stg.rrn_number, stg.card_number, stg.auth_code, " +
                "stg.payment_date, stg.transaction_date, stg.batch_number, stg.transaction_type, " +
                // SIGNED VOLUME (2026-07-18, reverses 2026-07-08 option B): refunds (RFND)
                // are stored NEGATIVE so fact + all summaries net refunds out of volume,
                // matching the raw feed / MIS reconciliation basis. Sign is forced from
                // transaction_type (not trusted from the feed): purchases +ABS, refunds -ABS.
                // msf/vat/interchange remain ABS; total_amount_settled stays raw SIGNED.
                "stg.card_scheme, stg.card_type, stg.card_product_code, stg.dcc, stg.txn_currency, " +
                "CASE WHEN UPPER(TRIM(COALESCE(stg.transaction_type,''))) IN ('RFND','REFUND') " +
                "     THEN -ABS(stg.txn_currency_amount) ELSE ABS(stg.txn_currency_amount) END, " +
                "stg.store_base_currency, " +
                "CASE WHEN UPPER(TRIM(COALESCE(stg.transaction_type,''))) IN ('RFND','REFUND') " +
                "     THEN -ABS(stg.store_base_currency_amount) ELSE ABS(stg.store_base_currency_amount) END, " +
                "ABS(stg.msf), ABS(stg.vat), stg.total_amount_settled, ABS(stg.interchange_fee), stg.destination " +
                "FROM stg_trnx_raw stg " +
                "LEFT JOIN dim_store s ON s.tenant_id = stg.tenant_id AND s.sid = NULLIF(TRIM(stg.sid), '') " +
                "LEFT JOIN dim_merchant m ON m.tenant_id = stg.tenant_id AND m.mid = NULLIF(TRIM(stg.mid), '') " +
                "LEFT JOIN dim_terminal t ON t.tenant_id = stg.tenant_id " +
                "  AND t.tid = NULLIF(TRIM(stg.tid), '') AND (t.store_id = s.store_id OR s.store_id IS NULL) " +
                "LEFT JOIN dim_store s2 ON s2.tenant_id = stg.tenant_id AND s2.store_id = t.store_id " +
                "WHERE stg.tenant_id = ? AND stg.payment_date IS NOT NULL";
            int inserted = jdbcTemplate.update(sql, tenantId);
            log.info(String.format("Inserted %d fact rows in %.1fs", inserted, (System.currentTimeMillis() - tIns) / 1000.0));

            Integer matched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? " +
                "AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope, Integer.class, tenantId);
            Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? " +
                "AND DATE(payment_date) IN " + dateScope, Integer.class, tenantId);
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
            int storeFixed = jdbcTemplate.update(
                "UPDATE fact_transaction f SET store_id = s.store_id " +
                "FROM dim_store s, stg_trnx_raw stg " +
                "WHERE f.tenant_id = ? AND s.tenant_id = ? AND stg.tenant_id = ? " +
                "AND f.store_id IS NULL AND f.merchant_id IS NOT NULL " +
                "AND s.merchant_id = f.merchant_id " +
                "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                "AND s.internal_id = CONCAT('STORE_', stg.mid) " +
                "AND DATE(f.payment_date) IN " + dateScope,
                tenantId, tenantId, tenantId);
            int termFixed = jdbcTemplate.update(
                "UPDATE fact_transaction f SET terminal_id = t.terminal_id " +
                "FROM dim_terminal t, stg_trnx_raw stg " +
                "WHERE f.tenant_id = ? AND t.tenant_id = ? AND stg.tenant_id = ? " +
                "AND f.terminal_id IS NULL AND f.store_id IS NOT NULL " +
                "AND t.store_id = f.store_id " +
                "AND f.payment_date = stg.payment_date AND f.arn = stg.arn " +
                "AND t.internal_id = CONCAT('TERM_', stg.mid) " +
                "AND DATE(f.payment_date) IN " + dateScope,
                tenantId, tenantId, tenantId);
            if (storeFixed + termFixed > 0) {
                log.info(String.format("Fix-up: %d store_ids, %d terminal_ids in %.1fs",
                    storeFixed, termFixed, (System.currentTimeMillis() - tFix) / 1000.0));
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
                "  ecom_fee        = r.computed_ecom " +
                "FROM ( " +
                "  SELECT ft.transaction_id, ft.payment_date, " +
                // REFUND RULE (2026-07-08, business-confirmed): refunds carry ZERO
                // interchange and ZERO scheme fee. Feed transaction_type = 'RFND'.
                // Ecom flat fee untouched.
                // interchange: refund => 0; else matched rate (+cap) else flat 1.85% fallback
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         WHEN lr.interchange_pct IS NULL " +
                "         THEN 0.018500 * ABS(COALESCE(ft.store_base_currency_amount,0)) " +
                "         ELSE LEAST(lr.interchange_pct * ABS(COALESCE(ft.store_base_currency_amount,0)), " +
                "                    COALESCE(lr.cap_amount, 999999999999)) END AS computed_ic, " +
                // scheme fee: refund => 0; else matched scheme rate * ABS(settlement); wildcard fallback guarantees a row
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         ELSE (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))) END AS computed_scheme, " +
                // ecom flat fee: 0.18 on ECOM channel, else NULL (COALESCE'd to 0 in nets)
                "    CASE WHEN ch.channel = 'ECOM' THEN 0.18 ELSE NULL END AS computed_ecom " +
                "  FROM fact_transaction ft " +
                "  LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id " +
                "  LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id " +
                // SCHEME RESOLUTION FIX (2026-07-07): space-insensitive match so feed
                // variants like 'MASTER CARD' resolve to ref_card_scheme 'MasterCard'.
                // Without this, ~42% of rows (MASTER CARD) got group_name NULL -> wrong
                // interchange AND zero scheme fee. Strips spaces on BOTH sides.
                "  LEFT JOIN ref_card_scheme rcs " +
                // TIER FIX (2026-07-07): resolve tier + scheme group from the GRANULAR
                // product code (card_product_code = feed 'Card Type': VIPM/MCPM/MCDB...),
                // which carries the Premium/Standard signal. card_scheme is only the
                // network name ('Visa'/'Master Card') and always resolved Standard.
                // Match the product code first; fall back to the network name so rows
                // with a blank product code still resolve the scheme GROUP.
                "    ON REPLACE(UPPER(TRIM(rcs.code)),' ','') = REPLACE(UPPER(TRIM(COALESCE(NULLIF(TRIM(ft.card_product_code),''), ft.card_scheme))),' ','') " +
                "    OR REPLACE(UPPER(TRIM(rcs.name)),' ','') = REPLACE(UPPER(TRIM(COALESCE(NULLIF(TRIM(ft.card_product_code),''), ft.card_scheme))),' ','') " +
                // derive channel ONCE, reused by both rate LATERALs and the ecom CASE
                "  CROSS JOIN LATERAL (SELECT CASE WHEN UPPER(TRIM(COALESCE(dt.type,''))) " +
                "         IN ('ECOM PROFILE','MPGS','PAY BY LINK','PAY ON') THEN 'ECOM' ELSE 'POS' END AS channel) ch " +
                // derive refund flag ONCE, reused by both computed_ic and computed_scheme
                // (feed transaction_type carries exactly 'RFND' for refunds)
                "  CROSS JOIN LATERAL (SELECT (UPPER(TRIM(COALESCE(ft.transaction_type,''))) = 'RFND') AS is_refund) rf " +
                // derive mcc sector ONCE (was a correlated subquery inside the LATERAL)
                "  LEFT JOIN mcc_sector_map msm ON msm.tenant_id = ft.tenant_id AND msm.mcc = ds.mcc " +
                // PERF (2026-07-14): lateral split into MCC-keyed + wildcard branches so
                // the planner drives each via an index instead of scanning all ~365
                // candidate rows per transaction (was 9.4M heap blocks / ~270s per window).
                // Branch 1 uses idx_interchange_rate_local_mcc (tenant_id, mcc);
                // Branch 2 uses idx_interchange_rate_local_generic (partial, mcc IS NULL).
                // Same candidate set, same priority pick - semantics unchanged.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT ilr.interchange_pct, ilr.cap_amount FROM ( " +
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.tenant_id = ft.tenant_id " +
                "        AND i.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "        AND i.mcc = ds.mcc " +
                "      UNION ALL " +
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.tenant_id = ft.tenant_id " +
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
                "      AND (ilr.min_ticket_aed IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= ilr.min_ticket_aed) " +
                "      AND (ilr.max_ticket_aed IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) <  ilr.max_ticket_aed) " +
                "    ORDER BY ilr.priority DESC, ilr.id ASC LIMIT 1 " +
                "  ) lr ON TRUE " +
                // SCHEME FEE: match dest x channel; prefer scheme-specific row, then the
                // scheme_group IS NULL wildcard (seeded 2026-07-07) so EVERY scheme -
                // incl. Amex / MASTER CARD / unmapped - gets a rate instead of 0.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT s.fee_pct FROM scheme_fee_rate s " +
                "    WHERE s.tenant_id = ft.tenant_id " +
                "      AND s.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "      AND s.channel = ch.channel " +
                "      AND (s.scheme_group IS NULL OR s.scheme_group = COALESCE(rcs.group_name,'')) " +
                "    ORDER BY (s.scheme_group IS NOT NULL) DESC LIMIT 1 " +
                "  ) sfr ON TRUE " +
                "  WHERE ft.tenant_id = ? AND " + dateRangeFt + " AND DATE(ft.payment_date) IN " + dateScope +
                " ) r " +
                "WHERE f.transaction_id = r.transaction_id AND f.payment_date = r.payment_date",
                tenantId);
            log.info(String.format("Fee computation (single-pass): %d rows in %.1fs",
                feeRows, (System.currentTimeMillis() - tFee) / 1000.0));

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
                        "sum_daily_explorer",
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
                        "FROM fact_transaction f JOIN dim_merchant m ON f.merchant_id = m.merchant_id " +
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

                phase1.add(runAsync(exec, "sum_daily_mcc", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), " +
                        "SUM(COALESCE(f.scheme_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id " +
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
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
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
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
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
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
                        "LEFT JOIN dim_store st ON f.store_id=st.store_id " +
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
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
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
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'TXN_SIZE_BUCKET', " +
                        "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
                        "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
                        "WHEN store_base_currency_amount < 1000 THEN '500-1K' ELSE '1K+' END, COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), " +
                        "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
                        "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
                        "WHEN store_base_currency_amount < 1000 THEN '500-1K' ELSE '1K+' END " +
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
                "  AND f.payment_date >= (CURRENT_DATE - INTERVAL '60 days') " +
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
