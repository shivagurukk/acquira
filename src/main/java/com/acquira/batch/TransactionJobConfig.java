package com.acquira.batch;

import com.acquira.model.StagingTransaction;
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

@Configuration
public class TransactionJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public TransactionJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Bean
    public Job transactionLoadJob(
            @org.springframework.beans.factory.annotation.Qualifier("cleanTargetDayStep") Step cleanTargetDayStep,
            @org.springframework.beans.factory.annotation.Qualifier("ingestTransactionStep") Step ingestTransactionStep,
            @org.springframework.beans.factory.annotation.Qualifier("stagingToFactStep") Step stagingToFactStep,
            @org.springframework.beans.factory.annotation.Qualifier("populateSummaryStep") Step populateSummaryStep,
            @org.springframework.beans.factory.annotation.Qualifier("calculateBusinessMetricsStep") Step calculateBusinessMetricsStep) {
        return new JobBuilder("transactionLoadJob", jobRepository)
                .start(cleanTargetDayStep) // CRITICAL: Delete existing data first
                .next(ingestTransactionStep)
                .next(stagingToFactStep)
                .next(populateSummaryStep)
                .next(calculateBusinessMetricsStep)
                .build();
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
                jdbcTemplate.update("DELETE FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date = ?",
                        tenantId, targetDate);
                jdbcTemplate.update("DELETE FROM merchant_opportunity_score WHERE tenant_id = ? AND calc_date = ?",
                        tenantId, targetDate);

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

                // No change to parameters needed, just the SQL structure.

                // Let's check original args:
                // targetDate, date7d, date7d, date30d, date30d, date30d, date30d, targetDate,
                // tenantId
                // The original code had: targetDate (param 8) for status check?
                // Original: WHEN MAX >= ? THEN 'ACTIVE'. Arg 8 was targetDate.
                // That means strictly active TODAY? usually "Active" means txn in last 30d.
                // Let's stick to original arg mapping but correct one:
                // jdbc update args:
                // 1: targetDate
                // 2: date7d
                // 3: date7d
                // 4: date30d
                // 5: date30d
                // 6: date30d
                // 7: date30d
                // 8: date30d (ACTIVE if max >= date30d) -- FIXING THIS LOGIC FOR BETTER SENSE
                // 9: date30d (DORMANT if max < date30d)
                // 10: targetDate (status_change_date)
                // 11: tenantId

                // Recalling Original Params:
                // Update(sql, targetDate, date7d, date7d, date30d, date30d, date30d, date30d,
                // targetDate, tenantId)
                // There were 9 args?
                // SQL: ?, ?, ?, ?, ?, ?, ?, ?, ? (9 params?)
                // Count in string:
                // 1 (calc), 2 (7d), 3 (7d), 4 (30d), 5 (30d), 6 (30d), 7 (30d), 8 (Status 1), 9
                // (Status 2), 10 (StatusChange), 11 (Tenant)
                // Original Code had limited ?s. Let's verify original code.
                // Original Code:
                // WHEN MAX >= ? (Active)
                // WHEN MAX < ? (Dormant)
                // ELSE ...
                // ? (StatusChange)
                // WHERE .. ? (Tenant)

                // It seems I should just stick to a reliable logic:
                // Active = Txn in last 30 days.

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

    @Bean
    @StepScope
    public Tasklet populateSummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            if (tenantId == null) {
                return RepeatStatus.FINISHED;
            }

            // 1. Aggregate Daily Bank
            String sqlBank = "INSERT INTO sum_daily_bank (tenant_id, business_date, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_vat, total_net_revenue) "
                    +
                    "SELECT tenant_id, DATE(payment_date), COUNT(*), SUM(txn_currency_amount), SUM(msf), SUM(interchange_fee), "
                    +
                    "0, SUM(vat), " // Scheme Fee = 0 for now
                    +
                    "SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - 0) " + // Net Revenue = MSF - Int - Scheme
                    "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY tenant_id, DATE(payment_date)";
            jdbcTemplate.update(sqlBank, tenantId, tenantId);

            // 2. Aggregate Daily Merchant
            // 2. Aggregate Daily Merchant (Updated for Debit/Credit Slit and Sales User)
            String sqlMerch = """
                    INSERT INTO sum_daily_merchant (
                        tenant_id, business_date, merchant_id,
                        total_txns, total_volume, total_msf, total_interchange,
                        total_scheme_fee, total_margin,
                        total_debit_prepaid_volume, total_credit_volume, sales_user_id
                    )
                    SELECT
                        f.tenant_id,
                        DATE(f.payment_date),
                        f.merchant_id,
                        COUNT(*),
                        SUM(f.txn_currency_amount),
                        SUM(f.msf),
                        SUM(f.interchange_fee),
                        0, -- Scheme Fee
                        SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - 0),

                        -- Split Logic
                        SUM(CASE WHEN UPPER(f.card_type) IN ('DEBIT', 'PREPAID') THEN f.txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(f.card_type) = 'CREDIT' THEN f.txn_currency_amount ELSE 0 END),

                        m.sales_user_id

                    FROM fact_transaction f
                    JOIN dim_merchant m ON f.merchant_id = m.merchant_id
                    WHERE f.tenant_id = ?
                      AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id
                    """;
            jdbcTemplate.update(sqlMerch, tenantId, tenantId);

            // 3. Aggregate Daily MCC
            String sqlMcc = "INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, total_volume, total_msf, total_scheme_fee, total_net_revenue) "
                    +
                    "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf), "
                    +
                    "0, " // Scheme Fee
                    +
                    "SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - 0) " +
                    "FROM fact_transaction f " +
                    "LEFT JOIN dim_store s ON f.store_id = s.store_id " +
                    "WHERE f.tenant_id = ? AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme";
            jdbcTemplate.update(sqlMcc, tenantId, tenantId);

            // 4. Aggregate Daily Scheme
            String sqlScheme = "INSERT INTO sum_daily_scheme (tenant_id, business_date, card_scheme, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) "
                    +
                    "SELECT tenant_id, DATE(payment_date), card_scheme, COUNT(*), SUM(txn_currency_amount), SUM(msf), SUM(interchange_fee), "
                    +
                    "0, "
                    +
                    "SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - 0) "
                    +
                    "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY tenant_id, DATE(payment_date), card_scheme";
            jdbcTemplate.update(sqlScheme, tenantId, tenantId);

            // 5. Aggregate Daily Channel (Join dim_terminal for 'type')
            String sqlChannel = "INSERT INTO sum_daily_channel (tenant_id, business_date, channel, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) "
                    +
                    "SELECT f.tenant_id, DATE(f.payment_date), t.type, COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf), SUM(f.interchange_fee), "
                    +
                    "0, "
                    +
                    "SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0) - 0) "
                    +
                    "FROM fact_transaction f "
                    +
                    "LEFT JOIN dim_terminal t ON f.terminal_id = t.terminal_id "
                    +
                    "WHERE f.tenant_id = ? AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY f.tenant_id, DATE(f.payment_date), t.type";
            jdbcTemplate.update(sqlChannel, tenantId, tenantId);

            // 6. Aggregate Monthly Bank
            // Delete existing month row first to allow re-run updates
            // (Strictly we should filter months relevant to the Staging dates)

            // Simplified: Re-calculate months for updated dates
            // Query unique months from Staging
            // We can't iterate easily in simple JDBC here without logic.
            // Option: DELETE FROM sum_monthly... WHERE (tenant, month_key) IN (SELECT
            // tenant, proper key from Fact where Date in Staging)
            // Or just SKIP monthly Agg for now? No, dashboard needs it.
            // Let's do a bulk delete/insert for touched months.

            String deleteMonth = "DELETE FROM sum_monthly_bank WHERE tenant_id = ? AND month_key IN " +
                    "(SELECT DISTINCT CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) FROM stg_trnx_raw WHERE tenant_id = ?)";
            jdbcTemplate.update(deleteMonth, tenantId, tenantId);

            String sqlMonth = "INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_vat, total_net_revenue) "
                    +
                    "SELECT tenant_id, CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER), SUM(total_txns), SUM(total_volume), SUM(total_msf), SUM(total_interchange), "
                    +
                    "SUM(total_scheme_fee), SUM(total_vat), SUM(total_net_revenue) "
                    +
                    "FROM sum_daily_bank "
                    +
                    "WHERE tenant_id = ? AND CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER) IN " +
                    "(SELECT DISTINCT CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY tenant_id, TO_CHAR(business_date, 'YYYYMM')";
            jdbcTemplate.update(sqlMonth, tenantId, tenantId);

            // 4. Aggregate Daily Terminal (Granular for Zero Sales logic & Drill down)
            String sqlTerminal = "INSERT INTO sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id, total_txns, total_volume, total_msf, total_revenue) "
                    +
                    "SELECT tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id, COUNT(*), SUM(txn_currency_amount), SUM(msf), "
                    +
                    "SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0)) " +
                    "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?) "
                    +
                    "GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id";
            jdbcTemplate.update(sqlTerminal, tenantId, tenantId);

            // 7. Aggregate Daily Finance (Finance Summary Report)
            // Clean up potentially existing rows for idempotency is handled in
            // stagingToFactStep (added sum_daily_finance to list there)
            String sqlFinance = """
                    INSERT INTO sum_daily_finance (
                        tenant_id, business_date,
                        -- Dom Debit
                        dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin,
                        -- Dom Credit
                        dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin,
                        -- International
                        int_cnt, int_vol, int_msf, int_optin,
                        -- Total
                        total_vol, total_msf
                    )
                    SELECT
                        tenant_id,
                        DATE(payment_date),

                        -- Domestic Debit & Prepaid
                        COUNT(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) IN ('DEBIT', 'PREPAID') AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),

                        -- Domestic Credit
                        COUNT(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'DOMESTIC' AND UPPER(card_type) = 'CREDIT' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),

                        -- International (All Types)
                        COUNT(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN 1 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN txn_currency_amount ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' THEN msf ELSE 0 END),
                        SUM(CASE WHEN UPPER(destination) = 'INTERNATIONAL' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),

                        -- Totals
                        SUM(txn_currency_amount),
                        SUM(msf)

                    FROM fact_transaction
                    WHERE tenant_id = ?
                      AND DATE(payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY tenant_id, DATE(payment_date)
                    """;
            jdbcTemplate.update(sqlFinance, tenantId, tenantId);

            // 8. Aggregate Daily Insight (Highly Granular for Insight Hub)
            String sqlInsight = """
                    INSERT INTO sum_daily_insight (
                        tenant_id, business_date,
                        merchant_id, store_id, terminal_id,
                        card_scheme, card_type, destination, channel, is_opt_in,
                        total_txns, total_volume, total_msf
                    )
                    SELECT
                        f.tenant_id,
                        DATE(f.payment_date),
                        f.merchant_id,
                        f.store_id,
                        f.terminal_id,
                        f.card_scheme,
                        f.card_type,
                        f.destination,
                        COALESCE(t.type, 'POS'), -- Default to POS if unknown
                        f.dcc,
                        COUNT(*),
                        SUM(f.txn_currency_amount),
                        SUM(f.msf)
                    FROM fact_transaction f
                    LEFT JOIN dim_terminal t ON f.terminal_id = t.terminal_id
                    WHERE f.tenant_id = ?
                      AND DATE(f.payment_date) IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)
                    GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id,
                             f.card_scheme, f.card_type, f.destination, COALESCE(t.type, 'POS'), f.dcc
                    """;
            jdbcTemplate.update(sqlInsight, tenantId, tenantId);

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
    @Bean
    public Step ingestTransactionStep(ItemReader<StagingTransaction> transactionExcelReader,
            ItemProcessor<StagingTransaction, StagingTransaction> transactionTenantProcessor,
            ItemWriter<StagingTransaction> transactionWriter) {
        return new StepBuilder("ingestTransactionStep", jobRepository)
                .<StagingTransaction, StagingTransaction>chunk(5000, transactionManager)
                .reader(transactionExcelReader)
                .processor(transactionTenantProcessor)
                .writer(transactionWriter)
                .taskExecutor(taskExecutor())
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
            String[] tables = { "sum_daily_merchant", "sum_daily_bank", "sum_daily_mcc", "sum_daily_scheme",
                    "sum_daily_channel", "sum_daily_terminal", "sum_daily_finance", "sum_daily_insight" };
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
}
