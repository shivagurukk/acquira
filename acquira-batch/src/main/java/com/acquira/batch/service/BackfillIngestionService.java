package com.acquira.batch.service;

import com.acquira.common.model.DataSourceConfig;
import com.acquira.common.repository.DataSourceConfigRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackfillIngestionService {

    private final UniversalDatabaseClient dbClient;
    private final DataSourceConfigRepository dataSourceRepo;
    private final JdbcTemplate jdbcTemplate; // For staging inserts & aggregation
    private final PartitionMaintenanceService partitionService;
    private final com.acquira.common.service.MonthlyMetricsRebuilder monthlyMetricsRebuilder;
    // Shared ingest pipeline pieces (2026-08-28) — the SAME beans the upload
    // job uses, so backfilled days price and summarize identically.
    private final FeeComputationService feeComputationService;
    private final SummaryPopulationService summaryPopulationService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    // Track single active job progress (simple singleton approach for now)
    private final AtomicReference<BackfillProgress> currentProgress = new AtomicReference<>(new BackfillProgress());

    // INGEST TRUST: backfill bypasses Spring Batch entirely, so it has to record
    // its own ledger row — see the openRun call in startBackfill.
    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.ingest.IngestRunRecorder ingestRunRecorder;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.ingest.IngestReconciliationService ingestReconciliation;

    @Data
    public static class BackfillRequest {
        private LocalDate startDate;
        private LocalDate endDate;
        private Long tenantId;
        private Long dataSourceId;
        private List<String> sourceQueries; // List of SQLs to run per date
    }

    @Data
    public static class BackfillProgress {
        private String status = "IDLE"; // IDLE, RUNNING, COMPLETED, FAILED
        private int totalDays;
        private int completedDays;
        private LocalDate currentDate;
        private List<String> errorMessages = new ArrayList<>();
        private long totalRowsIngested;
    }

    public BackfillProgress getProgress() {
        return currentProgress.get();
    }

    // Backfill writes fact + summary rows outside the Spring Batch jobs, so
    // CacheEvictionJobListener never fires for it — clear the report caches
    // here, same contract as BulkMigrationService.evictReportCaches.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.acquira.common.service.ReportCacheWarmup> reportCacheWarmup;

    private void evictReportCaches() {
        for (String name : com.acquira.common.config.ReportCacheConfig.ALL_CACHES) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
        reportCacheWarmup.ifAvailable(w -> w.requestWarm("backfill"));
    }

    @Async
    public CompletableFuture<Void> startBackfill(BackfillRequest request) {
        BackfillProgress progress = new BackfillProgress();
        progress.setStatus("RUNNING");
        progress.setTotalDays((int) (request.getEndDate().toEpochDay() - request.getStartDate().toEpochDay()) + 1);
        progress.setCurrentDate(request.getStartDate());
        currentProgress.set(progress);

        // Propagate tenant to this @Async worker thread so TenantAspect sets the
        // Postgres app.current_tenant RLS backstop. Every query below already
        // scopes by the explicit request.getTenantId(); this keeps the
        // defense-in-depth session var consistent with the other async paths and
        // avoids a bypass if RLS is ever forced. Cleared in the finally.
        com.acquira.common.config.TenantContext.setCurrentTenant(request.getTenantId());

        // INGEST TRUST: backfill is not a Spring Batch job, so IngestRunJobListener
        // never sees it. Before the ledger, a backfill that rewrote three months of
        // history left no trace an operator could find. Open the run explicitly.
        final Long ingestRunId = ingestRunRecorder.openRun(
                request.getTenantId(), com.acquira.common.ingest.IngestSource.BACKFILL,
                null, "backfill", null, "REPLACE",
                com.acquira.common.config.TenantContext.getCurrentTenant() == null ? "system" : "backfill",
                null);

        log.info("Starting Backfill for Tenant: {},  Range: {} to {}", request.getTenantId(), request.getStartDate(),
                request.getEndDate());

        try {
            // 0. Ensure Partitions exist for all years involved
            int startYear = request.getStartDate().getYear();
            int endYear = request.getEndDate().getYear();
            for (int year = startYear; year <= endYear; year++) {
                partitionService.ensurePartitionsForYear(year);
            }

            // 1. Get Source Config. NOTE: data_source_config is a GLOBAL registry
            // (no tenant_id column), so per-tenant ownership cannot be checked here —
            // access control is the SUPER_ADMIN gate on BackfillController, matching
            // how MigrationController guards its arbitrary-tenant endpoints.
            DataSourceConfig dsConfig = dataSourceRepo.findById(request.getDataSourceId())
                    .orElseThrow(() -> new RuntimeException("DataSource not found: " + request.getDataSourceId()));

            // Monthly metrics are derived per MONTH, not per day (see the call
            // after this loop) — collect the months whose days actually landed.
            java.util.Set<String> touchedMonths = new java.util.LinkedHashSet<>();
            // Summary population is batched the same way (2026-08-28): collect
            // the days whose FACT write succeeded, aggregate them all in ONE
            // summary pass after the loop. Per-day summary calls rebuilt every
            // month-grain rollup once per day — ~30x redundant work.
            java.util.List<java.sql.Date> landedDates = new java.util.ArrayList<>();

            LocalDate loopDate = request.getStartDate();
            while (!loopDate.isAfter(request.getEndDate())) {
                progress.setCurrentDate(loopDate);
                log.info("Processing Backfill Date: {}", loopDate);

                try {
                    processSingleDate(request.getTenantId(), dsConfig, request.getSourceQueries(), loopDate);
                    progress.setCompletedDays(progress.getCompletedDays() + 1);
                    touchedMonths.add(String.format("%04d-%02d", loopDate.getYear(), loopDate.getMonthValue()));
                    landedDates.add(java.sql.Date.valueOf(loopDate));
                } catch (Exception e) {
                    log.error("Failed to process date: " + loopDate, e);
                    progress.getErrorMessages().add("Date " + loopDate + ": " + e.getMessage());
                    // We continue to next date even if one fails
                }

                loopDate = loopDate.plusDays(1);
            }

            // ONE summary pass for every landed day — the SHARED
            // SummaryPopulationService (same clean-slate deletes, aggregations,
            // finance rollup, top-spending customer, sargable scopes and
            // per-tenant advisory lock as the upload job). Daily grains are
            // per-date so batching changes nothing; month grains are rebuilt
            // once per touched month instead of once per day. Must run BEFORE
            // the monthly merchant metrics below, which read sum_daily_merchant.
            if (!landedDates.isEmpty()) {
                try {
                    summaryPopulationService.populateForDates(request.getTenantId(), landedDates);
                } catch (Exception e) {
                    log.error("Batched summary population failed", e);
                    progress.getErrorMessages().add("Summaries: " + e.getMessage());
                }
            }

            // PERF: monthly merchant metrics are a whole-MONTH aggregate over
            // sum_daily_merchant. Deriving them inside the day loop re-computed
            // every month once per day in it — a 31-day run rebuilt the same
            // month 31 times, each time with a per-merchant SELECT+save. Doing
            // it once per touched month after all days have landed produces the
            // identical end state for a fraction of the work.
            // Per-month try/catch mirrors the per-day fault isolation above: one
            // month's failure must not skip the remaining months or flip a run
            // whose fact/summary data all landed into FAILED.
            for (String monthYear : touchedMonths) {
                try {
                    calculateDashboardMetrics(request.getTenantId(), monthYear);
                } catch (Exception e) {
                    log.error("Failed to rebuild monthly metrics for: " + monthYear, e);
                    progress.getErrorMessages().add("Month " + monthYear + " metrics: " + e.getMessage());
                }
            }

            progress.setStatus("COMPLETED");
            log.info("Backfill Completed.");

        } catch (Exception e) {
            log.error("Backfill Critical Failure", e);
            progress.setStatus("FAILED");
            progress.getErrorMessages().add("Critical: " + e.getMessage());
        } finally {
            // INGEST TRUST: close the ledger row on every terminal path, success or
            // failure. A backfill that died halfway still rewrote days, and that is
            // exactly the run someone will need to find later.
            try {
                ingestRunRecorder.updateCounts(ingestRunId, null, null, null, null, null, null,
                        null, request.getStartDate(), request.getEndDate(),
                        (int) (request.getEndDate().toEpochDay() - request.getStartDate().toEpochDay()) + 1);
                ingestReconciliation.reconcile(ingestRunId);
                ingestRunRecorder.closeRun(ingestRunId, progress.getStatus(), null);
            } catch (Exception le) {
                log.warn("Could not close the backfill ingest ledger row (non-fatal): {}", le.toString());
            }
            // Even a failed run may have rewritten fact/summary days already
            // processed — clear on any terminal status, like the job listener.
            evictReportCaches();
            com.acquira.common.config.TenantContext.clear();
        }

        return CompletableFuture.completedFuture(null);
    }

    private void processSingleDate(Long tenantId, DataSourceConfig ds, List<String> queries, LocalDate targetDate) {
        // A. Clear Staging for this Tenant (we assume single-threaded backfill per
        // tenant for safety)
        // Alternatively, we could just DELETE WHERE tenant_id=? AND payment_date =
        // targetDate if we want to be more specific,
        // but standard flow is TRUNCATE/DELETE staging.
        // Let's being safe: Delete only for this date in case parallel uploads are
        // happening?
        // Actually, the main pipeline deletes *everything* for the tenant in staging.
        // Let's follow that pattern to avoid phantom data, but strictly speaking
        // checking existing job config:
        // cleanTargetDayStep -> "DELETE FROM stg_trnx_raw WHERE tenant_id = ?"
        jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);

        // B. Fetch & Insert from ALL queries
        for (String sql : queries) {
            List<Map<String, Object>> rows = dbClient.executeQuery(ds, sql, Map.of("targetDate", targetDate));
            if (!rows.isEmpty()) {
                batchInsertStaging(rows, tenantId);
                currentProgress.get().setTotalRowsIngested(currentProgress.get().getTotalRowsIngested() + rows.size());
            }
        }

        // B2. NORMALIZE STAGED AMOUNTS (added 2026-08-10).
        // This path wrote staging rows RAW and went straight to aggregation: it never
        // ran transactionTenantProcessor, so it skipped both ISO-numeric -> currency-code
        // resolution (leaving '048'/'818' stored as the currency) and minor-unit
        // division (landing amounts 100x too large for EGP/AED and 1000x for BHD).
        // Any tenant backfilled through here had a silently corrupt warehouse.
        normalizeStagedAmounts(tenantId);

        // C. Run Aggregation Pipeline (Same logic as TransactionJobConfig)
        runAggregationPipeline(tenantId, targetDate);

        // D. Metrics. Only the per-DAY activity snapshot runs here; the monthly
        // merchant metrics are rebuilt once per month by the caller after every
        // day has landed.
        calculateBusinessMetrics(tenantId, targetDate);
    }

    /**
     * Bring staged rows to the same contract as the file/pull paths: real currency
     * codes, and amounts in major units at the CURRENCY's own precision (BHD keeps
     * its third decimal; EGP/AED get two). Mirrors
     * IntegrationPullService.normalizeStagedTransactions.
     */
    private void normalizeStagedAmounts(Long tenantId) {
        // ISO numeric ('048','818') -> alpha code ('BHD','EGP'), both currency columns.
        for (String col : new String[] { "txn_currency", "store_base_currency" }) {
            jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET " + col + " = TRIM(rc.currency_code) FROM ref_country rc " +
                "WHERE s.tenant_id = ? AND rc.iso_numeric IS NOT NULL AND rc.currency_code IS NOT NULL " +
                "AND TRIM(s." + col + ") = TRIM(rc.iso_numeric)", tenantId);
        }

        Boolean minorUnits = true;
        try {
            String fmt = jdbcTemplate.queryForObject(
                "SELECT COALESCE(input_format,'CMM') FROM tenant WHERE tenant_id = ?", String.class, tenantId);
            minorUnits = !"AMS".equalsIgnoreCase(fmt);
        } catch (Exception e) {
            log.warn("[Backfill] Could not read tenant {} input_format ({}) - assuming CMM/minor units",
                    tenantId, e.getMessage());
        }
        if (!minorUnits) {
            log.info("[Backfill] Tenant {} is AMS - staged amounts already carry final decimals.", tenantId);
            return;
        }

        // Scale from the divisor: LOG10(1000)=3 for BHD, LOG10(100)=2 for EGP/AED.
        String[][] cols = {
            { "txn_currency_amount", "txn_currency" },
            { "store_base_currency_amount", "store_base_currency" },
            { "total_amount_settled", "store_base_currency" }
        };
        int divided = 0;
        for (String[] c : cols) {
            divided += jdbcTemplate.update(
                "UPDATE stg_trnx_raw s SET " + c[0] + " = ROUND(s." + c[0] + " / d.div, " +
                "  CAST(ROUND(LOG(10, d.div)) AS INT)) " +
                "FROM LATERAL (SELECT COALESCE((SELECT MAX(CASE WHEN rc.decimal_notation_value > 0 " +
                "                THEN rc.decimal_notation_value ELSE 100 END) FROM ref_country rc " +
                "                WHERE TRIM(rc.currency_code) = TRIM(s." + c[1] + ")), 100)::NUMERIC AS div) d " +
                "WHERE s.tenant_id = ? AND s." + c[0] + " IS NOT NULL", tenantId);
        }
        divided += jdbcTemplate.update(
            "UPDATE stg_trnx_raw SET interchange_fee = ROUND(interchange_fee / 10000, 4) " +
            "WHERE tenant_id = ? AND interchange_fee IS NOT NULL", tenantId);
        log.info("[Backfill] Normalized {} staged amount values for tenant {}", divided, tenantId);
    }

    private void batchInsertStaging(List<Map<String, Object>> rows, Long tenantId) {
        String insertSql = """
                    INSERT INTO stg_trnx_raw (
                        entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
                        mid, merchant_internal_id, merchant_name,
                        sid, merchant_store_internal_id, cmm_merchant_store_internal_id, merchant_store_legal_name, store_name,
                        tid, arn, rrn_number, card_number, auth_code,
                        payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
                        txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                        msf, vat, total_amount_settled, interchange_fee, destination,
                        tenant_id, load_time
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """;

        jdbcTemplate.batchUpdate(insertSql, rows, 1000, (ps, row) -> {
            int i = 1;
            // Map keys MUST match what the user SELECTs. We assume standard keys or we map
            // loosely.
            // For robustness, we should look for keys case-insensitively or enforce
            // standard aliases.
            // Here we assume the user provides correct column aliases in SQL.

            ps.setString(i++, getString(row, "entity_name"));
            ps.setString(i++, getString(row, "aggregator_internal_id"));
            ps.setString(i++, getString(row, "aggregator_name"));
            ps.setString(i++, getString(row, "aggregator_code"));
            ps.setString(i++, getString(row, "mid"));
            ps.setString(i++, getString(row, "merchant_internal_id"));
            ps.setString(i++, getString(row, "merchant_name"));
            ps.setString(i++, getString(row, "sid"));
            ps.setString(i++, getString(row, "merchant_store_internal_id"));
            ps.setString(i++, getString(row, "cmm_merchant_store_internal_id"));
            ps.setString(i++, getString(row, "merchant_store_legal_name"));
            ps.setString(i++, getString(row, "store_name"));
            ps.setString(i++, getString(row, "tid"));
            ps.setString(i++, getString(row, "arn"));
            ps.setString(i++, getString(row, "rrn_number"));
            ps.setString(i++, getString(row, "card_number"));
            ps.setString(i++, getString(row, "auth_code"));

            // Dates
            ps.setTimestamp(i++, toTimestamp(row.get("payment_date")));
            ps.setTimestamp(i++, toTimestamp(row.get("transaction_date")));

            ps.setString(i++, getString(row, "batch_number"));
            ps.setString(i++, getString(row, "transaction_type"));
            ps.setString(i++, getString(row, "card_scheme"));
            ps.setString(i++, getString(row, "card_type"));

            // Boolean
            Object dccObj = row.get("dcc");
            if (dccObj != null) {
                if (dccObj instanceof Boolean)
                    ps.setBoolean(i++, (Boolean) dccObj);
                else
                    ps.setBoolean(i++, Boolean.parseBoolean(dccObj.toString()));
            } else {
                ps.setNull(i++, java.sql.Types.BOOLEAN);
            }

            ps.setString(i++, getString(row, "txn_currency"));
            ps.setBigDecimal(i++, toBigDecimal(row.get("txn_currency_amount")));
            ps.setString(i++, getString(row, "store_base_currency"));
            ps.setBigDecimal(i++, toBigDecimal(row.get("store_base_currency_amount")));
            ps.setBigDecimal(i++, toBigDecimal(row.get("msf")));
            ps.setBigDecimal(i++, toBigDecimal(row.get("vat")));
            ps.setBigDecimal(i++, toBigDecimal(row.get("total_amount_settled")));
            ps.setBigDecimal(i++, toBigDecimal(row.get("interchange_fee")));
            ps.setString(i++, getString(row, "destination"));

            ps.setLong(i++, tenantId);
        });
    }

    // ==================================================================================
    // PIPELINE — shared with the upload job (IngestSql + FeeComputationService +
    // SummaryPopulationService), no longer a local replication of its SQL.
    // ==================================================================================
    private void runAggregationPipeline(Long tenantId, LocalDate date) {
        // 0. Auto-create missing dimensions (merchant/store/terminal) from
        // staging — same SQL as the upload job's autoCreateDimensionsStep.
        autoCreateDimensions(tenantId);

        // 1+2. FACT WRITE — APPEND-ONLY via the SHARED upload pipeline
        // (2026-08-28). This used to be a drifted local mirror: no destination
        // normalization or destination_raw, no DOMESTIC txn-currency fallback,
        // UNSIGNED refund volumes, no issuer_country — and NO fee engine at
        // all, so backfilled days kept raw feed interchange with NULL scheme/
        // ecom fees and disagreed with uploaded days on every fee screen.
        //
        // Now: stage the day's rows in a session temp table shaped LIKE
        // fact_transaction via IngestSql.stagingToFactInsertSql (the job's
        // exact INSERT ... SELECT), price them with FeeComputationService (the
        // job's exact two-phase fee pass + gap report), then atomically delete
        // the old day and flush the finished rows in ONE INSERT — each fact
        // row written once, fees and statuses already populated. Everything
        // runs inside a TransactionTemplate because temp tables require all
        // statements to share a connection (this service's statements
        // otherwise run on autocommit pooled connections).
        final java.util.List<java.sql.Date> dayList =
            java.util.Collections.singletonList(java.sql.Date.valueOf(date));
        final String dateScope = IngestScopes.dateInList(dayList);
        final String rngBare = IngestScopes.rangeClause(dayList, "");
        final String rngF = IngestScopes.rangeClause(dayList, "f.");
        final String rngFt = IngestScopes.rangeClause(dayList, "ft.");
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> {
                jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fact_batch");
                jdbcTemplate.execute(
                    "CREATE TEMP TABLE tmp_fact_batch (LIKE fact_transaction INCLUDING DEFAULTS)");
                int inserted = jdbcTemplate.update(
                    IngestSql.stagingToFactInsertSql("tmp_fact_batch", " AND DATE(stg.payment_date) = ?"),
                    tenantId, date);
                // Temp tables have no stats until analyzed; the fee joins need
                // row counts to pick hash joins.
                jdbcTemplate.execute("ANALYZE tmp_fact_batch");
                feeComputationService.computeFees(tenantId, "tmp_fact_batch",
                    rngFt, rngF, rngBare, dateScope);
                int deleted = jdbcTemplate.update(
                    "DELETE FROM fact_transaction WHERE tenant_id = ? AND " + rngBare +
                    "DATE(payment_date) IN " + dateScope, tenantId);
                int flushed = jdbcTemplate.update(
                    "INSERT INTO fact_transaction SELECT * FROM tmp_fact_batch");
                jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fact_batch");
                log.info("[Backfill] {}: staged {} fact rows (replaced {}), flushed {} in one append-only write",
                    date, inserted, deleted, flushed);
            });

        // Summaries deliberately do NOT run here. They are batched ONCE for all
        // landed dates by startBackfill after the day loop (same pattern as the
        // monthly merchant metrics): running the summary service per day meant
        // the three MONTHLY tables (sum_monthly_bank / sum_monthly_insight /
        // sum_monthly_card — the largest unpartitioned table, a whole-month
        // fact aggregation) were deleted and rebuilt once PER DAY — a 30-day
        // run rebuilt each month's rollups ~30 times, which is where the
        // ~9-minutes-per-day cost lived. The daily aggregations are additive
        // per date, so one batched pass over all dates produces the identical
        // end state.
    }

    /**
     * Auto-create missing dim_merchant / dim_store / dim_terminal rows from the
     * staged rows — an exact copy of the upload job's autoCreateDimensionsTasklet
     * (TransactionJobConfig), so a backfilled feed resolves the SAME dimension
     * rows a normal file upload would. Also re-syncs blank merchant names from
     * the feed (the only thing the old "auto-create" step actually did).
     */
    private void autoCreateDimensions(Long tenantId) {
        int merchantsAdded = jdbcTemplate.update(
                """
                        INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, status, created_date)
                        SELECT s.tenant_id,
                          'AUTO_SID_' || TRIM(s.sid),
                          COALESCE(NULLIF(TRIM(MAX(s.mid)), ''), 'AUTO_MID_' || TRIM(s.sid)),
                          COALESCE(
                            MAX(CASE WHEN s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> ''
                                     AND s.merchant_name !~ '^[0-9.]+$' THEN s.merchant_name END),
                            MAX(NULLIF(TRIM(s.merchant_store_legal_name), '')),
                            MAX(NULLIF(TRIM(s.store_name), '')),
                            'Merchant ' || TRIM(s.sid)),
                          'ACTIVE', NOW()
                        FROM stg_trnx_raw s
                        WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM dim_store ds WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid))
                          AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), ''))
                        GROUP BY s.tenant_id, TRIM(s.sid)
                        ON CONFLICT (tenant_id, internal_id) DO NOTHING
                        """, tenantId);

        int storesAdded = jdbcTemplate.update(
                """
                        INSERT INTO dim_store (tenant_id, internal_id, merchant_id, sid, name, status, created_date)
                        SELECT s.tenant_id,
                          'AUTO_STORE_SID_' || TRIM(s.sid),
                          m.merchant_id,
                          TRIM(s.sid),
                          COALESCE(MAX(NULLIF(TRIM(s.store_name), '')),
                                   MAX(NULLIF(TRIM(s.merchant_store_legal_name), '')),
                                   MAX(NULLIF(TRIM(s.merchant_name), '')),
                                   'Store ' || TRIM(s.sid)),
                          'ACTIVE', NOW()
                        FROM stg_trnx_raw s
                        JOIN dim_merchant m ON m.tenant_id = s.tenant_id
                          AND (m.mid = NULLIF(TRIM(s.mid), '')
                            OR m.internal_id = 'AUTO_SID_' || TRIM(s.sid))
                        WHERE s.tenant_id = ? AND NULLIF(TRIM(s.sid), '') IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM dim_store ds
                            WHERE ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid))
                          AND NOT EXISTS (SELECT 1 FROM dim_terminal dt
                            WHERE dt.tenant_id = s.tenant_id AND dt.tid = NULLIF(TRIM(s.tid), ''))
                        GROUP BY s.tenant_id, m.merchant_id, TRIM(s.sid)
                        ON CONFLICT (tenant_id, internal_id) DO NOTHING
                        """, tenantId);

        int terminalsAdded = jdbcTemplate.update(
                """
                        INSERT INTO dim_terminal (tenant_id, internal_id, store_id, tid, status, created_date)
                        SELECT s.tenant_id,
                          'AUTO_TERM_' || TRIM(s.sid) || '_' || TRIM(s.tid),
                          ds.store_id,
                          TRIM(s.tid),
                          'ACTIVE', NOW()
                        FROM stg_trnx_raw s
                        JOIN dim_store ds ON ds.tenant_id = s.tenant_id AND ds.sid = TRIM(s.sid)
                        WHERE s.tenant_id = ? AND NULLIF(TRIM(s.tid), '') IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM dim_terminal dt WHERE dt.tenant_id = s.tenant_id
                            AND dt.store_id = ds.store_id AND dt.tid = TRIM(s.tid))
                        GROUP BY s.tenant_id, ds.store_id, TRIM(s.sid), TRIM(s.tid)
                        ON CONFLICT (tenant_id, internal_id) DO NOTHING
                        """, tenantId);

        jdbcTemplate.update("""
                UPDATE dim_merchant m SET name = sub.merchant_name
                FROM (
                    SELECT DISTINCT TRIM(s.mid) AS mid, s.merchant_name
                    FROM stg_trnx_raw s
                    WHERE s.tenant_id = ? AND NULLIF(TRIM(s.merchant_name), '') IS NOT NULL
                ) sub
                WHERE m.mid = sub.mid AND m.tenant_id = ? AND (m.name IS NULL OR m.name = '')
                """, tenantId, tenantId);

        if (merchantsAdded > 0 || storesAdded > 0 || terminalsAdded > 0) {
            log.info("Backfill autoCreateDimensions: +{} merchants, +{} stores, +{} terminals",
                    merchantsAdded, storesAdded, terminalsAdded);
        }
    }

    // ===================================
    // Business Metrics
    // ===================================
    private void calculateBusinessMetrics(Long tenantId, LocalDate date) {
        long start = System.currentTimeMillis();

        // Active/Dormant Status
        jdbcTemplate.update(
                """
                        INSERT INTO merchant_activity_summary (
                            tenant_id, merchant_id, calc_date,
                            first_txn_date, last_txn_date,
                            last_7d_cnt, last_7d_value, last_30d_cnt, last_30d_value,
                            status, status_change_date
                        )
                        SELECT
                            m.tenant_id, m.merchant_id, d.target_date,
                            MIN(f.payment_date), MAX(f.payment_date),
                            COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN 1 END), 0),
                            COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN f.store_base_currency_amount ELSE 0 END), 0),
                            COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN 1 END), 0),
                            COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN f.store_base_currency_amount ELSE 0 END), 0),
                            CASE WHEN MAX(f.payment_date) >= d.target_date - INTERVAL '30 days' THEN 'ACTIVE'
                                 WHEN MAX(f.payment_date) < d.target_date - INTERVAL '30 days' THEN 'DORMANT'
                                 ELSE 'ONBOARDED' END,
                            d.target_date
                        FROM dim_merchant m
                        CROSS JOIN (SELECT DISTINCT DATE(payment_date) as target_date FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL) d
                        LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id AND f.tenant_id = m.tenant_id
                        WHERE m.tenant_id = ?
                        GROUP BY m.tenant_id, m.merchant_id, d.target_date
                        ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET
                            first_txn_date=EXCLUDED.first_txn_date, last_txn_date=EXCLUDED.last_txn_date,
                            last_7d_cnt=EXCLUDED.last_7d_cnt, last_7d_value=EXCLUDED.last_7d_value,
                            last_30d_cnt=EXCLUDED.last_30d_cnt, last_30d_value=EXCLUDED.last_30d_value,
                            status=EXCLUDED.status, status_change_date=EXCLUDED.status_change_date
                        """,
                tenantId, tenantId);

        // Opportunity scores
        jdbcTemplate.update(
                """
                        INSERT INTO merchant_opportunity_score (tenant_id, merchant_id, score, reason_tags, calc_date)
                        SELECT tenant_id, merchant_id,
                            CASE WHEN last_30d_value > 1000 THEN 80 ELSE 40 END,
                            'Automated Score', calc_date
                        FROM merchant_activity_summary
                        WHERE tenant_id = ? AND calc_date IN (SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL)
                        ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET
                            score=EXCLUDED.score, reason_tags=EXCLUDED.reason_tags
                        """,
                tenantId, tenantId);

        long elapsed = System.currentTimeMillis()
                - start;
        log.info("Business Metrics calculation completed in {}ms", elapsed);
    }

    /**
     * Rebuild monthly merchant metrics for ONE month (YYYY-MM). Delegates to the
     * shared bulk rebuilder — this used to be a per-merchant SELECT+save loop
     * called once per backfilled day.
     */
    private void calculateDashboardMetrics(Long tenantId, String monthYear) {
        long start = System.currentTimeMillis();
        int saved = monthlyMetricsRebuilder.rebuildMonth(tenantId.intValue(), monthYear);
        log.info("Dashboard Metrics for {} completed in {}ms ({} rows)",
                monthYear, System.currentTimeMillis() - start, saved);
    }

    // Helpers
    private String getString(Map<String, Object> row, String key) {
        // Try exact match, then case-insensitive
        if (row.containsKey(key))
            return row.get(key) != null ? row.get(key).toString() : null;
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase(key))
                return row.get(k) != null ? row.get(k).toString() : null;
        }
        return null;
    }

    private java.sql.Timestamp toTimestamp(Object obj) {
        if (obj == null)
            return null;
        if (obj instanceof java.sql.Timestamp)
            return (java.sql.Timestamp) obj;
        if (obj instanceof LocalDateTime)
            return java.sql.Timestamp.valueOf((LocalDateTime) obj);
        if (obj instanceof LocalDate)
            return java.sql.Timestamp.valueOf(((LocalDate) obj).atStartOfDay());
        if (obj instanceof java.sql.Date)
            return new java.sql.Timestamp(((java.sql.Date) obj).getTime());
        return null; // or try parse string
    }

    private java.math.BigDecimal toBigDecimal(Object obj) {
        if (obj == null)
            return null;
        if (obj instanceof java.math.BigDecimal)
            return (java.math.BigDecimal) obj;
        if (obj instanceof Number)
            return java.math.BigDecimal.valueOf(((Number) obj).doubleValue());
        try {
            return new java.math.BigDecimal(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
