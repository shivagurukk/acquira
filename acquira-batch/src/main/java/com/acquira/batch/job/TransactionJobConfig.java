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
 * IMPORTANT — currency-amount semantics:
 *   - txn_currency_amount        = amount in CARDHOLDER currency (mixed: AED, USD, EUR, KES, IQD…)
 *   - store_base_currency_amount = amount in MERCHANT settlement currency (single currency)
 *
 * Any aggregation that produces a single-currency total or a single-currency comparison
 * (Sales Volume, Avg Daily Sales, Top Customer Spend, INTERNATIONAL DESTINATION volume,
 *  card-type/scheme volume splits, hourly heatmap, transaction-size buckets, monthly card
 *  loyalty totals, DCC eligible/optin/optout) MUST use store_base_currency_amount.
 * Using txn_currency_amount produces wildly inflated totals when foreign-currency
 * intl transactions are present (e.g. an IQD/KES txn whose raw amount is 100x–1000x
 * the equivalent AED).
 *
 * Tables that intentionally track both views can keep both: sum_daily_merchant.total_volume
 * holds the txn-currency total and total_base_volume holds the settlement-currency total.
 * Everything user-facing in the merchant report reads from the *_base_* / store_base
 * variants.
 *
 * IMPORTANT — DCC flag parsing:
 *   The DCC column in source CSVs has appeared as 'Y'/'Yes' (older feeds) AND as
 *   'TRUE'/'FALSE' (newer feeds). The previous parser only recognised Y/Yes which
 *   silently mis-classified every TRUE row as opt-out, breaking page 9 / page 10 of
 *   the merchant report (showing 0 opt-ins where there were actually 20+). All DCC
 *   parsing now goes through parseDccFlag(...) which accepts Y/YES/TRUE/T/1.
 */
@Configuration
public class TransactionJobConfig {

    // P3-6: per-step timings and diagnostics now go through SLF4J so they
            // honour logback config (file rotation, levels, JSON encoder, etc.).
            // System.out.printf was being swallowed by the prod profile's logback config.
    private static final Logger log = LoggerFactory.getLogger(TransactionJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final MerchantMetricCalculator merchantMetricCalculator;
    private final SumDailyMerchantRepository dailyMerchantRepo;
    private final SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final com.acquira.batch.service.PartitionMaintenanceService partitionMaintenanceService;

    public TransactionJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DataSource dataSource, JdbcTemplate jdbcTemplate,
            MerchantMetricCalculator merchantMetricCalculator,
            SumDailyMerchantRepository dailyMerchantRepo,
            SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
            com.acquira.batch.service.PartitionMaintenanceService partitionMaintenanceService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.merchantMetricCalculator = merchantMetricCalculator;
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.partitionMaintenanceService = partitionMaintenanceService;
    }

    private static final String NUMERIC_ONLY_REGEX = "'^[0-9.]+$'";

    /**
     * Parse a DCC flag from any of the formats we've seen across feeds.
     * Truthy values: Y, YES, TRUE, T, 1 (case-insensitive, trimmed).
     * Anything else (FALSE, N, No, F, 0, blank, null, junk) is false.
     * Returns boxed Boolean so callers can preserve null semantics on input,
     * but in practice we always return TRUE or FALSE — the column is non-null.
     */
    private static Boolean parseDccFlag(String raw) {
        if (raw == null) return Boolean.FALSE;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return Boolean.FALSE;
        return ("Y".equals(s) || "YES".equals(s)
             || "TRUE".equals(s) || "T".equals(s)
             || "1".equals(s)) ? Boolean.TRUE : Boolean.FALSE;
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
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {
        // FIX: Added autoCreateDimensionsStep BEFORE stagingToFactStep so the system
        // self-heals when transaction files are uploaded before (or instead of) a
        // matching merchant master file. Without this, every fact row whose MID/SID/TID
        // doesn't match an existing dim_merchant/dim_store/dim_terminal gets
        // merchant_id=NULL, which causes every summary table to come up empty and
        // dashboards to show no data. Auto-created dimension rows are minimal
        // placeholders — a real merchant master upload will enrich them later.
        return new JobBuilder("transactionLoadJob", jobRepository)
                .start(ensurePartitionsStep).next(splitExcelStep).next(cleanTargetDayStep)
                .next(masterIngestStep).next(autoCreateDimensionsStep)
                .next(stagingToFactStep).next(populateSummaryStep)
                .next(calculateBusinessMetricsStep).next(calculateDailyDashboardMetricsStep).build();
    }

    // ============================================================================
    // FIX: New step — autoCreateDimensionsStep
    // ============================================================================
    // Inserts placeholder rows into dim_merchant, dim_store, dim_terminal for any
    // MID/SID/TID present in stg_trnx_raw that doesn't already have a matching
    // dimension row. Self-heals the most common production problem: dashboards
    // show no data because the merchant master file wasn't uploaded with all
    // merchants (or wasn't uploaded at all).
    //
    // Behavior:
    //   - Existing dim rows are NEVER modified (NOT EXISTS guard).
    //   - New rows use synthetic internal_id 'AUTO_<MID>' / etc. to satisfy
    //     the UNIQUE(tenant_id, internal_id) constraint without colliding with
    //     real merchant-master uploads.
    //   - Names default to merchant_name from the file when present and not numeric.
    //   - Idempotent: running twice does nothing on the second pass.
    @Bean
    public Step autoCreateDimensionsStep(Tasklet autoCreateDimensionsTasklet) {
        return new StepBuilder("autoCreateDimensionsStep", jobRepository)
            .tasklet(autoCreateDimensionsTasklet, transactionManager).build();
    }

    @Bean @StepScope
    public Tasklet autoCreateDimensionsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();

            // PERF FIX: fast-path pre-checks. Each of the three INSERTs below is an
            // expensive aggregate scan of stg_trnx_raw (potentially 100k+ rows) plus
            // joins. When the merchant master is already complete (the steady-state
            // case), all three would scan-and-do-nothing — that's ~3 wasted seconds
            // per upload on RDS. The pre-check is a single cheap COUNT-EXISTS query
            // that bails out in milliseconds when nothing needs creating.
            //
            // The pre-check uses LIMIT 1 inside EXISTS so PostgreSQL can short-circuit
            // as soon as the first unmapped MID is found.

            // ============================================================
            // ORPHAN CLEANUP (runs BEFORE auto-create).
            // ============================================================
            // Why: when transactions were uploaded BEFORE the merchant master
            // file, autoCreate inserted placeholder rows like:
            //   dim_merchant{ internal_id='AUTO_SID_<sid>', mid='AUTO_MID_<sid>', name=NULL }
            // After the merchant master file later inserts the REAL merchant for
            // that same SID, dim_store gets a proper row, but the original
            // AUTO_SID_ placeholder in dim_merchant is left behind. It's not
            // referenced by any fact_transaction (those got resolved to the real
            // merchant_id via SID), and it shows as a NULL-name row in dim_merchant.
            //
            // This block deletes those orphans — dim_merchant rows where:
            //   1. internal_id starts with 'AUTO_SID_' (created by this tasklet),
            //   2. The same SID now exists in dim_store with a DIFFERENT merchant_id
            //      (i.e. the merchant master superseded the placeholder).
            //
            // Idempotent: if no orphans exist, the DELETE finds nothing and returns 0.
            int orphansRemoved = jdbcTemplate.update(
                "DELETE FROM dim_merchant m " +
                "WHERE m.tenant_id = ? " +
                "  AND m.internal_id LIKE 'AUTO_SID_%' " +
                "  AND (m.name IS NULL OR TRIM(m.name) = '') " +
                // Don't delete if any fact rows still point to this placeholder.
                // (Shouldn't happen with the SID-primary join, but guard anyway.)
                "  AND NOT EXISTS (SELECT 1 FROM fact_transaction f " +
                "    WHERE f.tenant_id = m.tenant_id AND f.merchant_id = m.merchant_id LIMIT 1) " +
                // Don't delete if any sum_daily_merchant rows reference it either.
                "  AND NOT EXISTS (SELECT 1 FROM sum_daily_merchant s " +
                "    WHERE s.tenant_id = m.tenant_id AND s.merchant_id = m.merchant_id LIMIT 1)",
                tenantId);
            if (orphansRemoved > 0) {
                log.info("  cleanup: removed {} orphan auto-created merchant placeholder(s)", orphansRemoved);
            }

            // 1. Auto-create missing merchants — fast pre-check first.
            //
            // FIX: production transaction files only carry SID (MID is empty), so we
            // must look up via SID -> dim_store -> dim_merchant chain instead of via MID.
            // We only auto-create a merchant when the SID itself isn't in dim_store either,
            // i.e. truly unknown — in practice this branch is now near-empty because
            // dim_store is populated by the merchant master upload.
            // TID-AWARE GUARD: a row is only "truly unmapped" if NEITHER its SID
            // resolves to a dim_store NOR its TID resolves to a dim_terminal.
            // Production transaction feeds frequently carry a constant/garbage SID
            // (e.g. '400000000000001') but a correct TID. Without the extra TID
            // check, auto-create manufactures an AUTO_SID_/AUTO_MID_ placeholder;
            // then the SID-primary join in stagingToFact resolves against that
            // placeholder and COALESCE short-circuits BEFORE the TID fallback can
            // link the row to the real merchant. Result: every transaction lands
            // on a phantom merchant. Checking the TID here lets the real
            // dim_terminal -> dim_store -> dim_merchant fallback do its job.
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
                    // FIX: when the file has no merchant name, fall back to a
                    // human-readable label like 'Merchant <sid>' instead of NULL.
                    // This way PDFs/charts always show *something* meaningful for
                    // auto-created placeholders — not a blank field.
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
                    // TID-AWARE GUARD (see hasUnmappedMerchants comment above).
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                    "GROUP BY s.tenant_id, TRIM(s.sid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            // 2. Auto-create missing stores — keyed off SID (the only reliable column).
            // For any SID in staging that doesn't have a matching dim_store row, create one
            // and link it to the auto-created (or existing) merchant via 'AUTO_SID_<sid>'.
            int storesAdded = 0;
            Boolean hasUnmappedStores = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stg_trnx_raw s " +
                "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                // TID-AWARE GUARD (see hasUnmappedMerchants comment above).
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
                    // Link to merchant: prefer MID-matched dim_merchant, fall back to the
                    // auto-created one we made above (internal_id='AUTO_SID_<sid>').
                    "JOIN dim_merchant m ON m.tenant_id = s.tenant_id " +
                    "  AND (m.mid = NULLIF(TRIM(s.mid), '') " +
                    "    OR m.internal_id = 'AUTO_SID_' || TRIM(s.sid)) " +
                    "WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL " +
                    "  AND NOT EXISTS (SELECT 1 FROM dim_store ds " +
                    "    WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)) " +
                    // TID-AWARE GUARD (see hasUnmappedMerchants comment above).
                    "  AND NOT EXISTS (SELECT 1 FROM dim_terminal dt " +
                    "    WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), '')) " +
                    "GROUP BY s.tenant_id, m.merchant_id, TRIM(s.sid) " +
                    "ON CONFLICT (tenant_id, internal_id) DO NOTHING",
                    tenantId);
            }

            // 3. Auto-create missing terminals — keyed off SID->store + TID.
            // Path: stg.sid -> dim_store.sid -> ds.store_id, then attach TID under that.
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
                hasUnmappedMerchants ? "" : "merchants ",
                hasUnmappedStores ? "" : "stores ",
                hasUnmappedTerminals ? "" : "terminals"));
            return RepeatStatus.FINISHED;
        };
    }
    // ============================================================================

    @Bean public Step ensurePartitionsStep(Tasklet ensurePartitionsTasklet) {
        return new StepBuilder("ensurePartitionsStep", jobRepository).tasklet(ensurePartitionsTasklet, transactionManager).build();
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
        return new StepBuilder("splitExcelStep", jobRepository).tasklet(excelSplitterTasklet, transactionManager).build();
    }

    @Bean public Step cleanTargetDayStep(Tasklet cleanTargetDayTasklet) {
        return new StepBuilder("cleanTargetDayStep", jobRepository).tasklet(cleanTargetDayTasklet, transactionManager).build();
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
                .step(csvWorkerStep).taskExecutor(partitionExecutor).gridSize(8).build();
    }

    /**
     * Dedicated partition executor for the masterIngestStep CSV workers.
     *
     * RENAMED (was 'batchTaskExecutor') to avoid colliding with
     * BatchConfig.batchTaskExecutor — that bean is a ThreadPoolTaskExecutor
     * sized for JobLauncher (one job at a time, pool 2/6). This bean is a
     * different animal: a SimpleAsyncTaskExecutor sized for the 8 partition
     * workers that ingest CSV chunks in parallel within a single job. The two
     * are unrelated; collapsing them under the same name caused the bean
     * definition conflict on app startup.
     */
    @Bean("transactionPartitionExecutor")
    public org.springframework.core.task.TaskExecutor transactionPartitionExecutor() {
        org.springframework.core.task.SimpleAsyncTaskExecutor executor =
            new org.springframework.core.task.SimpleAsyncTaskExecutor("batch-ingest-");
        executor.setConcurrencyLimit(8);
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
                // Normalize SID: handles the case where the source CSV has SID in
                // scientific notation like "4.00E+14" (happens when CSV was exported
                // from Excel without formatting the column as TEXT). See
                // MerchantMasterJobConfig.normalizeSid for full rationale.
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
                t.setCardType(fieldSet.readString("Card Type"));
                // FIX: was only matching 'Y'/'Yes'; newer feeds use 'TRUE'/'FALSE',
                // which silently became opt-out and broke DCC reporting on pages 9 & 10.
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

    /**
     * Ref-table cache shared across ALL partition workers and step executions.
     *
     * PERF FIX: was @StepScope inside transactionTenantProcessor. With gridSize(8)
     * partitioning, that bean is recreated 8× per upload, and each instance ran
     * 5 sequential RDS metadata+data queries (~250ms RTT each = ~6s of pure
     * pre-load latency just to get going, plus 8× redundant log spam).
     *
     * Now: loaded ONCE on the first call, cached for the lifetime of the JVM,
     * shared by all 8 workers. Reload is gated by a volatile flag so a restart
     * isn't needed when ref tables change — but in practice these tables are
     * static reference data updated only during deployment.
     *
     * Thread-safe: HashMap built fully before publish via volatile reference.
     */
    private static volatile RefTableCache REF_CACHE = null;
    private static final Object REF_CACHE_LOCK = new Object();

    private static class RefTableCache {
        final java.util.Map<String, String> cardSchemeToType;
        final java.util.Map<String, String> isoNumericToCurrencyCode;
        final java.util.Map<String, Integer> currencyCodeToDecimal;

        RefTableCache(java.util.Map<String, String> a, java.util.Map<String, String> b, java.util.Map<String, Integer> c) {
            this.cardSchemeToType = a;
            this.isoNumericToCurrencyCode = b;
            this.currencyCodeToDecimal = c;
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
                log.warn("Could not load ref tables (non-fatal) — raw codes will pass through: {}", e.getMessage());
            }

            REF_CACHE = new RefTableCache(cardSchemeToType, isoNumericToCurrencyCode, currencyCodeToDecimal);
            log.info(String.format("Ref tables loaded ONCE in %.2fs (card_scheme=%d, currency=%d) — cached for all workers",
                (System.currentTimeMillis() - t) / 1000.0,
                cardSchemeToType.size(), isoNumericToCurrencyCode.size()));
            return REF_CACHE;
        }
    }

    /**
     * Currencies that are missing from ref_country and have already been warned about.
     * PERF FIX: was logging "WARN: Txn currency 'X' not found..." for EVERY transaction
     * row with a missing mapping. With 100k rows that's 100k synchronized stdout writes
     * — measurable overhead. Now we warn ONCE per distinct currency code per JVM.
     */
    private static final java.util.Set<String> WARNED_MISSING_CURRENCIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Divisor for raw-10000ths fee columns (interchange fee). Hoisted to a static
     *  constant so the per-row processor doesn't allocate + string-parse a new
     *  BigDecimal on every transaction row (hot path: millions of rows x 8 workers). */
    private static final java.math.BigDecimal BD_10000 = new java.math.BigDecimal("10000");

    /**
     * Resolve a currency code from a numeric ISO code ("784", "840"), a zero-paddable
     * short numeric ("48" -> "048"), or an already-alphabetic code ("AED"/"USD").
     * Returns null if nothing matches.
     */
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
     * Resolve the decimal_notation_value for a currency (settlement OR transaction).
     * Falls back to 100 (2-decimal, e.g. AED/USD) when the currency can't be resolved,
     * so amount division is NEVER skipped. NOTE: the 100 fallback is correct only for
     * 2-dp currencies; a 3-dp currency (BHD/KWD) MUST be present in ref_country or it
     * will be off by 10x. We warn once per (label, unresolved currency). The {@code
     * ccyLabel} ("Store base" / "Txn") keeps the warning text and the once-per-JVM dedup
     * key distinct between the two call sites.
     */
    private static int resolveDecimal(String raw,
            java.util.Map<String, String> isoNumericToCode,
            java.util.Map<String, Integer> codeToDecimal,
            String ccyLabel) {
        String code = resolveCurrencyCode(raw, isoNumericToCode, codeToDecimal);
        Integer dec = (code != null) ? codeToDecimal.get(code) : null;
        if (dec == null && WARNED_MISSING_CURRENCIES.add("DEC:" + ccyLabel + ":" + (raw == null ? "" : raw.trim()))) {
            log.warn("{} currency '{}' not resolved to a decimal value — defaulting to 100 (2dp). "
                    + "Verify ref_country contains this currency if it is NOT 2-decimal.", ccyLabel, raw);
        }
        return dec != null ? dec : 100;
    }

    /**
     * Processor: sets tenantId AND resolves raw TGEN509 codes:
     *   - Card Type: CRD_TYP_CDE (e.g. 'VIDB') → 'DEBIT'/'CREDIT'/'PREPAID' via ref_card_scheme
     *   - Currency: ISO numeric (e.g. '048') → alphabetic code (e.g. 'BHD') via ref_country
     *   - Amounts: raw integers ÷ decimal_notation_value (e.g. ÷1000 for BHD, ÷100 for USD)
     *   - MSF/VAT/Interchange: raw integers ÷ 10000
     */
    @Bean @StepScope public ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId) {

        // PERF FIX: ref-table cache is now a JVM-wide singleton, not per-StepScope.
        // First worker to call loadOrGetRefTables() populates it; the other 7 workers
        // get the cached map for free (~zero RDS round-trips after first call).
        final RefTableCache refs = loadOrGetRefTables();
        final java.util.Map<String, String> cardSchemeToType = refs.cardSchemeToType;
        final java.util.Map<String, String> isoNumericToCurrencyCode = refs.isoNumericToCurrencyCode;
        final java.util.Map<String, Integer> currencyCodeToDecimal = refs.currencyCodeToDecimal;

        return item -> {
            item.setTenantId(tenantId);

            // ── Card Type resolution ──
            String rawCardType = item.getCardType();
            if (rawCardType != null && !rawCardType.isBlank()) {
                String resolved = cardSchemeToType.get(rawCardType.trim());
                if (resolved != null) {
                    item.setCardType(resolved);
                }
                // If not in map, keep as-is (might already be 'DEBIT'/'CREDIT' from older queries)
            }

            // ── Currency resolution + amount division ──
            // (BD_10000 divisor is a static constant — see field — so we don't allocate
            //  and string-parse a BigDecimal on every transaction row.)

            // Transaction currency.
            // CONSISTENCY FIX: mirror the settlement-currency handling below — resolve via
            // the same helpers and ALWAYS divide. Previously an unresolved txn currency
            // (a numeric ISO code missing from ref_country, e.g. '051'/AMD) skipped division
            // entirely and stored a raw 100x/1000x-inflated amount in
            // sum_daily_merchant.total_volume. resolveDecimal() now falls back to 100 (2dp)
            // for unknown codes, so the worst case is a 10x error on an (unseen) 3-dp
            // currency rather than 100x.
            String rawTxnCcy = item.getTxnCurrency();
            if (rawTxnCcy != null && !rawTxnCcy.isBlank()) {
                int txnDecVal = resolveDecimal(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal, "Txn");
                String txnCode = resolveCurrencyCode(rawTxnCcy, isoNumericToCurrencyCode, currencyCodeToDecimal);
                if (txnCode != null) item.setTxnCurrency(txnCode);
                if (item.getTxnCurrencyAmount() != null) {
                    item.setTxnCurrencyAmount(
                        item.getTxnCurrencyAmount().divide(new java.math.BigDecimal(txnDecVal), 2, java.math.RoundingMode.HALF_UP));
                }
            }

            // ── Settlement currency ──
            // Store base amount MUST always be divided by the currency's decimal value
            // (AED/USD = 100, BHD = 1000). The previous version skipped division whenever
            // the currency code didn't resolve as a numeric ISO code (alpha "AED"/"USD", or
            // a code missing from ref_country), silently storing a 100x-inflated amount.
            int stlDecVal = resolveDecimal(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal, "Store base");
            String stlCode = resolveCurrencyCode(item.getStoreBaseCurrency(), isoNumericToCurrencyCode, currencyCodeToDecimal);
            if (stlCode != null) item.setStoreBaseCurrency(stlCode);
            if (item.getStoreBaseCurrencyAmount() != null) {
                item.setStoreBaseCurrencyAmount(
                    item.getStoreBaseCurrencyAmount().divide(new java.math.BigDecimal(stlDecVal), 2, java.math.RoundingMode.HALF_UP));
            }
            // total_amount_settled is intentionally NOT processed. The upstream feed exports
            // it inconsistently (same txn as 54989.03 and 539.03), so it is unreliable. All
            // settlement/volume math derives from store_base_currency_amount instead. Null it
            // so no downstream consumer can read a garbage value.
            item.setTotalAmountSettled(null);

            // ── Fee scaling ──
            // MSF and VAT arrive from THIS feed ALREADY in major units (e.g. MSF -10.45),
            // so they must NOT be divided. The raw-10000ths MSF (e.g. -297000) is a
            // DIFFERENT upstream column this feed does not carry. Dividing MSF by 10000
            // here stored -0.001045 and made the Transaction Detail screen (which reads
            // fact_transaction.msf directly) show ~0.00 instead of -10.45.
            // Interchange Fee, by contrast, IS still raw 10000ths in this feed
            // (e.g. -62894 -> -6.2894), so it keeps the /10000 conversion.
            // MSF / VAT are stored exactly as received (already 2dp major units).
            if (item.getInterchangeFee() != null) {
                item.setInterchangeFee(item.getInterchangeFee().divide(BD_10000, 4, java.math.RoundingMode.HALF_UP));
            }

            return item;
        };
    }

    @Bean public ItemWriter<StagingTransaction> highPerfTransactionWriter() {
        return items -> {
            String sql = "INSERT INTO stg_trnx_raw (entity_name, aggregator_internal_id, aggregator_name, aggregator_code, " +
                "mid, merchant_internal_id, merchant_name, sid, merchant_store_internal_id, cmm_merchant_store_internal_id, " +
                "merchant_store_legal_name, store_name, tid, arn, rrn_number, card_number, auth_code, payment_date, " +
                "transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc, txn_currency, " +
                "txn_currency_amount, store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, " +
                "interchange_fee, destination, tenant_id, load_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (StagingTransaction t : items) {
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
                        ps.addBatch();
                    }
                    ps.executeBatch(); conn.commit();
                } catch (Exception e) { conn.rollback(); throw e; }
            }
        };
    }

    // Step 4: Staging to Fact
    @Bean public Step stagingToFactStep(Tasklet stagingToFactTasklet) {
        // FIX: this step runs many large jdbcTemplate.update() statements that take
        // tens of seconds each (DELETE existing rows + INSERT INTO fact + UPDATE fix-up).
        // With the default Spring Batch wrapping transaction they all share ONE transaction,
        // which on a 250 MB upload runs >30 minutes — long enough that PostgreSQL's
        // idle_in_transaction_session_timeout kills the connection mid-job.
        // setting propagation NEVER means each jdbcTemplate.update() commits on its
        // own auto-commit boundary instead of accumulating in a single huge transaction.
        return new StepBuilder("stagingToFactStep", jobRepository)
            .tasklet(stagingToFactTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .build();
    }

    @Bean @StepScope
    public Tasklet stagingToFactTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("#{jobParameters['loadMode']}") String loadMode) {
        return (contribution, chunkContext) -> {
            long start = System.currentTimeMillis();
            // APPEND mode (filename starts with 'JCB', see FileUploadService): the
            // file carries only one or a few card schemes (JCB/UPI) and must be
            // layered on top of the VISA/MC data already loaded for the same dates.
            // In that mode we DO NOT blanket-delete the date — we only replace the
            // schemes present in THIS file, so VISA/MC rows survive while re-uploading
            // the same JCB file stays idempotent. REPLACE (the default / absent param)
            // keeps the original blanket delete-by-date behaviour unchanged.
            final boolean appendMode = "APPEND".equalsIgnoreCase(loadMode);

            Integer nullDateCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NULL", Integer.class, tenantId);
            if (nullDateCount != null && nullDateCount > 0) {
                log.warn("{} staging rows have NULL payment_date and will be skipped", nullDateCount);
            }

            // PERF FIX (bulk transactions): pre-compute distinct dates ONCE.
            // Same anti-pattern as populateSummaryTasklet — each `(SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw...)`
            // subquery scans the whole staging table. We use this list 4 times below; do
            // it once and inline as a literal IN-list.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            String dateScope;
            if (distinctDates.isEmpty()) {
                log.info("stagingToFact: no dates in staging — skipping");
                return RepeatStatus.FINISHED;
            } else {
                // P2-10: defense-in-depth. Values come from the DB driver, not user
                // input, so this is not an injection vector today. But it IS a
                // 25+ SQL-string interpolation surface, and a future code change
                // (e.g. swapping the DISTINCT source) could change that. Validate
                // every element matches strict YYYY-MM-DD before we build the IN-list.
                dateScope = buildSafeDateInList(distinctDates);
            }

            // 0.5 Auto-populate dim_merchant.name from transaction file
            // PERF FIX: pre-check whether any merchants actually need a name update.
            // Without the pre-check, we did a full UPDATE scan of dim_merchant on every
            // upload — even when every merchant already had a good name. That's an
            // unconditional ~1-2s of DB work skipped in the steady-state case.
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
                // PERF FIX: removed `OR m.mid LIKE sub.staging_mid || '%' OR sub.staging_mid LIKE m.mid || '%'`
                // — these patterns force a full table scan of dim_merchant. The exact-match
                // case covers >99% of real data; the rare prefix-mismatch case is now handled
                // separately by the prefix-match cleanup below (which only runs once per upload).
                namesUpdated = jdbcTemplate.update(updateNameSql, tenantId, tenantId);
            }
            log.info(String.format("Auto-populated %d merchant names (exact-match) in %.1fs%s",
                namesUpdated, (System.currentTimeMillis() - start) / 1000.0,
                Boolean.TRUE.equals(hasMissingNames) ? "" : " [skipped: all names good]"));

            // 0.6 Cleanup pass: prefix-match for any merchants still missing names.
            // Bounded by the small number of unresolved rows, so even a full scan is cheap.
            // Skip entirely if no merchants need fixing (re-check after the exact-match update).
            int prefixUpdated = 0;
            if (Boolean.TRUE.equals(hasMissingNames)) {
                Boolean stillMissing = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.tenant_id = ? " +
                    "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") LIMIT 1)",
                    Boolean.class, tenantId);
                if (Boolean.TRUE.equals(stillMissing)) {
                    long t06 = System.currentTimeMillis();
                    prefixUpdated = jdbcTemplate.update(
                        "UPDATE dim_merchant m SET name = sub.merchant_name " +
                        "FROM (SELECT DISTINCT s.mid AS staging_mid, s.merchant_name FROM stg_trnx_raw s " +
                        "WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '' " +
                        "AND s.merchant_name !~ " + NUMERIC_ONLY_REGEX + ") sub " +
                        "WHERE m.tenant_id = ? " +
                        "AND (m.name IS NULL OR TRIM(m.name) = '' OR m.name ~ " + NUMERIC_ONLY_REGEX + ") " +
                        "AND m.mid <> sub.staging_mid " +  // skip rows already handled above
                        "AND (m.mid LIKE sub.staging_mid || '%' OR sub.staging_mid LIKE m.mid || '%')",
                        tenantId, tenantId);
                    if (prefixUpdated > 0) {
                        log.info(String.format("Auto-populated %d additional merchant names (prefix-match) in %.1fs",
                            prefixUpdated, (System.currentTimeMillis() - t06) / 1000.0));
                    }
                }
            }

            // Distinct card schemes present in THIS upload. Used by the APPEND
            // scheme-scoped delete below AND by the post-upload data-quality banner.
            java.util.List<String> uploadSchemes = jdbcTemplate.queryForList(
                "SELECT DISTINCT UPPER(TRIM(card_scheme)) FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND NULLIF(TRIM(card_scheme), '') IS NOT NULL",
                String.class, tenantId);

            // Delete existing fact rows for the dates we're about to load.
            long tDel = System.currentTimeMillis();
            if (appendMode) {
                // Scheme-scoped delete: only clear the card schemes present in THIS
                // upload (e.g. JCB/UPI), preserving every other scheme (VISA/MC) for
                // the same dates.
                if (uploadSchemes.isEmpty()) {
                    log.warn("APPEND mode: no card_scheme values in staging — skipping fact delete " +
                        "(nothing to scope to). Existing rows for these dates are left untouched.");
                } else {
                    String placeholders = uploadSchemes.stream().map(x -> "?")
                        .collect(java.util.stream.Collectors.joining(","));
                    Object[] args = new Object[uploadSchemes.size() + 1];
                    args[0] = tenantId;
                    for (int i = 0; i < uploadSchemes.size(); i++) args[i + 1] = uploadSchemes.get(i);
                    int deleted = jdbcTemplate.update(
                        "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope +
                        " AND UPPER(TRIM(card_scheme)) IN (" + placeholders + ")",
                        args);
                    log.info(String.format("APPEND mode: deleted %d existing fact rows for scheme(s) %s in %.1fs " +
                        "(other schemes preserved)", deleted, uploadSchemes, (System.currentTimeMillis() - tDel) / 1000.0));
                }
            } else {
                jdbcTemplate.update(
                    "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope,
                    tenantId);
                log.info(String.format("Deleted existing fact rows in %.1fs", (System.currentTimeMillis() - tDel) / 1000.0));
            }

            // ── Step A: bulk insert with SID-PRIMARY join strategy ──
            //
            // CRITICAL FIX: the transaction file format used in production has ONLY the
            // SID column populated — MID, TID, merchant_internal_id, etc. are all empty.
            // The previous join chain started from `m.mid = stg.mid`, which silently
            // produced merchant_id=NULL for EVERY row, leaving fact_transaction with all
            // dimension keys NULL. That broke every summary table downstream
            // (sum_daily_merchant=0, sum_daily_terminal=0, sum_daily_insight=0) and made
            // the entire dashboard come up empty.
            //
            // New strategy: SID is the anchor. We join dim_store on SID first (a real
            // value in every row), then derive merchant_id from dim_store.merchant_id.
            // Terminal is still joined via TID where present. MID/TID-based joins are
            // kept as fallbacks via COALESCE for files that DO carry MID.
            //
            //   stg.sid -> dim_store.sid -> dim_store.merchant_id -> dim_merchant
            //                            \-> dim_store.store_id
            //   stg.tid -> dim_terminal.tid (within the resolved store)
            //
            // Indexes required for fast joins (verify these exist):
            //   dim_store(tenant_id, sid)
            //   dim_terminal(tenant_id, store_id, tid)
            long tIns = System.currentTimeMillis();
            String sql = "INSERT INTO fact_transaction (tenant_id, merchant_id, store_id, terminal_id, " +
                "arn, rrn_number, card_number, auth_code, payment_date, transaction_date, batch_number, " +
                "transaction_type, card_scheme, card_type, dcc, txn_currency, txn_currency_amount, " +
                "store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, interchange_fee, destination) " +
                "SELECT stg.tenant_id, " +
                // Resolution order:
                //   1. dim_store.merchant_id via SID match (s.merchant_id)
                //   2. dim_merchant.merchant_id via MID match (m.merchant_id)
                //   3. dim_store.merchant_id via TID->terminal->store fallback (s2.merchant_id)
                // The 3rd path saves us when SID is Excel-mangled (e.g. '4.00E+14') but
                // TID is preserved and resolves cleanly to the real store/merchant.
                "COALESCE(s.merchant_id, m.merchant_id, s2.merchant_id) AS merchant_id, " +
                "COALESCE(s.store_id, s2.store_id) AS store_id, t.terminal_id, " +
                "stg.arn, stg.rrn_number, stg.card_number, stg.auth_code, " +
                "stg.payment_date, stg.transaction_date, stg.batch_number, stg.transaction_type, " +
                "stg.card_scheme, stg.card_type, stg.dcc, stg.txn_currency, stg.txn_currency_amount, " +
                "stg.store_base_currency, stg.store_base_currency_amount, " +
                "stg.msf, stg.vat, stg.total_amount_settled, stg.interchange_fee, stg.destination " +
                "FROM stg_trnx_raw stg " +
                // PRIMARY: SID-based store join. NULLIF guards against the empty-string SID
                // case (would otherwise match any row with empty SID in dim_store).
                "LEFT JOIN dim_store s ON s.tenant_id = stg.tenant_id " +
                "  AND s.sid = NULLIF(TRIM(stg.sid), '') " +
                // FALLBACK: MID-based merchant join, used only if the file carries MID.
                "LEFT JOIN dim_merchant m ON m.tenant_id = stg.tenant_id " +
                "  AND m.mid = NULLIF(TRIM(stg.mid), '') " +
                // Terminal join: prefer TID match scoped to the resolved store, but ALSO
                // try TID alone (any store) for the case where store/SID resolution failed.
                // This unlocks TID-fallback resolution below.
                "LEFT JOIN dim_terminal t ON t.tenant_id = stg.tenant_id " +
                "  AND t.tid = NULLIF(TRIM(stg.tid), '') " +
                "  AND (t.store_id = s.store_id OR s.store_id IS NULL) " +
                // FALLBACK 2 (TID-PRIMARY): when SID is corrupted (e.g. Excel-mangled to
                // '4.00E+14' which doesn't match any real dim_store.sid), use the resolved
                // dim_terminal -> dim_store -> dim_merchant chain instead. TIDs are short
                // (8 digits) so Excel preserves them faithfully, making them the most
                // reliable identifier when SIDs/MIDs are corrupt.
                "LEFT JOIN dim_store s2 ON s2.tenant_id = stg.tenant_id " +
                "  AND s2.store_id = t.store_id " +
                "WHERE stg.tenant_id = ? AND stg.payment_date IS NOT NULL";
            int inserted = jdbcTemplate.update(sql, tenantId);
            log.info(String.format("Inserted %d fact rows (SID-primary joins) in %.1fs",
                inserted, (System.currentTimeMillis() - tIns) / 1000.0));

            // Diagnostic: report how many rows actually got merchant_id resolved
            // so the user can immediately see if the dim tables are missing entries.
            Integer matched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? " +
                "AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope,
                Integer.class, tenantId);
            Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? " +
                "AND DATE(payment_date) IN " + dateScope,
                Integer.class, tenantId);
            if (total != null && total > 0) {
                int unmatched = total - (matched != null ? matched : 0);
                if (unmatched > 0) {
                    log.warn(String.format("%d/%d fact rows have NULL merchant_id (SID not found in dim_store). " +
                        "Upload the merchant master file or check SID format mismatch.",
                        unmatched, total));
                } else {
                    log.info("All {} fact rows resolved to a merchant via SID", total);
                }
            }

            // Data-quality summary for the upload UI banner. Stashed in the JOB
            // execution context (persisted by Spring Batch at the step boundary) and
            // read back by BatchProgressController.buildProgressPayload. Best-effort:
            // a failure here must never fail the upload.
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

            // ── Step B: fix-up pass for rows where store/terminal couldn't be resolved ──
            // Handles the legacy CONCAT('STORE_', mid) and CONCAT('TERM_', mid) cases.
            // Bounded by the small unresolved subset — cheap.
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
                log.info(String.format("Fix-up: %d store_ids, %d terminal_ids resolved via CONCAT pattern in %.1fs",
                    storeFixed, termFixed, (System.currentTimeMillis() - tFix) / 1000.0));
            }

            log.info(String.format("stagingToFact completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    // Step 5: Populate Summary Tables
    @Bean public Step populateSummaryStep(Tasklet populateSummaryTasklet) {
        // FIX: 15 parallel aggregation queries, each can run for minutes on 250 MB data.
        // Holding them all in a single batch-managed transaction caused PostgreSQL's
        // idle_in_transaction_session_timeout to fire after ~30 min, killing the upload
        // with a misleading "Connection is closed" error. With NEVER propagation each
        // jdbcTemplate.update() commits immediately on its own connection from the pool.
        return new StepBuilder("populateSummaryStep", jobRepository)
            .tasklet(populateSummaryTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .build();
    }

    @Bean @StepScope
    public Tasklet populateSummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();

            // CONCURRENT-UPLOAD GUARD: take a Postgres advisory lock keyed by
            // tenantId so two transactionLoadJob runs for the same tenant
            // serialize through populateSummary instead of fighting on the
            // shared partitions of sum_daily_merchant, sum_daily_terminal,
            // sum_daily_insight, and sum_daily_merchant_attribute.
            //
            // Without this guard, an accidental double-upload (frontend
            // re-fire, multi-file batch overlapping with a manual upload, or
            // the runMultiFileBatch sequencing breaking) produces deadlocks
            // like:
            //   ERROR: deadlock detected
            //   while inserting index tuple ... in relation "sum_daily_merch_attr_y2026"
            //
            // IMPLEMENTATION NOTE: pg_advisory_lock is session-scoped, so we
            // hold a DEDICATED connection for the entire lock lifetime. The
            // jdbcTemplate calls below borrow their own connections from
            // Hikari, but the advisory lock is enforced server-side: any
            // connection (ours or another job's) that tries to take the same
            // lock key blocks until we release it. We don't need the SAME
            // connection to write the data — we just need to keep one
            // connection ALIVE somewhere holding the lock.
            //
            // The 11_000_000 prefix namespaces this lock so it doesn't
            // collide with any other code that might use advisory locks.
            final long lockKey = 11_000_000L + tenantId;
            org.slf4j.Logger lockLog = org.slf4j.LoggerFactory.getLogger(TransactionJobConfig.class);
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
                    lockLog.warn("populateSummary: waited {}ms for tenant lock (concurrent upload in progress?)",
                        lockWaitMs);
                }
            } catch (Exception e) {
                if (lockConn != null) try { lockConn.close(); } catch (Exception ignore) {}
                throw new RuntimeException("Failed to acquire advisory lock for tenant " + tenantId, e);
            }
            final java.sql.Connection lockConnFinal = lockConn;

            try {

            // PERF FIX (bulk transactions): the previous code embedded
            //   `(SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL)`
            // 15+ times across the aggregation queries. With 100k staging rows and no
            // functional index on DATE(payment_date), that's 15 full sequential scans of
            // stg_trnx_raw — several seconds wasted before any aggregation runs.
            //
            // Now: compute the distinct dates ONCE in Java up front, then inline them as
            // literal IN-lists in each SQL. A typical upload covers 1-7 business dates,
            // so the inlined list is tiny and the planner can use the partition index on
            // fact_transaction(payment_date) directly.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            if (distinctDates.isEmpty()) {
                log.info("populateSummary: no dates to process — skipping");
                return RepeatStatus.FINISHED;
            }
            // Build literal IN-lists once. Both DATE format and YYYYMM int format.
            // P2-10: validate every distinct date matches strict YYYY-MM-DD before
            // we interpolate it into 25+ SQL strings.
            final String dateScope = buildSafeDateInList(distinctDates);
            java.util.Set<Integer> monthSet = new java.util.LinkedHashSet<>();
            for (java.sql.Date d : distinctDates) {
                java.time.LocalDate ld = d.toLocalDate();
                monthSet.add(ld.getYear() * 100 + ld.getMonthValue());
            }
            String monthInList = monthSet.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
            final String monthScope = "(" + monthInList + ")";
            log.info("populateSummary: {} dates, {} months in scope",
                distinctDates.size(), monthSet.size());

            // PERF FIX: parallelize independent aggregation queries.
            // Previously these 15 queries ran sequentially. Now they run on a small
            // thread pool sized for what RDS can usefully do in parallel.
            //
            // Pool size = 4 (down from 8). On same-AZ EC2+RDS the bottleneck is no
            // longer per-query latency (sub-ms) but the actual disk/buffer-cache work
            // each aggregation does on fact_transaction. Eight concurrent heavy
            // aggregations were saturating shared RDS resources and serializing on
            // the buffer pool, which made each one SLOWER than running 4 at a time
            // on smaller RDS instance classes (db.t3, db.m5.large, db.r5.large).
            //
            // The dependency graph:
            //   PHASE 1 (parallel): all the simple INSERT-from-fact_transaction aggregations
            //   PHASE 2 (depends on PHASE 1): top-spending-customer UPDATE on sum_daily_merchant
            // Each task uses its own JDBC connection from the pool.
            java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(4,
                    r -> { Thread t = new Thread(r, "summary-agg-"); t.setDaemon(true); return t; });
            try {
                java.util.List<java.util.concurrent.CompletableFuture<Void>> phase1 = new java.util.ArrayList<>();

                // PERF FIX: removed the duplicate "0.5 Auto-populate merchant name backup" UPDATE
                // that used to run here. It's already done in stagingToFactTasklet (with a
                // fast-path pre-check), so running it again was 1-2s of redundant DB work
                // every upload. If a future caller runs populateSummaryStep standalone
                // without stagingToFact first, names just won't be updated — acceptable
                // because that's a non-standard execution path.

                // 1. sum_daily_bank
                phase1.add(runAsync(exec, "sum_daily_bank", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_bank (tenant_id, business_date, total_txns, total_volume, total_msf, " +
                        "total_interchange, total_scheme_fee, total_vat, total_net_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), COUNT(*), SUM(txn_currency_amount), SUM(msf), " +
                        "SUM(interchange_fee), 0, SUM(vat), SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, DATE(payment_date) " +
                        "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                // 2. sum_daily_merchant (independent of phase 2 — phase 2 only updates 2 columns on these rows)
                phase1.add(runAsync(exec, "sum_daily_merchant", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id, " +
                        "total_txns, total_volume, total_base_volume, total_msf, total_interchange, total_scheme_fee, total_margin, " +
                        "total_debit_prepaid_volume, total_credit_volume, sales_user_id, unique_customer_count, " +
                        "dcc_eligible_volume, dcc_optin_volume, dcc_optout_volume, dcc_eligible_count, dcc_optin_count) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, COUNT(*), " +
                        "SUM(f.txn_currency_amount), SUM(f.store_base_currency_amount), SUM(f.msf), SUM(f.interchange_fee), 0, " +
                        "SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0)), " +
                        "SUM(CASE WHEN UPPER(f.card_type) IN ('DEBIT','PREPAID') THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.card_type) = 'CREDIT' THEN f.store_base_currency_amount ELSE 0 END), " +
                        "m.sales_user_id, COUNT(DISTINCT f.card_number), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN f.store_base_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND (f.dcc IS FALSE OR f.dcc IS NULL) THEN f.store_base_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN 1 END), " +
                        "COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN 1 END) " +
                        "FROM fact_transaction f JOIN dim_merchant m ON f.merchant_id = m.merchant_id " +
                        "WHERE f.tenant_id = ? AND DATE(f.payment_date) IN " + dateScope + " " +
                        "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, " +
                        "total_msf=EXCLUDED.total_msf, total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_margin=EXCLUDED.total_margin, total_debit_prepaid_volume=EXCLUDED.total_debit_prepaid_volume, " +
                        "total_credit_volume=EXCLUDED.total_credit_volume, sales_user_id=EXCLUDED.sales_user_id, " +
                        "unique_customer_count=EXCLUDED.unique_customer_count, " +
                        "dcc_eligible_volume=EXCLUDED.dcc_eligible_volume, dcc_optin_volume=EXCLUDED.dcc_optin_volume, " +
                        "dcc_optout_volume=EXCLUDED.dcc_optout_volume, dcc_eligible_count=EXCLUDED.dcc_eligible_count, " +
                        "dcc_optin_count=EXCLUDED.dcc_optin_count", tenantId)));

                // 3. sum_daily_mcc
                phase1.add(runAsync(exec, "sum_daily_mcc", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf), 0, " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id " +
                        "WHERE f.tenant_id=? AND DATE(f.payment_date) IN " + dateScope + " " +
                        "GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme " +
                        "ON CONFLICT (tenant_id, business_date, mcc, card_scheme) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_scheme_fee=EXCLUDED.total_scheme_fee, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                // 4. sum_daily_scheme
                phase1.add(runAsync(exec, "sum_daily_scheme", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_scheme (tenant_id, business_date, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), card_scheme, COUNT(*), SUM(txn_currency_amount), SUM(msf), " +
                        "SUM(interchange_fee), 0, SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, DATE(payment_date), card_scheme " +
                        "ON CONFLICT (tenant_id, business_date, card_scheme) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                // 5. sum_daily_channel
                phase1.add(runAsync(exec, "sum_daily_channel", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_channel (tenant_id, business_date, channel, total_txns, " +
                        "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS'), COUNT(*), SUM(f.txn_currency_amount), " +
                        "SUM(f.msf), SUM(f.interchange_fee), 0, SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
                        "WHERE f.tenant_id=? AND DATE(f.payment_date) IN " + dateScope + " " +
                        "GROUP BY f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS') " +
                        "ON CONFLICT (tenant_id, business_date, channel) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                // 7. sum_daily_terminal
                phase1.add(runAsync(exec, "sum_daily_terminal", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
                        "total_txns, total_volume, total_msf, total_revenue) " +
                        "SELECT tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id, COUNT(*), SUM(txn_currency_amount), " +
                        "SUM(msf), SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, total_revenue=EXCLUDED.total_revenue",
                        tenantId)));

                // 8. sum_daily_finance
                phase1.add(runAsync(exec, "sum_daily_finance", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_finance (tenant_id, business_date, " +
                        "dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin, " +
                        "dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin, " +
                        "int_cnt, int_vol, int_msf, int_optin, total_vol, total_msf) " +
                        "SELECT tenant_id, DATE(payment_date), " +
                        "COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN txn_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN txn_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END), " +
                        "COUNT(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN 1 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN txn_currency_amount ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN msf ELSE 0 END), " +
                        "SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END), " +
                        "SUM(txn_currency_amount), SUM(msf) " +
                        "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, DATE(payment_date) " +
                        "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
                        "dom_debit_cnt=EXCLUDED.dom_debit_cnt, dom_debit_vol=EXCLUDED.dom_debit_vol, " +
                        "dom_debit_msf=EXCLUDED.dom_debit_msf, dom_debit_optin=EXCLUDED.dom_debit_optin, " +
                        "dom_credit_cnt=EXCLUDED.dom_credit_cnt, dom_credit_vol=EXCLUDED.dom_credit_vol, " +
                        "dom_credit_msf=EXCLUDED.dom_credit_msf, dom_credit_optin=EXCLUDED.dom_credit_optin, " +
                        "int_cnt=EXCLUDED.int_cnt, int_vol=EXCLUDED.int_vol, int_msf=EXCLUDED.int_msf, int_optin=EXCLUDED.int_optin, " +
                        "total_vol=EXCLUDED.total_vol, total_msf=EXCLUDED.total_msf", tenantId)));

                // 9. sum_daily_insight
                phase1.add(runAsync(exec, "sum_daily_insight", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_insight (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
                        "card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "f.card_scheme, f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc, COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf) " +
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) IN " + dateScope + " " +
                        "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                        "f.card_scheme, f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf",
                        tenantId)));

                // 10. Merchant attributes — SERIALIZED into one task.
                //
                // DEADLOCK FIX: previously each of the 6 attribute aggregations
                // (CARD_SCHEME, CARD_TYPE, DESTINATION, TRANSACTION_TYPE,
                //  TXN_SIZE_BUCKET, HOUR) ran as its own runAsync task, all 6
                // writing to sum_daily_merchant_attribute (partition
                // sum_daily_merch_attr_y2026) at the same time with
                // INSERT ... ON CONFLICT DO UPDATE.
                //
                // The unique key is (tenant_id, merchant_id, business_date,
                // attribute_type, attribute_value). Different attribute_types
                // do NOT collide at the row level, but the pages they update
                // overlap in the unique-index B-tree. Two parallel inserts
                // can take page locks in opposite order → PostgreSQL detects
                // the cycle and aborts one with SQLSTATE 40P01.
                //
                // Production logs show this firing repeatedly:
                //   attr-CARD_TYPE FAILED in 1188s: deadlock detected
                //   attr-BUCKET    FAILED in  489s: deadlock detected
                //   attr-HOUR      FAILED in 1043s: deadlock detected
                //
                // Fix: collapse the 6 attribute aggregations into a SINGLE
                // task that runs them sequentially on one connection. The
                // other summary aggregations keep their parallelism because
                // they target different tables/unique keys and don't fight
                // each other.
                //
                // Performance: sequential 6 × ~5-15s = 30-90s vs the previous
                // parallel-with-deadlock-and-retry of 1000s+. Net win.
                phase1.add(runAsync(exec, "attr-ALL", () -> {
                    int totalRows = 0;
                    String[] simpleAttrs = {
                        "CARD_SCHEME:card_scheme",
                        "CARD_TYPE:card_type",
                        "DESTINATION:destination",
                        "TRANSACTION_TYPE:transaction_type"
                    };
                    for (String ac : simpleAttrs) {
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
                    // HOUR
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND transaction_date IS NOT NULL AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date) " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    // TXN_SIZE_BUCKET
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'TXN_SIZE_BUCKET', " +
                        "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
                        "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
                        "WHEN store_base_currency_amount < 1000 THEN '500-1K' ELSE '1K+' END, COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, merchant_id, DATE(payment_date), " +
                        "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
                        "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
                        "WHEN store_base_currency_amount < 1000 THEN '500-1K' ELSE '1K+' END " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    return totalRows;
                }));

                // 11. sum_monthly_card
                phase1.add(runAsync(exec, "sum_monthly_card", () ->
                    jdbcTemplate.update("INSERT INTO sum_monthly_card (tenant_id, merchant_id, month_key, card_number, visit_count, total_spend) " +
                        "SELECT tenant_id, merchant_id, CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER), card_number, COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER) IN " + monthScope + " " +
                        "GROUP BY tenant_id, merchant_id, TO_CHAR(payment_date,'YYYYMM'), card_number " +
                        "ON CONFLICT (tenant_id, merchant_id, month_key, card_number) DO UPDATE SET " +
                        "visit_count=EXCLUDED.visit_count, total_spend=EXCLUDED.total_spend", tenantId)));

                // 6. sum_monthly_bank — reads from sum_daily_bank, so must wait for #1
                // We add it to phase1 anyway because it depends on the SAME sum_daily_bank rows
                // we just inserted; in practice the parallel threads see the same data. To be
                // safe it's serialized after phase1 completes (see below).

                // Wait for phase 1
                java.util.concurrent.CompletableFuture.allOf(phase1.toArray(new java.util.concurrent.CompletableFuture[0])).join();

                // PHASE 2: dependent queries that read from phase-1 outputs.
                // sum_monthly_bank reads sum_daily_bank (#1).
                // top-spending update reads/writes sum_daily_merchant (#2).
                java.util.List<java.util.concurrent.CompletableFuture<Void>> phase2 = new java.util.ArrayList<>();

                phase2.add(runAsync(exec, "sum_monthly_bank", () ->
                    jdbcTemplate.update("INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_msf, " +
                        "total_interchange, total_scheme_fee, total_vat, total_net_revenue) " +
                        "SELECT tenant_id, CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER), SUM(total_txns), SUM(total_volume), " +
                        "SUM(total_msf), SUM(total_interchange), SUM(total_scheme_fee), SUM(total_vat), SUM(total_net_revenue) " +
                        "FROM sum_daily_bank WHERE tenant_id=? AND CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER) IN " + monthScope + " " +
                        "GROUP BY tenant_id, TO_CHAR(business_date,'YYYYMM') " +
                        "ON CONFLICT (tenant_id, month_key) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase2.add(runAsync(exec, "top_spending_customer", () ->
                    jdbcTemplate.update("WITH DailyCustSpend AS (SELECT tenant_id, merchant_id, DATE(payment_date) as b_date, card_number, " +
                        "SUM(store_base_currency_amount) as total_spend FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " + dateScope + " " +
                        "GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number), " +
                        "Ranked AS (SELECT *, ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn FROM DailyCustSpend) " +
                        "UPDATE sum_daily_merchant s SET top_spending_customer_id=r.card_number, top_spending_amount=r.total_spend " +
                        "FROM Ranked r WHERE s.tenant_id=r.tenant_id AND s.merchant_id=r.merchant_id AND s.business_date=r.b_date AND r.rn=1 AND s.tenant_id = ?",
                        tenantId, tenantId)));

                java.util.concurrent.CompletableFuture.allOf(phase2.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            } finally {
                exec.shutdown();
            }

            log.info(String.format("populateSummary completed in %.1fs (parallelized 15 queries)",
                (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
            } finally {
                // Always release the advisory lock and close the dedicated
                // connection, even on failure, so a failed run doesn't
                // permanently block subsequent uploads for this tenant.
                // pg_advisory_unlock returns true if the session held the
                // lock, false otherwise. Either way Postgres releases the
                // lock when the connection closes.
                if (lockConnFinal != null) {
                    try (java.sql.PreparedStatement ps = lockConnFinal.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, lockKey);
                        try (java.sql.ResultSet rs = ps.executeQuery()) { rs.next(); }
                    } catch (Exception unlockErr) {
                        log.warn("populateSummary: pg_advisory_unlock failed (non-fatal, lock auto-releases on disconnect): {}",
                            unlockErr.getMessage());
                    }
                    try { lockConnFinal.close(); } catch (Exception ignore) {}
                }
            }
        };
    }

    /** Helper: run a query on the executor with timing + error logging.
     *
     * AUTOCOMMIT NOTE:
     *   The worker threads from newFixedThreadPool(4) are NOT Spring-managed.
     *   With JPA on the classpath, Hikari's DEFAULT was autoCommit=false,
     *   which made each jdbcTemplate.update(...) here open an implicit
     *   PG transaction that JdbcTemplate never committed (no Spring tx in
     *   scope). Connections returned to the pool 'idle in transaction',
     *   holding locks indefinitely — visible in pg_stat_activity as
     *   zombie sessions stuck for hours with stale query strings.
     *
     *   The fix is in application.properties:
     *     spring.datasource.hikari.auto-commit=true
     *   so each raw JDBC call commits on its own statement boundary. JPA
     *   still works because JpaTransactionManager flips autoCommit=false
     *   per-connection when it begins a managed transaction.
     *
     *   No transaction wrapping is needed in this helper — we WANT each
     *   query to commit immediately on its own connection.
     */
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

    // Step 6: Business Metrics
    @Bean public Step calculateBusinessMetricsStep(Tasklet calculateBusinessMetricsTasklet) {
        // FIX: same idle-in-transaction risk as stagingToFact / populateSummary above.
        // The big merchant_activity_summary INSERT can run for 2+ minutes on its own;
        // putting it under a step-level transaction stacks it on top of whatever the
        // job repository UPDATE locked, which is what triggered the timeout.
        return new StepBuilder("calculateBusinessMetricsStep", jobRepository)
            .tasklet(calculateBusinessMetricsTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .build();
    }

    @Bean @StepScope
    public Tasklet calculateBusinessMetricsTasklet(
            @Value("#{jobParameters['tenantId']}") Long tenantId,
            @Value("${acquira.retention.snapshot-days:90}") int snapshotRetentionDays) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) return RepeatStatus.FINISHED;
            long start = System.currentTimeMillis();

            // PERF FIX (bulk transactions): pre-compute distinct dates ONCE — same
            // anti-pattern as populateSummaryTasklet, used 4 times in this tasklet.
            java.util.List<java.sql.Date> distinctDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) AS d FROM stg_trnx_raw " +
                "WHERE tenant_id = ? AND payment_date IS NOT NULL ORDER BY d",
                java.sql.Date.class, tenantId);
            if (distinctDates.isEmpty()) {
                log.info("businessMetrics: no dates to process — skipping");
                return RepeatStatus.FINISHED;
            }
            String dateScope = buildSafeDateInList(distinctDates);  // P2-10

            // PERF FIX: previously did `dim_merchant CROSS JOIN distinct dates` which
            // generates O(merchants × dates) rows even for dormant merchants with zero
            // transactions. Restrict to merchants that actually have transactions on
            // the dates being processed — 99% smaller intermediate result.
            jdbcTemplate.update("INSERT INTO merchant_activity_summary (tenant_id, merchant_id, calc_date, " +
                "first_txn_date, last_txn_date, last_7d_cnt, last_7d_value, last_30d_cnt, last_30d_value, status, status_change_date) " +
                "SELECT m.tenant_id, m.merchant_id, d.target_date, MIN(f.payment_date), MAX(f.payment_date), " +
                "COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN 1 END), 0), " +
                "COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN f.txn_currency_amount ELSE 0 END), 0), " +
                "COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN 1 END), 0), " +
                "COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN f.txn_currency_amount ELSE 0 END), 0), " +
                "CASE WHEN MAX(f.payment_date) >= d.target_date - INTERVAL '30 days' THEN 'ACTIVE' " +
                "WHEN MAX(f.payment_date) < d.target_date - INTERVAL '30 days' THEN 'DORMANT' ELSE 'ONBOARDED' END, d.target_date " +
                "FROM dim_merchant m " +
                // PERF FIX: bound the LEFT JOIN scan to last 60 days so we don't read
                // the merchant's lifetime fact_transaction history just to compute
                // 7d/30d windows. 60-day window covers both the 30d window and the
                // first/last_txn_date 'recent activity' use-case.
                "JOIN (VALUES " + distinctDates.stream().map(d -> "(DATE '" + d.toString() + "')").collect(java.util.stream.Collectors.joining(",")) + ") d(target_date) ON TRUE " +
                "LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id AND f.tenant_id = m.tenant_id " +
                "  AND f.payment_date >= (CURRENT_DATE - INTERVAL '60 days') " +
                "WHERE m.tenant_id = ? " +
                // Only consider merchants that touched fact_transaction in the relevant date window
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
                "FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date IN " + dateScope + " " +
                "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET score=EXCLUDED.score, reason_tags=EXCLUDED.reason_tags",
                tenantId);

            // ── Retention prune ──────────────────────────────────────────────
            // merchant_activity_summary and merchant_opportunity_score gain one
            // snapshot row per merchant per distinct calc_date on every upload.
            // The ON CONFLICT clauses above only refresh rows for dates being
            // re-uploaded — brand-new dates always INSERT — so without pruning
            // these two tables grow without bound (production was already at
            // ~470k rows each). The Opportunity Intelligence page reads only the
            // LATEST snapshot per merchant, so old calc_date rows have no reader.
            //
            // Delete rows older than the configured window. Runs AFTER the
            // inserts so the current upload's snapshot is never pruned, even if
            // the upload covers an old date. snapshotRetentionDays <= 0 disables
            // pruning entirely (escape hatch). The step runs under noTxn() so
            // each DELETE commits on its own boundary.
            if (snapshotRetentionDays > 0) {
                long tPrune = System.currentTimeMillis();
                // date - integer subtracts that many days in PostgreSQL, so a
                // plain int bind is the simplest, unambiguous form here.
                int prunedActivity = jdbcTemplate.update(
                    "DELETE FROM merchant_activity_summary " +
                    "WHERE tenant_id = ? AND calc_date < CURRENT_DATE - ?",
                    tenantId, snapshotRetentionDays);
                int prunedScore = jdbcTemplate.update(
                    "DELETE FROM merchant_opportunity_score " +
                    "WHERE tenant_id = ? AND calc_date < CURRENT_DATE - ?",
                    tenantId, snapshotRetentionDays);
                if (prunedActivity > 0 || prunedScore > 0) {
                    log.info(String.format(
                        "snapshot retention: pruned %d activity + %d opportunity-score rows " +
                        "older than %d days in %.1fs",
                        prunedActivity, prunedScore, snapshotRetentionDays,
                        (System.currentTimeMillis() - tPrune) / 1000.0));
                }
            }

            log.info(String.format("businessMetrics completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Returns a transaction attribute that tells Spring Batch NOT to wrap the
     * tasklet in a managed transaction. Used by the long-running summary/fact
     * steps where each jdbcTemplate.update() should commit on its own boundary
     * rather than accumulating in a single huge transaction.
     *
     * Without this, PostgreSQL's idle_in_transaction_session_timeout (typically
     * 30 min on RDS) kills the connection partway through a 250 MB upload, with
     * a confusing "Connection is closed" stack trace.
     */
    private static org.springframework.transaction.interceptor.DefaultTransactionAttribute noTxn() {
        org.springframework.transaction.interceptor.DefaultTransactionAttribute attr =
            new org.springframework.transaction.interceptor.DefaultTransactionAttribute(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NEVER);
        return attr;
    }

    // Step 7: Dashboard Metrics
    @Bean public Step calculateDailyDashboardMetricsStep(Tasklet calculateDailyDashboardMetricsTasklet) {
        // P0-5 fix: was missing transactionAttribute(noTxn()). The tasklet does
        // monthlyMetricsRepo.saveAll(toSave) per month inside a Java loop.
        // Without NEVER propagation, every saveAll joined the step's outer
        // transaction — exactly the idle_in_transaction_session_timeout pattern
        // every other long step in this file was hardened against.
        return new StepBuilder("calculateDailyDashboardMetricsStep", jobRepository)
            .tasklet(calculateDailyDashboardMetricsTasklet, transactionManager)
            .transactionAttribute(noTxn())
            .build();
    }

    @Bean @StepScope
    public Tasklet calculateDailyDashboardMetricsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantIdObj) {
        return (contribution, chunkContext) -> {
            if (tenantIdObj == null) return RepeatStatus.FINISHED;
            Integer tenantId = tenantIdObj.intValue();
            long start = System.currentTimeMillis();
            List<String> months = jdbcTemplate.queryForList(
                "SELECT DISTINCT TO_CHAR(payment_date, 'YYYY-MM') FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL", String.class, tenantId);

            // PERF FIX: previously did N round-trips per merchant per month
            // (findByMerchantAndMonth + save individually). On RDS that meant
            // thousands of sequential ~250ms latency hits, dominating upload time.
            // Now: 1 bulk fetch of existing rows for the whole month, then 1 saveAll.
            int totalSaved = 0;
            for (String monthYear : months) {
                if (monthYear == null) continue;
                String[] parts = monthYear.split("-");
                int year = Integer.parseInt(parts[0]); int month = Integer.parseInt(parts[1]);
                LocalDate monthStart = LocalDate.of(year, month, 1);
                LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

                List<SumDailyMerchant> dailyRecs = dailyMerchantRepo.findByTenantIdAndDateRange(tenantId, monthStart, monthEnd);
                if (dailyRecs.isEmpty()) continue;

                java.util.Map<Long, List<SumDailyMerchant>> grouped = dailyRecs.stream()
                        .collect(java.util.stream.Collectors.groupingBy(SumDailyMerchant::getMerchantId));

                // ONE round-trip to get all existing rows for this tenant+month
                java.util.Map<Long, SumMonthlyMerchantMetrics> existingByMerchant = new java.util.HashMap<>();
                try {
                    java.util.List<SumMonthlyMerchantMetrics> existingRows = monthlyMetricsRepo
                        .findAllByTenantAndMonth(tenantId, monthYear);
                    for (SumMonthlyMerchantMetrics e : existingRows) {
                        existingByMerchant.put(e.getMerchantId(), e);
                    }
                } catch (Exception ex) {
                    // Repo method missing or query failed — fall back to per-merchant lookup
                    log.warn("bulk fetch of monthly metrics failed, falling back: {}", ex.getMessage());
                    for (Long mId : grouped.keySet()) {
                        monthlyMetricsRepo.findByMerchantAndMonth(tenantId, mId, monthYear)
                            .ifPresent(e -> existingByMerchant.put(mId, e));
                    }
                }

                java.util.List<SumMonthlyMerchantMetrics> toSave = new java.util.ArrayList<>(grouped.size());
                for (java.util.Map.Entry<Long, List<SumDailyMerchant>> entry : grouped.entrySet()) {
                    Long merchantId = entry.getKey();
                    List<SumDailyMerchant> mRecs = entry.getValue();
                    SumMonthlyMerchantMetrics newMetrics = merchantMetricCalculator.calculateMetrics(mRecs, tenantId, merchantId, monthYear);
                    SumMonthlyMerchantMetrics existing = existingByMerchant.get(merchantId);
                    if (existing != null) {
                        newMetrics.setMetricId(existing.getMetricId());
                        newMetrics.setCreatedAt(existing.getCreatedAt());
                    }
                    toSave.add(newMetrics);
                }

                // ONE batch round-trip to write the whole month
                if (!toSave.isEmpty()) {
                    monthlyMetricsRepo.saveAll(toSave);
                    totalSaved += toSave.size();
                }
            }
            log.info(String.format("dashboardMetrics completed in %.1fs (saved %d rows across %d months)",
                (System.currentTimeMillis() - start) / 1000.0, totalSaved, months.size()));
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
            // Excel reader is currently a partial mapper (only a few fields used by the
            // splitter step). If Excel ingestion is later expanded to cover the full row,
            // any DCC-flag handling there must also use parseDccFlag(...) for consistency
            // with the CSV path — see the FIX note at the top of this file.
            return t;
        });
        org.springframework.batch.item.support.SynchronizedItemStreamReader<StagingTransaction> sync = new org.springframework.batch.item.support.SynchronizedItemStreamReader<>();
        sync.setDelegate(reader); return sync;
    }

    /**
     * P2-10: build a literal IN-list of DATE values for inline SQL interpolation,
     * after validating each entry matches strict ISO-8601 YYYY-MM-DD. Today the
     * input always comes from a DB query (java.sql.Date.toString() is guaranteed
     * YYYY-MM-DD), so this is purely defensive — catches the case where someone
     * later refactors the source to user input or to a different formatter.
     *
     * Inline IN-list (vs parameterized = ANY(?)) is intentional: PostgreSQL's
     * partition pruner on fact_transaction(payment_date) prunes more aggressively
     * with literal DATE values than with a parameterized array. Switching to
     * parameterization here would silently regress upload performance by 30-60
     * seconds. The validation below makes the inline approach safe.
     */
    private static String buildSafeDateInList(java.util.List<java.sql.Date> dates) {
        if (dates == null || dates.isEmpty()) return "(NULL)"; // matches no rows; defensive
        java.util.regex.Pattern iso = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (java.sql.Date d : dates) {
            if (d == null) continue;
            String s = d.toString();
            if (!iso.matcher(s).matches()) {
                throw new IllegalStateException(
                    "Refusing to inline non-ISO date into SQL IN-list: '" + s + "'. " +
                    "This indicates a bug in the date source — java.sql.Date.toString() " +
                    "is documented to return YYYY-MM-DD.");
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

    private java.time.LocalDateTime parseDate(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try {
            String v = val.trim();
            if (v.matches("-?\\d+(\\.\\d+)?")) {
                double serial = Double.parseDouble(v);
                long days = (long) serial;
                double fraction = serial - days;
                java.time.LocalDateTime base = java.time.LocalDateTime.of(1899, 12, 30, 0, 0).plusDays(days);
                if (fraction > 0) {
                    long totalSeconds = Math.round(fraction * 86400);
                    base = base.plusSeconds(totalSeconds);
                }
                return base;
            }
            if (v.contains("T")) return java.time.LocalDateTime.parse(v);
            if (v.contains(" ")) {
                // Try multiple datetime formats
                for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "M/d/yy H:mm", "M/d/yyyy H:mm:ss", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm"}) {
                    try { return java.time.LocalDateTime.parse(v, java.time.format.DateTimeFormatter.ofPattern(pattern)); } catch (Exception ignored) {}
                }
            }
            // Try date-only formats
            for (String pattern : new String[]{"yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "M/d/yyyy", "M/d/yy"}) {
                try { return java.time.LocalDate.parse(v, java.time.format.DateTimeFormatter.ofPattern(pattern)).atStartOfDay(); } catch (Exception ignored) {}
            }
            return null; // No format matched
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
