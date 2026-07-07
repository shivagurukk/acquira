package com.acquira.batch.service;

import com.acquira.common.model.DataSourceConfig;
import com.acquira.common.model.SumDailyMerchant;
import com.acquira.common.model.SumMonthlyMerchantMetrics;
import com.acquira.common.repository.DataSourceConfigRepository;
import com.acquira.common.repository.SumDailyMerchantRepository;
import com.acquira.common.repository.SumMonthlyMerchantMetricsRepository;
import com.acquira.common.service.MerchantMetricCalculator;
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
    private final SumDailyMerchantRepository dailyMerchantRepo;
    private final SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final MerchantMetricCalculator merchantMetricCalculator;

    // Track single active job progress (simple singleton approach for now)
    private final AtomicReference<BackfillProgress> currentProgress = new AtomicReference<>(new BackfillProgress());

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

    @Async
    public CompletableFuture<Void> startBackfill(BackfillRequest request) {
        BackfillProgress progress = new BackfillProgress();
        progress.setStatus("RUNNING");
        progress.setTotalDays((int) (request.getEndDate().toEpochDay() - request.getStartDate().toEpochDay()) + 1);
        progress.setCurrentDate(request.getStartDate());
        currentProgress.set(progress);

        log.info("Starting Backfill for Tenant: {},  Range: {} to {}", request.getTenantId(), request.getStartDate(),
                request.getEndDate());

        try {
            // 0. Ensure Partitions exist for all years involved
            int startYear = request.getStartDate().getYear();
            int endYear = request.getEndDate().getYear();
            for (int year = startYear; year <= endYear; year++) {
                partitionService.ensurePartitionsForYear(year);
            }

            // 1. Get Source Config
            DataSourceConfig dsConfig = dataSourceRepo.findById(request.getDataSourceId())
                    .orElseThrow(() -> new RuntimeException("DataSource not found: " + request.getDataSourceId()));

            LocalDate loopDate = request.getStartDate();
            while (!loopDate.isAfter(request.getEndDate())) {
                progress.setCurrentDate(loopDate);
                log.info("Processing Backfill Date: {}", loopDate);

                try {
                    processSingleDate(request.getTenantId(), dsConfig, request.getSourceQueries(), loopDate);
                    progress.setCompletedDays(progress.getCompletedDays() + 1);
                } catch (Exception e) {
                    log.error("Failed to process date: " + loopDate, e);
                    progress.getErrorMessages().add("Date " + loopDate + ": " + e.getMessage());
                    // We continue to next date even if one fails
                }

                loopDate = loopDate.plusDays(1);
            }

            progress.setStatus("COMPLETED");
            log.info("Backfill Completed.");

        } catch (Exception e) {
            log.error("Backfill Critical Failure", e);
            progress.setStatus("FAILED");
            progress.getErrorMessages().add("Critical: " + e.getMessage());
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

        // C. Run Aggregation Pipeline (Same logic as TransactionJobConfig)
        runAggregationPipeline(tenantId, targetDate);

        // D. Metrics
        calculateBusinessMetrics(tenantId, targetDate);
        calculateDashboardMetrics(tenantId, targetDate);
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
    // PIPELINE REPLICATION (Reusing SQL logic from TransactionJobConfig)
    // ==================================================================================
    private void runAggregationPipeline(Long tenantId, LocalDate date) {
        // Scope variables
        String dateScope = "(SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL)";

        // 1. Delete Existing Fact
        jdbcTemplate.update("""
                DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) = ?
                """, tenantId, date);

        // 2. Insert Fact
        jdbcTemplate.update(
                """
                        INSERT INTO fact_transaction (
                                tenant_id, merchant_id, store_id, terminal_id,
                                arn, rrn_number, card_number, auth_code,
                                payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
                                txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                                msf, vat, total_amount_settled, interchange_fee, destination
                            )
                            SELECT
                                stg.tenant_id,
                                m.merchant_id, s.store_id, t.terminal_id,
                                stg.arn, stg.rrn_number, stg.card_number, stg.auth_code,
                                stg.payment_date, stg.transaction_date, stg.batch_number, stg.transaction_type,
                                stg.card_scheme, stg.card_type, stg.dcc,
                                stg.txn_currency, ABS(stg.txn_currency_amount), stg.store_base_currency, ABS(stg.store_base_currency_amount),
                                ABS(stg.msf), ABS(stg.vat), stg.total_amount_settled, ABS(stg.interchange_fee), stg.destination
                            FROM stg_trnx_raw stg
                            LEFT JOIN dim_merchant m ON stg.mid = m.mid AND m.tenant_id = ?
                            LEFT JOIN dim_store s ON s.merchant_id = m.merchant_id
                                AND (s.sid = stg.sid OR s.internal_id = stg.merchant_store_internal_id OR s.internal_id = CONCAT('STORE_', stg.mid))
                                AND s.tenant_id = ?
                            LEFT JOIN dim_terminal t ON t.store_id = s.store_id
                                AND (t.tid = stg.tid OR t.internal_id = stg.tid OR t.internal_id = CONCAT('TERM_', stg.mid))
                                AND t.tenant_id = ?
                            WHERE stg.tenant_id = ? AND DATE(stg.payment_date) = ?
                        """,
                tenantId, tenantId, tenantId, tenantId, date);

        // 3. Update Summaries
        // For backfill, we can run the standard summary logic scoped to this execution
        // Since stg_trnx_raw currently holds ONLY this date (due to delete step A),
        // we can reuse the generic 'dateScope' query which selects potentially just
        // this one date.

        // 3.0 Auto-create merchant if missing (from staging) -- Essential
        jdbcTemplate.update("""
                UPDATE dim_merchant m SET name = sub.merchant_name
                FROM (
                    SELECT DISTINCT s.mid, s.merchant_name
                    FROM stg_trnx_raw s
                    WHERE s.tenant_id = ? AND s.merchant_name IS NOT NULL
                ) sub
                WHERE m.mid = sub.mid AND m.tenant_id = ? AND (m.name IS NULL OR m.name = '')
                """, tenantId, tenantId);

        // 3.1 Bank
        jdbcTemplate.update("""
                INSERT INTO sum_daily_bank (tenant_id, business_date, total_txns, total_volume, total_msf,
                     total_interchange, total_scheme_fee, total_vat, total_net_revenue)
                SELECT tenant_id, DATE(payment_date), COUNT(*), SUM(txn_currency_amount), SUM(msf),
                     SUM(interchange_fee), 0, SUM(vat), SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0))
                FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) = ?
                GROUP BY tenant_id, DATE(payment_date)
                ON CONFLICT (tenant_id, business_date) DO UPDATE SET
                     total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf,
                     total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee,
                     total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue
                """, tenantId, date);

        // 3.2 Merchant
        jdbcTemplate.update(
                """
                           INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
                               total_txns, total_volume, total_base_volume, total_msf, total_interchange, total_scheme_fee, total_margin,
                               total_debit_prepaid_volume, total_credit_volume, sales_user_id, unique_customer_count,
                               dcc_eligible_volume, dcc_optin_volume, dcc_optout_volume, dcc_eligible_count, dcc_optin_count)
                           SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, COUNT(*),
                               SUM(f.txn_currency_amount), SUM(f.store_base_currency_amount), SUM(f.msf), SUM(f.interchange_fee), 0,
                               SUM(COALESCE(f.msf,0) - COALESCE(f.interchange_fee,0)),
                               SUM(CASE WHEN UPPER(f.card_type) IN ('DEBIT','PREPAID') THEN f.txn_currency_amount ELSE 0 END),
                               SUM(CASE WHEN UPPER(f.card_type) = 'CREDIT' THEN f.txn_currency_amount ELSE 0 END),
                               m.sales_user_id, COUNT(DISTINCT f.card_number),
                               SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN f.txn_currency_amount ELSE 0 END),
                               SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN f.txn_currency_amount ELSE 0 END),
                               SUM(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND (f.dcc IS FALSE OR f.dcc IS NULL) THEN f.txn_currency_amount ELSE 0 END),
                               COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' THEN 1 END),
                               COUNT(CASE WHEN UPPER(f.destination)='INTERNATIONAL' AND f.dcc IS TRUE THEN 1 END)
                           FROM fact_transaction f JOIN dim_merchant m ON f.merchant_id = m.merchant_id
                           WHERE f.tenant_id = ? AND DATE(f.payment_date) = ?
                           GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id
                           ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
                               total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf,
                               total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee,
                               total_margin=EXCLUDED.total_margin, total_debit_prepaid_volume=EXCLUDED.total_debit_prepaid_volume,
                               total_credit_volume=EXCLUDED.total_credit_volume, sales_user_id=EXCLUDED.sales_user_id,
                               unique_customer_count=EXCLUDED.unique_customer_count,
                               dcc_eligible_volume=EXCLUDED.dcc_eligible_volume, dcc_optin_volume=EXCLUDED.dcc_optin_volume,
                               dcc_optout_volume=EXCLUDED.dcc_optout_volume, dcc_eligible_count=EXCLUDED.dcc_eligible_count,
                               dcc_optin_count=EXCLUDED.dcc_optin_count
                        """,
                tenantId, date);

        // 2.1 Top spending customer per merchant-day
        jdbcTemplate.update(
                """
                        WITH DailyCustSpend AS (
                            SELECT tenant_id, merchant_id, DATE(payment_date) as b_date, card_number,
                                SUM(txn_currency_amount) as total_spend
                            FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) = ?
                            GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number
                        ), Ranked AS (
                            SELECT *, ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn
                            FROM DailyCustSpend
                        )
                        UPDATE sum_daily_merchant s SET top_spending_customer_id=r.card_number, top_spending_amount=r.total_spend
                        FROM Ranked r WHERE s.tenant_id=r.tenant_id AND s.merchant_id=r.merchant_id
                            AND s.business_date=r.b_date AND r.rn=1 AND s.tenant_id = ?
                        """,
                tenantId, date, tenantId);

        // 3. sum_daily_mcc
        jdbcTemplate.update("""
                INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns,
                    total_volume, total_msf, total_scheme_fee, total_net_revenue)
                SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*),
                    SUM(f.txn_currency_amount), SUM(f.msf), 0, SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0))
                FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id
                WHERE f.tenant_id=? AND DATE(f.payment_date) = ?
                GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme
                ON CONFLICT (tenant_id, business_date, mcc, card_scheme) DO UPDATE SET
                    total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf,
                    total_scheme_fee=EXCLUDED.total_scheme_fee, total_net_revenue=EXCLUDED.total_net_revenue
                """, tenantId, date);

        // 4. sum_daily_scheme
        jdbcTemplate.update(
                """
                        INSERT INTO sum_daily_scheme (tenant_id, business_date, card_scheme, total_txns,
                            total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue)
                        SELECT tenant_id, DATE(payment_date), card_scheme, COUNT(*),
                            SUM(txn_currency_amount), SUM(msf), SUM(interchange_fee), 0, SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0))
                        FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                        GROUP BY tenant_id, DATE(payment_date), card_scheme
                        ON CONFLICT (tenant_id, business_date, card_scheme) DO UPDATE SET
                            total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf,
                            total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee,
                            total_net_revenue=EXCLUDED.total_net_revenue
                        """,
                tenantId, date);

        // 5. sum_daily_channel
        jdbcTemplate.update("""
                INSERT INTO sum_daily_channel (tenant_id, business_date, channel, total_txns,
                    total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue)
                SELECT f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS'), COUNT(*),
                    SUM(f.txn_currency_amount), SUM(f.msf), SUM(f.interchange_fee), 0,
                    SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0))
                FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id
                WHERE f.tenant_id=? AND DATE(f.payment_date) = ?
                GROUP BY f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS')
                ON CONFLICT (tenant_id, business_date, channel) DO UPDATE SET
                    total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf,
                    total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee,
                    total_net_revenue=EXCLUDED.total_net_revenue
                """, tenantId, date);

        // 6. sum_monthly_bank
        String monthScope = "(SELECT DISTINCT CAST(TO_CHAR(payment_date, 'YYYYMM') AS INTEGER) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL)";
        jdbcTemplate.update(
                """
                        INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_msf,
                            total_interchange, total_scheme_fee, total_vat, total_net_revenue)
                        SELECT tenant_id, CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER),
                            SUM(total_txns), SUM(total_volume), SUM(total_msf), SUM(total_interchange),
                            SUM(total_scheme_fee), SUM(total_vat), SUM(total_net_revenue)
                        FROM sum_daily_bank WHERE tenant_id=? AND CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER) IN """
                        + monthScope
                        + """
                                GROUP BY tenant_id, TO_CHAR(business_date,'YYYYMM')
                                ON CONFLICT (tenant_id, month_key) DO UPDATE SET
                                    total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf,
                                    total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee,
                                    total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue
                                """,
                tenantId, tenantId, tenantId);

        // 7. sum_daily_terminal
        jdbcTemplate.update("""
                INSERT INTO sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id,
                    total_txns, total_volume, total_msf, total_revenue)
                SELECT tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id,
                    COUNT(*), SUM(txn_currency_amount), SUM(msf), SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0))
                FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id
                ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET
                    total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume,
                    total_msf=EXCLUDED.total_msf, total_revenue=EXCLUDED.total_revenue
                """, tenantId, date);

        // 8. sum_daily_finance
        jdbcTemplate.update(
                """
                        INSERT INTO sum_daily_finance (tenant_id, business_date,
                            dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin,
                            dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin,
                            int_cnt, int_vol, int_msf, int_optin, total_vol, total_msf)
                        SELECT tenant_id, DATE(payment_date),
                            COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN 1 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN txn_currency_amount ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') THEN msf ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type) IN ('DEBIT','PREPAID') AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                            COUNT(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN 1 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN txn_currency_amount ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' THEN msf ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='DOMESTIC' AND UPPER(card_type)='CREDIT' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                            COUNT(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN 1 END),
                            SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN txn_currency_amount ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' THEN msf ELSE 0 END),
                            SUM(CASE WHEN UPPER(destination)='INTERNATIONAL' AND dcc IS TRUE THEN txn_currency_amount ELSE 0 END),
                            SUM(txn_currency_amount), SUM(msf)
                        FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                        GROUP BY tenant_id, DATE(payment_date)
                        ON CONFLICT (tenant_id, business_date) DO UPDATE SET
                            dom_debit_cnt=EXCLUDED.dom_debit_cnt, dom_debit_vol=EXCLUDED.dom_debit_vol,
                            dom_debit_msf=EXCLUDED.dom_debit_msf, dom_debit_optin=EXCLUDED.dom_debit_optin,
                            dom_credit_cnt=EXCLUDED.dom_credit_cnt, dom_credit_vol=EXCLUDED.dom_credit_vol,
                            dom_credit_msf=EXCLUDED.dom_credit_msf, dom_credit_optin=EXCLUDED.dom_credit_optin,
                            int_cnt=EXCLUDED.int_cnt, int_vol=EXCLUDED.int_vol,
                            int_msf=EXCLUDED.int_msf, int_optin=EXCLUDED.int_optin,
                            total_vol=EXCLUDED.total_vol, total_msf=EXCLUDED.total_msf
                        """,
                tenantId, date);

        // 9. sum_daily_insight
        jdbcTemplate.update(
                """
                        INSERT INTO sum_daily_insight (tenant_id, business_date, merchant_id, store_id, terminal_id,
                            card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf)
                        SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id,
                            f.card_scheme, f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc,
                            COUNT(*), SUM(f.txn_currency_amount), SUM(f.msf)
                        FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id
                        WHERE f.tenant_id=? AND DATE(f.payment_date) = ?
                        GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id,
                            f.card_scheme, f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc
                        ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)
                        DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf
                        """,
                tenantId, date);

        // 10. Merchant attributes (CARD_SCHEME, CARD_TYPE, DESTINATION,
        // TRANSACTION_TYPE, HOUR)
        String[] attrCols = { "CARD_SCHEME:card_scheme", "CARD_TYPE:card_type", "DESTINATION:destination",
                "TRANSACTION_TYPE:transaction_type" };
        String dateScopeStr = "(SELECT DATE('" + date.toString() + "'))"; // Simple literal date for attribute loop, or
                                                                          // bind param?
        // Let's use bind param pattern carefully or just formatted string since its
        // internal
        for (String ac : attrCols) {
            String[] parts = ac.split(":");
            jdbcTemplate.update(String.format(
                    """
                            INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume)
                            SELECT tenant_id, merchant_id, DATE(payment_date), '%s', UPPER(COALESCE(%s,'UNKNOWN')), COUNT(*), SUM(txn_currency_amount)
                            FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                            GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(COALESCE(%s,'UNKNOWN'))
                            ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET
                                metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume
                            """,
                    parts[0], parts[1], parts[1]), tenantId, date);
        }

        // HOUR attribute
        jdbcTemplate.update(
                """
                        INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume)
                        SELECT tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(txn_currency_amount)
                        FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                        GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date)
                        ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET
                            metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume
                        """,
                tenantId, date);

        // TXN_SIZE_BUCKET attribute
        jdbcTemplate.update(
                """
                        INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume)
                        SELECT tenant_id, merchant_id, DATE(payment_date), 'TXN_SIZE_BUCKET',
                            CASE WHEN txn_currency_amount < 50 THEN '< 50'
                                 WHEN txn_currency_amount < 100 THEN '50-100'
                                 WHEN txn_currency_amount < 250 THEN '100-250'
                                 WHEN txn_currency_amount < 500 THEN '250-500'
                                 WHEN txn_currency_amount < 1000 THEN '500-1K'
                                 ELSE '1K+' END,
                            COUNT(*), SUM(txn_currency_amount)
                        FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) = ?
                        GROUP BY tenant_id, merchant_id, DATE(payment_date),
                            CASE WHEN txn_currency_amount < 50 THEN '< 50'
                                 WHEN txn_currency_amount < 100 THEN '50-100'
                                 WHEN txn_currency_amount < 250 THEN '100-250'
                                 WHEN txn_currency_amount < 500 THEN '250-500'
                                 WHEN txn_currency_amount < 1000 THEN '500-1K'
                                 ELSE '1K+' END
                        ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET
                            metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume
                        """,
                tenantId, date);

        // 11. sum_monthly_card
        jdbcTemplate
                .update("""
                        INSERT INTO sum_monthly_card (tenant_id, merchant_id, month_key, card_number, visit_count, total_spend)
                        SELECT tenant_id, merchant_id, CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER), card_number, COUNT(*), SUM(txn_currency_amount)
                        FROM fact_transaction WHERE tenant_id=? AND CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER) IN """
                        + monthScope + """
                                GROUP BY tenant_id, merchant_id, TO_CHAR(payment_date,'YYYYMM'), card_number
                                ON CONFLICT (tenant_id, merchant_id, month_key, card_number) DO UPDATE SET
                                    visit_count=EXCLUDED.visit_count, total_spend=EXCLUDED.total_spend
                                """, tenantId, tenantId, tenantId);
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
                            COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '7 days' THEN f.txn_currency_amount ELSE 0 END), 0),
                            COALESCE(COUNT(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN 1 END), 0),
                            COALESCE(SUM(CASE WHEN f.payment_date >= d.target_date - INTERVAL '30 days' THEN f.txn_currency_amount ELSE 0 END), 0),
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

    private void calculateDashboardMetrics(Long tenantId, LocalDate date) {
        long start = System.currentTimeMillis();

        // Calculate Month-Year Key
        String monthYear = date.getYear() + "-" + String.format("%02d", date.getMonthValue());

        // Fetch daily records for this month
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());

        List<SumDailyMerchant> dailyRecs = dailyMerchantRepo.findByTenantIdAndDateRange(tenantId.intValue(), monthStart,
                monthEnd);
        java.util.Map<Long, List<SumDailyMerchant>> grouped = dailyRecs.stream()
                .collect(java.util.stream.Collectors.groupingBy(SumDailyMerchant::getMerchantId));

        for (java.util.Map.Entry<Long, List<SumDailyMerchant>> entry : grouped.entrySet()) {
            Long merchantId = entry.getKey();
            List<SumDailyMerchant> mRecs = entry.getValue();

            SumMonthlyMerchantMetrics newMetrics = merchantMetricCalculator.calculateMetrics(mRecs, tenantId.intValue(),
                    merchantId, monthYear);
            java.util.Optional<SumMonthlyMerchantMetrics> existing = monthlyMetricsRepo.findByMerchantAndMonth(
                    tenantId.intValue(),
                    merchantId, monthYear);
            if (existing.isPresent()) {
                newMetrics.setMetricId(existing.get().getMetricId());
                newMetrics.setCreatedAt(existing.get().getCreatedAt());
            }
            monthlyMetricsRepo.save(newMetrics);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Dashboard Metrics calculation completed in {}ms", elapsed);
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
