package com.acquira.batch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dedicated DCC REVENUE feed pipeline — SEPARATE from the transaction,
 * merchant-master and rental jobs, same routing as rentals (screen upload,
 * Server File Processor, scheduled pull), decision 2026-08-31.
 *
 * File shape (header-name mapped, order-independent):
 *   SID, Tenant Id, Merchant Share, Acquirer Share, Date        (store level)
 *   MID, Tenant Id, Acquirer Share [, Merchant Share], Date     (merchant level)
 * A row resolves by SID when present, else by MID (leading zeros ignored —
 * feed MIDs are zero-padded); MID rows land with store_id/sid NULL, which is
 * all the summary layer needs since AncillarySql rolls up per merchant.
 * Amounts are tenant base currency, MAJOR units. The "Tenant Id" column
 * (bank short code, institution id, or numeric tenant id) is VALIDATED
 * against the tenant the upload resolved to — a mismatched row is REJECTED;
 * the file column never routes data into another tenant.
 *
 * REPLACE-BY-DATE (unlike rentals' hash dedupe): the apply step DELETEs
 * fact_dcc_revenue for exactly the (tenant, dates) staged PENDING, then
 * inserts fresh — re-uploading a corrected day fully supersedes the previous
 * numbers and is idempotent on repeat upload. SID resolves via dim_store;
 * unknown SIDs stay UNMATCHED in staging (no dim auto-create) and apply on a
 * later load once the merchant master catches up.
 *
 * After apply, AncillarySql re-derives the dcc_* columns on
 * sum_daily_merchant / sum_daily_finance_rollup for the touched dates, and
 * CacheEvictionJobListener clears the report caches — dashboards reflect the
 * new revenue without waiting out the 6h TTL.
 *
 * Jobs:
 *   dccLoadJob   — file path (upload / server file): clean tenant staging ->
 *                  ingest file -> validate + apply.
 *   dbPullDccJob — scheduled pull: IntegrationPullService stages the rows
 *                  itself, then this job runs validate + apply only.
 */
@Configuration
public class DccRevenueJobConfig {

    private static final Logger log = LoggerFactory.getLogger(DccRevenueJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private MdcStepListener mdcStepListener;

    @org.springframework.beans.factory.annotation.Autowired
    private IngestRunJobListener ingestRunJobListener;

    @org.springframework.beans.factory.annotation.Autowired
    private CacheEvictionJobListener cacheEvictionJobListener;

    public DccRevenueJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Plain staging holder — JDBC-written, so no JPA entity needed. */
    public static class DccRow {
        Long tenantId;
        String sid;
        String mid;
        String fileTenantId;
        java.math.BigDecimal merchantShare;
        java.math.BigDecimal acquirerShare;
        java.time.LocalDate paymentDate;
    }

    @Bean
    public Job dccLoadJob(
            @org.springframework.beans.factory.annotation.Qualifier("cleanDccStagingStep") Step cleanDccStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("ingestDccStep") Step ingestDccStep,
            @org.springframework.beans.factory.annotation.Qualifier("applyDccStep") Step applyDccStep) {
        return new JobBuilder("dccLoadJob", jobRepository)
                .start(cleanDccStagingStep)
                .next(ingestDccStep)
                .next(applyDccStep)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .build();
    }

    @Bean
    public Job dbPullDccJob(
            @org.springframework.beans.factory.annotation.Qualifier("applyDccStep") Step applyDccStep) {
        return new JobBuilder("dbPullDccJob", jobRepository)
                .start(applyDccStep)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .build();
    }

    // ── Step 1: wipe this tenant's previous DCC staging ────────────────────
    // Staging rows persist AFTER the job (PROCESSED/REJECTED/UNMATCHED) so the
    // screen can show exceptions; each new load replaces the previous load's
    // rows for that tenant only.
    @Bean
    public Step cleanDccStagingStep(
            @org.springframework.beans.factory.annotation.Qualifier("cleanDccStagingTasklet") Tasklet cleanDccStagingTasklet) {
        return new StepBuilder("cleanDccStagingStep", jobRepository)
                .tasklet(cleanDccStagingTasklet, transactionManager)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet cleanDccStagingTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            int n = jdbcTemplate.update("DELETE FROM stg_dcc_revenue_raw WHERE tenant_id = ?", tenantId);
            log.info("[DCC] Cleared {} previous staging rows for tenant {}", n, tenantId);
            return RepeatStatus.FINISHED;
        };
    }

    // ── Step 2: file -> staging ────────────────────────────────────────────

    @Bean
    public Step ingestDccStep(
            @org.springframework.beans.factory.annotation.Qualifier("dccFileReader") org.springframework.batch.item.ItemStreamReader<DccRow> dccFileReader,
            @org.springframework.beans.factory.annotation.Qualifier("dccTenantProcessor") ItemProcessor<DccRow, DccRow> dccTenantProcessor,
            @org.springframework.beans.factory.annotation.Qualifier("dccWriter") ItemWriter<DccRow> dccWriter) {
        return new StepBuilder("ingestDccStep", jobRepository)
                .<DccRow, DccRow>chunk(1000, transactionManager)
                .reader(dccFileReader)
                .processor(dccTenantProcessor)
                .writer(dccWriter)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<DccRow, DccRow> dccTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId) {
        return item -> { item.tenantId = tenantId; return item; };
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.ItemStreamReader<DccRow> dccFileReader(
            @Value("#{jobParameters['fullPath']}") String fullPath) {
        ExcelItemReader<DccRow> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(fullPath));
        reader.setLinesToSkip(1);

        // Header lookup is normalized (lowercase, spaces/underscores stripped),
        // so "Tenant Id" / "TenantID" / "tenant_id" all resolve. "Date" falls
        // back to "Payment Date" for feeds that reuse the transaction wording.
        reader.setRowMapper((row, rowNum) -> {
            DccRow r = new DccRow();
            r.sid = MerchantMasterJobConfig.normalizeSid(reader.getCellValue(row, "SID"));
            String mid = reader.getCellValue(row, "MID");
            if (mid == null || mid.trim().isEmpty()) mid = reader.getCellValue(row, "Merchant Id");
            r.mid = MerchantMasterJobConfig.normalizeSid(mid);
            r.fileTenantId = reader.getCellValue(row, "Tenant Id");
            r.merchantShare = parseDecimal(reader.getCellValue(row, "Merchant Share"));
            r.acquirerShare = parseDecimal(reader.getCellValue(row, "Acquirer Share"));
            String d = reader.getCellValue(row, "Date");
            if (d == null || d.trim().isEmpty()) d = reader.getCellValue(row, "Payment Date");
            r.paymentDate = parseDate(d);
            return r;
        });

        reader.setCsvRowMapper((rd, rowNum) -> {
            @SuppressWarnings("unchecked")
            ExcelItemReader<DccRow> rr = (ExcelItemReader<DccRow>) rd;
            DccRow r = new DccRow();
            r.sid = MerchantMasterJobConfig.normalizeSid(rr.getCsvCellValue("SID"));
            String mid = rr.getCsvCellValue("MID");
            if (mid == null || mid.trim().isEmpty()) mid = rr.getCsvCellValue("Merchant Id");
            r.mid = MerchantMasterJobConfig.normalizeSid(mid);
            r.fileTenantId = rr.getCsvCellValue("Tenant Id");
            r.merchantShare = parseDecimal(rr.getCsvCellValue("Merchant Share"));
            r.acquirerShare = parseDecimal(rr.getCsvCellValue("Acquirer Share"));
            String d = rr.getCsvCellValue("Date");
            if (d == null || d.trim().isEmpty()) d = rr.getCsvCellValue("Payment Date");
            r.paymentDate = parseDate(d);
            return r;
        });

        return reader;
    }

    private java.math.BigDecimal parseDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try { return new java.math.BigDecimal(val.replaceAll(",", "").trim()); }
        catch (Exception e) { return null; }
    }

    /**
     * Delegates to {@link FeedDateParser} — shared with the rental feed so the
     * BH export format ('08-MAY-26': two-digit year AND upper-case month)
     * parses identically in both. See that class for why a plain ofPattern
     * list silently rejected every row of an AFS Bahrain file.
     */
    private java.time.LocalDate parseDate(String val) {
        java.time.LocalDate d = FeedDateParser.parse(val);
        if (d == null && val != null && !val.trim().isEmpty()) {
            log.warn("[DCC] Could not parse Date '{}' — storing NULL (row will be REJECTED)", val);
        }
        return d;
    }

    @Bean
    public ItemWriter<DccRow> dccWriter() {
        final String sqlPrefix = "INSERT INTO stg_dcc_revenue_raw "
                + "(tenant_id, sid, mid, file_tenant_id, merchant_share, acquirer_share, payment_date, load_time) VALUES ";
        final String onePh = "(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        return chunk -> {
            java.util.List<? extends DccRow> items = chunk.getItems();
            if (items.isEmpty()) return;
            StringBuilder sql = new StringBuilder(sqlPrefix.length() + items.size() * (onePh.length() + 1));
            sql.append(sqlPrefix);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append(onePh);
            }
            jdbcTemplate.update(sql.toString(), ps -> {
                int p = 1;
                for (DccRow r : items) {
                    ps.setObject(p++, r.tenantId, java.sql.Types.INTEGER);
                    ps.setString(p++, trunc(r.sid, 50));
                    ps.setString(p++, trunc(r.mid, 50));
                    ps.setString(p++, trunc(r.fileTenantId, 50));
                    ps.setBigDecimal(p++, r.merchantShare);
                    ps.setBigDecimal(p++, r.acquirerShare);
                    ps.setObject(p++, r.paymentDate, java.sql.Types.DATE);
                }
            });
        };
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ── Step 3: validate, replace-by-date, resolve dims, apply ─────────────

    @Bean
    public Step applyDccStep(
            @org.springframework.beans.factory.annotation.Qualifier("applyDccTasklet") Tasklet applyDccTasklet) {
        return new StepBuilder("applyDccStep", jobRepository)
                .tasklet(applyDccTasklet, transactionManager)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet applyDccTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            long t0 = System.currentTimeMillis();

            // 1. Reject rows that can never apply. A row needs SID or MID —
            //    SID wins when both are present (a MID-level file has no SID).
            int rejMissing = jdbcTemplate.update(
                "UPDATE stg_dcc_revenue_raw SET status='REJECTED', "
                + "error_message='Missing SID/MID, share amount or date' "
                + "WHERE tenant_id=? AND status='PENDING' AND ((sid IS NULL AND mid IS NULL) "
                + "OR (merchant_share IS NULL AND acquirer_share IS NULL) OR payment_date IS NULL)",
                tenantId);

            // 2. Tenant-isolation check: the file's own "Tenant Id" column must
            //    match the tenant this upload resolved to (numeric id, bank
            //    short code, or institution id — case-insensitive). A mismatch
            //    is a mis-labelled or mis-routed extract; refusing the ROW (not
            //    remapping it) keeps the file column from ever routing data.
            int rejTenant = jdbcTemplate.update(
                "UPDATE stg_dcc_revenue_raw r SET status='REJECTED', "
                + "error_message='Tenant Id '''||r.file_tenant_id||''' does not match the upload tenant' "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.file_tenant_id IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM tenant t WHERE t.tenant_id=r.tenant_id "
                // "12.0" = an Excel numeric cell read back as text; strip the
                // decimal tail before comparing to the numeric id.
                + "  AND (CAST(t.tenant_id AS TEXT)=regexp_replace(TRIM(r.file_tenant_id), '\\.0+$', '') "
                + "       OR UPPER(t.bank_short_code)=UPPER(TRIM(r.file_tenant_id)) "
                + "       OR UPPER(t.institution_id)=UPPER(TRIM(r.file_tenant_id))))",
                tenantId);

            // 3. Mark UNMATCHED — SIDs dim_store doesn't know yet. Deliberately
            //    NO dim auto-create from a DCC file; rows stay visible on the
            //    screen and apply on the next load after the merchant master
            //    catches up.
            int unmatched = jdbcTemplate.update(
                "UPDATE stg_dcc_revenue_raw r SET status='UNMATCHED', "
                + "error_message='SID not found in dim_store' "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.sid IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM dim_store d WHERE d.tenant_id=r.tenant_id AND d.sid=r.sid)",
                tenantId);

            //    MID rows resolve against dim_merchant instead; leading zeros
            //    are ignored on both sides (feed MIDs are zero-padded).
            int unmatchedMid = jdbcTemplate.update(
                "UPDATE stg_dcc_revenue_raw r SET status='UNMATCHED', "
                + "error_message='MID not found in dim_merchant' "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.sid IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.tenant_id=r.tenant_id "
                + "  AND LTRIM(m.mid, '0') = LTRIM(r.mid, '0'))",
                tenantId);

            // 4. REPLACE-BY-DATE: wipe this tenant's fact rows for exactly the
            //    dates about to apply, so a re-uploaded day is recalculated
            //    from scratch. Scoped to (tenant, dates-in-this-load) only —
            //    other days and other tenants are never touched.
            int wiped = jdbcTemplate.update(
                "DELETE FROM fact_dcc_revenue f WHERE f.tenant_id=? AND f.payment_date IN ("
                + "SELECT DISTINCT payment_date FROM stg_dcc_revenue_raw "
                + "WHERE tenant_id=? AND status='PENDING')",
                tenantId, tenantId);

            // 5. Apply — SID rows resolve via dim_store (denormalizing
            //    merchant_id so merchant rollups never join at read time).
            int inserted = jdbcTemplate.update(
                "INSERT INTO fact_dcc_revenue (tenant_id, merchant_id, store_id, sid, "
                + "merchant_share, acquirer_share, payment_date) "
                + "SELECT r.tenant_id, s.merchant_id, s.store_id, r.sid, "
                + "COALESCE(r.merchant_share,0), COALESCE(r.acquirer_share,0), r.payment_date "
                + "FROM stg_dcc_revenue_raw r JOIN dim_store s ON s.tenant_id=r.tenant_id AND s.sid=r.sid "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.sid IS NOT NULL",
                tenantId);

            //    MID rows resolve via dim_merchant, store_id/sid stay NULL.
            //    dim_merchant can carry duplicate MID rows (re-uploaded
            //    masters), so pick one merchant_id per normalized MID —
            //    MIN() keeps the apply deterministic and single-row.
            int insertedMid = jdbcTemplate.update(
                "INSERT INTO fact_dcc_revenue (tenant_id, merchant_id, store_id, sid, "
                + "merchant_share, acquirer_share, payment_date) "
                + "SELECT r.tenant_id, mm.merchant_id, NULL, NULL, "
                + "COALESCE(r.merchant_share,0), COALESCE(r.acquirer_share,0), r.payment_date "
                + "FROM stg_dcc_revenue_raw r JOIN ("
                + "  SELECT LTRIM(mid, '0') AS mid_key, MIN(merchant_id) AS merchant_id "
                + "  FROM dim_merchant WHERE tenant_id=? GROUP BY LTRIM(mid, '0')"
                + ") mm ON mm.mid_key = LTRIM(r.mid, '0') "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.sid IS NULL",
                tenantId, tenantId);
            inserted += insertedMid;

            // Collect the touched dates BEFORE flipping status, then mark done.
            java.util.List<java.time.LocalDate> dates = jdbcTemplate.query(
                "SELECT DISTINCT payment_date FROM stg_dcc_revenue_raw WHERE tenant_id=? AND status='PENDING'",
                (rs, i) -> rs.getDate(1).toLocalDate(), tenantId);
            int processed = jdbcTemplate.update(
                "UPDATE stg_dcc_revenue_raw SET status='PROCESSED', error_message=NULL "
                + "WHERE tenant_id=? AND status='PENDING'",
                tenantId);

            // 6. Re-derive the ancillary summary columns for the touched dates
            //    (a re-upload that DROPPED a previously-loaded day still zeroes
            //    correctly: that day was in this wipe's date set only if staged,
            //    so days absent from the file keep their last-applied values by
            //    design — replace is per uploaded date, not per file history).
            com.acquira.common.service.AncillarySql.applyDates(jdbcTemplate, tenantId, dates);

            log.info("[DCC] Tenant {} apply: {} fact rows inserted ({} MID-level) over {} date(s) "
                    + "({} prior rows wiped), {} processed, {} unmatched ({} MID), "
                    + "{} rejected ({} tenant-mismatch) in {} ms",
                    tenantId, inserted, insertedMid, dates.size(), wiped,
                    processed, unmatched + unmatchedMid, unmatchedMid,
                    rejMissing + rejTenant, rejTenant,
                    System.currentTimeMillis() - t0);
            return RepeatStatus.FINISHED;
        };
    }
}
