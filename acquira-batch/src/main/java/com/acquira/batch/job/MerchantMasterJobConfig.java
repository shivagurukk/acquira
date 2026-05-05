package com.acquira.batch.job;

import com.acquira.common.model.StagingMerchant;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
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

@Configuration
public class MerchantMasterJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public MerchantMasterJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Bean
    public Job merchantMasterJob(
            @org.springframework.beans.factory.annotation.Qualifier("ingestMerchantStep") Step ingestMerchantStep,
            @org.springframework.beans.factory.annotation.Qualifier("upsertAndSummarizeStep") Step upsertAndSummarizeStep) {
        // PERF FIX: was 3 steps (ingestMerchantStep → upsertDimensionsStep → populateActivitySummaryStep).
        // Each step transition writes ~3-4 records to BATCH_STEP_EXECUTION + BATCH_JOB_EXECUTION_CONTEXT
        // on RDS. From India that's ~1.5s per transition just for Spring Batch metadata I/O.
        // Merging the two SQL-only steps removes one full transition (~1.5s saved).
        return new JobBuilder("merchantMasterJob", jobRepository)
                .start(ingestMerchantStep)
                .next(upsertAndSummarizeStep)
                .build();
    }

    @Bean
    public Step upsertAndSummarizeStep(Tasklet upsertAndSummarizeTasklet) {
        return new StepBuilder("upsertAndSummarizeStep", jobRepository)
                .tasklet(upsertAndSummarizeTasklet, transactionManager)
                .build();
    }

    /** PERF FIX: combined tasklet that runs upsertDimensions + populateActivitySummary
     *  in a single Spring Batch step. Avoids one step-transition's worth of metadata I/O. */
    @Bean
    @StepScope
    public Tasklet upsertAndSummarizeTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
                                              Tasklet upsertDimensionsTasklet,
                                              Tasklet populateActivitySummaryTasklet) {
        return (contribution, chunkContext) -> {
            long t0 = System.currentTimeMillis();
            upsertDimensionsTasklet.execute(contribution, chunkContext);
            long t1 = System.currentTimeMillis();
            populateActivitySummaryTasklet.execute(contribution, chunkContext);
            long t2 = System.currentTimeMillis();
            System.out.printf("upsertAndSummarize: dim=%dms summary=%dms total=%dms%n",
                t1 - t0, t2 - t1, t2 - t0);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @StepScope
    public Tasklet populateActivitySummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            java.time.LocalDate today = java.time.LocalDate.now();
            String sql = """
                INSERT INTO merchant_activity_summary (
                    tenant_id, merchant_id, calc_date,
                    first_txn_date, last_txn_date,
                    last_7d_cnt, last_7d_value, last_30d_cnt, last_30d_value,
                    status, status_change_date
                )
                SELECT
                    m.tenant_id, m.merchant_id, ?,
                    MIN(f.payment_date), MAX(f.payment_date),
                    COALESCE(COUNT(CASE WHEN f.payment_date >= ? THEN 1 END), 0),
                    COALESCE(SUM(CASE WHEN f.payment_date >= ? THEN f.txn_currency_amount ELSE 0 END), 0),
                    COALESCE(COUNT(CASE WHEN f.payment_date >= ? THEN 1 END), 0),
                    COALESCE(SUM(CASE WHEN f.payment_date >= ? THEN f.txn_currency_amount ELSE 0 END), 0),
                    CASE WHEN MAX(f.payment_date) >= ? THEN 'ACTIVE'
                         WHEN MAX(f.payment_date) < ? THEN 'DORMANT' ELSE 'ONBOARDED' END,
                    ?
                FROM dim_merchant m
                LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id AND f.tenant_id = m.tenant_id
                WHERE m.tenant_id = ?
                GROUP BY m.tenant_id, m.merchant_id
                ON CONFLICT (tenant_id, merchant_id, calc_date)
                DO UPDATE SET status = EXCLUDED.status, status_change_date = EXCLUDED.status_change_date
                """;
            java.time.LocalDate date7d = today.minusDays(7);
            java.time.LocalDate date30d = today.minusDays(30);
            jdbcTemplate.update(sql, today, date7d, date7d, date30d, date30d, date30d, date30d, today, tenantId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step ingestMerchantStep(ItemReader<StagingMerchant> merchantExcelReader,
            ItemProcessor<StagingMerchant, StagingMerchant> merchantTenantProcessor,
            @org.springframework.beans.factory.annotation.Qualifier("merchantWriter") ItemWriter<StagingMerchant> merchantWriter) {
        // PERF FIX: chunk size raised from 100 to 400. On RDS each chunk commit
        // is a network round-trip, so 100 was 4x more chatty than necessary.
        // 400 chosen because the merchant row has ~70 columns and Postgres has
        // a ~32k bind-parameter limit per statement: 400 * 70 = 28000, safe.
        return new StepBuilder("ingestMerchantStep", jobRepository)
                .<StagingMerchant, StagingMerchant>chunk(400, transactionManager)
                .reader(merchantExcelReader)
                .processor(merchantTenantProcessor)
                .writer(merchantWriter)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<StagingMerchant, StagingMerchant> merchantTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId) {
        return item -> { item.setTenantId(tenantId); return item; };
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.ItemStreamReader<StagingMerchant> merchantExcelReader(
            @Value("#{jobParameters['fullPath']}") String fullPath) {
        ExcelItemReader<StagingMerchant> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(fullPath));
        reader.setLinesToSkip(1);

        reader.setRowMapper((row, rowNum) -> {
            StagingMerchant m = new StagingMerchant();
            m.setInstitutionCode(reader.getCellValue(row, "Institution Code"));
            m.setInstitutionName(reader.getCellValue(row, "Institution Name"));
            m.setEntityInternalId(reader.getCellValue(row, "EntityInternalId"));
            m.setEntityName(reader.getCellValue(row, "Entity Name"));
            m.setEntityCode(reader.getCellValue(row, "Entity Code"));
            m.setAggregatorInternalId(reader.getCellValue(row, "AggregatorInternalId"));
            m.setAggregatorName(reader.getCellValue(row, "Aggregator Name"));
            m.setAggregatorCode(reader.getCellValue(row, "Aggregator Code"));
            m.setMerchantInternalId(reader.getCellValue(row, "MerchantInternalId"));
            m.setMid(reader.getCellValue(row, "MID"));
            m.setMerchantName(reader.getCellValue(row, "MerchantName"));
            m.setMerchantStatus(reader.getCellValue(row, "MerchantStatus"));
            m.setRiskLevel(reader.getCellValue(row, "RiskLevel"));
            m.setProduct(reader.getCellValue(row, "Product"));
            m.setMerchantStoreInternalId(reader.getCellValue(row, "MerchantStoreInternalId"));
            m.setSid(reader.getCellValue(row, "SID"));
            m.setStoreLegalName(reader.getCellValue(row, "StoreLegalName"));
            m.setStoreName(reader.getCellValue(row, "StoreName"));
            m.setStoreStatus(reader.getCellValue(row, "Store Status"));
            m.setStoreDesc(reader.getCellValue(row, "Store Desc"));
            m.setAddress(reader.getCellValue(row, "Address"));
            m.setCity(reader.getCellValue(row, "City"));
            m.setState(reader.getCellValue(row, "State"));
            m.setPostalCode(reader.getCellValue(row, "PostalCode"));
            m.setBusinessType(reader.getCellValue(row, "Business Type"));
            m.setBusinessMcc(reader.getCellValue(row, "Business MCC"));
            m.setVatNumber(reader.getCellValue(row, "VATNumber"));
            m.setIndustryType(reader.getCellValue(row, "Industry Type"));
            m.setCustomerType(reader.getCellValue(row, "Customer Type"));
            m.setSourceOfFund(reader.getCellValue(row, "SourceOffund"));
            m.setExpectedVolume(parseDecimal(reader.getCellValue(row, "Expected Volume")));
            m.setPrimaryContactPerson(reader.getCellValue(row, "PrimaryContactPerson"));
            m.setPrimaryContactNumber(reader.getCellValue(row, "PrimaryContactNumber"));
            m.setPrimaryContactEmail(reader.getCellValue(row, "PrimaryContactEmail"));
            m.setPrimaryContactDesignation(reader.getCellValue(row, "PrimaryContactDesignation"));
            m.setSecondaryContactPerson(reader.getCellValue(row, "SecondaryContactPerson"));
            m.setSecondaryContactEmail(reader.getCellValue(row, "SecondaryContactEmail"));
            m.setSecondaryContactNumber(reader.getCellValue(row, "SecondaryContactNumber"));
            m.setSecondaryContactDesignation(reader.getCellValue(row, "SecondaryContactDesignation"));
            m.setRegulatedActivity(parseBoolean(reader.getCellValue(row, "regulatedActivity")));
            m.setRegulatedActivityDesc(reader.getCellValue(row, "regulatedActivityDescription"));
            m.setAuditorName(reader.getCellValue(row, "auditorName"));
            m.setIsPep(parseBoolean(reader.getCellValue(row, "isPEP")));
            m.setPepReason(reader.getCellValue(row, "PEPReason"));
            m.setHighRiskAdverseMedia(parseBoolean(reader.getCellValue(row, "highRiskAdverseMedia")));
            m.setHighRiskSourceOfWealth(parseBoolean(reader.getCellValue(row, "highRiskSourceOfWealth")));
            m.setRiskLevelHigh(parseBoolean(reader.getCellValue(row, "Risk Level High")));
            m.setRiskLevelProhibited(parseBoolean(reader.getCellValue(row, "Risk Level Prohibited")));
            m.setRiskLevelRestricted(parseBoolean(reader.getCellValue(row, "Risk Level Restricted")));
            m.setTerminalInternalId(reader.getCellValue(row, "TerminalInternalId"));
            m.setTid(reader.getCellValue(row, "TID"));
            m.setTerminalName(reader.getCellValue(row, "Terminal Name"));
            m.setTerminalStatus(reader.getCellValue(row, "Terminal Status"));
            m.setTerminalDeviceNumber(reader.getCellValue(row, "Terminal Device Number"));
            m.setTerminalType(reader.getCellValue(row, "Terminal Type"));
            m.setTerminalDescription(reader.getCellValue(row, "Terminal Description"));
            m.setBankName(reader.getCellValue(row, "BankName"));
            m.setBankAccountName(reader.getCellValue(row, "BankAccountName"));
            m.setBankAccountNumber(reader.getCellValue(row, "BankAccountNumber"));
            m.setSwiftCode(reader.getCellValue(row, "SwiftCode"));
            m.setIbanNumber(reader.getCellValue(row, "IBANNumber"));
            m.setSalesUserEmail(reader.getCellValue(row, "Sales User Email"));
            m.setSalesUserId(reader.getCellValue(row, "Sales User Id"));
            m.setReferralPartner(reader.getCellValue(row, "Referral Partner"));
            m.setDateOfOnboarding(parseDate(reader.getCellValue(row, "Date of Onboarding")));
            m.setReviewedDate(parseDate(reader.getCellValue(row, "Reviewed Date")));
            m.setNextReviewedDate(parseDate(reader.getCellValue(row, "Next Reviewed Date")));
            m.setCreatedDate(parseDate(reader.getCellValue(row, "CreatedDate")));
            m.setMerchantCreatedDate(parseDate(reader.getCellValue(row, "Merchant CreatedDate")));
            m.setMerchantStoreCreatedDate(parseDate(reader.getCellValue(row, "MerchantStore CreatedDate")));
            m.setTerminalCreatedDate(parseDate(reader.getCellValue(row, "Terminal CreatedDate")));
            return m;
        });

        reader.setCsvRowMapper((r, rowNum) -> {
            @SuppressWarnings("unchecked")
            ExcelItemReader<StagingMerchant> rr = (ExcelItemReader<StagingMerchant>) r;
            StagingMerchant m = new StagingMerchant();
            m.setInstitutionCode(rr.getCsvCellValue("Institution Code"));
            m.setInstitutionName(rr.getCsvCellValue("Institution Name"));
            m.setEntityInternalId(rr.getCsvCellValue("EntityInternalId"));
            m.setEntityName(rr.getCsvCellValue("Entity Name"));
            m.setEntityCode(rr.getCsvCellValue("Entity Code"));
            m.setAggregatorInternalId(rr.getCsvCellValue("AggregatorInternalId"));
            m.setAggregatorName(rr.getCsvCellValue("Aggregator Name"));
            m.setAggregatorCode(rr.getCsvCellValue("Aggregator Code"));
            m.setMerchantInternalId(rr.getCsvCellValue("MerchantInternalId"));
            m.setMid(rr.getCsvCellValue("MID"));
            m.setMerchantName(rr.getCsvCellValue("MerchantName"));
            m.setMerchantStatus(rr.getCsvCellValue("MerchantStatus"));
            m.setRiskLevel(rr.getCsvCellValue("RiskLevel"));
            m.setProduct(rr.getCsvCellValue("Product"));
            m.setMerchantStoreInternalId(rr.getCsvCellValue("MerchantStoreInternalId"));
            m.setSid(rr.getCsvCellValue("SID"));
            m.setStoreLegalName(rr.getCsvCellValue("StoreLegalName"));
            m.setStoreName(rr.getCsvCellValue("StoreName"));
            m.setStoreStatus(rr.getCsvCellValue("Store Status"));
            m.setStoreDesc(rr.getCsvCellValue("Store Desc"));
            m.setAddress(rr.getCsvCellValue("Address"));
            m.setCity(rr.getCsvCellValue("City"));
            m.setState(rr.getCsvCellValue("State"));
            m.setPostalCode(rr.getCsvCellValue("PostalCode"));
            m.setBusinessType(rr.getCsvCellValue("Business Type"));
            m.setBusinessMcc(rr.getCsvCellValue("Business MCC"));
            m.setVatNumber(rr.getCsvCellValue("VATNumber"));
            m.setIndustryType(rr.getCsvCellValue("Industry Type"));
            m.setCustomerType(rr.getCsvCellValue("Customer Type"));
            m.setSourceOfFund(rr.getCsvCellValue("SourceOffund"));
            m.setExpectedVolume(parseDecimal(rr.getCsvCellValue("Expected Volume")));
            m.setPrimaryContactPerson(rr.getCsvCellValue("PrimaryContactPerson"));
            m.setPrimaryContactNumber(rr.getCsvCellValue("PrimaryContactNumber"));
            m.setPrimaryContactEmail(rr.getCsvCellValue("PrimaryContactEmail"));
            m.setPrimaryContactDesignation(rr.getCsvCellValue("PrimaryContactDesignation"));
            m.setSecondaryContactPerson(rr.getCsvCellValue("SecondaryContactPerson"));
            m.setSecondaryContactEmail(rr.getCsvCellValue("SecondaryContactEmail"));
            m.setSecondaryContactNumber(rr.getCsvCellValue("SecondaryContactNumber"));
            m.setSecondaryContactDesignation(rr.getCsvCellValue("SecondaryContactDesignation"));
            m.setRegulatedActivity(parseBoolean(rr.getCsvCellValue("regulatedActivity")));
            m.setRegulatedActivityDesc(rr.getCsvCellValue("regulatedActivityDescription"));
            m.setAuditorName(rr.getCsvCellValue("auditorName"));
            m.setIsPep(parseBoolean(rr.getCsvCellValue("isPEP")));
            m.setPepReason(rr.getCsvCellValue("PEPReason"));
            m.setHighRiskAdverseMedia(parseBoolean(rr.getCsvCellValue("highRiskAdverseMedia")));
            m.setHighRiskSourceOfWealth(parseBoolean(rr.getCsvCellValue("highRiskSourceOfWealth")));
            m.setRiskLevelHigh(parseBoolean(rr.getCsvCellValue("Risk Level High")));
            m.setRiskLevelProhibited(parseBoolean(rr.getCsvCellValue("Risk Level Prohibited")));
            m.setRiskLevelRestricted(parseBoolean(rr.getCsvCellValue("Risk Level Restricted")));
            m.setTerminalInternalId(rr.getCsvCellValue("TerminalInternalId"));
            m.setTid(rr.getCsvCellValue("TID"));
            m.setTerminalName(rr.getCsvCellValue("Terminal Name"));
            m.setTerminalStatus(rr.getCsvCellValue("Terminal Status"));
            m.setTerminalDeviceNumber(rr.getCsvCellValue("Terminal Device Number"));
            m.setTerminalType(rr.getCsvCellValue("Terminal Type"));
            m.setTerminalDescription(rr.getCsvCellValue("Terminal Description"));
            m.setBankName(rr.getCsvCellValue("BankName"));
            m.setBankAccountName(rr.getCsvCellValue("BankAccountName"));
            m.setBankAccountNumber(rr.getCsvCellValue("BankAccountNumber"));
            m.setSwiftCode(rr.getCsvCellValue("SwiftCode"));
            m.setIbanNumber(rr.getCsvCellValue("IBANNumber"));
            m.setSalesUserEmail(rr.getCsvCellValue("Sales User Email"));
            m.setSalesUserId(rr.getCsvCellValue("Sales User Id"));
            m.setReferralPartner(rr.getCsvCellValue("Referral Partner"));
            m.setDateOfOnboarding(parseDate(rr.getCsvCellValue("Date of Onboarding")));
            m.setReviewedDate(parseDate(rr.getCsvCellValue("Reviewed Date")));
            m.setNextReviewedDate(parseDate(rr.getCsvCellValue("Next Reviewed Date")));
            m.setCreatedDate(parseDate(rr.getCsvCellValue("CreatedDate")));
            m.setMerchantCreatedDate(parseDate(rr.getCsvCellValue("Merchant CreatedDate")));
            m.setMerchantStoreCreatedDate(parseDate(rr.getCsvCellValue("MerchantStore CreatedDate")));
            m.setTerminalCreatedDate(parseDate(rr.getCsvCellValue("Terminal CreatedDate")));
            return m;
        });

        return reader;
    }

    private java.math.BigDecimal parseDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try { return new java.math.BigDecimal(val.replaceAll(",", "").trim()); }
        catch (Exception e) { return null; }
    }

    private Boolean parseBoolean(String val) {
        if (val == null) return false;
        val = val.trim().toUpperCase();
        return "Y".equals(val) || "YES".equals(val) || "TRUE".equals(val) || "1".equals(val);
    }

    private java.time.LocalDateTime parseDate(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try { return java.time.LocalDate.parse(val.trim()).atStartOfDay(); }
        catch (Exception e) { return null; }
    }

    @Bean
    public JdbcBatchItemWriter<StagingMerchant> merchantWriter() {
        return new JdbcBatchItemWriterBuilder<StagingMerchant>()
                .dataSource(dataSource)
                .sql("""
                    INSERT INTO stg_merchant_master_raw (
                        institution_code, institution_name, entity_internal_id, entity_name, entity_code,
                        aggregator_internal_id, aggregator_name, aggregator_code,
                        merchant_internal_id, mid, merchant_name, merchant_status,
                        merchant_store_internal_id, sid, store_legal_name, store_name, store_status,
                        business_type, business_mcc, vat_number,
                        primary_contact_person, primary_contact_number, primary_contact_email, primary_contact_designation,
                        secondary_contact_person, secondary_contact_email, secondary_contact_number, secondary_contact_designation,
                        address, city, state, postal_code, store_desc,
                        industry_type, customer_type, source_of_fund, expected_volume,
                        regulated_activity, regulated_activity_desc, auditor_name,
                        is_pep, pep_reason, high_risk_adverse_media, high_risk_source_of_wealth,
                        risk_level, risk_level_high, risk_level_prohibited, risk_level_restricted, product,
                        date_of_onboarding, reviewed_date, next_reviewed_date,
                        sales_user_email, sales_user_id, referral_partner, created_date,
                        terminal_internal_id, tid, terminal_name, terminal_status,
                        terminal_device_number, terminal_type, terminal_description,
                        bank_name, bank_account_name, bank_account_number, swift_code, iban_number,
                        merchant_created_date, merchant_store_created_date, terminal_created_date,
                        tenant_id, load_time
                    ) VALUES (
                        :institutionCode, :institutionName, :entityInternalId, :entityName, :entityCode,
                        :aggregatorInternalId, :aggregatorName, :aggregatorCode,
                        :merchantInternalId, :mid, :merchantName, :merchantStatus,
                        :merchantStoreInternalId, :sid, :storeLegalName, :storeName, :storeStatus,
                        :businessType, :businessMcc, :vatNumber,
                        :primaryContactPerson, :primaryContactNumber, :primaryContactEmail, :primaryContactDesignation,
                        :secondaryContactPerson, :secondaryContactEmail, :secondaryContactNumber, :secondaryContactDesignation,
                        :address, :city, :state, :postalCode, :storeDesc,
                        :industryType, :customerType, :sourceOfFund, :expectedVolume,
                        :regulatedActivity, :regulatedActivityDesc, :auditorName,
                        :isPep, :pepReason, :highRiskAdverseMedia, :highRiskSourceOfWealth,
                        :riskLevel, :riskLevelHigh, :riskLevelProhibited, :riskLevelRestricted, :product,
                        :dateOfOnboarding, :reviewedDate, :nextReviewedDate,
                        :salesUserEmail, :salesUserId, :referralPartner, :createdDate,
                        :terminalInternalId, :tid, :terminalName, :terminalStatus,
                        :terminalDeviceNumber, :terminalType, :terminalDescription,
                        :bankName, :bankAccountName, :bankAccountNumber, :swiftCode, :ibanNumber,
                        :merchantCreatedDate, :merchantStoreCreatedDate, :terminalCreatedDate,
                        :tenantId, CURRENT_TIMESTAMP
                    )
                    """)
                .beanMapped()
                .build();
    }

    // PERF FIX: removed orphaned `upsertDimensionsStep` bean — it's no longer referenced
    // by merchantMasterJob (replaced by upsertAndSummarizeStep above) and was just
    // creating an unused Spring bean at startup. The tasklet itself is still needed
    // since upsertAndSummarizeTasklet injects it.

    @Bean
    @StepScope
    public Tasklet upsertDimensionsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            String tId = String.valueOf(tenantId);
            long stepStart = System.currentTimeMillis();

            // PERF FIX: removed two debug SELECT queries (each one a full RDS round-trip
            // dumping rows to stdout). They added ~500ms latency on RDS for zero value
            // in production. Re-enable behind a debug flag if you need them.

            // 1. Upsert Merchants
            //    merchant_name from file may contain numeric IDs (not real names).
            //    If merchant_name is purely numeric, set name to NULL so the transaction
            //    pipeline can fill it later with the real name from stg_trnx_raw.
            String upsertMerchantSql = """
                INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, status, created_date, sales_user_id, sales_email, referral_partner, risk_level)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(merchant_internal_id, mid),
                    mid,
                    MAX(CASE WHEN merchant_name ~ '^[0-9.]+$' THEN NULL ELSE NULLIF(TRIM(merchant_name), '') END),
                    COALESCE(MAX(merchant_status), 'ACTIVE'),
                    MAX(created_date),
                    MAX(sales_user_id),
                    MAX(sales_user_email),
                    MAX(referral_partner),
                    MAX(risk_level)
                FROM stg_merchant_master_raw
                WHERE tenant_id = TID
                GROUP BY tenant_id, COALESCE(merchant_internal_id, mid), mid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    name = CASE WHEN EXCLUDED.name IS NOT NULL AND TRIM(EXCLUDED.name) <> ''
                                THEN EXCLUDED.name ELSE dim_merchant.name END,
                    status = EXCLUDED.status,
                    sales_user_id = EXCLUDED.sales_user_id,
                    sales_email = EXCLUDED.sales_email,
                    referral_partner = EXCLUDED.referral_partner,
                    risk_level = EXCLUDED.risk_level
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertMerchantSql);

            // PERF FIX: removed second debug SELECT after merchant upsert.
            System.out.println("Upserted Merchants for tenant " + tId);

            // 2. Upsert Stores
            String upsertStoreSql = """
                INSERT INTO dim_store (tenant_id, internal_id, merchant_id, sid, name, legal_name, address, city, state, postal_code, mcc, status, created_date)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(merchant_store_internal_id, sid, CONCAT('STORE_', s.mid)),
                    MAX(m.merchant_id), sid,
                    MAX(COALESCE(store_name, merchant_name)),
                    MAX(store_legal_name), MAX(s.address), MAX(s.city), MAX(s.state),
                    MAX(s.postal_code), MAX(s.business_mcc),
                    COALESCE(MAX(store_status), 'ACTIVE'), MAX(merchant_store_created_date)
                FROM stg_merchant_master_raw s
                JOIN dim_merchant m ON s.mid = m.mid AND m.tenant_id = TID
                WHERE s.tenant_id = TID
                GROUP BY s.tenant_id, COALESCE(merchant_store_internal_id, sid, CONCAT('STORE_', s.mid)), sid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    name = EXCLUDED.name, address = EXCLUDED.address,
                    city = EXCLUDED.city, state = EXCLUDED.state, status = EXCLUDED.status
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertStoreSql);
            System.out.println("Upserted Stores for tenant " + tId);

            // 3. Upsert Terminals
            // PERF FIX: removed `OR raw.tid = t.tid OR ... CONCAT('TERM_', raw.mid)` joins
            // — those force seq scans of dim_store/dim_terminal. Equality on (sid,
            // internal_id) covers all common cases and uses indexes.
            String upsertTerminalSql = """
                INSERT INTO dim_terminal (tenant_id, internal_id, store_id, tid, device_number, type, status, created_date)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(terminal_internal_id, tid, CONCAT('TERM_', raw.mid)),
                    MAX(s.store_id), tid,
                    MAX(terminal_device_number), MAX(terminal_type),
                    COALESCE(MAX(terminal_status), 'ACTIVE'), MAX(terminal_created_date)
                FROM stg_merchant_master_raw raw
                JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                JOIN dim_store s ON s.merchant_id = m.merchant_id
                    AND (s.sid = raw.sid OR s.internal_id = raw.merchant_store_internal_id)
                    AND s.tenant_id = TID
                WHERE raw.tenant_id = TID AND (raw.tid IS NOT NULL OR raw.terminal_internal_id IS NOT NULL)
                GROUP BY raw.tenant_id, COALESCE(terminal_internal_id, tid, CONCAT('TERM_', raw.mid)), tid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    tid = EXCLUDED.tid, device_number = EXCLUDED.device_number, status = EXCLUDED.status
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertTerminalSql);
            System.out.println("Upserted Terminals for tenant " + tId);

            // 4. Contacts
            jdbcTemplate.execute(("DELETE FROM merchant_contact WHERE merchant_id IN (SELECT m.merchant_id FROM dim_merchant m JOIN stg_merchant_master_raw s ON m.mid = s.mid WHERE m.tenant_id = TID AND s.tenant_id = TID)").replace("TID", tId));

            jdbcTemplate.execute("""
                INSERT INTO merchant_contact (tenant_id, merchant_id, contact_name, role, email, phone, is_primary)
                SELECT DISTINCT CAST(TID AS INTEGER), m.merchant_id,
                    primary_contact_person, COALESCE(primary_contact_designation, 'Primary'),
                    primary_contact_email, primary_contact_number, TRUE
                FROM stg_merchant_master_raw raw JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                WHERE raw.tenant_id = TID AND raw.primary_contact_person IS NOT NULL
                """.replace("TID", tId));

            jdbcTemplate.execute("""
                INSERT INTO merchant_contact (tenant_id, merchant_id, contact_name, role, email, phone, is_primary)
                SELECT DISTINCT CAST(TID AS INTEGER), m.merchant_id,
                    secondary_contact_person, COALESCE(secondary_contact_designation, 'Secondary'),
                    secondary_contact_email, secondary_contact_number, FALSE
                FROM stg_merchant_master_raw raw JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                WHERE raw.tenant_id = TID AND raw.secondary_contact_person IS NOT NULL
                """.replace("TID", tId));
            System.out.println("Upserted Contacts for tenant " + tId);

            // 5. Risk Profile
            jdbcTemplate.execute(("DELETE FROM merchant_risk_profile WHERE merchant_id IN (SELECT m.merchant_id FROM dim_merchant m JOIN stg_merchant_master_raw s ON m.mid = s.mid WHERE m.tenant_id = TID AND s.tenant_id = TID)").replace("TID", tId));

            jdbcTemplate.execute("""
                INSERT INTO merchant_risk_profile (tenant_id, merchant_id, compliance_status, kyc_status, aml_checks_passed, last_review_date, notes)
                SELECT DISTINCT CAST(TID AS INTEGER), m.merchant_id,
                    CASE WHEN risk_level_prohibited = TRUE THEN 'PROHIBITED' WHEN risk_level_restricted = TRUE THEN 'RESTRICTED' ELSE 'COMPLIANT' END,
                    CASE WHEN is_pep = TRUE THEN 'PEP' ELSE 'VERIFIED' END,
                    CASE WHEN high_risk_adverse_media = TRUE THEN FALSE ELSE TRUE END,
                    reviewed_date, pep_reason
                FROM stg_merchant_master_raw raw JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                WHERE raw.tenant_id = TID
                """.replace("TID", tId));
            System.out.println("Upserted Risk Profiles for tenant " + tId);

            // 6. Bank Accounts
            jdbcTemplate.execute(("DELETE FROM dim_bank_account WHERE store_id IN (SELECT s.store_id FROM dim_store s JOIN dim_merchant m ON s.merchant_id = m.merchant_id JOIN stg_merchant_master_raw stg ON m.mid = stg.mid WHERE s.tenant_id = TID AND stg.tenant_id = TID)").replace("TID", tId));

            jdbcTemplate.execute("""
                INSERT INTO dim_bank_account (tenant_id, store_id, bank_name, account_number, swift_code, iban)
                SELECT CAST(TID AS INTEGER), MAX(s.store_id), MAX(bank_name), bank_account_number, MAX(swift_code), MAX(iban_number)
                FROM stg_merchant_master_raw raw
                JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                JOIN dim_store s ON s.merchant_id = m.merchant_id
                    AND (s.sid = raw.sid OR s.internal_id = raw.merchant_store_internal_id)
                    AND s.tenant_id = TID
                WHERE raw.tenant_id = TID AND raw.bank_account_number IS NOT NULL
                GROUP BY raw.tenant_id, bank_account_number
                """.replace("TID", tId));
            System.out.println("Upserted Bank Accounts for tenant " + tId);
            System.out.printf("upsertDimensions completed in %.1fs%n", (System.currentTimeMillis() - stepStart) / 1000.0);

            // 7. Auto-assign unmapped sales users
            try {
                var defaultLeads = jdbcTemplate.queryForList(
                    "SELECT id FROM sales_team_mapping WHERE tenant_id = " + tId + " AND is_default = true LIMIT 1");
                if (!defaultLeads.isEmpty()) {
                    Long defaultLeadId = ((Number) defaultLeads.get(0).get("id")).longValue();
                    int assigned = jdbcTemplate.update(
                        "INSERT INTO sales_user_assignment (tenant_id, sales_user_id, team_lead_id, assigned_at) " +
                        "SELECT DISTINCT m.tenant_id, m.sales_user_id, " + defaultLeadId + ", NOW() " +
                        "FROM dim_merchant m WHERE m.tenant_id = " + tId +
                        " AND m.sales_user_id IS NOT NULL AND m.sales_user_id != '' " +
                        "AND NOT EXISTS (SELECT 1 FROM sales_user_assignment a WHERE a.tenant_id = m.tenant_id AND a.sales_user_id = m.sales_user_id) " +
                        "ON CONFLICT (tenant_id, sales_user_id) DO NOTHING");
                    System.out.println("Auto-assigned " + assigned + " unmapped sales users for tenant " + tId);
                }
            } catch (Exception e) {
                System.err.println("Warning: Auto-assign failed (non-fatal): " + e.getMessage());
            }

            return RepeatStatus.FINISHED;
        };
    }
}
