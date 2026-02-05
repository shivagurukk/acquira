package com.acquira.batch;

import com.acquira.model.StagingTransaction;
import com.acquira.service.MerchantMetricCalculator;
import com.acquira.repository.SumDailyMerchantRepository;
import com.acquira.repository.SumMonthlyMerchantMetricsRepository;
import com.acquira.model.SumDailyMerchant;
import com.acquira.model.SumMonthlyMerchantMetrics;

import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
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
import java.time.LocalDate;
import java.util.List;
import com.acquira.batch.ExcelSplitterTasklet;
import com.acquira.batch.CsvPartitioner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Configuration
public class TransactionJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final MerchantMetricCalculator merchantMetricCalculator;
    private final SumDailyMerchantRepository dailyMerchantRepo;
    private final SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final com.acquira.service.PartitionMaintenanceService partitionMaintenanceService;

    public TransactionJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DataSource dataSource, JdbcTemplate jdbcTemplate,
            MerchantMetricCalculator merchantMetricCalculator,
            SumDailyMerchantRepository dailyMerchantRepo,
            SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
            com.acquira.service.PartitionMaintenanceService partitionMaintenanceService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.merchantMetricCalculator = merchantMetricCalculator;
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.partitionMaintenanceService = partitionMaintenanceService;
    }

    @Bean
    public Job transactionLoadJob(
            @org.springframework.beans.factory.annotation.Qualifier("countRowsStep") Step countRowsStep,
            @org.springframework.beans.factory.annotation.Qualifier("splitExcelStep") Step splitExcelStep, // New
            @org.springframework.beans.factory.annotation.Qualifier("cleanTargetDayStep") Step cleanTargetDayStep,
            @org.springframework.beans.factory.annotation.Qualifier("masterIngestStep") Step masterIngestStep, // New
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("ensurePartitionsStep") Step ensurePartitionsStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateDailyDashboardMetricsStep") Step calculateDailyDashboardMetricsStep) {

        return new JobBuilder("transactionLoadJob", jobRepository)
                .start(countRowsStep)
                .next(ensurePartitionsStep) // 0.5 Ensure Partitions Exist
                .next(splitExcelStep) // 1. Split
                .next(cleanTargetDayStep) // 2. Clean
                .next(masterIngestStep) // 3. Parallel Ingest
                .next(stagingToFactStep)
                .next(populateSummaryStep)
                .next(calculateBusinessMetricsStep)
                .next(calculateDailyDashboardMetricsStep)
                .build();
    }

    // ==================================================================================
    // Step 0: Count Rows (Fast Scan)
    // ==================================================================================
    @Bean
    public Step countRowsStep(Tasklet countRowsTasklet) {
        return new StepBuilder("countRowsStep", jobRepository)
                .tasklet(countRowsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet countRowsTasklet(@Value("#{jobParameters['fullPath']}") String fullPath) {
        return (contribution, chunkContext) -> {
            if (fullPath == null)
                return RepeatStatus.FINISHED;

            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(fullPath))) {
                long lines = 0;
                while (reader.readLine() != null) {
                    lines++;
                }
                // Subtract header if exists
                long dataRows = lines > 0 ? lines - 1 : 0;

                // Store in Job Context for Controller to read
                chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                        .putLong("totalReqRows", dataRows);
            } catch (Exception e) {
                // Log and ignore, progress bar will just be indeterminate or 0
                System.err.println("Failed to count rows: " + e.getMessage());
            }
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step ensurePartitionsStep(Tasklet ensurePartitionsTasklet) {
        return new StepBuilder("ensurePartitionsStep", jobRepository)
                .tasklet(ensurePartitionsTasklet, transactionManager)
                .build();
    }

    @Bean
    public Tasklet ensurePartitionsTasklet() {
        return (contribution, chunkContext) -> {
            partitionMaintenanceService.ensurePartitionsForCurrentAndNextYear();
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step calculateBusinessMetricsStep(Tasklet calculateBusinessMetricsTasklet) {
        return new StepBuilder("calculateBusinessMetricsStep", jobRepository)
                .tasklet(calculateBusinessMetricsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet calculateBusinessMetricsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null)
                return RepeatStatus.FINISHED;

            // We need to re-calc metrics for every unique DATE in the uploaded file,
            // because lifecycle/counts are snapshot-based per calc_date.
            // However, iterating in SQL or Java for every single date is complex.
            // For now, let's assume we update metrics for the LATEST date found in the
            // file,
            // or we run it for all distinct dates found.

            // To be safe and correct for "History", we should run for all dates.
            // But 'MerchantActivitySummary' is often a snapshot *AS OF* a date.
            // If we ingest 30 days of history, do we want 30 snapshots? Yes, ideally.
            // Let's iterate using a list of dates retrieved from Staging.

            List<LocalDate> dates = jdbcTemplate.queryForList(
                    "SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?", LocalDate.class,
                    tenantId);

            for (LocalDate targetDate : dates) {
                if (targetDate == null)
                    continue;

                // 1. Cleanup Idempotency
                // (Redundant DELETEs removed as we use ON CONFLICT DO UPDATE below)

                // 2. Insert Merchant Activity Summary
                String sqlActivityJdbc = """
                            INSERT INTO merchant_activity_summary (
                                tenant_id, merchant_id, calc_date,
                                first_txn_date, last_txn_date,
                                last_7d_cnt, last_7d_value,
                                last_30d_cnt, last_30d_value,
                                status, status_change_date
                            )
                            SELECT
                                m.tenant_id, m.merchant_id, ?,
                                MIN(f.payment_date), MAX(f.payment_date),
                                COALESCE(COUNT(CASE WHEN f.payment_date >= ? THEN 1 END), 0),
                                COALESCE(SUM(CASE WHEN f.payment_date >= ? THEN f.txn_currency_amount ELSE 0 END), 0),
                                COALESCE(COUNT(CASE WHEN f.payment_date >= ? THEN 1 END), 0),
                                COALESCE(SUM(CASE WHEN f.payment_date >= ? THEN f.txn_currency_amount ELSE 0 END), 0),
                                CASE
                                    WHEN MAX(f.payment_date) >= ? THEN 'ACTIVE'
                                    WHEN MAX(f.payment_date) < ? THEN 'DORMANT'
                                    ELSE 'ONBOARDED'
                                END,
                                ?
                            FROM dim_merchant m
                            LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id AND f.tenant_id = m.tenant_id
                            WHERE m.tenant_id = ?
                            GROUP BY m.tenant_id, m.merchant_id
                            ON CONFLICT (tenant_id, merchant_id, calc_date)
                            DO UPDATE SET
                                first_txn_date = EXCLUDED.first_txn_date,
                                last_txn_date = EXCLUDED.last_txn_date,
                                last_7d_cnt = EXCLUDED.last_7d_cnt,
                                last_7d_value = EXCLUDED.last_7d_value,
                                last_30d_cnt = EXCLUDED.last_30d_cnt,
                                last_30d_value = EXCLUDED.last_30d_value,
                                status = EXCLUDED.status,
                                status_change_date = EXCLUDED.status_change_date
                        """;

                LocalDate date7d = targetDate.minusDays(7);
                LocalDate date30d = targetDate.minusDays(30);

                jdbcTemplate.update(sqlActivityJdbc,
                        targetDate, // 1. calc_date
                        date7d, date7d, // 2. last_7d_cnt, 3. last_7d_value
                        date30d, date30d, // 4. last_30d_cnt, 5. last_30d_value
                        date30d, date30d, // 6. Active check, 7. Dormant check
                        targetDate, // 8. status_change_date
                        tenantId); // 9. tenant_id

                // 3. Insert Opportunity Score
                String sqlScore = """
                            INSERT INTO merchant_opportunity_score (tenant_id, merchant_id, score, reason_tags, calc_date)
                            SELECT
                                tenant_id, merchant_id,
                                CASE WHEN last_30d_value > 1000 THEN 80 ELSE 40 END,
                                'Automated Score',
                                calc_date
                            FROM merchant_activity_summary
                            WHERE tenant_id = ? AND calc_date = ?
                            ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET
                                score = EXCLUDED.score,
                                reason_tags = EXCLUDED.reason_tags
                        """;
                jdbcTemplate.update(sqlScore, tenantId, targetDate);
            }
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step populateSummaryStep(Tasklet populateSummaryTasklet) {
        return new StepBuilder("populateSummaryStep", jobRepository)
                .tasklet(populateSummaryTasklet, transactionManager)
                .build();
    }

    // ... populateSummaryTasklet omitted for brevity (unchanged) ...
    // Note: Since I am replacing the block, I need to keep populateSummaryTasklet
    // or rely on subsequent tool calls?
    // Wait, the "EndLine" was 623. That includes ingestTransactionStep? NO.
    // ingestTransactionStep is AFTER populateSummaryStep defs in my previous
    // replace plan?
    // Let me check lines again.
    // ingestTransactionStep starts at 613.
    // calculateBusinessMetricsStep starts at 60.
    // cleanTargetDayStep starts at 591.
    //
    @Bean
    @StepScope
    public Tasklet populateSummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) {
                return RepeatStatus.FINISHED;
            }

            // 1. Aggregate Daily Bank
            String sqlBank = """
                    INSERT INTO sum_daily_bank (
                        tenant_id, business_date, total_txns, total_volume, total_msf,
                        total_interchange, total_scheme_fee, total_vat, total_net_revenue
                    )
                    SELECT
                        tenant_id, DATE(payment_date), COUNT(*), SUM(txn_currency_amount), SUM(msf),
                        SUM(interchange_fee), 0, SUM(vat),
                        SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - 0)
                    FROM fact_transaction
                    WHERE tenant_id = ?
                      AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, DATE(payment_date)
                    ON CONFLICT (tenant_id, business_date) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_interchange = EXCLUDED.total_interchange,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_vat = EXCLUDED.total_vat,
                        total_net_revenue = EXCLUDED.total_net_revenue
                    """;
            jdbcTemplate.update(sqlBank, tenantId, tenantId);

            // 2. Aggregate Daily Merchant
            String sqlMerch = """
                    INSERT INTO sum_daily_merchant (
                        tenant_id, business_date, merchant_id,
                        total_txns, total_volume, total_msf, total_interchange,
                        total_scheme_fee, total_margin,
                        total_debit_prepaid_volume, total_credit_volume, sales_user_id,
                        unique_customer_count,
                        dcc_eligible_volume, dcc_optin_volume, dcc_optout_volume,
                        dcc_eligible_count, dcc_optin_count
                    )
                    SELECT
                        f.tenant_id, DATE(f.payment_date), f.merchant_id, COUNT(*),
                        SUM(f.txn_currency_amount), SUM(f.msf), SUM(f.interchange_fee), 0,
                        SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - 0),
                        SUM(CASE WHEN UPPER(f.card_type) IN ('DEBIT', 'PREPAID') THEN f.txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(f.card_type) = 'CREDIT' THEN f.txn_currency_amount ELSE 0 END),
                        m.sales_user_id,
                        COUNT(DISTINCT f.card_number),
                        SUM(CASE WHEN UPPER(f.destination) = 'INTERNATIONAL' THEN f.txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(f.destination) = 'INTERNATIONAL' AND f.dcc IS TRUE THEN f.txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(f.destination) = 'INTERNATIONAL' AND (f.dcc IS FALSE OR f.dcc IS NULL) THEN f.txn_currency_amount ELSE 0 END),
                        COUNT(CASE WHEN UPPER(f.destination) = 'INTERNATIONAL' THEN 1 END),
                        COUNT(CASE WHEN UPPER(f.destination) = 'INTERNATIONAL' AND f.dcc IS TRUE THEN 1 END)
                    FROM fact_transaction f
                    JOIN dim_merchant m ON f.merchant_id = m.merchant_id
                    WHERE f.tenant_id = ?
                      AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id
                    ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_interchange = EXCLUDED.total_interchange,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_margin = EXCLUDED.total_margin,
                        total_debit_prepaid_volume = EXCLUDED.total_debit_prepaid_volume,
                        total_credit_volume = EXCLUDED.total_credit_volume,
                        sales_user_id = EXCLUDED.sales_user_id,
                        unique_customer_count = EXCLUDED.unique_customer_count,
                        dcc_eligible_volume = EXCLUDED.dcc_eligible_volume,
                        dcc_optin_volume = EXCLUDED.dcc_optin_volume,
                        dcc_optout_volume = EXCLUDED.dcc_optout_volume,
                        dcc_eligible_count = EXCLUDED.dcc_eligible_count,
                        dcc_optin_count = EXCLUDED.dcc_optin_count
                    """;
            jdbcTemplate.update(sqlMerch, tenantId, tenantId);

            // 2.1 Update Top Spending Customer (Complex Aggregation)
            // We do this as a separate update for performance and clarity
            String sqlTopCust = """
                    WITH DailyCustSpend AS (
                        SELECT
                            tenant_id, merchant_id, DATE(payment_date) as b_date, card_number,
                            SUM(txn_currency_amount) as total_spend
                        FROM fact_transaction
                        WHERE tenant_id = ?
                          AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                        GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number
                    ),
                    RankedSpend AS (
                        SELECT
                            tenant_id, merchant_id, b_date, card_number, total_spend,
                            ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn
                        FROM DailyCustSpend
                    )
                    UPDATE sum_daily_merchant s
                    SET top_spending_customer_id = rs.card_number,
                        top_spending_amount = rs.total_spend
                    FROM RankedSpend rs
                    WHERE s.tenant_id = rs.tenant_id
                      AND s.merchant_id = rs.merchant_id
                      AND s.business_date = rs.b_date
                      AND rs.rn = 1
                      AND s.tenant_id = ? -- Bind param
                    """;
            jdbcTemplate.update(sqlTopCust, tenantId, tenantId, tenantId);

            // 3. Aggregate Daily MCC
            String sqlMcc = """
                    INSERT INTO sum_daily_mcc (
                        tenant_id, business_date, mcc, card_scheme, total_txns,
                        total_volume, total_msf, total_scheme_fee, total_net_revenue
                    )
                    SELECT
                        f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*),
                        SUM(f.txn_currency_amount), SUM(f.msf), 0,
                        SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - 0)
                    FROM fact_transaction f
                    LEFT JOIN dim_store s ON f.store_id = s.store_id
                    WHERE f.tenant_id = ? AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme
                    ON CONFLICT (tenant_id, business_date, mcc, card_scheme) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_net_revenue = EXCLUDED.total_net_revenue
                    """;
            jdbcTemplate.update(sqlMcc, tenantId, tenantId);

            // 4. Aggregate Daily Scheme
            String sqlScheme = """
                    INSERT INTO sum_daily_scheme (
                        tenant_id, business_date, card_scheme, total_txns,
                        total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue
                    )
                    SELECT
                        tenant_id, DATE(payment_date), card_scheme, COUNT(*),
                        SUM(txn_currency_amount), SUM(msf), SUM(interchange_fee), 0,
                        SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - 0)
                    FROM fact_transaction
                    WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, DATE(payment_date), card_scheme
                    ON CONFLICT (tenant_id, business_date, card_scheme) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_interchange = EXCLUDED.total_interchange,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_net_revenue = EXCLUDED.total_net_revenue
                    """;
            jdbcTemplate.update(sqlScheme, tenantId, tenantId);

            // 5. Aggregate Daily Channel
            String sqlChannel = """
                    INSERT INTO sum_daily_channel (
                        tenant_id, business_date, channel, total_txns,
                        total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue
                    )
                    SELECT
                        f.tenant_id, DATE(f.payment_date), t.type, COUNT(*),
                        SUM(f.txn_currency_amount), SUM(f.msf), SUM(f.interchange_fee), 0,
                        SUM(COALESCE(msf,0) - COALESCE(f.interchange_fee,0) - 0)
                    FROM fact_transaction f
                    LEFT JOIN dim_terminal t ON f.terminal_id = t.terminal_id
                    WHERE f.tenant_id = ? AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), t.type
                    ON CONFLICT (tenant_id, business_date, channel) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_interchange = EXCLUDED.total_interchange,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_net_revenue = EXCLUDED.total_net_revenue
                    """;
            jdbcTemplate.update(sqlChannel, tenantId, tenantId);

            // 6. Aggregate Monthly Bank
            String sqlMonth = """
                    INSERT INTO sum_monthly_bank (
                        tenant_id, month_key, total_txns, total_volume, total_msf,
                        total_interchange, total_scheme_fee, total_vat, total_net_revenue
                    )
                    SELECT
                        tenant_id, CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER),
                        SUM(total_txns), SUM(total_volume), SUM(total_msf), SUM(total_interchange),
                        SUM(total_scheme_fee), SUM(total_vat), SUM(total_net_revenue)
                    FROM sum_daily_bank
                    WHERE tenant_id = ? AND CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER) IN
                      (SELECT DISTINCT CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, TO_CHAR(business_date, 'YYYYMM')
                    ON CONFLICT (tenant_id, month_key) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_interchange = EXCLUDED.total_interchange,
                        total_scheme_fee = EXCLUDED.total_scheme_fee,
                        total_vat = EXCLUDED.total_vat,
                        total_net_revenue = EXCLUDED.total_net_revenue
                    """;
            jdbcTemplate.update(sqlMonth, tenantId, tenantId);

            // 4. Aggregate Daily Terminal
            String sqlTerminal = """
                    INSERT INTO sum_daily_terminal (
                        tenant_id, business_date, merchant_id, store_id, terminal_id,
                        total_txns, total_volume, total_msf, total_revenue
                    )
                    SELECT
                        tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id,
                        COUNT(*), SUM(txn_currency_amount), SUM(msf),
                        SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0))
                    FROM fact_transaction
                    WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id
                    ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf,
                        total_revenue = EXCLUDED.total_revenue
                    """;
            jdbcTemplate.update(sqlTerminal, tenantId, tenantId);

            // 7. Aggregate Daily Finance
            String sqlFinance = """
                    INSERT INTO sum_daily_finance (
                        tenant_id, business_date,
                        dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin,
                        dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin,
                        int_cnt, int_vol, int_msf, int_optin,
                        total_vol, total_msf
                    )
                    SELECT
                        tenant_id, DATE(payment_date),
                        COUNT(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                        COUNT(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                        COUNT(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                        SUM(txn_currency_amount), SUM(msf)
                    FROM fact_transaction
                    WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, DATE(payment_date)
                    ON CONFLICT (tenant_id, business_date) DO UPDATE SET
                        dom_debit_cnt = EXCLUDED.dom_debit_cnt, dom_debit_vol = EXCLUDED.dom_debit_vol,
                        dom_debit_msf = EXCLUDED.dom_debit_msf, dom_debit_optin = EXCLUDED.dom_debit_optin,
                        dom_credit_cnt = EXCLUDED.dom_credit_cnt, dom_credit_vol = EXCLUDED.dom_credit_vol,
                        dom_credit_msf = EXCLUDED.dom_credit_msf, dom_credit_optin = EXCLUDED.dom_credit_optin,
                        int_cnt = EXCLUDED.int_cnt, int_vol = EXCLUDED.int_vol,
                        int_msf = EXCLUDED.int_msf, int_optin = EXCLUDED.int_optin,
                        total_vol = EXCLUDED.total_vol, total_msf = EXCLUDED.total_msf
                    """;
            jdbcTemplate.update(sqlFinance, tenantId, tenantId);

            // 8. Aggregate Daily Insight
            String sqlInsight = """
                    INSERT INTO sum_daily_insight (
                        tenant_id, business_date, merchant_id, store_id, terminal_id,
                        card_scheme, card_type, destination, channel, is_opt_in,
                        total_txns, total_volume, total_msf
                    )
                    SELECT
                        f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id,
                        f.card_scheme, f.card_type, f.destination, COALESCE(t.type, 'POS'), f.dcc,
                        COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf)
                    FROM fact_transaction f
                    LEFT JOIN dim_terminal t ON f.terminal_id = t.terminal_id
                    WHERE f.tenant_id = ? AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id,
                             f.card_scheme, f.card_type, f.destination, COALESCE(t.type, 'POS'), f.dcc
                    ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) DO UPDATE SET
                        total_txns = EXCLUDED.total_txns,
                        total_volume = EXCLUDED.total_volume,
                        total_msf = EXCLUDED.total_msf
                    """;
            jdbcTemplate.update(sqlInsight, tenantId, tenantId);

            // 9. Populate Generic Attributes (SumDailyMerchantAttribute)
            String[] attrTypes = { "CARD_SCHEME", "CARD_TYPE", "DESTINATION", "TRANSACTION_TYPE" };
            for (String attrType : attrTypes) {
                String col = switch (attrType) {
                    case "CARD_SCHEME" -> "card_scheme";
                    case "CARD_TYPE" -> "card_type";
                    case "DESTINATION" -> "destination";
                    case "TRANSACTION_TYPE" -> "transaction_type";
                    default -> "NULL";
                };
                String sqlAttr = String.format(
                        """
                                INSERT INTO sum_daily_merchant_attribute (
                                    tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume
                                )
                                SELECT
                                    tenant_id, merchant_id, DATE(payment_date), '%s', COALESCE(%s, 'UNKNOWN'), COUNT(*), SUM(txn_currency_amount)
                                FROM fact_transaction
                                WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                                GROUP BY tenant_id, merchant_id, DATE(payment_date), %s
                                ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET
                                    metric_count = EXCLUDED.metric_count,
                                    metric_volume = EXCLUDED.metric_volume
                                """,
                        attrType, col, col);
                jdbcTemplate.update(sqlAttr, tenantId, tenantId);
            }

            // 9.3 Attribute: HOUR
            String sqlHour = """
                    INSERT INTO sum_daily_merchant_attribute (
                        tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume
                    )
                    SELECT
                        tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(txn_currency_amount)
                    FROM fact_transaction
                    WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date)
                    ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET
                        metric_count = EXCLUDED.metric_count,
                        metric_volume = EXCLUDED.metric_volume
                    """;
            jdbcTemplate.update(sqlHour, tenantId, tenantId);

            // 10. Populate SumMonthlyCard
            String sqlSumCard = """
                    INSERT INTO sum_monthly_card (
                        tenant_id, merchant_id, month_key, card_number, visit_count, total_spend
                    )
                    SELECT
                        tenant_id, merchant_id, CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER), card_number, COUNT(*), SUM(txn_currency_amount)
                    FROM fact_transaction
                    WHERE tenant_id = ? AND CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) IN
                      (SELECT DISTINCT CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, merchant_id, TO_CHAR(payment_date, 'YYYYMM'), card_number
                    ON CONFLICT (tenant_id, merchant_id, month_key, card_number) DO UPDATE SET
                        visit_count = EXCLUDED.visit_count,
                        total_spend = EXCLUDED.total_spend
                    """;
            jdbcTemplate.update(sqlSumCard, tenantId, tenantId);

            return RepeatStatus.FINISHED;
        };
    }

    // ==================================================================================
    // Step 1: Clean Staging (Prepare for fresh upload)
    // ==================================================================================
    @Bean
    public Step cleanTargetDayStep(Tasklet cleanTargetDayTasklet) {
        return new StepBuilder("cleanTargetDayStep", jobRepository)
                .tasklet(cleanTargetDayTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet cleanTargetDayTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null)
                return RepeatStatus.FINISHED;
            // Clear ALL staging data for this tenant to prepare for new file ingestion
            jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);
            return RepeatStatus.FINISHED;
        };
    }

    // ==================================================================================
    // Step 2: Ingest (Excel -> DB)
    // ==================================================================================
    // ==================================================================================
    // Step 2: Parallel Ingestion (Split -> Parallel Load)
    // ==================================================================================

    // 2a. Splitter Step
    @Bean
    public Step splitExcelStep(ExcelSplitterTasklet excelSplitterTasklet) {
        return new StepBuilder("splitExcelStep", jobRepository)
                .tasklet(excelSplitterTasklet, transactionManager)
                .build();
    }

    // 2b. Master Step (Partitioner)
    @Bean
    public Step masterIngestStep(Step csvWorkerStep, CsvPartitioner partitioner) {
        return new StepBuilder("masterIngestStep", jobRepository)
                .partitioner("csvWorkerStep", partitioner)
                .step(csvWorkerStep)
                .taskExecutor(taskExecutor())
                .gridSize(8) // Max threads
                .build();
    }

    @Bean
    public org.springframework.core.task.TaskExecutor taskExecutor() {
        org.springframework.core.task.SimpleAsyncTaskExecutor executor = new org.springframework.core.task.SimpleAsyncTaskExecutor(
                "spring_batch");
        executor.setConcurrencyLimit(10);
        return executor;
    }

    @Bean
    @StepScope
    public CsvPartitioner csvPartitioner(@Value("#{jobExecutionContext['partitionDirectory']}") String dir) {
        CsvPartitioner partitioner = new CsvPartitioner();
        partitioner.setPartitionDirectory(dir);
        return partitioner;
    }

    // 2c. Worker Step (Read CSV -> Write to DB)
    @Bean
    public Step csvWorkerStep(
            org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> csvTransactionReader,
            ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor,
            ItemWriter<StagingTransaction> transactionWriter) {
        return new StepBuilder("csvWorkerStep", jobRepository)
                .<StagingTransaction, StagingTransaction>chunk(5000, transactionManager)
                .reader(csvTransactionReader)
                .processor(transactionTenantProcessor)
                .writer(transactionWriter)
                .build();
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> csvTransactionReader(
            @Value("#{stepExecutionContext['fileName']}") String fileName) {

        org.springframework.batch.item.file.FlatFileItemReader<StagingTransaction> reader = new org.springframework.batch.item.file.FlatFileItemReader<>();
        if (fileName != null) {
            reader.setResource(new FileSystemResource(fileName));
        }

        // Skip header as Splitter preserves it, and we want to Map by Name
        reader.setLinesToSkip(1);

        reader.setLineMapper(new org.springframework.batch.item.file.mapping.DefaultLineMapper<>() {
            {
                setLineTokenizer(new org.springframework.batch.item.file.transform.DelimitedLineTokenizer() {
                    {
                        setDelimiter(",");
                        setQuoteCharacter('"');
                        // We rely on strict header mapping? No, CSV tokens are positional?
                        // Wait, DelimitedLineTokenizer handles CSV.
                        // But we need to Map to FieldSet...
                        // Ideally we use "Entity Name", "MID" etc.
                        // But if we want to use Names, we need to know the names.
                        // The Splitter writes the Header first.
                        // So we can use the header from the file to drive the tokenizer?
                        // Standard FlatFileItemReader doesn't look at header dynamically unless
                        // configured.

                        // For simplicity and speed in this worker, we will assume standard columns?
                        // NO, the user Excel columns are dynamic order.
                        // The Splitter WRITES the header.
                        // So the CSV HAS the header.
                        // We should use a tokenizer that looks at the header?
                        // The easiest way is to use 'setStrict(false)' and maybe list all possible
                        // columns?
                        // Or better: Use name matching with assumed names.

                        // Let's define the names we expect to Map.
                        setNames("Entity Name", "Aggregator Internal Id", "Aggregator Name", "AggregatorCode",
                                "MID", "Merchant Internal Id", "Merchant Name",
                                "SID", "Merchant Store Internal Id", "CMM Merchant Store Internal Id",
                                "Merchant Store Legal Name", "Store Name",
                                "TerminalID", "ARN", "RRN Number", "CardNumber", "Auth Code",
                                "Payment Date", "Transaction Date", "BatchNumber", "Transaction Type", "CardScheme",
                                "Card Type", "DCC",
                                "Txn Currency", "Txn Currency Amount", "Store Base Currency",
                                "Store Base Currency Amount",
                                "MSF", "VAT", "Total Amount Settled", "Interchange Fee", "Destination");
                    }
                });

                setFieldSetMapper(fieldSet -> {
                    StagingTransaction t = new StagingTransaction();
                    // Safe extraction (handles missing columns gracefully if names match)
                    t.setEntityName(fieldSet.readString("Entity Name"));

                    // ... Map all fields (Similar to ExcelItemReader but using FieldSet)
                    // Wait, this is duplication. But necessary for CSV.
                    // Let's copy the logic.

                    t.setAggregatorInternalId(fieldSet.readString("Aggregator Internal Id"));
                    t.setAggregatorName(fieldSet.readString("Aggregator Name"));
                    t.setAggregatorCode(fieldSet.readString("AggregatorCode"));
                    t.setMid(fieldSet.readString("MID"));
                    t.setMerchantInternalId(fieldSet.readString("Merchant Internal Id"));
                    t.setMerchantName(fieldSet.readString("Merchant Name"));
                    t.setSid(fieldSet.readString("SID"));
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
                    t.setTransactionDate(parseDate(fieldSet.readString("Transaction Date")));

                    t.setBatchNumber(fieldSet.readString("BatchNumber"));
                    t.setTransactionType(fieldSet.readString("Transaction Type"));
                    t.setCardScheme(fieldSet.readString("CardScheme"));
                    t.setCardType(fieldSet.readString("Card Type"));

                    // DCC Logic
                    String dcc = fieldSet.readString("DCC");
                    t.setDcc(dcc != null && (dcc.equalsIgnoreCase("Y") || dcc.equalsIgnoreCase("Yes")));

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
            }
        });
        return reader;
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.support.SynchronizedItemStreamReader<StagingTransaction> transactionExcelReader(
            @Value("#{jobParameters['fullPath']}") String fullPath) {
        ExcelItemReader<StagingTransaction> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(fullPath));
        reader.setLinesToSkip(1);

        reader.setRowMapper((row, rowNum) -> {
            StagingTransaction t = new StagingTransaction();

            t.setEntityName(reader.getCellValue(row, "Entity Name"));
            t.setAggregatorInternalId(reader.getCellValue(row, "Aggregator Internal Id"));
            t.setAggregatorName(reader.getCellValue(row, "Aggregator Name"));
            t.setAggregatorCode(reader.getCellValue(row, "AggregatorCode"));

            t.setMid(reader.getCellValue(row, "MID"));
            t.setMerchantInternalId(reader.getCellValue(row, "Merchant Internal Id"));
            t.setMerchantName(reader.getCellValue(row, "Merchant Name"));

            t.setSid(reader.getCellValue(row, "SID"));
            t.setMerchantStoreInternalId(reader.getCellValue(row, "Merchant Store Internal Id"));
            t.setCmmMerchantStoreInternalId(reader.getCellValue(row, "CMM Merchant Store Internal Id"));
            t.setMerchantStoreLegalName(reader.getCellValue(row, "Merchant Store Legal Name"));
            t.setStoreName(reader.getCellValue(row, "Store Name"));

            t.setTid(reader.getCellValue(row, "TerminalID"));
            t.setArn(reader.getCellValue(row, "ARN"));
            t.setRrnNumber(reader.getCellValue(row, "RRN Number"));
            t.setCardNumber(reader.getCellValue(row, "CardNumber"));
            t.setAuthCode(reader.getCellValue(row, "Auth Code"));

            t.setPaymentDate(parseDate(reader.getCellValue(row, "Payment Date")));
            t.setTransactionDate(parseDate(reader.getCellValue(row, "Transaction Date")));

            t.setBatchNumber(reader.getCellValue(row, "BatchNumber"));
            t.setTransactionType(reader.getCellValue(row, "Transaction Type"));
            t.setCardScheme(reader.getCellValue(row, "CardScheme"));

            // Map Card Type (Debit/Credit/Prepaid)
            t.setCardType(reader.getCellValue(row, "Card Type"));

            String dccStr = reader.getCellValue(row, "DCC");
            t.setDcc(dccStr != null && (dccStr.equalsIgnoreCase("Y") || dccStr.equalsIgnoreCase("Yes")));

            t.setTxnCurrency(reader.getCellValue(row, "Txn Currency"));
            t.setTxnCurrencyAmount(parseDecimal(reader.getCellValue(row, "Txn Currency Amount")));

            t.setStoreBaseCurrency(reader.getCellValue(row, "Store Base Currency"));
            t.setStoreBaseCurrencyAmount(parseDecimal(reader.getCellValue(row, "Store Base Currency Amount")));

            t.setMsf(parseDecimal(reader.getCellValue(row, "MSF")));
            t.setVat(parseDecimal(reader.getCellValue(row, "VAT")));
            t.setTotalAmountSettled(parseDecimal(reader.getCellValue(row, "Total Amount Settled")));
            t.setInterchangeFee(parseDecimal(reader.getCellValue(row, "Interchange Fee")));
            t.setDestination(reader.getCellValue(row, "Destination"));

            return t;
        });

        org.springframework.batch.item.support.SynchronizedItemStreamReader<StagingTransaction> synchronizedReader = new org.springframework.batch.item.support.SynchronizedItemStreamReader<>();
        synchronizedReader.setDelegate(reader);
        return synchronizedReader;
    }

    private java.math.BigDecimal parseDecimal(String val) {
        if (val == null || val.trim().isEmpty())
            return null;
        try {
            return new java.math.BigDecimal(val.replaceAll(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private java.time.LocalDateTime parseDate(String val) {
        if (val == null || val.trim().isEmpty())
            return null;
        try {
            String cleanVal = val.trim();
            // Check if it's an Excel Serial Date (numeric)
            if (cleanVal.matches("-?\\d+(\\.\\d+)?")) {
                double serial = Double.parseDouble(cleanVal);
                return java.time.LocalDateTime.of(1899, 12, 30, 0, 0).plusDays((long) serial);
            }
            // Try ISO with T
            if (cleanVal.contains("T")) {
                return java.time.LocalDateTime.parse(cleanVal);
            }
            // Try SQL/Excel style with space: yyyy-MM-dd HH:mm:ss
            if (cleanVal.contains(" ")) {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss");
                return java.time.LocalDateTime.parse(cleanVal, formatter);
            }
            // Try Standard ISO Date: yyyy-MM-dd
            return java.time.LocalDate.parse(cleanVal).atStartOfDay();
        } catch (Exception e) {
            System.err.println("Found payment date but could not parse: " + val);
            return null;
        }
    }

    @Bean
    @StepScope
    public ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId) {
        return item -> {
            item.setTenantId(tenantId);
            return item;
        };
    }

    @Bean
    public JdbcBatchItemWriter<StagingTransaction> transactionWriter() {
        return new JdbcBatchItemWriterBuilder<StagingTransaction>()
                .dataSource(dataSource)
                .sql("""
                            INSERT INTO stg_trnx_raw (
                                entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
                                mid, merchant_internal_id, merchant_name,
                                sid, merchant_store_internal_id, cmm_merchant_store_internal_id, merchant_store_legal_name, store_name,
                                tid, arn, rrn_number, card_number, auth_code,
                                payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
                                txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                                msf, vat, total_amount_settled, interchange_fee, destination,
                                tenant_id, load_time
                            ) VALUES (
                                :entityName, :aggregatorInternalId, :aggregatorName, :aggregatorCode,
                                :mid, :merchantInternalId, :merchantName,
                                :sid, :merchantStoreInternalId, :cmmMerchantStoreInternalId, :merchantStoreLegalName, :storeName,
                                :tid, :arn, :rrnNumber, :cardNumber, :authCode,
                                :paymentDate, :transactionDate, :batchNumber, :transactionType, :cardScheme, :cardType, :dcc,
                                :txnCurrency, :txnCurrencyAmount, :storeBaseCurrency, :storeBaseCurrencyAmount,
                                :msf, :vat, :totalAmountSettled, :interchangeFee, :destination,
                                :tenantId, CURRENT_TIMESTAMP
                            )
                        """)
                .beanMapped()
                .build();
    }

    // ==================================================================================
    // Step 3: Staging to Fact (With Auto-Cleanup of Overlapping Data)
    // ==================================================================================
    @Bean
    public Step stagingToFactStep(Tasklet stagingToFactTasklet) {
        return new StepBuilder("stagingToFactStep", jobRepository)
                .tasklet(stagingToFactTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet stagingToFactTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            // 1. Clean existing Fact/Summaries for ANY date present in Staging
            // This ensures we fully replace data for the dates we are uploading, acting as
            // an idempotent overwrite for those days.

            String deleteFact = "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)";
            jdbcTemplate.update(deleteFact, tenantId, tenantId);

            // Also clean summaries for these dates to avoid duplicates
            // (Note: Most summary tables now use INSERT ... ON CONFLICT DO UPDATE,
            // but we keep the list here if any are strictly delete-append)
            String[] tables = {}; // All moved to UPSERT logic in populateSummaryTasklet
            for (String tbl : tables) {
                jdbcTemplate.update("DELETE FROM " + tbl
                        + " WHERE tenant_id = ? AND business_date IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)",
                        tenantId, tenantId);
            }

            // 2. Insert into Fact
            String sql = """
                        INSERT INTO fact_transaction (
                            tenant_id,
                            merchant_id, store_id, terminal_id,
                            arn, rrn_number, card_number, auth_code,
                            payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
                            txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                            msf, vat, total_amount_settled, interchange_fee, destination
                        )
                        SELECT
                            CAST(:tenantId AS INTEGER),
                            m.merchant_id,
                            s.store_id,
                            t.terminal_id,
                            stg.arn,
                            stg.rrn_number,
                            stg.card_number,
                            stg.auth_code,
                            stg.payment_date,
                            stg.transaction_date,
                            stg.batch_number,
                            stg.transaction_type,
                            stg.card_scheme,
                            stg.card_type,
                            stg.dcc,
                            stg.txn_currency,
                            stg.txn_currency_amount,
                            stg.store_base_currency,
                            stg.store_base_currency_amount,
                            stg.msf,
                            stg.vat,
                            stg.total_amount_settled,
                            stg.interchange_fee,
                            stg.destination
                        FROM stg_trnx_raw stg
                        -- Join Merchant: Match on MID
                        LEFT JOIN dim_merchant m ON stg.mid = m.mid AND m.tenant_id = :tenantId
                        -- Join Store: Match on SID or Internal ID
                        LEFT JOIN dim_store s ON s.merchant_id = m.merchant_id
                                             AND (s.sid = stg.sid OR s.internal_id = stg.merchant_store_internal_id OR s.internal_id = CONCAT('STORE_', stg.mid))
                                             AND s.tenant_id = :tenantId
                        -- Join Terminal: Match on TID
                        LEFT JOIN dim_terminal t ON t.store_id = s.store_id
                                                AND (t.tid = stg.tid OR t.internal_id = stg.tid OR t.internal_id = CONCAT('TERM_', stg.mid))
                                                AND t.tenant_id = :tenantId
                        WHERE stg.tenant_id = :tenantId
                    """
                    .replace(":tenantId", String.valueOf(tenantId));

            jdbcTemplate.execute(sql);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step calculateDailyDashboardMetricsStep(Tasklet calculateDailyDashboardMetricsTasklet) {
        return new StepBuilder("calculateDailyDashboardMetricsStep", jobRepository)
                .tasklet(calculateDailyDashboardMetricsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet calculateDailyDashboardMetricsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantIdObj) {
        return (contribution, chunkContext) -> {
            if (tenantIdObj == null)
                return RepeatStatus.FINISHED;
            Integer tenantId = tenantIdObj.intValue();

            // 1. Identify distinct months in upload
            List<String> months = jdbcTemplate.queryForList(
                    "SELECT DISTINCT TO_CHAR(payment_date, 'YYYY-MM') FROM stg_trnx_raw WHERE tenant_id = ?",
                    String.class, tenantId);

            for (String monthYear : months) {
                if (monthYear == null)
                    continue;
                String[] parts = monthYear.split("-");
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);

                LocalDate start = LocalDate.of(year, month, 1);
                LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

                List<SumDailyMerchant> dailyRecs = dailyMerchantRepo.findByTenantIdAndDateRange(tenantId, start, end);
                java.util.Map<Long, List<SumDailyMerchant>> grouped = dailyRecs.stream()
                        .collect(java.util.stream.Collectors.groupingBy(SumDailyMerchant::getMerchantId));

                for (java.util.Map.Entry<Long, List<SumDailyMerchant>> entry : grouped.entrySet()) {
                    Long merchantId = entry.getKey();
                    List<SumDailyMerchant> mRecs = entry.getValue();

                    // Calculate new metrics
                    SumMonthlyMerchantMetrics newMetrics = merchantMetricCalculator.calculateMetrics(mRecs, tenantId,
                            merchantId, monthYear);

                    // Check if exists to preserve ID (UPSERT)
                    java.util.Optional<SumMonthlyMerchantMetrics> existing = monthlyMetricsRepo
                            .findByMerchantAndMonth(tenantId, merchantId, monthYear);

                    if (existing.isPresent()) {
                        SumMonthlyMerchantMetrics existingEntity = existing.get();
                        // Copy ID to ensure update
                        newMetrics.setMetricId(existingEntity.getMetricId());
                        // Copy Audit fields if needed, or let them update
                        newMetrics.setCreatedAt(existingEntity.getCreatedAt());
                    }

                    monthlyMetricsRepo.save(newMetrics);
                }
            }
            return RepeatStatus.FINISHED;
        };
    }
}
