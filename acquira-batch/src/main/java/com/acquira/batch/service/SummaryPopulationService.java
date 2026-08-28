package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

import static com.acquira.batch.service.IngestScopes.dateInList;
import static com.acquira.batch.service.IngestScopes.rangeClause;

/**
 * The ONE summary-population pass: clean-slate deletes + every sum_daily_* /
 * sum_monthly_* aggregation + the finance rollup + top-spending-customer, run
 * for an explicit list of business dates under the per-tenant advisory lock.
 *
 * Extracted VERBATIM from TransactionJobConfig.populateSummaryTasklet
 * (2026-08-28) so the upload job, BulkMigrationService (migration AND the
 * super-admin summary rebuild) and BackfillIngestionService all aggregate with
 * the SAME SQL. Both of the latter previously carried hand-maintained mirrors
 * of this block — the exact drift the summary-rebuild-drift rule warns about
 * (rebuilt months silently losing columns), and their date predicates were
 * non-sargable (DATE(payment_date) BETWEEN), scanning every partition. The
 * mirrors are gone; the sargable range clauses come with this copy.
 *
 * Statements here run on pooled autocommit connections (the job step runs with
 * a no-op transaction attribute for the same reason), so callers must invoke
 * this AFTER their fact-write transaction has committed — the parallel workers
 * read fact_transaction from other connections.
 */
@Service
public class SummaryPopulationService {

    private static final Logger log = LoggerFactory.getLogger(SummaryPopulationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public SummaryPopulationService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /** Convenience for month-shaped callers (bulk migration / summary rebuild). */
    public void populateForRange(long tenantId, java.time.LocalDate from, java.time.LocalDate to) {
        populateForDates(tenantId, IngestScopes.daysBetween(from, to));
    }

    /**
     * Rebuild all summaries for the given business dates. The list must be
     * sorted ascending (IngestScopes.daysBetween and the job's DISTINCT-dates
     * query both produce sorted lists).
     */
    public void populateForDates(long tenantId, java.util.List<java.sql.Date> distinctDates) {
        if (distinctDates == null || distinctDates.isEmpty()) {
            log.info("populateSummary: no dates to process - skipping");
            return;
        }
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
            final String dateScope = dateInList(distinctDates);
            // Sargable companions to dateScope — see dateRangeClause(). Without
            // these every aggregation below scanned the tenant's whole fact
            // history instead of the days being loaded.
            final String rngBare = rangeClause(distinctDates, "");
            final String rngF = rangeClause(distinctDates, "f.");
            final String rngFt = rangeClause(distinctDates, "ft.");
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
                        "sum_daily_explorer", "sum_daily_merchant_destination",
                        "sum_daily_local_debit_bin", "sum_daily_finance_rollup",
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
                        "FROM fact_transaction WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
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
                        "FROM fact_transaction f JOIN dim_merchant m ON f.merchant_id = m.merchant_id AND m.tenant_id = f.tenant_id " +
                        "WHERE f.tenant_id = ? AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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

                // Merchant x destination with REAL fees (V2026_07_10_03 — the table's
                // promised population, previously never written). Settlement currency,
                // straight off fact with no dim_merchant join so bank-level totals
                // reconcile exactly with fact (unmatched-merchant rows keep NULL
                // merchant_id; the clean-slate DELETE above makes that safe under the
                // plain UNIQUE). NULL destination lands as DOMESTIC per the table's
                // documented convention. MIRRORED in BulkMigrationService.rebuildSummaries
                // — keep both in sync (summary-rebuild-drift rule).
                phase1.add(runAsync(exec, "sum_daily_merchant_destination", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_merchant_destination (tenant_id, business_date, merchant_id, destination, " +
                        "total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN UPPER(COALESCE(f.destination,'DOMESTIC'))='INTERNATIONAL' THEN 'INTERNATIONAL' ELSE 'DOMESTIC' END, " +
                        "COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), SUM(f.interchange_fee), " +
                        "SUM(COALESCE(f.scheme_fee,0)), SUM(COALESCE(f.ecom_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f " +
                        "WHERE f.tenant_id = ? AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN UPPER(COALESCE(f.destination,'DOMESTIC'))='INTERNATIONAL' THEN 'INTERNATIONAL' ELSE 'DOMESTIC' END " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, destination) DO UPDATE SET " +
                        "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                        "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                        "total_ecom_fee=EXCLUDED.total_ecom_fee, total_net_revenue=EXCLUDED.total_net_revenue", tenantId)));

                phase1.add(runAsync(exec, "sum_daily_mcc", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, " +
                        "total_volume, total_msf, total_scheme_fee, total_net_revenue) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), " +
                        "SUM(COALESCE(f.scheme_fee,0)), " +
                        "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
                        "FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id AND s.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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
                        "FROM fact_transaction WHERE tenant_id=? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
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
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND " + rngBare + "DATE(payment_date) IN " + dateScope +
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
                        "FROM fact_transaction WHERE tenant_id=? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
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
                        "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "LEFT JOIN dim_store st ON f.store_id=st.store_id AND st.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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

                // sum_daily_local_debit_bin — the Local Debit Bank Dashboard
                // pre-aggregate: day x merchant x 6-digit BIN, restricted to
                // DOMESTIC DEBIT rows only. Same source, merchant rule, signed
                // settlement volume/msf, and card_type normalization as
                // sum_daily_full, so matched banks + the query-time "Other
                // Banks" bucket reconcile exactly with that table's
                // DOMESTIC x DEBIT cell. Strict destination='DOMESTIC' —
                // NULL/UNMAPPED tokens must not silently count as local.
                // Bank names are NOT stored here: the dashboard joins
                // ref_tenant_bin_bank at query time, so a BIN re-upload
                // re-labels all history with no rebuild. PANs not starting
                // with 6 clear digits land in the visible '??????' bucket.
                // Any change here MUST be mirrored in
                // BulkMigrationService.rebuildSummaries and
                // BackfillIngestionService.
                phase1.add(runAsync(exec, "sum_daily_local_debit_bin", () ->
                    jdbcTemplate.update("INSERT INTO sum_daily_local_debit_bin (tenant_id, business_date, merchant_id, bin6, " +
                        "total_txns, total_volume, total_msf) " +
                        "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN f.card_number ~ '^[0-9]{6}' THEN LEFT(f.card_number,6) ELSE '??????' END, " +
                        "COUNT(*), SUM(f.store_base_currency_amount), SUM(COALESCE(f.msf,0)) " +
                        "FROM fact_transaction f " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL " +
                        "AND UPPER(COALESCE(NULLIF(TRIM(f.card_type),''),'')) = 'DEBIT' " +
                        "AND f.destination = 'DOMESTIC' " +
                        "AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
                        " GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, " +
                        "CASE WHEN f.card_number ~ '^[0-9]{6}' THEN LEFT(f.card_number,6) ELSE '??????' END " +
                        "ON CONFLICT (tenant_id, business_date, merchant_id, bin6) " +
                        "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, " +
                        "total_msf=EXCLUDED.total_msf",
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
                        "LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
                        "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND " + rngF + "DATE(f.payment_date) IN " + dateScope +
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
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), " + schemeExpr +
                        " ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    for (String ac : new String[]{"CARD_TYPE:card_type","DESTINATION:destination","TRANSACTION_TYPE:transaction_type"}) {
                        String[] parts = ac.split(":");
                        totalRows += jdbcTemplate.update(String.format(
                            "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                            "SELECT tenant_id, merchant_id, DATE(payment_date), '%s', UPPER(COALESCE(%s,'UNKNOWN')), COUNT(*), SUM(store_base_currency_amount) " +
                            // rngBare spliced via concatenation, NOT a %s slot — it contains no
                            // format specifiers but keeping it out of the format string entirely
                            // means a future edit can't accidentally reorder the %s arguments.
                            "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND " + rngBare + "DATE(payment_date) IN %s " +
                            "GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(COALESCE(%s,'UNKNOWN')) " +
                            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
                            parts[0], parts[1], dateScope, parts[1]), tenantId);
                    }
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND transaction_date IS NOT NULL AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date) " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    // Clear the whole TXN_SIZE_BUCKET slice for this date scope before
                    // reinserting. Previously only the legacy '1K+' label was deleted,
                    // which was enough while the band labels were fixed constants. Now
                    // that bands are configurable per country, a relabelled or retuned
                    // band would otherwise leave its old rows behind and double-count
                    // (e.g. a BH tenant's historical '< 50' alongside its new '< 5').
                    jdbcTemplate.update(
                        "DELETE FROM sum_daily_merchant_attribute WHERE tenant_id=? AND business_date IN " + dateScope +
                        " AND attribute_type='TXN_SIZE_BUCKET'", tenantId);
                    // TICKET-SIZE BUCKETS (config-driven since 2026-08-11). These were
                    // the hardcoded constants 50/100/250/500/1000/5000 compared raw
                    // against the settlement amount — AED-shaped numbers. 50 BHD is a
                    // large ticket and 50 EGP is a trivial one, so the same band meant
                    // three different things across three tenants and the distribution
                    // was not comparable to anything. Bands now come from
                    // ticket_size_bucket, per country (AE keeps its historical values,
                    // so the UAE tenant is unchanged), with a per-tenant override.
                    // A transaction whose amount matches no band is skipped rather
                    // than dumped into a catch-all, so a gap in the configuration is
                    // visible as a missing row instead of a wrong one.
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT ft.tenant_id, ft.merchant_id, DATE(ft.payment_date), 'TXN_SIZE_BUCKET', tb.label, " +
                        "       COUNT(*), SUM(ft.store_base_currency_amount) " +
                        "FROM fact_transaction ft " +
                        "LEFT JOIN tenant tn ON tn.tenant_id = ft.tenant_id " +
                        "CROSS JOIN LATERAL ( " +
                        "  SELECT b.label FROM ticket_size_bucket b " +
                        "  WHERE b.country_code = COALESCE(tn.home_country_code,'AE') " +
                        "    AND (b.tenant_id IS NULL OR b.tenant_id = ft.tenant_id) " +
                        "    AND (b.min_amount IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= b.min_amount) " +
                        "    AND (b.max_amount IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) <  b.max_amount) " +
                        "  ORDER BY (b.tenant_id IS NOT NULL) DESC, b.seq ASC LIMIT 1 " +
                        ") tb " +
                        "WHERE ft.tenant_id=? AND ft.merchant_id IS NOT NULL AND " + rngFt + "DATE(ft.payment_date) IN " + dateScope +
                        " GROUP BY ft.tenant_id, ft.merchant_id, DATE(ft.payment_date), tb.label " +
                        "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                        "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume", tenantId);
                    totalRows += jdbcTemplate.update(
                        "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                        "SELECT tenant_id, merchant_id, DATE(payment_date), 'COUNTRY', UPPER(TRIM(txn_currency)), COUNT(*), SUM(store_base_currency_amount) " +
                        "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND " + rngBare + "DATE(payment_date) IN " + dateScope +
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
                // sum_daily_finance_rollup — the Finance Summary screen's fast
                // path (one row per tenant-day: pivot measures + fee stack).
                // Reads phase1's sum_daily_insight AND sum_daily_full, hence
                // phase2. Contiguous date runs collapse to one statement.
                phase2.add(runAsync(exec, "sum_daily_finance_rollup", () ->
                    com.acquira.common.service.FinanceRollupSql.rebuildDates(jdbcTemplate, tenantId,
                        distinctDates.stream().map(java.sql.Date::toLocalDate)
                            .collect(java.util.stream.Collectors.toList()))));
                phase2.add(runAsync(exec, "top_spending_customer", () ->
                    jdbcTemplate.update("WITH DailyCustSpend AS (SELECT tenant_id, merchant_id, DATE(payment_date) as b_date, card_number, " +
                        "SUM(store_base_currency_amount) as total_spend FROM fact_transaction WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                        " GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number), " +
                        "Ranked AS (SELECT *, ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn FROM DailyCustSpend) " +
                        "UPDATE sum_daily_merchant s SET top_spending_customer_id=r.card_number, top_spending_amount=r.total_spend " +
                        // PERF (2026-08-26): direct business_date scope on s — the join
                        // equality alone cannot prune this partitioned table. Implied by
                        // s.business_date = r.b_date (r.b_date ∈ dateScope by definition).
                        "FROM Ranked r WHERE s.tenant_id=r.tenant_id AND s.merchant_id=r.merchant_id AND s.business_date=r.b_date " +
                        "AND s.business_date IN " + dateScope + " AND r.rn=1 AND s.tenant_id = ?",
                        tenantId, tenantId)));
                java.util.concurrent.CompletableFuture.allOf(phase2.toArray(new java.util.concurrent.CompletableFuture[0])).join();

            } finally { exec.shutdown(); }

            log.info(String.format("populateSummary completed in %.1fs", (System.currentTimeMillis() - start) / 1000.0));
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
    }

    private static java.util.concurrent.CompletableFuture<Void> runAsync(
            java.util.concurrent.ExecutorService exec, String name,
            java.util.function.Supplier<Integer> work) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            long t = System.currentTimeMillis();
            try {
                int rows = work.get();
                log.warn(
                    "  [populateSummary] {} {} rows in {}s",
                    String.format("%-25s", name), rows,
                    String.format("%.2f", (System.currentTimeMillis() - t) / 1000.0));
            } catch (Exception e) {
                log.error(
                    "  [populateSummary] {} FAILED in {}s: {}",
                    String.format("%-25s", name),
                    String.format("%.2f", (System.currentTimeMillis() - t) / 1000.0),
                    e.getMessage());
                throw e;
            }
        }, exec);
    }
}
