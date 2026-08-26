package com.acquira.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Ensures partitions exist ahead of ingestion.
 *
 * Two partitioning strategies are supported, auto-detected per parent table
 * from pg_partitioned_table.partstrat:
 *
 *  'r' (RANGE — legacy):  shared date partitions, e.g. fact_transaction_y2026m07.
 *  'l' (LIST — tenant-wise, post REBUILD_TENANT_LIST_PARTITIONING.sql):
 *       one LIST partition per tenant, each RANGE-sub-partitioned by date:
 *       fact_transaction_t{tid} -> fact_transaction_t{tid}_y2026m07.
 *       Creation is delegated to the DB function ensure_tenant_partitions()
 *       (single source of truth, shared with the 'tenant-partitions'
 *       provisioning script) — this service just loops tenants per year.
 *
 * The strategy check means this class is deploy-order-safe: it behaves
 * exactly as before until the psql-only rebuild script has been run, then
 * switches to tenant-wise automatically.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionMaintenanceService {

    private final JdbcTemplate jdbcTemplate;

    // PERF: cache of "year verified" (legacy) / "tenant+year verified"
    // (tenant-wise) so uploads after the first don't re-check ~40 partitions
    // against RDS. If a partition is dropped externally, restart the app.
    private final java.util.Set<String> verifiedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // PERF: strategy detection cached per JVM. The LIST rebuild is a psql-only
    // exclusive-lock script and is always accompanied by an app restart, so a
    // per-JVM cache is safe (same stance as verifiedKeys: restart if changed
    // externally). Keeps subsequent-upload cost at zero DB round-trips.
    private volatile Boolean tenantListPartitionedCache;

    // Ceiling on how long partition DDL will queue for its ACCESS EXCLUSIVE lock.
    // Deliberately finite: the pool's connection-init-sql sets lock_timeout = 0, which
    // means "wait forever", and this DDL is the FIRST step of every transaction job.
    private static final String DDL_LOCK_TIMEOUT = "30s";

    private static final List<String> MONTHLY_PARTITIONED_TABLES = List.of(
            "fact_transaction");

    private static final List<String> YEARLY_PARTITIONED_TABLES = List.of(
            "sum_daily_merchant",
            "sum_daily_merchant_attribute",
            "sum_daily_merchant_destination",
            "sum_daily_terminal",
            "sum_daily_scheme",
            "sum_daily_channel",
            "sum_daily_bank",
            "sum_daily_finance",
            "sum_daily_insight",
            "sum_daily_full",
            "sum_daily_explorer",
            "sum_daily_local_debit_bin");
    // NOTE: merchant_daily_metrics is NOT partitioned — never add it here.

    private static final java.util.Map<String, String> PARTITION_PREFIX_OVERRIDES = java.util.Map.of(
            "sum_daily_merchant_attribute", "sum_daily_merch_attr");

    /**
     * Ensure partitions exist for current year and next year.
     * NOT @Transactional — each creation runs in its own transaction so one
     * failure can't poison the rest (PostgreSQL aborted-transaction cascade).
     */
    public void ensurePartitionsForCurrentAndNextYear() {
        int currentYear = LocalDate.now().getYear();
        ensurePartitionsForYear(currentYear);
        ensurePartitionsForYear(currentYear + 1);
    }

    public void ensurePartitionsForYear(int year) {
        if (isTenantListPartitioned("fact_transaction")) {
            ensureTenantWisePartitionsForYear(year);
        } else {
            ensureLegacyPartitionsForYear(year);
        }
    }

    // ─── Tenant-wise (LIST -> RANGE) path ────────────────────────────────────

    /**
     * One ensure_tenant_partitions(tid, year, year) call per tenant per
     * unverified (tenant, year) pair. New tenants created mid-JVM are covered
     * both here (tenant list re-read on every uncached call) and by the
     * 'tenant-partitions' provisioning script at creation time.
     */
    private void ensureTenantWisePartitionsForYear(int year) {
        List<Long> tenantIds = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM tenant ORDER BY tenant_id", Long.class);
        for (Long tid : tenantIds) {
            String key = "t" + tid + ":y" + year;
            if (verifiedKeys.contains(key)) continue;
            try {
                ensureTenantPartitions(tid, year);
                verifiedKeys.add(key);
            } catch (Exception e) {
                log.warn("ensure_tenant_partitions failed for tenant {} year {}: {}",
                        tid, year, e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureTenantPartitions(Long tenantId, int year) {
        jdbcTemplate.execute(String.format(
                "SELECT ensure_tenant_partitions(%d, %d, %d)", tenantId, year, year));
        log.info("Verified tenant-wise partitions for tenant {} year {}", tenantId, year);
    }

    private boolean isTenantListPartitioned(String table) {
        Boolean cached = tenantListPartitionedCache;
        if (cached != null) return cached;
        try {
            Boolean isList = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_partitioned_table pt "
                            + "JOIN pg_class c ON c.oid = pt.partrelid "
                            + "WHERE c.relname = ? AND pt.partstrat = 'l')",
                    Boolean.class, table);
            boolean result = Boolean.TRUE.equals(isList);
            tenantListPartitionedCache = result;
            return result;
        } catch (Exception e) {
            // NOT cached — a transient failure shouldn't pin us to legacy for
            // the JVM lifetime; next call re-detects.
            log.warn("Partition strategy check failed for {} — assuming legacy: {}", table, e.getMessage());
            return false;
        }
    }

    // ─── Legacy (shared RANGE) path — unchanged behavior ────────────────────

    private void ensureLegacyPartitionsForYear(int year) {
        String key = "legacy:y" + year;
        if (verifiedKeys.contains(key)) {
            log.debug("Partitions for year {} already verified this session, skipping", year);
            return;
        }
        log.info("Checking (legacy shared) partitions for year: {}", year);
        ensureMonthlyPartitions(year);
        ensureYearlyPartitions(year);
        verifiedKeys.add(key);
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
     * Each partition creation runs in its own NEW transaction so a single
     * failure rolls back only that attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createPartitionIfNotExists(String table, String suffix, LocalDate start, LocalDate end) {
        String prefix = PARTITION_PREFIX_OVERRIDES.getOrDefault(table, table);
        String partitionName = prefix + suffix;

        try {
            // CREATE TABLE ... PARTITION OF takes ACCESS EXCLUSIVE on the parent, and
            // the Hikari pool opens every connection with lock_timeout = 0 (see
            // connection-init-sql) — so without this the very first step of every
            // transaction job can wait for that lock forever, with nothing in the
            // application able to break it. SET LOCAL is scoped to this REQUIRES_NEW
            // transaction, leaving the unbounded default in place for the long ingest
            // steps that genuinely need it.
            jdbcTemplate.execute("SET LOCAL lock_timeout = '" + DDL_LOCK_TIMEOUT + "'");

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
        } catch (org.springframework.dao.CannotAcquireLockException e) {
            // Distinct from "not partitioned": something is holding a conflicting lock
            // on the parent. Rows for this period will land in the DEFAULT partition and
            // permanently lose partition pruning, so this must be loud, not a warning.
            log.error("Partition {} NOT created — could not acquire the lock on '{}' within {}. "
                    + "Rows for this period will fall into the default partition. "
                    + "Check pg_locks/pg_stat_activity for a blocking session, then re-run.",
                    partitionName, table, DDL_LOCK_TIMEOUT);
        } catch (Exception e) {
            log.warn("Partition {} skipped (table '{}' may not be partitioned): {}",
                    partitionName, table, e.getMessage());
        }
    }

    /**
     * Per-partition autovacuum/storage settings (parent reloptions do NOT
     * cascade to partitions). fact_transaction: tight thresholds + insert
     * knobs; summaries: aggressive vacuum + fillfactor 90 for HOT updates.
     * Tenant-wise partitions get the same tuning inside
     * ensure_tenant_partitions() / tune_partition_autovacuum() in the DB.
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
