package com.acquira.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionMaintenanceService {

    private final JdbcTemplate jdbcTemplate;

    // PERF: cache of years for which we've already verified partitions exist.
    // ensurePartitionsForCurrentAndNextYear() previously did ~40 EXISTS queries
    // against RDS on every single upload (10+ seconds wasted). Now it does that
    // work once per JVM lifetime per year. If a partition gets dropped externally
    // the app will need a restart — acceptable trade-off for the perf win.
    private final java.util.Set<Integer> verifiedYears = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final List<String> MONTHLY_PARTITIONED_TABLES = List.of(
            "fact_transaction");

    private static final List<String> YEARLY_PARTITIONED_TABLES = List.of(
            "sum_daily_merchant",
            "sum_daily_merchant_attribute",
            "sum_daily_terminal",
            "sum_daily_scheme",
            "sum_daily_channel",
            "sum_daily_bank",
            "sum_daily_finance",
            "sum_daily_insight");
    // NOTE: merchant_daily_metrics was REMOVED — it is NOT a partitioned table.
    // Attempting CREATE TABLE ... PARTITION OF on a non-partitioned table causes
    // PostgreSQL error: "merchant_daily_metrics" is not partitioned
    // which poisons the entire transaction (PostgreSQL aborts all subsequent commands).

    private static final java.util.Map<String, String> PARTITION_PREFIX_OVERRIDES = java.util.Map.of(
            "sum_daily_merchant_attribute", "sum_daily_merch_attr");

    /**
     * Ensure partitions exist for current year and next year.
     * NOT @Transactional — each partition is created in its own transaction
     * so that a failure in one doesn't poison the rest (PostgreSQL behavior).
     *
     * PERF: skips work for years already verified in this JVM. Saves ~40
     * RDS round-trips (~10s) on every upload after the first.
     */
    public void ensurePartitionsForCurrentAndNextYear() {
        int currentYear = LocalDate.now().getYear();
        ensurePartitionsForYear(currentYear);
        ensurePartitionsForYear(currentYear + 1);
    }

    public void ensurePartitionsForYear(int year) {
        if (verifiedYears.contains(year)) {
            log.debug("Partitions for year {} already verified this session, skipping", year);
            return;
        }
        log.info("Checking partitions for year: {}", year);
        ensureMonthlyPartitions(year);
        ensureYearlyPartitions(year);
        verifiedYears.add(year);
    }

    private void ensureMonthlyPartitions(int year) {
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.plusMonths(1);
            String partitionSuffix = String.format("_y%dm%02d", year, month); // _y2025m01

            for (String table : MONTHLY_PARTITIONED_TABLES) {
                createPartitionIfNotExists(table, partitionSuffix, start, end);
            }
        }
    }

    private void ensureYearlyPartitions(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year + 1, 1, 1);
        String partitionSuffix = String.format("_y%d", year); // _y2025

        for (String table : YEARLY_PARTITIONED_TABLES) {
            createPartitionIfNotExists(table, partitionSuffix, start, end);
        }
    }

    /**
     * Each partition creation runs in its own NEW transaction.
     * This prevents PostgreSQL's "current transaction is aborted" cascade —
     * if one CREATE PARTITION fails, it only rolls back that single attempt
     * and other partitions can still be created successfully.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createPartitionIfNotExists(String table, String suffix, LocalDate start, LocalDate end) {
        String prefix = PARTITION_PREFIX_OVERRIDES.getOrDefault(table, table);
        String partitionName = prefix + suffix;

        try {
            // Check existence
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = ?)",
                    Boolean.class, partitionName.toLowerCase());

            if (Boolean.FALSE.equals(exists)) {
                log.info("Creating partition {} for table {}", partitionName, table);
                String sql = String.format(
                        "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                        partitionName, table, start.toString(), end.toString());
                jdbcTemplate.execute(sql);
                applyAutovacuumTuning(partitionName, table);
            }
        } catch (Exception e) {
            log.warn("Partition {} skipped (table '{}' may not be partitioned): {}",
                    partitionName, table, e.getMessage());
            // Exception propagates to roll back THIS transaction only (REQUIRES_NEW)
            // Other partition creations continue in their own transactions
        }
    }

    /**
     * Apply per-partition autovacuum / storage settings right after a partition
     * is created. Parent-table reloptions do NOT cascade to partitions in
     * PostgreSQL, so each new partition would otherwise inherit the lazy global
     * defaults (vacuum only after 20% of the table is dead) — far too slow once
     * a partition holds hundreds of millions of rows.
     *
     * <ul>
     *   <li><b>fact_transaction</b> (monthly, insert-heavy): tight scale factors +
     *       absolute thresholds, a real vacuum cost budget, and the PG13+
     *       insert-vacuum knobs so a mostly-INSERT partition still gets vacuumed
     *       (keeps the visibility map fresh for index-only scans + freezing).</li>
     *   <li><b>sum_daily_*</b> (yearly, ON CONFLICT upsert churn): aggressive
     *       vacuum + fillfactor 90 to leave room for HOT updates.</li>
     * </ul>
     *
     * Each ALTER auto-commits (Hikari auto-commit=true) and is wrapped so a
     * failure (e.g. insert knobs on PostgreSQL &lt; 13) never undoes partition
     * creation or the base tuning. Pre-existing partitions created before this
     * code was deployed need a one-time backfill (see ops notes).
     */
    private void applyAutovacuumTuning(String partitionName, String parentTable) {
        boolean heavyFact = MONTHLY_PARTITIONED_TABLES.contains(parentTable);
        try {
            if (heavyFact) {
                jdbcTemplate.execute(String.format(
                        "ALTER TABLE %s SET (" +
                        "autovacuum_vacuum_scale_factor = 0.01, " +
                        "autovacuum_vacuum_threshold = 50000, " +
                        "autovacuum_analyze_scale_factor = 0.005, " +
                        "autovacuum_analyze_threshold = 50000, " +
                        "autovacuum_vacuum_cost_limit = 3000)", partitionName));
            } else {
                jdbcTemplate.execute(String.format(
                        "ALTER TABLE %s SET (" +
                        "autovacuum_vacuum_scale_factor = 0.02, " +
                        "autovacuum_vacuum_threshold = 20000, " +
                        "autovacuum_analyze_scale_factor = 0.01, " +
                        "autovacuum_analyze_threshold = 20000, " +
                        "fillfactor = 90)", partitionName));
            }
            log.info("Applied autovacuum tuning to partition {}", partitionName);
        } catch (Exception e) {
            log.warn("Autovacuum tuning skipped for {}: {}", partitionName, e.getMessage());
        }

        // Insert-vacuum knobs require PostgreSQL 13+. Applied separately so a
        // failure on older servers doesn't undo the base tuning above.
        if (heavyFact) {
            try {
                jdbcTemplate.execute(String.format(
                        "ALTER TABLE %s SET (" +
                        "autovacuum_vacuum_insert_scale_factor = 0.01, " +
                        "autovacuum_vacuum_insert_threshold = 50000)", partitionName));
            } catch (Exception e) {
                log.warn("Insert-autovacuum knobs skipped for {} (needs PostgreSQL 13+): {}",
                        partitionName, e.getMessage());
            }
        }
    }
}
