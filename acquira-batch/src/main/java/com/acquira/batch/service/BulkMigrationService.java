package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * BULK DATA MIGRATION SERVICE
 *
 * Purpose: Migrate 2+ years of transaction data from an existing source table
 * directly into the Acquira pipeline WITHOUT going through CSV upload.
 *
 * Flow:
 *   source_table → fact_transaction → summary tables (11 tables) → metrics
 *
 * Performance:
 *   - Processes month-by-month to avoid memory/lock issues
 *   - Uses bulk INSERT...SELECT (no row-by-row)
 *   - Expected: ~1M rows/min on decent PostgreSQL hardware
 *   - 2 years of data (~50M rows) ≈ 50 min total
 *
 * Usage:
 *   POST /api/admin/migration/start
 *   Body: { "tenantId": 1, "sourceTable": "legacy_transactions",
 *           "startMonth": "2024-01", "endMonth": "2025-12",
 *           "columnMapping": { "mid": "merchant_id_col", "payment_date": "txn_date", ... } }
 */
@Service
public class BulkMigrationService {

    private static final Logger log = LoggerFactory.getLogger(BulkMigrationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final com.acquira.common.repository.SumDailyMerchantRepository dailyMerchantRepo;
    private final com.acquira.common.repository.SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final com.acquira.common.service.MerchantMetricCalculator merchantMetricCalculator;
    private final PartitionMaintenanceService partitionService;

    // Track migration progress
    private volatile String currentPhase = "IDLE";
    private volatile int completedMonths = 0;
    private volatile int totalMonths = 0;
    private volatile long totalRowsMigrated = 0;
    private volatile String currentMonth = "";
    private volatile long startTimeMs = 0;

    // One bulk run (migration OR summary rebuild) at a time — both share the
    // progress fields above, so a second concurrent run would corrupt them and
    // interleave month-scoped DELETE+INSERT on the same summary tables.
    private final java.util.concurrent.atomic.AtomicBoolean runActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean isRunning() {
        return runActive.get();
    }

    // Report caches must be dropped after any bulk write outside the batch
    // jobs (migration, day deletion) — same reason CacheEvictionJobListener
    // exists for the ingest jobs.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.cache.CacheManager cacheManager;

    private final com.acquira.common.service.MonthlyMetricsRebuilder monthlyMetricsRebuilder;

    // Shared ingest pipeline pieces (2026-08-28) — the SAME beans the upload
    // job and backfill use, so migrated months price and summarize identically.
    @org.springframework.beans.factory.annotation.Autowired
    private FeeComputationService feeComputationService;

    @org.springframework.beans.factory.annotation.Autowired
    private SummaryPopulationService summaryPopulationService;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public BulkMigrationService(JdbcTemplate jdbcTemplate,
                                com.acquira.common.repository.SumDailyMerchantRepository dailyMerchantRepo,
                                com.acquira.common.repository.SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
                                com.acquira.common.service.MerchantMetricCalculator merchantMetricCalculator,
                                PartitionMaintenanceService partitionService,
                                com.acquira.common.service.MonthlyMetricsRebuilder monthlyMetricsRebuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.merchantMetricCalculator = merchantMetricCalculator;
        this.partitionService = partitionService;
        this.monthlyMetricsRebuilder = monthlyMetricsRebuilder;
    }

    private void evictReportCaches() {
        for (String name : com.acquira.common.config.ReportCacheConfig.ALL_CACHES) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
    }

    public Map<String, Object> getProgress() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phase", currentPhase);
        p.put("currentMonth", currentMonth);
        p.put("completedMonths", completedMonths);
        p.put("totalMonths", totalMonths);
        p.put("totalRowsMigrated", totalRowsMigrated);
        if (startTimeMs > 0) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            p.put("elapsedSeconds", elapsed / 1000);
            if (completedMonths > 0 && totalMonths > 0) {
                long avgPerMonth = elapsed / completedMonths;
                long remaining = avgPerMonth * (totalMonths - completedMonths);
                p.put("estimatedRemainingSeconds", remaining / 1000);
            }
        }
        return p;
    }

    /**
     * MAIN MIGRATION METHOD
     *
     * @param tenantId       Target tenant
     * @param sourceTable    Table containing legacy transactions (must be in same DB)
     * @param startMonth     First month to migrate (YYYY-MM)
     * @param endMonth       Last month to migrate (YYYY-MM)
     * @param columnMapping  Maps Acquira columns to source columns:
     *                       Required: mid, payment_date, txn_currency_amount
     *                       Optional: card_number, card_scheme, card_type, dcc, destination,
     *                                 store_base_currency_amount, msf, interchange_fee,
     *                                 merchant_name, transaction_type, transaction_date,
     *                                 arn, rrn_number, auth_code, txn_currency, store_base_currency
     */
    // SQL-safe identifier pattern: only letters, digits, underscores
    private static final java.util.regex.Pattern SAFE_IDENTIFIER = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private void validateIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": '" + value + "'. Only letters, digits, and underscores are allowed.");
        }
    }

    private void validateColumnMapping(String sourceTable, Map<String, String> columnMapping) {
        // Get actual columns from source table
        List<String> actualCols = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
            String.class, sourceTable);
        java.util.Set<String> colSet = new java.util.HashSet<>(actualCols);

        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            String val = entry.getValue().trim();
            // Literals are allowed, but only in forms that CANNOT carry SQL.
            //
            // SECURITY: this used to accept anything starting with a quote or
            // with "COALESCE", and skipped validateIdentifier entirely for
            // those — so a mapping value like
            //   'x'||(SELECT string_agg(password_hash,',') FROM users)
            // was emitted verbatim into the INSERT..SELECT, writing this app's
            // own credentials into fact_transaction where they were readable
            // through the normal Transactions screen. Quoted values are now
            // required to be a single, self-contained literal with no way to
            // resume expression context, and COALESCE is no longer a bypass.
            if (val.equals("NULL") || val.equals("0") || val.equals("false") || val.equals("true")
                || val.matches("-?\\d+(\\.\\d+)?")) {
                continue;
            }
            if (val.startsWith("'")) {
                // Exactly one quoted literal: opening quote, no embedded quote
                // except doubled '' escapes, closing quote, then nothing.
                if (!val.matches("^'(?:[^']|'')*'$")) {
                    throw new IllegalArgumentException(
                        "Invalid quoted literal in column mapping '" + entry.getKey() + "': " + val
                        + ". A quoted mapping must be a single literal such as 'ABC' — expressions, "
                        + "concatenation and subqueries are not allowed.");
                }
                continue;
            }
            // Must be a valid column name that exists in source table
            validateIdentifier(val, "column mapping '" + entry.getKey() + "'");
            if (!colSet.contains(val.toLowerCase()) && !colSet.contains(val)) {
                throw new IllegalArgumentException("Column '" + val + "' (mapped from '" + entry.getKey() + "') does not exist in table '" + sourceTable + "'. Available: " + actualCols);
            }
        }
    }

    public void startMigration(Long tenantId, String sourceTable, String startMonth,
                               String endMonth, Map<String, String> columnMapping) {

        if (!runActive.compareAndSet(false, true)) {
            throw new IllegalStateException("Another migration or summary rebuild is already running");
        }
        startTimeMs = System.currentTimeMillis();
        currentPhase = "INITIALIZING";
        completedMonths = 0;
        totalRowsMigrated = 0;

        try {
            // SECURITY: Validate source table name against injection
            validateIdentifier(sourceTable, "source table name");

            // SECURITY: Validate all column mapping values
            validateColumnMapping(sourceTable, columnMapping);

            // Validate source table exists
            Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, sourceTable);
            if (tableExists == null || tableExists == 0) {
                throw new IllegalArgumentException("Source table '" + sourceTable + "' does not exist");
            }

            // Parse months
            YearMonth start = YearMonth.parse(startMonth);
            YearMonth end = YearMonth.parse(endMonth);
            List<YearMonth> months = new ArrayList<>();
            YearMonth cursor = start;
            while (!cursor.isAfter(end)) {
                months.add(cursor);
                cursor = cursor.plusMonths(1);
            }
            totalMonths = months.size();
            log.info("[MIGRATION] Starting bulk migration: {} months ({} to {}), tenant={}, source={}",
                totalMonths, startMonth, endMonth, tenantId, sourceTable);

            // Ensure partitions exist for all years in range
            currentPhase = "CREATING_PARTITIONS";
            for (int year = start.getYear(); year <= end.getYear() + 1; year++) {
                partitionService.ensurePartitionsForYear(year);
            }

            // Process month-by-month
            for (int i = 0; i < months.size(); i++) {
                YearMonth ym = months.get(i);
                currentMonth = ym.toString();
                currentPhase = "MIGRATING_" + currentMonth;
                log.info("[MIGRATION] Processing month {}/{}: {}", i + 1, totalMonths, ym);

                long monthStart = System.currentTimeMillis();
                long rowsMigrated = migrateMonth(tenantId, sourceTable, ym, columnMapping);
                totalRowsMigrated += rowsMigrated;

                currentPhase = "SUMMARIZING_" + currentMonth;
                populateSummariesForMonth(tenantId, ym);

                completedMonths = i + 1;
                long monthMs = System.currentTimeMillis() - monthStart;
                log.info("[MIGRATION] Month {} complete: {} rows in {}s ({} rows/sec)",
                    ym, rowsMigrated, String.format("%.1f", monthMs / 1000.0),
                    monthMs > 0 ? rowsMigrated * 1000 / monthMs : 0);
            }

            // Final: calculate business metrics and dashboard metrics
            currentPhase = "CALCULATING_METRICS";
            log.info("[MIGRATION] Calculating business metrics...");
            calculateMetricsForRange(tenantId, start, end);

            currentPhase = "COMPLETED";
            long totalMs = System.currentTimeMillis() - startTimeMs;
            log.info("[MIGRATION] COMPLETE: {} months, {} total rows in {}s",
                totalMonths, totalRowsMigrated, String.format("%.1f", totalMs / 1000.0));

        } catch (Exception e) {
            currentPhase = "FAILED: " + e.getMessage();
            log.error("[MIGRATION] Failed: {}", e.getMessage(), e);
            throw new RuntimeException("Migration failed: " + e.getMessage(), e);
        } finally {
            runActive.set(false);
            // Evict on success AND failure — a failed run may still have
            // written months of data before dying.
            evictReportCaches();
        }
    }

    /**
     * SUPER-ADMIN SUMMARY REBUILD (correction tool).
     *
     * Re-derives every summary table and the dashboard metrics from the rows already
     * in fact_transaction for one tenant, month by month — the same
     * populateSummariesForMonth / calculateMetricsForRange pipeline the migration
     * uses, minus the ingestion. Nothing is ingested and no transactions are touched:
     * use this after correcting fact data directly (e.g. a SQL fix) so every
     * dashboard replicates the change end to end.
     *
     * Passing null for either month bound auto-detects it from the tenant's
     * first/last transaction.
     */
    public void rebuildSummaries(Long tenantId, YearMonth startMonth, YearMonth endMonth) {
        if (!runActive.compareAndSet(false, true)) {
            throw new IllegalStateException("Another migration or summary rebuild is already running");
        }
        startTimeMs = System.currentTimeMillis();
        currentPhase = "INITIALIZING";
        completedMonths = 0;
        totalMonths = 0;
        totalRowsMigrated = 0;
        currentMonth = "";

        try {
            if (startMonth == null || endMonth == null) {
                LocalDate minDate = jdbcTemplate.queryForObject(
                    "SELECT MIN(DATE(payment_date)) FROM fact_transaction WHERE tenant_id = ?",
                    LocalDate.class, tenantId);
                LocalDate maxDate = jdbcTemplate.queryForObject(
                    "SELECT MAX(DATE(payment_date)) FROM fact_transaction WHERE tenant_id = ?",
                    LocalDate.class, tenantId);
                if (minDate == null || maxDate == null) {
                    throw new IllegalStateException("No transaction data found for this tenant — nothing to rebuild");
                }
                if (startMonth == null) startMonth = YearMonth.from(minDate);
                if (endMonth == null) endMonth = YearMonth.from(maxDate);
            }
            if (startMonth.isAfter(endMonth)) {
                throw new IllegalArgumentException("startMonth must be before or equal to endMonth");
            }

            List<YearMonth> months = new ArrayList<>();
            for (YearMonth cursor = startMonth; !cursor.isAfter(endMonth); cursor = cursor.plusMonths(1)) {
                months.add(cursor);
            }
            totalMonths = months.size();
            log.info("[REBUILD] Rebuilding summaries from fact_transaction: {} months ({} to {}), tenant={}",
                totalMonths, startMonth, endMonth, tenantId);

            for (int i = 0; i < months.size(); i++) {
                YearMonth ym = months.get(i);
                currentMonth = ym.toString();
                currentPhase = "REBUILDING_" + currentMonth;
                populateSummariesForMonth(tenantId, ym);
                completedMonths = i + 1;
            }

            currentPhase = "CALCULATING_METRICS";
            calculateMetricsForRange(tenantId, startMonth, endMonth);

            // Final stats refresh across every table the rebuild rewrote, so all
            // dashboard queries plan against the finished shape rather than
            // whatever autovacuum has caught up with so far.
            currentPhase = "ANALYZING";
            analyzeQuietly(
                "sum_daily_merchant", "sum_daily_merchant_attribute", "sum_daily_bank",
                "sum_daily_scheme", "sum_daily_channel", "sum_daily_terminal",
                "sum_daily_finance", "sum_daily_insight", "sum_daily_mcc",
                "sum_daily_full", "sum_daily_explorer", "sum_daily_merchant_destination",
                "sum_daily_local_debit_bin", "sum_daily_finance_rollup",
                "sum_monthly_insight",
                "sum_monthly_bank", "sum_monthly_card", "merchant_activity_summary");

            currentPhase = "COMPLETED";
            long totalMs = System.currentTimeMillis() - startTimeMs;
            log.info("[REBUILD] COMPLETE: {} months for tenant {} in {}s",
                totalMonths, tenantId, String.format("%.1f", totalMs / 1000.0));

        } catch (Exception e) {
            currentPhase = "FAILED: " + e.getMessage();
            log.error("[REBUILD] Failed: {}", e.getMessage(), e);
            throw new RuntimeException("Summary rebuild failed: " + e.getMessage(), e);
        } finally {
            runActive.set(false);
            // Same posture as startMigration: a failed run may still have
            // rewritten months of summaries before dying.
            evictReportCaches();
        }
    }

    /**
     * SUPER-ADMIN FULL-DAY DELETE (correction tool).
     *
     * Removes ALL transactions (both AMS and CMM — there is no source discriminator)
     * for one tenant + one calendar date, and cleans up every summary table so the
     * dashboards reflect the deletion immediately. The day is left EMPTY (no repopulate);
     * re-upload a file for that date to bring it back.
     *
     * Why this is more than a single DELETE:
     *   - Daily summary tables (sum_daily_*) hold that date's aggregates → deleted by date.
     *   - Monthly rollups (sum_monthly_bank) span the whole month → we cannot just drop the
     *     day; we delete the month row and REBUILD it from the REMAINING sum_daily_bank rows,
     *     so the rest of the month survives intact.
     *   - sum_monthly_card aggregates the month per card → same treatment (delete month, rebuild
     *     from remaining fact rows for that month).
     *
     * Everything runs in ONE transaction so a failure leaves the data untouched.
     *
     * @return a summary map of how many rows were removed from each table.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> deleteDay(Long tenantId, LocalDate date) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        if (date == null) throw new IllegalArgumentException("date is required");

        YearMonth ym = YearMonth.from(date);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        int monthKey = ym.getYear() * 100 + ym.getMonthValue();

        Map<String, Object> deleted = new LinkedHashMap<>();

        // 1. Fact table — the source of truth. Both AMS and CMM rows for this date.
        deleted.put("fact_transaction", jdbcTemplate.update(
            "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) = ?",
            tenantId, date));

        // 2. Daily summary tables — all keyed by (tenant_id, business_date).
        String[] dailyTables = {
            "sum_daily_bank", "sum_daily_merchant", "sum_daily_merchant_attribute",
            "sum_daily_scheme", "sum_daily_channel", "sum_daily_terminal",
            "sum_daily_finance", "sum_daily_insight", "sum_daily_mcc",
            // 2026-08-15: these three were missing — a deleted day survived in
            // Explorer/Full rollups (and now the destination split) until the
            // next upload for that date rebuilt them.
            "sum_daily_full", "sum_daily_explorer", "sum_daily_merchant_destination",
            "sum_daily_local_debit_bin", "sum_daily_finance_rollup"
        };
        for (String tbl : dailyTables) {
            deleted.put(tbl, jdbcTemplate.update(
                "DELETE FROM " + tbl + " WHERE tenant_id = ? AND business_date = ?",
                tenantId, date));
        }

        // 3. merchant_activity_summary / merchant_opportunity_score are keyed by calc_date.
        deleted.put("merchant_activity_summary", jdbcTemplate.update(
            "DELETE FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date = ?",
            tenantId, date));
        deleted.put("merchant_opportunity_score", jdbcTemplate.update(
            "DELETE FROM merchant_opportunity_score WHERE tenant_id = ? AND calc_date = ?",
            tenantId, date));

        // 4. sum_monthly_card — month-grained. Delete the month, rebuild from REMAINING fact rows.
        jdbcTemplate.update("DELETE FROM sum_monthly_card WHERE tenant_id = ? AND month_key = ?",
            tenantId, monthKey);
        int cardRebuilt = jdbcTemplate.update(
            "INSERT INTO sum_monthly_card (tenant_id, merchant_id, month_key, card_number, visit_count, total_spend) " +
            "SELECT tenant_id, merchant_id, ?, card_number, COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL " +
            // Sargable (2026-08-29): bare payment_date bounds use the index;
            // DATE(payment_date) BETWEEN wrapped the column and forced a scan.
            "AND payment_date >= CAST(? AS DATE) AND payment_date < CAST(? AS DATE) + INTERVAL '1 day' " +
            "GROUP BY tenant_id, merchant_id, card_number",
            monthKey, tenantId, monthStart, monthEnd);
        deleted.put("sum_monthly_card_rebuilt", cardRebuilt);

        // 5. sum_monthly_bank — month rollup. Delete the month row, rebuild from the REMAINING
        //    sum_daily_bank rows (which no longer include the deleted day).
        jdbcTemplate.update("DELETE FROM sum_monthly_bank WHERE tenant_id = ? AND month_key = ?",
            tenantId, monthKey);
        int bankRebuilt = jdbcTemplate.update(
            "INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_base_volume, total_msf, " +
            "total_interchange, total_scheme_fee, total_ecom_fee, total_vat, total_net_revenue) " +
            "SELECT tenant_id, ?, SUM(total_txns), SUM(total_volume), SUM(COALESCE(total_base_volume,0)), SUM(total_msf), " +
            "SUM(total_interchange), SUM(total_scheme_fee), SUM(COALESCE(total_ecom_fee,0)), SUM(total_vat), SUM(total_net_revenue) " +
            "FROM sum_daily_bank WHERE tenant_id=? AND business_date BETWEEN ? AND ? " +
            "GROUP BY tenant_id",
            monthKey, tenantId, monthStart, monthEnd);
        deleted.put("sum_monthly_bank_rebuilt", bankRebuilt);

        // 6. sum_monthly_merchant_metrics — month-grained dashboard metrics. If no daily rows
        //    remain for the month, the metrics for it are stale → delete them. If days remain,
        //    leave them; they recompute on the next upload for the month.
        Integer remainingDays = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sum_daily_merchant WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            Integer.class, tenantId, monthStart, monthEnd);
        if (remainingDays == null || remainingDays == 0) {
            deleted.put("sum_monthly_merchant_metrics", jdbcTemplate.update(
                "DELETE FROM sum_monthly_merchant_metrics WHERE tenant_id = ? AND month_year = ?",
                tenantId, ym.toString()));
        } else {
            deleted.put("sum_monthly_merchant_metrics", "kept (" + remainingDays + " days remain in month)");
        }

        // 7. merchant_daily_metrics (reporting) — keyed by report_date.
        deleted.put("merchant_daily_metrics", jdbcTemplate.update(
            "DELETE FROM merchant_daily_metrics WHERE tenant_id = ? AND report_date = ?",
            tenantId, date));

        log.warn("[DELETE-DAY] tenant={} date={} removed: {}", tenantId, date, deleted);
        evictReportCaches();
        return deleted;
    }

    /**
     * Migrate one month of data: source_table → fact_transaction
     */
    private long migrateMonth(Long tenantId, String sourceTable, YearMonth ym,
                              Map<String, String> cm) {
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        // Build column expressions with safe fallbacks
        // REQUIRED columns (must exist in source):
        String midCol = cm.getOrDefault("mid", "mid");
        String paymentDateCol = cm.getOrDefault("payment_date", "payment_date");
        String txnAmountCol = cm.getOrDefault("txn_currency_amount", "txn_currency_amount");

        // OPTIONAL columns: if user maps them, use the mapped column; if not, use safe defaults
        // These defaults produce literal values (not column references) so they never fail
        String cardNumberCol = cm.containsKey("card_number") ? cm.get("card_number") : "NULL";
        String cardSchemeCol = cm.containsKey("card_scheme") ? cm.get("card_scheme") : "'UNKNOWN'";
        String cardTypeCol = cm.containsKey("card_type") ? cm.get("card_type") : "'UNKNOWN'";
        String dccCol = cm.containsKey("dcc") ? cm.get("dcc") : "false";
        String destinationCol = cm.containsKey("destination") ? cm.get("destination") : "'DOMESTIC'";
        String baseAmountCol = cm.containsKey("store_base_currency_amount") ? cm.get("store_base_currency_amount") : txnAmountCol;
        String msfCol = cm.containsKey("msf") ? cm.get("msf") : "0";
        String interchangeCol = cm.containsKey("interchange_fee") ? cm.get("interchange_fee") : "0";
        String merchantNameCol = cm.containsKey("merchant_name") ? cm.get("merchant_name") : "NULL";
        String txnTypeCol = cm.containsKey("transaction_type") ? cm.get("transaction_type") : "'SALE'";
        String txnDateCol = cm.containsKey("transaction_date") ? cm.get("transaction_date") : paymentDateCol;
        String arnCol = cm.containsKey("arn") ? cm.get("arn") : "NULL";
        String rrnCol = cm.containsKey("rrn_number") ? cm.get("rrn_number") : "NULL";
        String authCodeCol = cm.containsKey("auth_code") ? cm.get("auth_code") : "NULL";
        // CURRENCY DEFAULT (fixed 2026-08-10). These were hardcoded to 'BHD', so a
        // migration whose column mapping omitted currency stamped EVERY row as
        // Bahraini Dinar regardless of tenant — mislabelling an Egyptian tenant's
        // whole history as BHD, and the UAE tenant's as BHD too. The tenant's own
        // configured currency is the only defensible default; if that cannot be
        // resolved we refuse rather than guess, because a wrong currency label on
        // migrated history is effectively unrecoverable once summaries are built.
        String tenantCurrency = null;
        try {
            tenantCurrency = jdbcTemplate.queryForObject(
                "SELECT NULLIF(TRIM(COALESCE(t.base_currency, rc.currency_code)), '') " +
                "FROM tenant t LEFT JOIN ref_country rc ON rc.country_code = t.home_country_code " +
                "WHERE t.tenant_id = ?", String.class, tenantId);
        } catch (Exception e) {
            log.warn("[BulkMigration] Could not resolve currency for tenant {}: {}", tenantId, e.getMessage());
        }
        if ((!cm.containsKey("txn_currency") || !cm.containsKey("store_base_currency"))
                && (tenantCurrency == null || tenantCurrency.isBlank())) {
            throw new IllegalStateException(
                "Tenant " + tenantId + " has no resolvable base currency and the column mapping does not supply "
                + "txn_currency / store_base_currency. Set the tenant's Jurisdiction and currency, or map the "
                + "source currency columns, before migrating.");
        }
        String tenantCurrencyLiteral = "'" + (tenantCurrency == null ? "" : tenantCurrency.replace("'", "''")) + "'";
        String txnCurrencyCol = cm.containsKey("txn_currency") ? cm.get("txn_currency") : tenantCurrencyLiteral;
        String baseCurrencyCol = cm.containsKey("store_base_currency") ? cm.get("store_base_currency") : tenantCurrencyLiteral;

        // The month delete moved INTO the append-only transaction below, so the
        // old month disappears and the priced batch lands atomically.

        // Helper: prefix column ref with 'src.' only if it's an actual column name (not a literal like NULL, 0, 'SALE')
        java.util.function.Function<String, String> col = c -> {
            if (c == null) return "NULL";
            String trimmed = c.trim();
            // Literals: NULL, numbers, quoted strings, boolean
            if (trimmed.equals("NULL") || trimmed.equals("0") || trimmed.equals("false") || trimmed.equals("true")
                || trimmed.startsWith("'") || trimmed.matches("-?\\d+(\\.\\d+)?")) {
                return trimmed;
            }
            return "src." + trimmed;
        };

        // Insert from source → session batch table with merchant lookup (the
        // finished, fee-priced rows are flushed to fact_transaction below).
        String sql = "INSERT INTO tmp_fact_batch (tenant_id, merchant_id, payment_date, transaction_date, " +
            "card_number, card_scheme, card_type, dcc, destination, " +
            "txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount, " +
            "msf, interchange_fee, transaction_type, arn, rrn_number, auth_code) " +
            "SELECT ?, m.merchant_id, " + col.apply(paymentDateCol) + ", " + col.apply(txnDateCol) + ", " +
            col.apply(cardNumberCol) + ", " + col.apply(cardSchemeCol) + ", " + col.apply(cardTypeCol) + ", " +
            col.apply(dccCol) + ", " + col.apply(destinationCol) + ", " +
            col.apply(txnCurrencyCol) + ", " + col.apply(txnAmountCol) + ", " +
            col.apply(baseCurrencyCol) + ", " + col.apply(baseAmountCol) + ", " +
            col.apply(msfCol) + ", " + col.apply(interchangeCol) + ", " +
            col.apply(txnTypeCol) + ", " + col.apply(arnCol) + ", " + col.apply(rrnCol) + ", " + col.apply(authCodeCol) + " " +
            "FROM " + sourceTable + " src " +
            // FAN-OUT GUARD (2026-08-25): dim_merchant.mid is NOT unique per tenant, and
            // the bidirectional prefix LIKE made it worse (one source row could match many
            // merchants, e.g. mid '12' matching '123','1200',…), duplicating rows into
            // fact and inflating every summary rebuilt off it. Resolve to at most ONE
            // merchant via LATERAL … LIMIT 1, preferring an exact mid match, then the
            // shortest/lowest-id candidate. Mirrors the staging→fact fix elsewhere.
            "LEFT JOIN LATERAL (SELECT m.merchant_id FROM dim_merchant m " +
            "  WHERE m.tenant_id = ? AND (" +
            "    m.mid = CAST(src." + midCol + " AS VARCHAR) OR " +
            "    m.mid LIKE CAST(src." + midCol + " AS VARCHAR) || '%' OR " +
            "    CAST(src." + midCol + " AS VARCHAR) LIKE m.mid || '%') " +
            "  ORDER BY (m.mid = CAST(src." + midCol + " AS VARCHAR)) DESC, LENGTH(m.mid), m.merchant_id " +
            "  LIMIT 1) m ON TRUE " +
            "WHERE src." + paymentDateCol + " IS NOT NULL AND DATE(src." + paymentDateCol + ") BETWEEN ? AND ?";

        // =================================================================
        // APPEND-ONLY MONTH WRITE + SHARED FEE ENGINE (2026-08-28).
        //
        // Same pipeline shape as the upload job and backfill: stage the month
        // in a session temp table shaped LIKE fact_transaction, price it with
        // the SHARED FeeComputationService (migrated months previously got NO
        // fee pass at all — raw source interchange, NULL scheme/ecom fees, no
        // channel or resolution status), then atomically replace the month
        // with ONE INSERT. Runs inside a TransactionTemplate because temp
        // tables require every statement to share a connection (this service
        // otherwise runs on autocommit pooled connections), and so the
        // delete+flush is atomic. Scopes come from IngestScopes: sargable
        // payment_date ranges instead of the old DATE(payment_date) BETWEEN,
        // which scanned every partition.
        // =================================================================
        final java.util.List<java.sql.Date> monthDays = IngestScopes.daysBetween(monthStart, monthEnd);
        final String dateScope = IngestScopes.dateInList(monthDays);
        final String rngBare = IngestScopes.rangeClause(monthDays, "");
        final String rngF = IngestScopes.rangeClause(monthDays, "f.");
        final String rngFt = IngestScopes.rangeClause(monthDays, "ft.");
        final String insertSql = sql;
        Integer staged = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .execute(tx -> {
                jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fact_batch");
                jdbcTemplate.execute(
                    "CREATE TEMP TABLE tmp_fact_batch (LIKE fact_transaction INCLUDING DEFAULTS)");
                int inserted = jdbcTemplate.update(insertSql, tenantId, tenantId, monthStart, monthEnd);
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
                log.info("[MIGRATION] {}: staged {} rows (replaced {}), flushed {} in one append-only write",
                    ym, inserted, deleted, flushed);
                return inserted;
            });
        int rows = staged == null ? 0 : staged;

        // Auto-create merchants that weren't found in dim_merchant
        if (merchantNameCol != null && !"NULL".equals(merchantNameCol)) {
            String autoCreateSql = "INSERT INTO dim_merchant (tenant_id, mid, name, internal_id, status, created_date) " +
                "SELECT DISTINCT ?, CAST(src." + midCol + " AS VARCHAR), " + col.apply(merchantNameCol) + ", " +
                "CAST(src." + midCol + " AS VARCHAR), 'ACTIVE', NOW() " +
                "FROM " + sourceTable + " src " +
                "WHERE DATE(src." + paymentDateCol + ") BETWEEN ? AND ? " +
                "AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.tenant_id = ? " +
                "AND (m.mid = CAST(src." + midCol + " AS VARCHAR) OR m.mid LIKE CAST(src." + midCol + " AS VARCHAR) || '%')) " +
                "ON CONFLICT (tenant_id, internal_id) DO NOTHING";
            int created = jdbcTemplate.update(autoCreateSql, tenantId, monthStart, monthEnd, tenantId);
            if (created > 0) log.info("[MIGRATION] Auto-created {} merchants for {}", created, ym);
        }

        return rows;
    }
    /**
     * Populate all summary tables for one month.
     *
     * Since 2026-08-28 this delegates to the SHARED SummaryPopulationService —
     * the exact code the upload job's populateSummaryStep runs — instead of a
     * hand-maintained mirror. The mirror was the standing drift hazard this
     * javadoc used to warn about (it deletes each table's month first, so any
     * column aggregated differently was silently WIPED for every rebuilt
     * month; it had really happened: txn_currency_amount volumes and
     * hardcoded-zero scheme/ecom/vat fees). Delegating also makes the rebuild
     * scopes sargable (payment_date range clauses -> partition pruning) and
     * serializes against concurrent ingests via the same per-tenant advisory
     * lock the job takes.
     */
    private void populateSummariesForMonth(Long tenantId, YearMonth ym) {
        summaryPopulationService.populateForRange(tenantId, ym.atDay(1), ym.atEndOfMonth());
        log.info("[MIGRATION] Summaries complete for {}", ym);
    }
    /**
     * ANALYZE the named tables, ignoring failures.
     *
     * Statistics are an optimisation, never a correctness requirement — a
     * permissions error or a lock conflict here must not abort a rebuild that has
     * already rewritten months of summaries.
     */
    private void analyzeQuietly(String... tables) {
        for (String table : tables) {
            try {
                jdbcTemplate.execute("ANALYZE " + table);
            } catch (Exception e) {
                log.warn("[MIGRATION] ANALYZE {} failed (non-fatal): {}", table, e.getMessage());
            }
        }
    }

    /**
     * Calculate business metrics for the full date range
     */
    private void calculateMetricsForRange(Long tenantId, YearMonth start, YearMonth end) {
        // Dashboard metrics (month by month). The per-merchant SELECT+save loop
        // that used to live here (2 round trips per merchant per month) is now
        // the shared bulk rebuilder: 2 queries + 1 batched write per month,
        // independent of merchant count.
        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            currentMonth = cursor.toString();
            monthlyMetricsRebuilder.rebuildMonth(tenantId.intValue(), cursor.toString());
            cursor = cursor.plusMonths(1);
        }
    }

    /**
     * DRY RUN: Validate source table and column mapping without inserting data.
     * Returns sample data and row count.
     */
    public Map<String, Object> dryRun(Long tenantId, String sourceTable, Map<String, String> columnMapping) {
        Map<String, Object> result = new LinkedHashMap<>();

        // SECURITY: Validate table name
        if (!SAFE_IDENTIFIER.matcher(sourceTable).matches()) {
            result.put("error", "Invalid table name: only letters, digits, and underscores are allowed.");
            return result;
        }

        // Check table exists
        try {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, sourceTable);
            result.put("tableExists", exists != null && exists > 0);
            if (exists == null || exists == 0) {
                result.put("error", "Source table '" + sourceTable + "' does not exist");
                return result;
            }
        } catch (Exception e) {
            result.put("error", "Cannot check table: " + e.getMessage());
            return result;
        }

        // Get column list
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
                sourceTable);
            result.put("columns", columns);
        } catch (Exception e) {
            result.put("columnsError", e.getMessage());
        }

        // Get row count
        try {
            Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + sourceTable, Long.class);
            result.put("totalRows", rowCount);
        } catch (Exception e) {
            result.put("countError", e.getMessage());
        }

        // SECURITY: dryRun validated only the table name and then concatenated
        // every mapping VALUE into SELECT lists below — so it was a full
        // error-based SQL oracle against the app's own database (e.g.
        // "payment_date": "(SELECT current_setting('is_superuser'))" was
        // executed and its value returned in columnMappingValidation).
        // Validate with exactly the same rules the real migration uses. This is
        // a diagnostic endpoint, so an invalid mapping is REPORTED rather than
        // thrown — but nothing unvalidated is allowed to reach a statement.
        boolean mappingSafe = true;
        try {
            validateColumnMapping(sourceTable, columnMapping);
        } catch (RuntimeException e) {
            mappingSafe = false;
            result.put("columnMappingError", e.getMessage());
        }

        // Get date range. Only a validated mapping may contribute the column
        // name; otherwise fall back to the hardcoded default.
        String dateCol = mappingSafe
                ? columnMapping.getOrDefault("payment_date", "payment_date")
                : "payment_date";
        try {
            Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                "SELECT MIN(" + dateCol + ") as min_date, MAX(" + dateCol + ") as max_date FROM " + sourceTable);
            result.put("dateRange", dateRange);
        } catch (Exception e) {
            result.put("dateRangeError", "Column '" + dateCol + "' not found: " + e.getMessage());
        }

        // Get sample rows
        try {
            List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                "SELECT * FROM " + sourceTable + " LIMIT 5");
            result.put("sampleRows", sample);
        } catch (Exception e) {
            result.put("sampleError", e.getMessage());
        }

        // Probe each mapped column. Runs ONLY when the mapping passed
        // validation above — probing an unvalidated value is exactly the
        // arbitrary-SQL execution this endpoint must not offer.
        Map<String, String> mappingStatus = new LinkedHashMap<>();
        if (mappingSafe) {
            for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
                try {
                    jdbcTemplate.queryForObject(
                        "SELECT " + entry.getValue() + " FROM " + sourceTable + " LIMIT 1", Object.class);
                    mappingStatus.put(entry.getKey(), "OK -> " + entry.getValue());
                } catch (Exception e) {
                    mappingStatus.put(entry.getKey(), "FAILED -> " + entry.getValue() + " (" + e.getMessage() + ")");
                }
            }
        } else {
            for (String k : columnMapping.keySet()) {
                mappingStatus.put(k, "NOT CHECKED — fix columnMappingError first");
            }
        }
        result.put("columnMappingValidation", mappingStatus);
        result.put("tenantId", tenantId);

        return result;
    }
}
