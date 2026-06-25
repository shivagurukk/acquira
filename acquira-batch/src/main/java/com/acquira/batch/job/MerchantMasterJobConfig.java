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
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class MerchantMasterJobConfig {

    // P3-6: SLF4J for per-step diagnostics so they honour logback config
    // (file rotation, levels, JSON encoder). Was System.out.printf, which
    // the prod profile's logback was swallowing.
    private static final Logger log = LoggerFactory.getLogger(MerchantMasterJobConfig.class);

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
        return new JobBuilder("merchantMasterJob", jobRepository)
                .start(ingestMerchantStep)
                .next(upsertAndSummarizeStep)
                .build();
    }

    @Bean
    public Step upsertAndSummarizeStep(Tasklet upsertAndSummarizeTasklet) {
        return new StepBuilder("upsertAndSummarizeStep", jobRepository)
                .tasklet(upsertAndSummarizeTasklet, transactionManager)
                .transactionAttribute(noTxn())
                .build();
    }

    private static org.springframework.transaction.interceptor.DefaultTransactionAttribute noTxn() {
        return new org.springframework.transaction.interceptor.DefaultTransactionAttribute(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_NEVER);
    }

    @Bean
    @StepScope
    public Tasklet upsertAndSummarizeTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
                                              Tasklet upsertDimensionsTasklet) {
        return (contribution, chunkContext) -> {
            long t0 = System.currentTimeMillis();
            upsertDimensionsTasklet.execute(contribution, chunkContext);
            long t1 = System.currentTimeMillis();
            log.info(String.format("upsertAndSummarize: dim=%dms total=%dms (activity summary skipped — not affected by merchant upload)",
                t1 - t0, t1 - t0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @StepScope
    public Tasklet populateActivitySummaryTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            long t0 = System.currentTimeMillis();
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
                LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id
                    AND f.tenant_id = m.tenant_id
                    AND f.payment_date >= ?  -- bounded scan
                WHERE m.tenant_id = ?
                GROUP BY m.tenant_id, m.merchant_id
                ON CONFLICT (tenant_id, merchant_id, calc_date)
                DO UPDATE SET status = EXCLUDED.status, status_change_date = EXCLUDED.status_change_date
                """;
            java.time.LocalDate date7d = today.minusDays(7);
            java.time.LocalDate date30d = today.minusDays(30);
            java.time.LocalDate date365d = today.minusDays(365);
            jdbcTemplate.update(sql, today, date7d, date7d, date30d, date30d, date30d, date30d, today, date365d, tenantId);
            log.info(String.format("populateActivitySummary completed in %.1fs",
                (System.currentTimeMillis() - t0) / 1000.0));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step ingestMerchantStep(ItemReader<StagingMerchant> merchantExcelReader,
            ItemProcessor<StagingMerchant, StagingMerchant> merchantTenantProcessor,
            @org.springframework.beans.factory.annotation.Qualifier("merchantWriter") ItemWriter<StagingMerchant> merchantWriter) {
        return new StepBuilder("ingestMerchantStep", jobRepository)
                .<StagingMerchant, StagingMerchant>chunk(800, transactionManager)
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
            m.setMid(normalizeSid(reader.getCellValue(row, "MID")));
            m.setMerchantName(reader.getCellValue(row, "MerchantName"));
            m.setMerchantStatus(reader.getCellValue(row, "MerchantStatus"));
            m.setRiskLevel(reader.getCellValue(row, "RiskLevel"));
            m.setProduct(reader.getCellValue(row, "Product"));
            m.setMerchantStoreInternalId(reader.getCellValue(row, "MerchantStoreInternalId"));
            m.setSid(normalizeSid(reader.getCellValue(row, "SID")));
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
            m.setMid(normalizeSid(rr.getCsvCellValue("MID")));
            m.setMerchantName(rr.getCsvCellValue("MerchantName"));
            m.setMerchantStatus(rr.getCsvCellValue("MerchantStatus"));
            m.setRiskLevel(rr.getCsvCellValue("RiskLevel"));
            m.setProduct(rr.getCsvCellValue("Product"));
            m.setMerchantStoreInternalId(rr.getCsvCellValue("MerchantStoreInternalId"));
            m.setSid(normalizeSid(rr.getCsvCellValue("SID")));
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

    private static final java.time.format.DateTimeFormatter[] DATE_FORMATS = {
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
        java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
    };

    private java.time.LocalDateTime parseDate(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        String s = val.trim();
        if (s.matches("\\d+(?:\\.\\d+)?")) {
            try {
                double serial = Double.parseDouble(s);
                if (serial > 1 && serial < 92000) {
                    return java.time.LocalDate.of(1899, 12, 30)
                            .plusDays((long) serial).atStartOfDay();
                }
            } catch (Exception ignored) {}
        }
        try { return java.time.LocalDateTime.parse(s); } catch (Exception ignored) {}
        for (java.time.format.DateTimeFormatter f : DATE_FORMATS) {
            try {
                return java.time.LocalDate.parse(s, f).atStartOfDay();
            } catch (Exception ignored) {
                try { return java.time.LocalDateTime.parse(s, f); } catch (Exception ignored2) {}
            }
        }
        log.warn("parseDate: could not parse date value '{}' — storing NULL", s);
        return null;
    }

    /**
     * Normalize SID/MID/TID values that may have been mangled by Excel into
     * scientific notation (e.g. "4.00E+14" -> "400000107230001").
     * Called from both CSV and Excel readers, and from TransactionJobConfig.
     */
    static String normalizeSid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.matches("-?\\d+(?:\\.\\d+)?[Ee][+-]?\\d+")) {
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(s);
                return bd.toBigInteger().toString();
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return s;
    }

    @Bean
    public ItemWriter<StagingMerchant> merchantWriter() {
        final String[] columns = {
            "institution_code", "institution_name", "entity_internal_id", "entity_name", "entity_code",
            "aggregator_internal_id", "aggregator_name", "aggregator_code",
            "merchant_internal_id", "mid", "merchant_name", "merchant_status",
            "merchant_store_internal_id", "sid", "store_legal_name", "store_name", "store_status",
            "business_type", "business_mcc", "vat_number",
            "primary_contact_person", "primary_contact_number", "primary_contact_email", "primary_contact_designation",
            "secondary_contact_person", "secondary_contact_email", "secondary_contact_number", "secondary_contact_designation",
            "address", "city", "state", "postal_code", "store_desc",
            "industry_type", "customer_type", "source_of_fund", "expected_volume",
            "regulated_activity", "regulated_activity_desc", "auditor_name",
            "is_pep", "pep_reason", "high_risk_adverse_media", "high_risk_source_of_wealth",
            "risk_level", "risk_level_high", "risk_level_prohibited", "risk_level_restricted", "product",
            "date_of_onboarding", "reviewed_date", "next_reviewed_date",
            "sales_user_email", "sales_user_id", "referral_partner", "created_date",
            "terminal_internal_id", "tid", "terminal_name", "terminal_status",
            "terminal_device_number", "terminal_type", "terminal_description",
            "bank_name", "bank_account_name", "bank_account_number", "swift_code", "iban_number",
            "merchant_created_date", "merchant_store_created_date", "terminal_created_date",
            "tenant_id"
        };
        final int colCount = columns.length;
        final String colList = String.join(", ", columns) + ", load_time";
        final StringBuilder onePlaceholder = new StringBuilder("(");
        for (int i = 0; i < colCount; i++) onePlaceholder.append(i == 0 ? "?" : ",?");
        onePlaceholder.append(",CURRENT_TIMESTAMP)");
        final String onePh = onePlaceholder.toString();

        return chunk -> {
            java.util.List<? extends StagingMerchant> items = chunk.getItems();
            if (items.isEmpty()) return;
            long t0 = System.currentTimeMillis();
            StringBuilder sql = new StringBuilder(64 * 1024);
            sql.append("INSERT INTO stg_merchant_master_raw (").append(colList).append(") VALUES ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append(onePh);
            }
            jdbcTemplate.update(sql.toString(), ps -> {
                int p = 1;
                for (StagingMerchant m : items) {
                    setStr(ps, p++, m.getInstitutionCode(), 50);
                    setStr(ps, p++, m.getInstitutionName(), 100);
                    setStr(ps, p++, m.getEntityInternalId(), 50);
                    setStr(ps, p++, m.getEntityName(), 100);
                    setStr(ps, p++, m.getEntityCode(), 50);
                    setStr(ps, p++, m.getAggregatorInternalId(), 50);
                    setStr(ps, p++, m.getAggregatorName(), 100);
                    setStr(ps, p++, m.getAggregatorCode(), 50);
                    setStr(ps, p++, m.getMerchantInternalId(), 50);
                    setStr(ps, p++, m.getMid(), 50);
                    setStr(ps, p++, m.getMerchantName(), 150);
                    setStr(ps, p++, m.getMerchantStatus(), 50);
                    setStr(ps, p++, m.getMerchantStoreInternalId(), 50);
                    setStr(ps, p++, m.getSid(), 50);
                    setStr(ps, p++, m.getStoreLegalName(), 150);
                    setStr(ps, p++, m.getStoreName(), 150);
                    setStr(ps, p++, m.getStoreStatus(), 50);
                    setStr(ps, p++, m.getBusinessType(), 100);
                    setStr(ps, p++, m.getBusinessMcc(), 10);
                    setStr(ps, p++, m.getVatNumber(), 50);
                    setStr(ps, p++, m.getPrimaryContactPerson(), 100);
                    setStr(ps, p++, m.getPrimaryContactNumber(), 50);
                    setStr(ps, p++, m.getPrimaryContactEmail(), 100);
                    setStr(ps, p++, m.getPrimaryContactDesignation(), 100);
                    setStr(ps, p++, m.getSecondaryContactPerson(), 100);
                    setStr(ps, p++, m.getSecondaryContactEmail(), 100);
                    setStr(ps, p++, m.getSecondaryContactNumber(), 50);
                    setStr(ps, p++, m.getSecondaryContactDesignation(), 100);
                    ps.setString(p++, m.getAddress());
                    setStr(ps, p++, m.getCity(), 100);
                    setStr(ps, p++, m.getState(), 100);
                    setStr(ps, p++, m.getPostalCode(), 20);
                    ps.setString(p++, m.getStoreDesc());
                    setStr(ps, p++, m.getIndustryType(), 100);
                    setStr(ps, p++, m.getCustomerType(), 100);
                    setStr(ps, p++, m.getSourceOfFund(), 100);
                    if (m.getExpectedVolume() != null) ps.setBigDecimal(p++, m.getExpectedVolume());
                    else ps.setNull(p++, java.sql.Types.NUMERIC);
                    if (m.getRegulatedActivity() != null) ps.setBoolean(p++, m.getRegulatedActivity());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    ps.setString(p++, m.getRegulatedActivityDesc());
                    setStr(ps, p++, m.getAuditorName(), 100);
                    if (m.getIsPep() != null) ps.setBoolean(p++, m.getIsPep());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    ps.setString(p++, m.getPepReason());
                    if (m.getHighRiskAdverseMedia() != null) ps.setBoolean(p++, m.getHighRiskAdverseMedia());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    if (m.getHighRiskSourceOfWealth() != null) ps.setBoolean(p++, m.getHighRiskSourceOfWealth());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    setStr(ps, p++, m.getRiskLevel(), 20);
                    if (m.getRiskLevelHigh() != null) ps.setBoolean(p++, m.getRiskLevelHigh());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    if (m.getRiskLevelProhibited() != null) ps.setBoolean(p++, m.getRiskLevelProhibited());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    if (m.getRiskLevelRestricted() != null) ps.setBoolean(p++, m.getRiskLevelRestricted());
                    else ps.setNull(p++, java.sql.Types.BOOLEAN);
                    setStr(ps, p++, m.getProduct(), 100);
                    setTs(ps, p++, m.getDateOfOnboarding());
                    setTs(ps, p++, m.getReviewedDate());
                    setTs(ps, p++, m.getNextReviewedDate());
                    setStr(ps, p++, m.getSalesUserEmail(), 100);
                    setStr(ps, p++, m.getSalesUserId(), 50);
                    setStr(ps, p++, m.getReferralPartner(), 100);
                    setTs(ps, p++, m.getCreatedDate());
                    setStr(ps, p++, m.getTerminalInternalId(), 50);
                    setStr(ps, p++, m.getTid(), 50);
                    setStr(ps, p++, m.getTerminalName(), 100);
                    setStr(ps, p++, m.getTerminalStatus(), 50);
                    setStr(ps, p++, m.getTerminalDeviceNumber(), 50);
                    setStr(ps, p++, m.getTerminalType(), 50);
                    ps.setString(p++, m.getTerminalDescription());
                    setStr(ps, p++, m.getBankName(), 100);
                    setStr(ps, p++, m.getBankAccountName(), 100);
                    setStr(ps, p++, m.getBankAccountNumber(), 50);
                    setStr(ps, p++, m.getSwiftCode(), 50);
                    setStr(ps, p++, m.getIbanNumber(), 50);
                    setTs(ps, p++, m.getMerchantCreatedDate());
                    setTs(ps, p++, m.getMerchantStoreCreatedDate());
                    setTs(ps, p++, m.getTerminalCreatedDate());
                    if (m.getTenantId() != null) ps.setLong(p++, m.getTenantId());
                    else ps.setNull(p++, java.sql.Types.BIGINT);
                }
            });
            if (System.currentTimeMillis() - t0 > 200) {
                log.info("  staging-insert chunk={} in {}ms", items.size(), System.currentTimeMillis() - t0);
            }
        };
    }

    private static void setTs(java.sql.PreparedStatement ps, int idx, java.time.LocalDateTime v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.TIMESTAMP);
        else ps.setTimestamp(idx, java.sql.Timestamp.valueOf(v));
    }

    private static void setStr(java.sql.PreparedStatement ps, int idx, String v, int maxLen) throws java.sql.SQLException {
        if (v == null) { ps.setNull(idx, java.sql.Types.VARCHAR); return; }
        if (v.length() > maxLen) v = v.substring(0, maxLen);
        ps.setString(idx, v);
    }

    @Bean
    @StepScope
    public Tasklet upsertDimensionsTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId,
                                            @Value("#{jobParameters['startedAt']}") Long startedAt) {
        return (contribution, chunkContext) -> {
            String tId = String.valueOf(tenantId);
            long stepStart = System.currentTimeMillis();

            // Purge stale staging rows from previous uploads (keep only this run's rows)
            long t0 = System.currentTimeMillis();
            long cutoffMs = (startedAt != null ? startedAt : System.currentTimeMillis()) - 30_000L;
            int purged = jdbcTemplate.update(
                "DELETE FROM stg_merchant_master_raw WHERE tenant_id = ? AND load_time < ?",
                tenantId, new java.sql.Timestamp(cutoffMs));
            log.info("  staging cleanup: removed {} stale rows for tenant {} in {}ms",
                purged, tId, System.currentTimeMillis() - t0);

            // ── 1. Upsert Merchants ──────────────────────────────────────────────────
            //
            // FIX BUG: updating merchant_name / store_name / store_legal_name in the
            // merchant master file was creating DUPLICATE merchant rows instead of
            // updating the existing one.
            //
            // Root cause: the old INSERT used
            //   COALESCE(merchant_internal_id, mid)
            // as the internal_id key. When a file row has a blank merchant_internal_id
            // column (common — most banks only fill MID), that expression evaluates to
            // just `mid`. But the FIRST upload of that merchant may have stored a
            // non-blank merchant_internal_id in dim_merchant.internal_id, so on re-upload
            // the COALESCE produces a DIFFERENT key, misses the ON CONFLICT, and inserts
            // a second row.
            //
            // Fix: use MID as the sole stable anchor. When merchant_internal_id is blank
            // we synthesise 'MID_<mid>' so the conflict key is always deterministic and
            // consistent across uploads regardless of whether the file carries
            // merchant_internal_id. MID never changes — it is a bank-assigned permanent ID.
            //
            // What IS updated (mutable descriptive fields): name, status, sales assignment,
            // risk level.
            // What is NEVER updated (immutable identifiers): mid, internal_id, tenant_id.
            String upsertMerchantSql = """
                INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, status, created_date, sales_user_id, sales_email, referral_partner, risk_level)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(NULLIF(TRIM(merchant_internal_id), ''), 'MID_' || TRIM(mid)),
                    mid,
                    MAX(CASE WHEN merchant_name ~ '^[0-9.]+$' THEN NULL ELSE NULLIF(TRIM(merchant_name), '') END),
                    COALESCE(MAX(merchant_status), 'ACTIVE'),
                    MAX(created_date),
                    MAX(sales_user_id),
                    MAX(sales_user_email),
                    MAX(referral_partner),
                    MAX(risk_level)
                FROM stg_merchant_master_raw
                WHERE tenant_id = TID AND NULLIF(TRIM(mid), '') IS NOT NULL
                GROUP BY tenant_id, COALESCE(NULLIF(TRIM(merchant_internal_id), ''), 'MID_' || TRIM(mid)), mid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    name          = CASE WHEN EXCLUDED.name IS NOT NULL AND TRIM(EXCLUDED.name) <> ''
                                         THEN EXCLUDED.name ELSE dim_merchant.name END,
                    status        = COALESCE(EXCLUDED.status, dim_merchant.status),
                    sales_user_id = COALESCE(EXCLUDED.sales_user_id, dim_merchant.sales_user_id),
                    sales_email   = COALESCE(EXCLUDED.sales_email, dim_merchant.sales_email),
                    referral_partner = COALESCE(EXCLUDED.referral_partner, dim_merchant.referral_partner),
                    risk_level    = COALESCE(EXCLUDED.risk_level, dim_merchant.risk_level)
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertMerchantSql);
            log.info("Upserted Merchants for tenant {}", tId);

            // ── 2. Upsert Stores ─────────────────────────────────────────────────────
            //
            // FIX BUG: same root cause as merchants — the conflict key
            // COALESCE(merchant_store_internal_id, sid, CONCAT('STORE_', mid)) could
            // resolve differently between uploads when merchant_store_internal_id is blank
            // in one file but not another, creating duplicate store rows.
            //
            // The DO UPDATE SET now explicitly covers store_name (name) and
            // store_legal_name (legal_name) so both fields update in place.
            // SID and internal_id are NEVER overwritten — they are immutable identifiers.
            String upsertStoreSql = """
                INSERT INTO dim_store (tenant_id, internal_id, merchant_id, sid, name, legal_name, address, city, state, postal_code, mcc, status, created_date)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(NULLIF(TRIM(merchant_store_internal_id), ''), 'SID_' || TRIM(s.sid), CONCAT('STORE_', s.mid)),
                    MAX(m.merchant_id),
                    s.sid,
                    MAX(COALESCE(NULLIF(TRIM(store_name), ''), NULLIF(TRIM(merchant_name), ''))),
                    MAX(store_legal_name),
                    MAX(s.address), MAX(s.city), MAX(s.state), MAX(s.postal_code),
                    MAX(s.business_mcc),
                    COALESCE(MAX(store_status), 'ACTIVE'),
                    MAX(merchant_store_created_date)
                FROM stg_merchant_master_raw s
                JOIN dim_merchant m ON s.mid = m.mid AND m.tenant_id = TID
                WHERE s.tenant_id = TID AND NULLIF(TRIM(s.sid), '') IS NOT NULL
                GROUP BY s.tenant_id, COALESCE(NULLIF(TRIM(merchant_store_internal_id), ''), 'SID_' || TRIM(s.sid), CONCAT('STORE_', s.mid)), s.sid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    -- SID and internal_id are IMMUTABLE — never overwritten.
                    -- Only mutable descriptive fields updated so name / legal_name
                    -- changes in the file take effect without creating a new store row.
                    merchant_id = EXCLUDED.merchant_id,
                    name        = COALESCE(NULLIF(TRIM(EXCLUDED.name), ''), dim_store.name),
                    legal_name  = COALESCE(NULLIF(TRIM(EXCLUDED.legal_name), ''), dim_store.legal_name),
                    mcc         = COALESCE(EXCLUDED.mcc, dim_store.mcc),
                    address     = COALESCE(EXCLUDED.address, dim_store.address),
                    city        = COALESCE(EXCLUDED.city, dim_store.city),
                    state       = COALESCE(EXCLUDED.state, dim_store.state),
                    status      = COALESCE(EXCLUDED.status, dim_store.status)
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertStoreSql);
            log.info("Upserted Stores for tenant {}", tId);

            // ── 3. Upsert Terminals ───────────────────────────────────────────────────
            //
            // FIX BUG: same pattern — conflict key resolved differently when
            // terminal_internal_id was blank in some uploads.
            // TID and internal_id are IMMUTABLE — never overwritten.
            String upsertTerminalSql = """
                INSERT INTO dim_terminal (tenant_id, internal_id, store_id, tid, device_number, type, status, created_date)
                SELECT
                    CAST(TID AS INTEGER),
                    COALESCE(NULLIF(TRIM(terminal_internal_id), ''), 'TID_' || TRIM(raw.tid), CONCAT('TERM_', raw.mid)),
                    MAX(s.store_id),
                    raw.tid,
                    MAX(terminal_device_number),
                    MAX(terminal_type),
                    COALESCE(MAX(terminal_status), 'ACTIVE'),
                    MAX(terminal_created_date)
                FROM stg_merchant_master_raw raw
                JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                JOIN dim_store s ON s.merchant_id = m.merchant_id
                    AND (s.sid = raw.sid OR s.internal_id = COALESCE(NULLIF(TRIM(raw.merchant_store_internal_id), ''), 'SID_' || TRIM(raw.sid)))
                    AND s.tenant_id = TID
                WHERE raw.tenant_id = TID
                  AND NULLIF(TRIM(raw.tid), '') IS NOT NULL
                GROUP BY raw.tenant_id,
                         COALESCE(NULLIF(TRIM(terminal_internal_id), ''), 'TID_' || TRIM(raw.tid), CONCAT('TERM_', raw.mid)),
                         raw.tid
                ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                    -- TID and internal_id are IMMUTABLE — never overwritten.
                    device_number = COALESCE(EXCLUDED.device_number, dim_terminal.device_number),
                    type          = COALESCE(EXCLUDED.type, dim_terminal.type),
                    status        = COALESCE(EXCLUDED.status, dim_terminal.status)
                """.replace("TID", tId);
            jdbcTemplate.execute(upsertTerminalSql);
            log.info("Upserted Terminals for tenant {}", tId);

            // ── 4/5/6. Contacts, Risk Profile, Bank Accounts (parallel) ──────────────
            long t456 = System.currentTimeMillis();
            java.util.concurrent.ExecutorService dimExec = java.util.concurrent.Executors.newFixedThreadPool(3,
                r -> { Thread t = new Thread(r, "merchant-dim-"); t.setDaemon(true); return t; });
            try {
                java.util.List<java.util.concurrent.CompletableFuture<Void>> tasks = new java.util.ArrayList<>();

                final String batchMerchantIds = """
                    SELECT DISTINCT m.merchant_id FROM dim_merchant m
                    JOIN stg_merchant_master_raw raw ON raw.mid = m.mid
                        AND raw.tenant_id = m.tenant_id
                    WHERE m.tenant_id = TID
                    """.replace("TID", tId);

                tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    long t = System.currentTimeMillis();
                    jdbcTemplate.execute(
                        "DELETE FROM merchant_contact WHERE tenant_id = " + tId
                        + " AND merchant_id IN (" + batchMerchantIds + ")");
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
                    log.info(String.format("  [parallel] contacts          %.2fs", (System.currentTimeMillis() - t) / 1000.0));
                }, dimExec));

                tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    long t = System.currentTimeMillis();
                    jdbcTemplate.execute(
                        "DELETE FROM merchant_risk_profile WHERE tenant_id = " + tId
                        + " AND merchant_id IN (" + batchMerchantIds + ")");
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
                    log.info(String.format("  [parallel] risk_profile      %.2fs", (System.currentTimeMillis() - t) / 1000.0));
                }, dimExec));

                tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    long t = System.currentTimeMillis();
                    jdbcTemplate.execute(
                        "DELETE FROM dim_bank_account WHERE tenant_id = " + tId
                        + " AND store_id IN ("
                        + "  SELECT s.store_id FROM dim_store s WHERE s.tenant_id = " + tId
                        + "   AND s.merchant_id IN (" + batchMerchantIds + "))");
                    jdbcTemplate.execute("""
                        INSERT INTO dim_bank_account (tenant_id, store_id, bank_name, account_number, swift_code, iban)
                        SELECT CAST(TID AS INTEGER), MAX(s.store_id), MAX(bank_name), bank_account_number, MAX(swift_code), MAX(iban_number)
                        FROM stg_merchant_master_raw raw
                        JOIN dim_merchant m ON raw.mid = m.mid AND m.tenant_id = TID
                        JOIN dim_store s ON s.merchant_id = m.merchant_id
                            AND (s.sid = raw.sid OR s.internal_id = COALESCE(NULLIF(TRIM(raw.merchant_store_internal_id), ''), 'SID_' || TRIM(raw.sid)))
                            AND s.tenant_id = TID
                        WHERE raw.tenant_id = TID AND raw.bank_account_number IS NOT NULL
                        GROUP BY raw.tenant_id, bank_account_number
                        """.replace("TID", tId));
                    log.info(String.format("  [parallel] bank_accounts     %.2fs", (System.currentTimeMillis() - t) / 1000.0));
                }, dimExec));

                java.util.concurrent.CompletableFuture.allOf(
                    tasks.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            } finally {
                dimExec.shutdown();
            }
            log.info(String.format("contacts+risk+bank (parallel) completed in %.1fs",
                (System.currentTimeMillis() - t456) / 1000.0));
            log.info(String.format("upsertDimensions completed in %.1fs", (System.currentTimeMillis() - stepStart) / 1000.0));

            // ── 7. Auto-assign unmapped sales users ───────────────────────────────────
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
                    log.info("Auto-assigned {} unmapped sales users for tenant {}", assigned, tId);
                }
            } catch (Exception e) {
                log.warn("Auto-assign failed (non-fatal): {}", e.getMessage());
            }

            return RepeatStatus.FINISHED;
        };
    }
}
