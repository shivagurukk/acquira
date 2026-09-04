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
 * Dedicated RENTAL feed pipeline — deliberately SEPARATE from the transaction
 * and merchant-master jobs (decision 2026-08-29): rentals share the upload
 * screen, Server File Processor and scheduled pull ROUTING, but none of the
 * transaction code paths.
 *
 * File shape (dummy headers until the real feeds are confirmed):
 *   AMS tenants: Entity Name, MID, SID, TID, Rental Amount, Payment Date
 *   CMM tenants: Entity Name, SID, Rental Amount, Payment Date
 *
 * LEVEL IS DERIVED, NEVER SUPPLIED:
 *   MID + SID + TID -> TERMINAL;  MID + SID -> STORE;  MID only -> MERCHANT.
 *   CMM feeds carry SID only -> always STORE. Invalid id combinations are
 *   marked REJECTED in stg_rental_raw with a reason (surfaced on the screen)
 *   and never reach fact_rental.
 *
 * Amounts are tenant base currency, MAJOR units for both input formats — no
 * minor-unit division. Each row is a dated charge (payment_date); dedupe is
 * md5(ids|amount|date) per tenant, so re-uploading a file is a no-op while a
 * new date or amount lands as a new charge.
 *
 * Jobs:
 *   rentalLoadJob   — file path (upload / server file): clean tenant staging ->
 *                     ingest file -> validate + apply.
 *   dbPullRentalJob — scheduled pull: IntegrationPullService stages the rows
 *                     itself, then this job runs validate + apply only (same
 *                     pattern as dbPullMerchantJob).
 */
@Configuration
public class RentalJobConfig {

    private static final Logger log = LoggerFactory.getLogger(RentalJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private MdcStepListener mdcStepListener;

    @org.springframework.beans.factory.annotation.Autowired
    private IngestRunJobListener ingestRunJobListener;

    @org.springframework.beans.factory.annotation.Autowired
    private CacheEvictionJobListener cacheEvictionJobListener;

    public RentalJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Plain staging holder — JDBC-written, so no JPA entity needed. */
    public static class RentalRow {
        Long tenantId;
        String entityName;
        String mid;
        String sid;
        String tid;
        java.math.BigDecimal rentalAmount;
        java.time.LocalDate paymentDate;
    }

    @Bean
    public Job rentalLoadJob(
            @org.springframework.beans.factory.annotation.Qualifier("cleanRentalStagingStep") Step cleanRentalStagingStep,
            @org.springframework.beans.factory.annotation.Qualifier("ingestRentalStep") Step ingestRentalStep,
            @org.springframework.beans.factory.annotation.Qualifier("applyRentalStep") Step applyRentalStep) {
        return new JobBuilder("rentalLoadJob", jobRepository)
                .start(cleanRentalStagingStep)
                .next(ingestRentalStep)
                .next(applyRentalStep)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .build();
    }

    @Bean
    public Job dbPullRentalJob(
            @org.springframework.beans.factory.annotation.Qualifier("applyRentalStep") Step applyRentalStep) {
        return new JobBuilder("dbPullRentalJob", jobRepository)
                .start(applyRentalStep)
                .listener(ingestRunJobListener)
                .listener(cacheEvictionJobListener)
                .build();
    }

    // ── Step 1: wipe this tenant's previous rental staging ─────────────────
    // Staging rows persist AFTER the job (PROCESSED/REJECTED/UNMATCHED) so the
    // screen can show exceptions; each new load replaces the previous load's
    // rows for that tenant only.
    @Bean
    public Step cleanRentalStagingStep(
            @org.springframework.beans.factory.annotation.Qualifier("cleanRentalStagingTasklet") Tasklet cleanRentalStagingTasklet) {
        return new StepBuilder("cleanRentalStagingStep", jobRepository)
                .tasklet(cleanRentalStagingTasklet, transactionManager)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet cleanRentalStagingTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            int n = jdbcTemplate.update("DELETE FROM stg_rental_raw WHERE tenant_id = ?", tenantId);
            log.info("[Rental] Cleared {} previous staging rows for tenant {}", n, tenantId);
            return RepeatStatus.FINISHED;
        };
    }

    // ── Step 2: file -> staging ────────────────────────────────────────────

    @Bean
    public Step ingestRentalStep(
            @org.springframework.beans.factory.annotation.Qualifier("rentalFileReader") org.springframework.batch.item.ItemStreamReader<RentalRow> rentalFileReader,
            @org.springframework.beans.factory.annotation.Qualifier("rentalTenantProcessor") ItemProcessor<RentalRow, RentalRow> rentalTenantProcessor,
            @org.springframework.beans.factory.annotation.Qualifier("rentalWriter") ItemWriter<RentalRow> rentalWriter) {
        return new StepBuilder("ingestRentalStep", jobRepository)
                .<RentalRow, RentalRow>chunk(1000, transactionManager)
                .reader(rentalFileReader)
                .processor(rentalTenantProcessor)
                .writer(rentalWriter)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<RentalRow, RentalRow> rentalTenantProcessor(
            @Value("#{jobParameters['tenantId']}") Long tenantId) {
        return item -> { item.tenantId = tenantId; return item; };
    }

    @Bean
    @StepScope
    public org.springframework.batch.item.ItemStreamReader<RentalRow> rentalFileReader(
            @Value("#{jobParameters['fullPath']}") String fullPath) {
        ExcelItemReader<RentalRow> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(fullPath));
        reader.setLinesToSkip(1);

        reader.setRowMapper((row, rowNum) -> {
            RentalRow r = new RentalRow();
            r.entityName = reader.getCellValue(row, "Entity Name");
            r.mid = MerchantMasterJobConfig.normalizeSid(reader.getCellValue(row, "MID"));
            r.sid = MerchantMasterJobConfig.normalizeSid(reader.getCellValue(row, "SID"));
            r.tid = MerchantMasterJobConfig.normalizeSid(reader.getCellValue(row, "TID"));
            r.rentalAmount = parseDecimal(reader.getCellValue(row, "Rental Amount"));
            r.paymentDate = parseDate(reader.getCellValue(row, "Payment Date"));
            return r;
        });

        reader.setCsvRowMapper((rd, rowNum) -> {
            @SuppressWarnings("unchecked")
            ExcelItemReader<RentalRow> rr = (ExcelItemReader<RentalRow>) rd;
            RentalRow r = new RentalRow();
            r.entityName = rr.getCsvCellValue("Entity Name");
            r.mid = MerchantMasterJobConfig.normalizeSid(rr.getCsvCellValue("MID"));
            r.sid = MerchantMasterJobConfig.normalizeSid(rr.getCsvCellValue("SID"));
            r.tid = MerchantMasterJobConfig.normalizeSid(rr.getCsvCellValue("TID"));
            r.rentalAmount = parseDecimal(rr.getCsvCellValue("Rental Amount"));
            r.paymentDate = parseDate(rr.getCsvCellValue("Payment Date"));
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
     * Delegates to {@link FeedDateParser} — shared with the DCC feed so the BH
     * export format ('08-MAY-26': two-digit year AND upper-case month) parses
     * identically in both. See that class for why a plain ofPattern list
     * silently rejected every row of an AFS Bahrain file.
     */
    private java.time.LocalDate parseDate(String val) {
        java.time.LocalDate d = FeedDateParser.parse(val);
        if (d == null && val != null && !val.trim().isEmpty()) {
            log.warn("[Rental] Could not parse Payment Date '{}' — storing NULL (row will be REJECTED)", val);
        }
        return d;
    }

    @Bean
    public ItemWriter<RentalRow> rentalWriter() {
        final String sqlPrefix = "INSERT INTO stg_rental_raw "
                + "(tenant_id, entity_name, mid, sid, tid, rental_amount, payment_date, load_time) VALUES ";
        final String onePh = "(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
        return chunk -> {
            java.util.List<? extends RentalRow> items = chunk.getItems();
            if (items.isEmpty()) return;
            StringBuilder sql = new StringBuilder(sqlPrefix.length() + items.size() * (onePh.length() + 1));
            sql.append(sqlPrefix);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append(onePh);
            }
            jdbcTemplate.update(sql.toString(), ps -> {
                int p = 1;
                for (RentalRow r : items) {
                    ps.setObject(p++, r.tenantId, java.sql.Types.INTEGER);
                    ps.setString(p++, trunc(r.entityName, 100));
                    ps.setString(p++, trunc(r.mid, 50));
                    ps.setString(p++, trunc(r.sid, 50));
                    ps.setString(p++, trunc(r.tid, 50));
                    ps.setBigDecimal(p++, r.rentalAmount);
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

    // ── Step 3: validate, derive level, resolve dims, apply ────────────────

    @Bean
    public Step applyRentalStep(
            @org.springframework.beans.factory.annotation.Qualifier("applyRentalTasklet") Tasklet applyRentalTasklet) {
        return new StepBuilder("applyRentalStep", jobRepository)
                .tasklet(applyRentalTasklet, transactionManager)
                .listener(mdcStepListener)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet applyRentalTasklet(@Value("#{jobParameters['tenantId']}") Long tenantId) {
        return (contribution, chunkContext) -> {
            long t0 = System.currentTimeMillis();

            String inputFormat;
            try {
                inputFormat = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(input_format,'CMM') FROM tenant WHERE tenant_id = ?",
                        String.class, tenantId);
            } catch (Exception e) {
                log.warn("[Rental] Could not read tenant {} input_format ({}) - assuming CMM", tenantId, e.toString());
                inputFormat = "CMM";
            }
            boolean ams = "AMS".equalsIgnoreCase(inputFormat);

            // 1. Reject rows that can never apply. Level is DERIVED from id
            //    presence, so an impossible combination must fail loudly here
            //    rather than land on the wrong entity.
            int rejMissing = jdbcTemplate.update(
                "UPDATE stg_rental_raw SET status='REJECTED', error_message='Missing rental amount or payment date' "
                + "WHERE tenant_id=? AND status='PENDING' AND (rental_amount IS NULL OR payment_date IS NULL)",
                tenantId);
            int rejNoIds = jdbcTemplate.update(
                "UPDATE stg_rental_raw SET status='REJECTED', error_message='No MID/SID/TID identifier on the row' "
                + "WHERE tenant_id=? AND status='PENDING' AND mid IS NULL AND sid IS NULL AND tid IS NULL",
                tenantId);
            int rejTidNoSid = jdbcTemplate.update(
                "UPDATE stg_rental_raw SET status='REJECTED', error_message='TID present without its SID' "
                + "WHERE tenant_id=? AND status='PENDING' AND tid IS NOT NULL AND sid IS NULL",
                tenantId);
            int rejFormat = 0;
            if (ams) {
                rejFormat = jdbcTemplate.update(
                    "UPDATE stg_rental_raw SET status='REJECTED', error_message='SID present without its MID' "
                    + "WHERE tenant_id=? AND status='PENDING' AND sid IS NOT NULL AND mid IS NULL",
                    tenantId);
            } else {
                // CMM feeds carry store-level (SID) rentals only.
                rejFormat = jdbcTemplate.update(
                    "UPDATE stg_rental_raw SET status='REJECTED', "
                    + "error_message='CMM rental feed carries store-level (SID) rentals only' "
                    + "WHERE tenant_id=? AND status='PENDING' AND (tid IS NOT NULL OR (mid IS NOT NULL AND sid IS NULL))",
                    tenantId);
            }

            // 2. Derive level + dedupe hash for the survivors.
            jdbcTemplate.update(
                "UPDATE stg_rental_raw SET "
                + "level = CASE WHEN tid IS NOT NULL THEN 'TERMINAL' "
                + "              WHEN sid IS NOT NULL THEN 'STORE' ELSE 'MERCHANT' END, "
                + "row_hash = md5(COALESCE(mid,'') || '|' || COALESCE(sid,'') || '|' || COALESCE(tid,'') || '|' "
                + "               || CAST(rental_amount AS TEXT) || '|' || CAST(payment_date AS TEXT)) "
                + "WHERE tenant_id=? AND status='PENDING'",
                tenantId);

            // 3. Mark UNMATCHED — ids the dims don't know yet. Deliberately NO
            //    dim auto-create from a rental file; rows stay visible on the
            //    screen and apply on the next load after the merchant master
            //    catches up.
            int unmatched = jdbcTemplate.update(
                "UPDATE stg_rental_raw r SET status='UNMATCHED', error_message = "
                + "  CASE r.level WHEN 'MERCHANT' THEN 'MID not found in dim_merchant' "
                + "               WHEN 'STORE' THEN 'SID not found in dim_store' "
                + "               ELSE 'TID not found in dim_terminal' END "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND ( "
                + "  (r.level='MERCHANT' AND NOT EXISTS (SELECT 1 FROM dim_merchant d WHERE d.tenant_id=r.tenant_id AND d.mid=r.mid)) "
                + "  OR (r.level='STORE' AND NOT EXISTS (SELECT 1 FROM dim_store d WHERE d.tenant_id=r.tenant_id AND d.sid=r.sid)) "
                + "  OR (r.level='TERMINAL' AND NOT EXISTS (SELECT 1 FROM dim_terminal d WHERE d.tenant_id=r.tenant_id AND d.tid=r.tid)) )",
                tenantId);

            // 4. Apply — one insert per level so each resolves its own dim chain.
            //    ON CONFLICT (tenant_id, row_hash) makes re-uploads no-ops.
            int insMerchant = jdbcTemplate.update(
                "INSERT INTO fact_rental (tenant_id, level, merchant_id, mid, sid, tid, rental_amount, payment_date, row_hash) "
                + "SELECT r.tenant_id, r.level, d.merchant_id, r.mid, r.sid, r.tid, r.rental_amount, r.payment_date, r.row_hash "
                + "FROM stg_rental_raw r JOIN dim_merchant d ON d.tenant_id=r.tenant_id AND d.mid=r.mid "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.level='MERCHANT' "
                + "ON CONFLICT (tenant_id, row_hash) DO NOTHING",
                tenantId);
            int insStore = jdbcTemplate.update(
                "INSERT INTO fact_rental (tenant_id, level, merchant_id, store_id, mid, sid, tid, rental_amount, payment_date, row_hash) "
                + "SELECT r.tenant_id, r.level, s.merchant_id, s.store_id, r.mid, r.sid, r.tid, r.rental_amount, r.payment_date, r.row_hash "
                + "FROM stg_rental_raw r JOIN dim_store s ON s.tenant_id=r.tenant_id AND s.sid=r.sid "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.level='STORE' "
                + "ON CONFLICT (tenant_id, row_hash) DO NOTHING",
                tenantId);
            int insTerminal = jdbcTemplate.update(
                "INSERT INTO fact_rental (tenant_id, level, merchant_id, store_id, terminal_id, mid, sid, tid, rental_amount, payment_date, row_hash) "
                + "SELECT r.tenant_id, r.level, s.merchant_id, t.store_id, t.terminal_id, r.mid, r.sid, r.tid, r.rental_amount, r.payment_date, r.row_hash "
                + "FROM stg_rental_raw r "
                + "JOIN dim_terminal t ON t.tenant_id=r.tenant_id AND t.tid=r.tid "
                + "LEFT JOIN dim_store s ON s.tenant_id=t.tenant_id AND s.store_id=t.store_id "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND r.level='TERMINAL' "
                + "ON CONFLICT (tenant_id, row_hash) DO NOTHING",
                tenantId);

            // A staged row whose hash already existed in fact_rental is a
            // duplicate of an earlier load, not an error.
            int duplicates = jdbcTemplate.update(
                "UPDATE stg_rental_raw r SET status='DUPLICATE', error_message='Charge already recorded (same ids, amount and date)' "
                + "WHERE r.tenant_id=? AND r.status='PENDING' AND EXISTS ("
                + "  SELECT 1 FROM fact_rental f WHERE f.tenant_id=r.tenant_id AND f.row_hash=r.row_hash "
                + "  AND f.created_at < (SELECT MIN(load_time) FROM stg_rental_raw x WHERE x.tenant_id=r.tenant_id))",
                tenantId);
            // Touched dates for the ancillary summary overlay — collected
            // BEFORE the status flip below empties the PENDING set.
            java.util.List<java.time.LocalDate> ancillaryDates = jdbcTemplate.query(
                "SELECT DISTINCT payment_date FROM stg_rental_raw "
                + "WHERE tenant_id=? AND status='PENDING' AND payment_date IS NOT NULL",
                (rs, i) -> rs.getDate(1).toLocalDate(), tenantId);
            int processed = jdbcTemplate.update(
                "UPDATE stg_rental_raw SET status='PROCESSED', error_message=NULL "
                + "WHERE tenant_id=? AND status='PENDING'",
                tenantId);

            // 5. Latest charge per entity onto the dims (convenience columns).
            jdbcTemplate.update(
                "UPDATE dim_merchant m SET rental_amount = l.rental_amount FROM ("
                + "  SELECT DISTINCT ON (merchant_id) merchant_id, rental_amount FROM fact_rental "
                + "  WHERE tenant_id=? AND level='MERCHANT' AND merchant_id IS NOT NULL "
                + "  ORDER BY merchant_id, payment_date DESC, rental_id DESC) l "
                + "WHERE m.tenant_id=? AND m.merchant_id=l.merchant_id",
                tenantId, tenantId);
            jdbcTemplate.update(
                "UPDATE dim_store s SET rental_amount = l.rental_amount FROM ("
                + "  SELECT DISTINCT ON (store_id) store_id, rental_amount FROM fact_rental "
                + "  WHERE tenant_id=? AND level='STORE' AND store_id IS NOT NULL "
                + "  ORDER BY store_id, payment_date DESC, rental_id DESC) l "
                + "WHERE s.tenant_id=? AND s.store_id=l.store_id",
                tenantId, tenantId);
            jdbcTemplate.update(
                "UPDATE dim_terminal t SET rental_amount = l.rental_amount FROM ("
                + "  SELECT DISTINCT ON (terminal_id) terminal_id, rental_amount FROM fact_rental "
                + "  WHERE tenant_id=? AND level='TERMINAL' AND terminal_id IS NOT NULL "
                + "  ORDER BY terminal_id, payment_date DESC, rental_id DESC) l "
                + "WHERE t.tenant_id=? AND t.terminal_id=l.terminal_id",
                tenantId, tenantId);

            // 6. Ancillary summary overlay: rental_amount onto
            //    sum_daily_merchant / sum_daily_finance_rollup for the loaded
            //    dates, so Net Spread reflects the charges without a summary
            //    rebuild. Duplicate rows are harmless here — the overlay
            //    re-derives from fact_rental, which the dedupe already guards.
            com.acquira.common.service.AncillarySql.applyDates(jdbcTemplate, tenantId, ancillaryDates);

            int rejected = rejMissing + rejNoIds + rejTidNoSid + rejFormat;
            log.info("[Rental] Tenant {} ({}) apply: {} merchant + {} store + {} terminal charges inserted, "
                    + "{} processed rows, {} duplicates, {} unmatched, {} rejected in {} ms",
                    tenantId, inputFormat, insMerchant, insStore, insTerminal,
                    processed, duplicates, unmatched, rejected, System.currentTimeMillis() - t0);
            return RepeatStatus.FINISHED;
        };
    }
}
