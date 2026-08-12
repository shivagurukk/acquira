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

    public BulkMigrationService(JdbcTemplate jdbcTemplate,
                                com.acquira.common.repository.SumDailyMerchantRepository dailyMerchantRepo,
                                com.acquira.common.repository.SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
                                com.acquira.common.service.MerchantMetricCalculator merchantMetricCalculator,
                                PartitionMaintenanceService partitionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.merchantMetricCalculator = merchantMetricCalculator;
        this.partitionService = partitionService;
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
            // Skip literals (NULL, numbers, quoted strings, booleans, COALESCE expressions)
            if (val.equals("NULL") || val.equals("0") || val.equals("false") || val.equals("true")
                || val.startsWith("'") || val.matches("-?\\d+(\\.\\d+)?")
                || val.toUpperCase().startsWith("COALESCE")) {
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
                "sum_daily_full", "sum_daily_explorer", "sum_monthly_insight",
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
            "sum_daily_finance", "sum_daily_insight", "sum_daily_mcc"
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
            "AND DATE(payment_date) BETWEEN ? AND ? " +
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

        // Delete existing data for this month first (idempotent)
        jdbcTemplate.update(
            "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);

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

        // Insert from source → fact_transaction with merchant lookup
        String sql = "INSERT INTO fact_transaction (tenant_id, merchant_id, payment_date, transaction_date, " +
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
            "LEFT JOIN dim_merchant m ON m.tenant_id = ? AND (" +
            "  m.mid = CAST(src." + midCol + " AS VARCHAR) OR " +
            "  m.mid LIKE CAST(src." + midCol + " AS VARCHAR) || '%' OR " +
            "  CAST(src." + midCol + " AS VARCHAR) LIKE m.mid || '%') " +
            "WHERE src." + paymentDateCol + " IS NOT NULL AND DATE(src." + paymentDateCol + ") BETWEEN ? AND ?";

        int rows = jdbcTemplate.update(sql, tenantId, tenantId, monthStart, monthEnd);

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
     * The SQL here MUST stay in lockstep with TransactionJobConfig's
     * populateSummary tasklet — this method deletes each table's month first, so
     * any column this copy aggregates differently is silently WIPED for every
     * rebuilt month. That is not hypothetical: this copy had drifted to
     * txn_currency_amount volumes (NULL in base-currency-only feeds) and
     * hardcoded-zero scheme/ecom/vat fees, so the first normalization rebuild
     * zeroed volumes and scheme fees on every screen. Volume basis is
     * store_base_currency_amount (settlement), fees come from fact columns.
     */
    private void populateSummariesForMonth(Long tenantId, YearMonth ym) {
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        String dateScope = String.format(
            "(SELECT generate_series('%s'::date, '%s'::date, '1 day')::date)",
            monthStart, monthEnd);
        String monthScope = "(" + ym.getYear() * 100 + ym.getMonthValue() + ")";

        // Clean existing summaries for this month (idempotent)
        jdbcTemplate.update("DELETE FROM sum_daily_merchant WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("DELETE FROM sum_daily_merchant_attribute WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("DELETE FROM sum_daily_bank WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("DELETE FROM sum_monthly_card WHERE tenant_id = ? AND month_key = ?",
            tenantId, ym.getYear() * 100 + ym.getMonthValue());

        // 1. sum_daily_bank
        jdbcTemplate.update("INSERT INTO sum_daily_bank (tenant_id, business_date, total_txns, total_volume, total_base_volume, total_msf, " +
            "total_interchange, total_scheme_fee, total_ecom_fee, total_vat, total_net_revenue) " +
            "SELECT tenant_id, DATE(payment_date), COUNT(*), SUM(store_base_currency_amount), SUM(store_base_currency_amount), SUM(msf), " +
            "SUM(interchange_fee), SUM(COALESCE(scheme_fee,0)), SUM(COALESCE(ecom_fee,0)), SUM(vat), " +
            "SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - COALESCE(scheme_fee,0) - COALESCE(ecom_fee,0)) " +
            "FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) BETWEEN ? AND ? " +
            "GROUP BY tenant_id, DATE(payment_date) " +
            "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, " +
            "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, total_ecom_fee=EXCLUDED.total_ecom_fee, " +
            "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue",
            tenantId, monthStart, monthEnd);

        // 2. sum_daily_merchant (most important for PDF reports)
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
            "WHERE f.tenant_id = ? AND DATE(f.payment_date) BETWEEN ? AND ? AND f.merchant_id IS NOT NULL " +
            "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, m.sales_user_id " +
            "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, " +
            "total_msf=EXCLUDED.total_msf, total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
            "total_ecom_fee=EXCLUDED.total_ecom_fee, " +
            "total_margin=EXCLUDED.total_margin, total_debit_prepaid_volume=EXCLUDED.total_debit_prepaid_volume, " +
            "total_credit_volume=EXCLUDED.total_credit_volume, sales_user_id=EXCLUDED.sales_user_id, " +
            "unique_customer_count=EXCLUDED.unique_customer_count, " +
            "dcc_eligible_volume=EXCLUDED.dcc_eligible_volume, dcc_optin_volume=EXCLUDED.dcc_optin_volume, " +
            "dcc_optout_volume=EXCLUDED.dcc_optout_volume, dcc_eligible_count=EXCLUDED.dcc_eligible_count, " +
            "dcc_optin_count=EXCLUDED.dcc_optin_count",
            tenantId, monthStart, monthEnd);

        // 3. Merchant attributes. CARD_SCHEME uses the upload job's normalization
        // (blank/'NULL' schemes fall back to card_type, else 'Unclassified') so
        // rebuilt attribute rows group under the same labels the upload writes.
        String schemeExpr = "UPPER(CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
            "          THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') " +
            "          ELSE card_scheme END)";
        jdbcTemplate.update(
            "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
            "SELECT tenant_id, merchant_id, DATE(payment_date), 'CARD_SCHEME', " + schemeExpr + ", COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? AND merchant_id IS NOT NULL " +
            "GROUP BY tenant_id, merchant_id, DATE(payment_date), " + schemeExpr + " " +
            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
            tenantId, monthStart, monthEnd);
        String[][] attrs = {{"CARD_TYPE","card_type"},
            {"DESTINATION","destination"}, {"TRANSACTION_TYPE","transaction_type"}};
        for (String[] attr : attrs) {
            jdbcTemplate.update(String.format(
                "INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
                "SELECT tenant_id, merchant_id, DATE(payment_date), '%s', UPPER(COALESCE(%s,'UNKNOWN')), COUNT(*), SUM(store_base_currency_amount) " +
                "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? AND merchant_id IS NOT NULL " +
                "GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(COALESCE(%s,'UNKNOWN')) " +
                "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
                "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
                attr[0], attr[1], attr[1]),
                tenantId, monthStart, monthEnd);
        }

        // HOUR attribute
        jdbcTemplate.update("INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
            "SELECT tenant_id, merchant_id, DATE(payment_date), 'HOUR', CAST(EXTRACT(HOUR FROM transaction_date) AS VARCHAR), COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? AND merchant_id IS NOT NULL AND transaction_date IS NOT NULL " +
            "GROUP BY tenant_id, merchant_id, DATE(payment_date), EXTRACT(HOUR FROM transaction_date) " +
            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
            tenantId, monthStart, monthEnd);

        // TXN_SIZE_BUCKET
        // Legacy aggregations stored a single '1K+' bucket; remove those rows for the
        // window so they can't double-count alongside the '1K-5K'/'5K+' split below.
        jdbcTemplate.update("DELETE FROM sum_daily_merchant_attribute WHERE tenant_id=? " +
            "AND business_date BETWEEN ? AND ? AND attribute_type='TXN_SIZE_BUCKET' AND attribute_value='1K+'",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
            "SELECT tenant_id, merchant_id, DATE(payment_date), 'TXN_SIZE_BUCKET', " +
            "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
            "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
            "WHEN store_base_currency_amount < 1000 THEN '500-1K' WHEN store_base_currency_amount < 5000 THEN '1K-5K' " +
            "ELSE '5K+' END, COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? AND merchant_id IS NOT NULL " +
            "GROUP BY tenant_id, merchant_id, DATE(payment_date), " +
            "CASE WHEN store_base_currency_amount < 50 THEN '< 50' WHEN store_base_currency_amount < 100 THEN '50-100' " +
            "WHEN store_base_currency_amount < 250 THEN '100-250' WHEN store_base_currency_amount < 500 THEN '250-500' " +
            "WHEN store_base_currency_amount < 1000 THEN '500-1K' WHEN store_base_currency_amount < 5000 THEN '1K-5K' " +
            "ELSE '5K+' END " +
            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
            tenantId, monthStart, monthEnd);

        // COUNTRY attribute (international spend by currency) — upload-job parity.
        jdbcTemplate.update("INSERT INTO sum_daily_merchant_attribute (tenant_id, merchant_id, business_date, attribute_type, attribute_value, metric_count, metric_volume) " +
            "SELECT tenant_id, merchant_id, DATE(payment_date), 'COUNTRY', UPPER(TRIM(txn_currency)), COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) BETWEEN ? AND ? " +
            "AND UPPER(destination) = 'INTERNATIONAL' AND NULLIF(TRIM(txn_currency), '') IS NOT NULL " +
            "GROUP BY tenant_id, merchant_id, DATE(payment_date), UPPER(TRIM(txn_currency)) HAVING COUNT(*) > 0 " +
            "ON CONFLICT (tenant_id, merchant_id, business_date, attribute_type, attribute_value) DO UPDATE SET " +
            "metric_count=EXCLUDED.metric_count, metric_volume=EXCLUDED.metric_volume",
            tenantId, monthStart, monthEnd);

        // 4. sum_monthly_card (loyalty)
        int monthKey = ym.getYear() * 100 + ym.getMonthValue();
        jdbcTemplate.update("INSERT INTO sum_monthly_card (tenant_id, merchant_id, month_key, card_number, visit_count, total_spend) " +
            "SELECT tenant_id, merchant_id, ?, card_number, COUNT(*), SUM(store_base_currency_amount) " +
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? AND merchant_id IS NOT NULL " +
            "GROUP BY tenant_id, merchant_id, card_number " +
            "ON CONFLICT (tenant_id, merchant_id, month_key, card_number) DO UPDATE SET " +
            "visit_count=EXCLUDED.visit_count, total_spend=EXCLUDED.total_spend",
            monthKey, tenantId, monthStart, monthEnd);

        // 5. sum_daily_scheme
        jdbcTemplate.update("DELETE FROM sum_daily_scheme WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_scheme (tenant_id, business_date, card_scheme, total_txns, " +
            "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
            "SELECT tenant_id, DATE(payment_date), " +
            "  CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
            "       THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') " +
            "       ELSE card_scheme END, " +
            "COUNT(*), SUM(store_base_currency_amount), SUM(msf), " +
            "SUM(interchange_fee), SUM(COALESCE(scheme_fee,0)), " +
            "SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)-COALESCE(scheme_fee,0)-COALESCE(ecom_fee,0)) " +
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? " +
            "GROUP BY tenant_id, DATE(payment_date), " +
            "  CASE WHEN NULLIF(TRIM(card_scheme), '') IS NULL OR UPPER(TRIM(card_scheme)) = 'NULL' " +
            "       THEN COALESCE(NULLIF(TRIM(card_type), ''), 'Unclassified') ELSE card_scheme END " +
            "HAVING SUM(store_base_currency_amount) > 0 " +
            "ON CONFLICT (tenant_id, business_date, card_scheme) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
            "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
            "total_net_revenue=EXCLUDED.total_net_revenue",
            tenantId, monthStart, monthEnd);

        // 6. sum_daily_channel
        jdbcTemplate.update("DELETE FROM sum_daily_channel WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_channel (tenant_id, business_date, channel, total_txns, " +
            "total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue) " +
            "SELECT f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS'), COUNT(*), SUM(f.store_base_currency_amount), " +
            "SUM(f.msf), SUM(f.interchange_fee), SUM(COALESCE(f.scheme_fee,0)), " +
            "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
            "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
            "WHERE f.tenant_id=? AND DATE(f.payment_date) BETWEEN ? AND ? " +
            "GROUP BY f.tenant_id, DATE(f.payment_date), COALESCE(t.type,'POS') " +
            "ON CONFLICT (tenant_id, business_date, channel) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
            "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
            "total_net_revenue=EXCLUDED.total_net_revenue",
            tenantId, monthStart, monthEnd);

        // 7. sum_daily_terminal
        jdbcTemplate.update("DELETE FROM sum_daily_terminal WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
            "total_txns, total_volume, total_base_volume, total_msf, total_interchange, total_scheme_fee, total_ecom_fee, total_revenue) " +
            "SELECT tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id, COUNT(*), SUM(store_base_currency_amount), " +
            "SUM(store_base_currency_amount), SUM(msf), SUM(COALESCE(interchange_fee,0)), SUM(COALESCE(scheme_fee,0)), SUM(COALESCE(ecom_fee,0)), " +
            "SUM(COALESCE(msf,0)-COALESCE(interchange_fee,0)-COALESCE(scheme_fee,0)-COALESCE(ecom_fee,0)) " +
            "FROM fact_transaction WHERE tenant_id=? AND merchant_id IS NOT NULL AND DATE(payment_date) BETWEEN ? AND ? " +
            "GROUP BY tenant_id, DATE(payment_date), merchant_id, store_id, terminal_id " +
            "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, " +
            "total_msf=EXCLUDED.total_msf, total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
            "total_ecom_fee=EXCLUDED.total_ecom_fee, total_revenue=EXCLUDED.total_revenue",
            tenantId, monthStart, monthEnd);

        // 8. sum_daily_finance
        jdbcTemplate.update("DELETE FROM sum_daily_finance WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
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
            "FROM fact_transaction WHERE tenant_id=? AND DATE(payment_date) BETWEEN ? AND ? " +
            "GROUP BY tenant_id, DATE(payment_date) " +
            "ON CONFLICT (tenant_id, business_date) DO UPDATE SET " +
            "dom_debit_cnt=EXCLUDED.dom_debit_cnt, dom_debit_vol=EXCLUDED.dom_debit_vol, " +
            "dom_debit_msf=EXCLUDED.dom_debit_msf, dom_debit_optin=EXCLUDED.dom_debit_optin, " +
            "dom_credit_cnt=EXCLUDED.dom_credit_cnt, dom_credit_vol=EXCLUDED.dom_credit_vol, " +
            "dom_credit_msf=EXCLUDED.dom_credit_msf, dom_credit_optin=EXCLUDED.dom_credit_optin, " +
            "int_cnt=EXCLUDED.int_cnt, int_vol=EXCLUDED.int_vol, int_msf=EXCLUDED.int_msf, int_optin=EXCLUDED.int_optin, " +
            "total_vol=EXCLUDED.total_vol, total_msf=EXCLUDED.total_msf",
            tenantId, monthStart, monthEnd);

        // 9. sum_daily_insight
        jdbcTemplate.update("DELETE FROM sum_daily_insight WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_insight (tenant_id, business_date, merchant_id, store_id, terminal_id, " +
            "card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf) " +
            "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
            "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
            "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') " +
            "     ELSE f.card_scheme END, " +
            "f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf) " +
            "FROM fact_transaction f LEFT JOIN dim_terminal t ON f.terminal_id=t.terminal_id AND t.tenant_id=f.tenant_id " +
            "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) BETWEEN ? AND ? " +
            "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
            "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
            "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
            "f.card_type, f.destination, COALESCE(t.type,'POS'), f.dcc " +
            "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) " +
            "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf",
            tenantId, monthStart, monthEnd);

        // 10. sum_daily_mcc
        jdbcTemplate.update("DELETE FROM sum_daily_mcc WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
            tenantId, monthStart, monthEnd);
        jdbcTemplate.update("INSERT INTO sum_daily_mcc (tenant_id, business_date, mcc, card_scheme, total_txns, " +
            "total_volume, total_msf, total_scheme_fee, total_net_revenue) " +
            "SELECT f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme, COUNT(*), SUM(f.store_base_currency_amount), SUM(f.msf), " +
            "SUM(COALESCE(f.scheme_fee,0)), " +
            "SUM(COALESCE(f.msf,0)-COALESCE(f.interchange_fee,0)-COALESCE(f.scheme_fee,0)-COALESCE(f.ecom_fee,0)) " +
            "FROM fact_transaction f LEFT JOIN dim_store s ON f.store_id=s.store_id AND s.tenant_id=f.tenant_id " +
            "WHERE f.tenant_id=? AND DATE(f.payment_date) BETWEEN ? AND ? " +
            "GROUP BY f.tenant_id, DATE(f.payment_date), s.mcc, f.card_scheme " +
            "ON CONFLICT (tenant_id, business_date, mcc, card_scheme) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
            "total_scheme_fee=EXCLUDED.total_scheme_fee, total_net_revenue=EXCLUDED.total_net_revenue",
            tenantId, monthStart, monthEnd);

        // 11. sum_monthly_bank
        jdbcTemplate.update("DELETE FROM sum_monthly_bank WHERE tenant_id = ? AND month_key = ?",
            tenantId, monthKey);
        jdbcTemplate.update("INSERT INTO sum_monthly_bank (tenant_id, month_key, total_txns, total_volume, total_base_volume, total_msf, " +
            "total_interchange, total_scheme_fee, total_ecom_fee, total_vat, total_net_revenue) " +
            "SELECT tenant_id, ?, SUM(total_txns), SUM(total_volume), SUM(COALESCE(total_base_volume,0)), " +
            "SUM(total_msf), SUM(total_interchange), SUM(total_scheme_fee), SUM(COALESCE(total_ecom_fee,0)), SUM(total_vat), SUM(total_net_revenue) " +
            "FROM sum_daily_bank WHERE tenant_id=? AND business_date BETWEEN ? AND ? " +
            "GROUP BY tenant_id " +
            "ON CONFLICT (tenant_id, month_key) DO UPDATE SET " +
            "total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, " +
            "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, total_ecom_fee=EXCLUDED.total_ecom_fee, " +
            "total_vat=EXCLUDED.total_vat, total_net_revenue=EXCLUDED.total_net_revenue",
            monthKey, tenantId, monthStart, monthEnd);

        // 11b-11d. sum_daily_full / sum_daily_explorer / sum_monthly_insight.
        // These three were previously ONLY rebuilt by the upload job's
        // populateSummary step, so a screen-triggered Summary Rebuild left them
        // stale — the Data Explorer and Volume & Revenue screens kept serving
        // pre-correction numbers unless the month's files were re-uploaded.
        // SQL mirrors TransactionJobConfig's populateSummary (same grain, same
        // scheme normalization), scoped to this month. Wrapped defensively:
        // deployments that predate the partition migration lack these tables,
        // and their absence must not fail the whole rebuild.
        try {
            jdbcTemplate.update("DELETE FROM sum_daily_full WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, monthStart, monthEnd);
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
                "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) BETWEEN ? AND ? " +
                "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, st.mcc, " +
                "COALESCE(t.type,'POS'), f.destination, " +
                "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
                "f.card_type, f.dcc " +
                "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in) " +
                "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf, " +
                "total_interchange=EXCLUDED.total_interchange, total_scheme_fee=EXCLUDED.total_scheme_fee, " +
                "total_ecom_fee=EXCLUDED.total_ecom_fee, total_net_revenue=EXCLUDED.total_net_revenue, " +
                "dcc_optin_count=EXCLUDED.dcc_optin_count",
                tenantId, monthStart, monthEnd);
        } catch (Exception e) {
            log.warn("[REBUILD] sum_daily_full rebuild skipped (non-fatal): {}", e.getMessage());
        }

        try {
            jdbcTemplate.update("DELETE FROM sum_daily_explorer WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, monthStart, monthEnd);
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
                "WHERE f.tenant_id=? AND f.merchant_id IS NOT NULL AND DATE(f.payment_date) BETWEEN ? AND ? " +
                "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id, f.store_id, f.terminal_id, " +
                "f.transaction_type, " +
                "CASE WHEN NULLIF(TRIM(f.card_scheme), '') IS NULL OR UPPER(TRIM(f.card_scheme)) = 'NULL' " +
                "     THEN COALESCE(NULLIF(TRIM(f.card_type), ''), 'Unclassified') ELSE f.card_scheme END, " +
                "f.card_type, f.destination, COALESCE(t.type,'POS'), f.txn_currency, f.store_base_currency, f.dcc " +
                "ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in) " +
                "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_txn_currency_amount=EXCLUDED.total_txn_currency_amount, " +
                "total_base_volume=EXCLUDED.total_base_volume, total_msf=EXCLUDED.total_msf, total_vat=EXCLUDED.total_vat, " +
                "total_settled=EXCLUDED.total_settled, total_interchange=EXCLUDED.total_interchange, " +
                "total_scheme_fee=EXCLUDED.total_scheme_fee, store_base_currency=EXCLUDED.store_base_currency",
                tenantId, monthStart, monthEnd);
        } catch (Exception e) {
            log.warn("[REBUILD] sum_daily_explorer rebuild skipped (non-fatal): {}", e.getMessage());
        }

        // Month rollup of the freshly rebuilt sum_daily_insight (step 9 above),
        // so monthly = SUM(daily) reconciles exactly — same as the upload job.
        try {
            jdbcTemplate.update("DELETE FROM sum_monthly_insight WHERE tenant_id = ? AND month_key = ?",
                tenantId, monthKey);
            jdbcTemplate.update("INSERT INTO sum_monthly_insight (tenant_id, month_key, merchant_id, store_id, terminal_id, " +
                "card_scheme, card_type, destination, channel, is_opt_in, total_txns, total_volume, total_msf) " +
                "SELECT tenant_id, ?, merchant_id, store_id, terminal_id, " +
                "card_scheme, card_type, destination, channel, is_opt_in, " +
                "SUM(total_txns), SUM(total_volume), SUM(total_msf) " +
                "FROM sum_daily_insight WHERE tenant_id=? AND business_date BETWEEN ? AND ? " +
                "GROUP BY tenant_id, merchant_id, store_id, terminal_id, " +
                "card_scheme, card_type, destination, channel, is_opt_in " +
                "ON CONFLICT (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in) " +
                "DO UPDATE SET total_txns=EXCLUDED.total_txns, total_volume=EXCLUDED.total_volume, total_msf=EXCLUDED.total_msf",
                monthKey, tenantId, monthStart, monthEnd);
        } catch (Exception e) {
            log.warn("[REBUILD] sum_monthly_insight rebuild skipped (non-fatal): {}", e.getMessage());
        }

        // 12. Top spending customer per merchant per day
        jdbcTemplate.update("WITH DailyCustSpend AS (SELECT tenant_id, merchant_id, DATE(payment_date) as b_date, card_number, " +
            "SUM(store_base_currency_amount) as total_spend FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) BETWEEN ? AND ? " +
            "AND merchant_id IS NOT NULL GROUP BY tenant_id, merchant_id, DATE(payment_date), card_number), " +
            "Ranked AS (SELECT *, ROW_NUMBER() OVER(PARTITION BY tenant_id, merchant_id, b_date ORDER BY total_spend DESC) as rn FROM DailyCustSpend) " +
            "UPDATE sum_daily_merchant s SET top_spending_customer_id=r.card_number, top_spending_amount=r.total_spend " +
            "FROM Ranked r WHERE s.tenant_id=r.tenant_id AND s.merchant_id=r.merchant_id AND s.business_date=r.b_date AND r.rn=1 AND s.tenant_id = ?",
            tenantId, monthStart, monthEnd, tenantId);

        // 13. merchant_activity_summary (business metrics per month)
        //
        // The join is bounded to payment_date < monthEnd+1day. Two reasons, and the
        // first is correctness, not speed:
        //
        //  1. This row is a SNAPSHOT as at monthEnd (calc_date = monthEnd, and the
        //     7d/30d windows and ACTIVE/DORMANT status are all measured back from
        //     it). Unbounded, rebuilding an OLD month pulled in transactions that
        //     happened AFTER it — so a merchant dormant in May 2026 was written as
        //     ACTIVE because it traded in July. Rebuilding history rewrote it with
        //     facts from the future.
        //  2. Unbounded, this was the only statement here with no date filter on
        //     fact_transaction: a full scan of every partition of the tenant's
        //     entire history, repeated ONCE PER MONTH of the rebuild range. A
        //     12-month rebuild did 12 full-history scans of the largest table in
        //     the database, which is what saturates I/O and makes dashboards
        //     time out while a rebuild runs.
        //
        // The bound stays in the ON clause, not WHERE, so merchants with no
        // transactions still produce an ONBOARDED row exactly as before. Compared
        // on the raw payment_date column (not DATE(...)) so partition pruning works.
        jdbcTemplate.update("INSERT INTO merchant_activity_summary (tenant_id, merchant_id, calc_date, " +
            "first_txn_date, last_txn_date, last_7d_cnt, last_7d_value, last_30d_cnt, last_30d_value, status, status_change_date) " +
            "SELECT m.tenant_id, m.merchant_id, ?, MIN(f.payment_date), MAX(f.payment_date), " +
            "COALESCE(COUNT(CASE WHEN f.payment_date >= ? - INTERVAL '7 days' THEN 1 END), 0), " +
            "COALESCE(SUM(CASE WHEN f.payment_date >= ? - INTERVAL '7 days' THEN f.store_base_currency_amount ELSE 0 END), 0), " +
            "COALESCE(COUNT(CASE WHEN f.payment_date >= ? - INTERVAL '30 days' THEN 1 END), 0), " +
            "COALESCE(SUM(CASE WHEN f.payment_date >= ? - INTERVAL '30 days' THEN f.store_base_currency_amount ELSE 0 END), 0), " +
            "CASE WHEN MAX(f.payment_date) >= ? - INTERVAL '30 days' THEN 'ACTIVE' " +
            "WHEN MAX(f.payment_date) < ? - INTERVAL '30 days' THEN 'DORMANT' ELSE 'ONBOARDED' END, ? " +
            "FROM dim_merchant m LEFT JOIN fact_transaction f ON m.merchant_id = f.merchant_id " +
            "  AND f.tenant_id = m.tenant_id AND f.payment_date < ? " +
            "WHERE m.tenant_id = ? GROUP BY m.tenant_id, m.merchant_id " +
            "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET " +
            "first_txn_date=EXCLUDED.first_txn_date, last_txn_date=EXCLUDED.last_txn_date, " +
            "last_7d_cnt=EXCLUDED.last_7d_cnt, last_7d_value=EXCLUDED.last_7d_value, " +
            "last_30d_cnt=EXCLUDED.last_30d_cnt, last_30d_value=EXCLUDED.last_30d_value, " +
            "status=EXCLUDED.status, status_change_date=EXCLUDED.status_change_date",
            monthEnd, monthEnd, monthEnd, monthEnd, monthEnd, monthEnd, monthEnd, monthEnd,
            monthEnd.plusDays(1), tenantId);

        // 14. merchant_opportunity_score — same trivial derivation from the freshly
        // written activity snapshot that the upload job's businessMetrics step does.
        // Without this, rebuilt months left the opportunity screen with stale or
        // (after a day-delete) missing scores.
        jdbcTemplate.update("INSERT INTO merchant_opportunity_score (tenant_id, merchant_id, score, reason_tags, calc_date) " +
            "SELECT tenant_id, merchant_id, CASE WHEN last_30d_value > 1000 THEN 80 ELSE 40 END, 'Automated Score', calc_date " +
            "FROM merchant_activity_summary WHERE tenant_id = ? AND calc_date = ? " +
            "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET score=EXCLUDED.score, reason_tags=EXCLUDED.reason_tags",
            tenantId, monthEnd);

        // Refresh planner statistics on the two summary tables the dashboards read
        // most. Every month of the rebuild deletes and reinserts their whole month
        // grain, which leaves the row estimates describing the pre-rebuild shape.
        // Dashboard queries then pick plans for the wrong table size, run long, and
        // hit the 30s statement_timeout that TenantAwareDataSource stamps on web
        // connections — the "screen times out while a rebuild is running" symptom.
        // ANALYZE samples rather than scans, so this is cheap per month.
        analyzeQuietly("sum_daily_merchant", "sum_daily_insight");

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
        // Dashboard metrics (month by month)
        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            currentMonth = cursor.toString();
            LocalDate monthStart = cursor.atDay(1);
            LocalDate monthEnd = cursor.atEndOfMonth();
            String monthYear = cursor.toString();

            List<com.acquira.common.model.SumDailyMerchant> dailyRecs =
                dailyMerchantRepo.findByTenantIdAndDateRange(tenantId.intValue(), monthStart, monthEnd);
            Map<Long, List<com.acquira.common.model.SumDailyMerchant>> grouped =
                dailyRecs.stream().collect(java.util.stream.Collectors.groupingBy(
                    com.acquira.common.model.SumDailyMerchant::getMerchantId));

            for (Map.Entry<Long, List<com.acquira.common.model.SumDailyMerchant>> entry : grouped.entrySet()) {
                var metrics = merchantMetricCalculator.calculateMetrics(
                    entry.getValue(), tenantId.intValue(), entry.getKey(), monthYear);
                var existing = monthlyMetricsRepo.findByMerchantAndMonth(
                    tenantId.intValue(), entry.getKey(), monthYear);
                if (existing.isPresent()) {
                    metrics.setMetricId(existing.get().getMetricId());
                    metrics.setCreatedAt(existing.get().getCreatedAt());
                }
                monthlyMetricsRepo.save(metrics);
            }
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

        // Get date range
        String dateCol = columnMapping.getOrDefault("payment_date", "payment_date");
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

        // Validate column mapping
        Map<String, String> mappingStatus = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            try {
                jdbcTemplate.queryForObject(
                    "SELECT " + entry.getValue() + " FROM " + sourceTable + " LIMIT 1", Object.class);
                mappingStatus.put(entry.getKey(), "OK -> " + entry.getValue());
            } catch (Exception e) {
                mappingStatus.put(entry.getKey(), "FAILED -> " + entry.getValue() + " (" + e.getMessage() + ")");
            }
        }
        result.put("columnMappingValidation", mappingStatus);
        result.put("tenantId", tenantId);

        return result;
    }
}
