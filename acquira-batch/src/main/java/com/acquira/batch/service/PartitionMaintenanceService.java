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

    // Optional (field-injected so the existing `new PartitionMaintenanceService(jdbc)`
    // unit-test constructor keeps compiling): needed only by drainDefaultPartition,
    // which must run DETACH + CREATE + move + ATTACH on ONE connection/transaction.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager txManager;

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

    // Partition-key column per monthly table — used by the default-partition
    // drain to bucket stranded rows into their proper months. Doubles as the
    // drain's table allowlist (identifiers are interpolated into DDL).
    private static final java.util.Map<String, String> PARTITION_KEY_COLUMNS = java.util.Map.of(
            "fact_transaction", "payment_date");

    // DETACH/ATTACH need ACCESS EXCLUSIVE on the parent; give them longer than
    // ordinary partition DDL since a drain is a deliberate, operator-invoked fix.
    private static final String DRAIN_LOCK_TIMEOUT = "60s";

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
        boolean monthlyOk = ensureMonthlyPartitions(year);
        boolean yearlyOk = ensureYearlyPartitions(year);
        // Only cache the year when every partition really exists. Previously a
        // failed CREATE (e.g. rows for the period already stranded in the
        // DEFAULT partition) was swallowed AND the year was cached as verified,
        // so the miss was never retried for the JVM lifetime and every later
        // upload for that period silently kept feeding the default partition.
        if (monthlyOk && yearlyOk) {
            verifiedKeys.add(key);
        } else {
            log.warn("Year {} NOT cached as verified — at least one partition failed to provision; "
                    + "it will be re-attempted on the next upload", year);
        }
    }

    private boolean ensureMonthlyPartitions(int year) {
        boolean allOk = true;
        for (int month = 1; month <= 12; month++) {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.plusMonths(1);
            String partitionSuffix = String.format("_y%dm%02d", year, month); // _y2025m01

            for (String table : MONTHLY_PARTITIONED_TABLES) {
                allOk &= createPartitionIfNotExists(table, partitionSuffix, start, end);
            }
        }
        return allOk;
    }

    private boolean ensureYearlyPartitions(int year) {
        boolean allOk = true;
        for (String table : YEARLY_PARTITIONED_TABLES) {
            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year + 1, 1, 1);
            String partitionSuffix = String.format("_y%d", year); // _y2025

            allOk &= createPartitionIfNotExists(table, partitionSuffix, start, end);
        }
        return allOk;
    }

    /**
     * Each partition creation runs in its own NEW transaction so a single
     * failure rolls back only that attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean createPartitionIfNotExists(String table, String suffix, LocalDate start, LocalDate end) {
        String prefix = PARTITION_PREFIX_OVERRIDES.getOrDefault(table, table);
        String partitionName = prefix + suffix;

        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = ?)",
                    Boolean.class, partitionName.toLowerCase());

            if (Boolean.FALSE.equals(exists)) {
                // CREATE TABLE ... PARTITION OF (and the ALTER TABLE tuning that follows)
                // take ACCESS EXCLUSIVE on the parent, and the Hikari pool opens every
                // connection with lock_timeout = 0 (see connection-init-sql) — so without
                // this the very first step of every transaction job can wait for that lock
                // forever, with nothing in the application able to break it. SET LOCAL is
                // scoped to this REQUIRES_NEW transaction, leaving the unbounded default in
                // place for the long ingest steps that genuinely need it. Issued only on
                // the create path so the cached "already exists" path stays at zero extra
                // round-trips.
                jdbcTemplate.execute("SET LOCAL lock_timeout = '" + DDL_LOCK_TIMEOUT + "'");

                log.info("Creating partition {} for table {}", partitionName, table);
                String sql = String.format(
                        "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                        partitionName, table, start.toString(), end.toString());
                jdbcTemplate.execute(sql);
                applyAutovacuumTuning(partitionName, table);
            }
            return true;
        } catch (org.springframework.dao.CannotAcquireLockException e) {
            // Distinct from "not partitioned": something is holding a conflicting lock
            // on the parent. Rows for this period will land in the DEFAULT partition and
            // permanently lose partition pruning, so this must be loud, not a warning.
            log.error("Partition {} NOT created — could not acquire the lock on '{}' within {}. "
                    + "Rows for this period will fall into the default partition. "
                    + "Check pg_locks/pg_stat_activity for a blocking session, then re-run.",
                    partitionName, table, DDL_LOCK_TIMEOUT);
            return false;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("default partition")) {
                // PostgreSQL refuses to create a partition whose range is already
                // covered by rows sitting in the DEFAULT partition ("updated partition
                // constraint for default partition ... would be violated by some row").
                // Every upload for this period will keep feeding the default partition
                // — and the default can NEVER be pruned, so all date-windowed queries
                // scan it in full. This is exactly what the drain endpoint fixes.
                log.error("Partition {} NOT created — rows for this period are stranded in {}_default. "
                        + "Partition pruning is broken for the whole table until they are moved out. "
                        + "Run POST /api/admin/partitions/drain-default to repair, then re-upload.",
                        partitionName, table);
            } else {
                log.warn("Partition {} skipped (table '{}' may not be partitioned): {}",
                        partitionName, table, e.getMessage());
            }
            return false;
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

    // ─── DEFAULT-partition drain (repair for rows that lost partition pruning) ───
    //
    // Rows land in <table>_default whenever their month partition didn't exist at
    // insert time (historical backfills before the staging-range provisioning fix,
    // or a lock-timeout on the CREATE). Once there, two things go permanently wrong:
    //  1. The DEFAULT partition can never be pruned, so EVERY date-windowed query
    //     (dashboards, businessMetrics, churn) scans it in full — the 2026-09 UAT
    //     incident had 19 GB / 58M rows in fact_transaction_default and a 30-minute
    //     businessMetrics INSERT.
    //  2. CREATE ... PARTITION OF for any month covered by those rows FAILS
    //     ("would be violated by some row"), so provisioning can never self-heal.
    // The only repair is: detach the default, create the missing month partitions,
    // re-route the rows through the parent, re-attach. That is what drain does,
    // in ONE transaction so a failure at any step rolls everything back.

    private final java.util.concurrent.atomic.AtomicBoolean drainRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile java.util.Map<String, Object> drainProgress =
            java.util.Map.of("phase", "IDLE");

    public boolean isDrainRunning() {
        return drainRunning.get();
    }

    public java.util.Map<String, Object> getDrainProgress() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(drainProgress);
        out.put("running", drainRunning.get());
        return out;
    }

    private void drainPhase(String phase, String detail) {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("phase", phase);
        p.put("detail", detail);
        p.put("at", java.time.OffsetDateTime.now().toString());
        drainProgress = p;
        log.info("[drainDefault] {} — {}", phase, detail);
    }

    /**
     * Cheap health snapshot of a table's DEFAULT partition: estimated rows +
     * on-disk size (no full scan). Pass detail=true for the exact per-month
     * breakdown — that scans the partition, so expect it to take a while when
     * the default is the very problem being diagnosed.
     */
    public java.util.Map<String, Object> defaultPartitionStatus(String parentTable, boolean detail) {
        String keyCol = PARTITION_KEY_COLUMNS.get(parentTable);
        if (keyCol == null) {
            throw new IllegalArgumentException("Unsupported table: " + parentTable
                    + " (supported: " + PARTITION_KEY_COLUMNS.keySet() + ")");
        }
        String def = parentTable + "_default";
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("table", parentTable);
        out.put("defaultPartition", def);
        out.put("estimatedRows", jdbcTemplate.queryForObject(
                "SELECT COALESCE(reltuples::bigint, 0) FROM pg_class WHERE relname = ?", Long.class, def));
        out.put("size", jdbcTemplate.queryForObject(
                "SELECT pg_size_pretty(pg_total_relation_size(?::regclass))", String.class, def));
        if (detail) {
            out.put("monthBreakdown", jdbcTemplate.queryForList(
                    "SELECT date_trunc('month', " + keyCol + ")::date AS month, COUNT(*) AS rows "
                            + "FROM " + def + " GROUP BY 1 ORDER BY 1 NULLS LAST"));
        }
        out.put("drain", getDrainProgress());
        return out;
    }

    /**
     * Move every dated row out of <parentTable>_default into proper monthly
     * partitions (created as needed), leaving only NULL-key rows behind.
     *
     * Runs DETACH -> CREATE months -> INSERT through parent -> TRUNCATE default
     * (NULL rows preserved via a temp table) -> ATTACH in a single transaction:
     * the TRUNCATE also reclaims the default's bloat at commit, and the final
     * ATTACH revalidates only the handful of surviving NULL rows instead of
     * re-scanning gigabytes of dead tuples.
     *
     * Holds ACCESS EXCLUSIVE on the parent for the duration — run it when no
     * ingestion is active and dashboard traffic is quiet.
     */
    public java.util.Map<String, Object> drainDefaultPartition(String parentTable) {
        final String keyCol = PARTITION_KEY_COLUMNS.get(parentTable);
        if (keyCol == null) {
            throw new IllegalArgumentException("Unsupported table: " + parentTable
                    + " (supported: " + PARTITION_KEY_COLUMNS.keySet() + ")");
        }
        if (isTenantListPartitioned(parentTable)) {
            throw new IllegalStateException(parentTable + " uses tenant-wise LIST partitioning; "
                    + "the default drain applies to the legacy shared-RANGE layout only");
        }
        if (txManager == null) {
            throw new IllegalStateException("No PlatformTransactionManager available — drain cannot run");
        }
        if (!drainRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("A default-partition drain is already running");
        }
        final String def = parentTable + "_default";
        final long t0 = System.currentTimeMillis();
        try {
            drainPhase("SCAN", "finding months stranded in " + def);
            final java.util.List<java.util.Map<String, Object>> months = jdbcTemplate.queryForList(
                    "SELECT date_trunc('month', " + keyCol + ")::date AS month, COUNT(*) AS rows "
                            + "FROM " + def + " WHERE " + keyCol + " IS NOT NULL GROUP BY 1 ORDER BY 1");
            final long nullRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + def + " WHERE " + keyCol + " IS NULL", Long.class);

            java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("table", parentTable);
            summary.put("monthsFound", months.size());
            summary.put("nullKeyRowsKept", nullRows);

            if (months.isEmpty()) {
                drainPhase("DONE", "default partition already clean — nothing to move");
                summary.put("rowsMoved", 0L);
                return summary;
            }

            org.springframework.transaction.support.TransactionTemplate tt =
                    new org.springframework.transaction.support.TransactionTemplate(txManager);
            tt.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            long moved = tt.execute(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '" + DRAIN_LOCK_TIMEOUT + "'");

                drainPhase("DETACH", "detaching " + def);
                jdbcTemplate.execute("ALTER TABLE " + parentTable + " DETACH PARTITION " + def);

                drainPhase("CREATE_PARTITIONS", months.size() + " month partition(s)");
                for (java.util.Map<String, Object> m : months) {
                    LocalDate start = ((java.sql.Date) m.get("month")).toLocalDate().withDayOfMonth(1);
                    LocalDate end = start.plusMonths(1);
                    String name = parentTable + String.format("_y%dm%02d", start.getYear(), start.getMonthValue());
                    jdbcTemplate.execute(String.format(
                            "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                            name, parentTable, start, end));
                    applyAutovacuumTuning(name, parentTable);
                }

                drainPhase("MOVE_ROWS", "re-routing dated rows through " + parentTable);
                int movedRows = jdbcTemplate.update(
                        "INSERT INTO " + parentTable + " SELECT * FROM " + def
                                + " WHERE " + keyCol + " IS NOT NULL");

                // TRUNCATE (not DELETE) so the ATTACH below revalidates only the
                // preserved NULL rows, and the old 19-GB heap is freed at commit.
                drainPhase("RESET_DEFAULT", "truncating " + def + ", preserving " + nullRows + " NULL-key row(s)");
                jdbcTemplate.execute("CREATE TEMP TABLE drain_keep_nulls ON COMMIT DROP AS "
                        + "SELECT * FROM " + def + " WHERE " + keyCol + " IS NULL");
                jdbcTemplate.execute("TRUNCATE " + def);
                jdbcTemplate.update("INSERT INTO " + def + " SELECT * FROM drain_keep_nulls");

                drainPhase("ATTACH", "re-attaching " + def + " as DEFAULT");
                jdbcTemplate.execute("ALTER TABLE " + parentTable + " ATTACH PARTITION " + def + " DEFAULT");
                return (long) movedRows;
            });

            // Years may have been cached as "verified" while their CREATE was
            // silently failing against the populated default — force a re-check.
            verifiedKeys.clear();

            drainPhase("ANALYZE", parentTable);
            try {
                jdbcTemplate.execute("ANALYZE " + parentTable);
            } catch (Exception e) {
                log.warn("ANALYZE {} after drain failed (non-fatal): {}", parentTable, e.getMessage());
            }

            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            drainPhase("DONE", String.format("%d row(s) moved across %d month(s) in %.1fs",
                    moved, months.size(), secs));
            summary.put("rowsMoved", moved);
            summary.put("months", months);
            summary.put("seconds", secs);
            return summary;
        } catch (Exception e) {
            drainPhase("FAILED", String.valueOf(e.getMessage()));
            throw e;
        } finally {
            drainRunning.set(false);
        }
    }
}
