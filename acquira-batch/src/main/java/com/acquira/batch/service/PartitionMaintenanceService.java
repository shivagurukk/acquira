package com.acquira.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionMaintenanceService {

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> PARTITIONED_TABLES = List.of(
            "fact_transaction",
            "sum_daily_merchant",
            "sum_daily_merchant_attribute",
            "sum_daily_terminal",
            "sum_daily_scheme",
            "sum_daily_channel",
            "sum_daily_bank",
            "sum_daily_finance",
            "sum_daily_insight",
            "sum_daily_mcc",
            "kpi_snapshot_daily");

    private static final java.util.Map<String, String> PARTITION_PREFIX_OVERRIDES = java.util.Map.of(
            "sum_daily_merchant_attribute", "sum_daily_merch_attr");

    @Transactional
    public void ensurePartitionsForCurrentAndNextYear() {
        int currentYear = LocalDate.now().getYear();
        ensurePartitionsForYear(currentYear);
        ensurePartitionsForYear(currentYear + 1);
    }

    public void ensurePartitionsForYear(int year) {
        log.info("Checking and creating partitions for year: {}", year);
        String startDate = year + "-01-01";
        String endDate = (year + 1) + "-01-01";

        for (String table : PARTITIONED_TABLES) {
            // Check if table is actually partitioned
            try {
                String relKind = jdbcTemplate.queryForObject(
                        "SELECT relkind FROM pg_class WHERE relname = ?",
                        String.class,
                        table);

                if (!"p".equals(relKind)) {
                    log.warn(
                            "Table {} exists but is not a partitioned table (relkind={}). Skipping partition creation.",
                            table, relKind);
                    continue;
                }
            } catch (Exception e) {
                log.warn("Could not determine if table {} is partitioned (maybe it doesn't exist). Skipping.", table);
                continue;
            }

            String prefix = PARTITION_PREFIX_OVERRIDES.getOrDefault(table, table);
            String partitionName = prefix + "_y" + year;

            // Check if partition exists
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = ?)",
                    Boolean.class,
                    partitionName.toLowerCase());

            if (Boolean.FALSE.equals(exists)) {
                log.info("Creating partition {} for table {}", partitionName, table);
                String sql = String.format(
                        "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
                        partitionName, table, startDate, endDate);
                try {
                    jdbcTemplate.execute(sql);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("would overlap partition")) {
                        log.warn(
                                "Partition creation failed for {} due to overlap (likely existing partition with different name). Ignoring.",
                                partitionName);
                    } else {
                        log.error("Failed to create partition {} for table {}: {}", partitionName, table,
                                e.getMessage());
                        // In Postgres, a failed statement aborts the transaction.
                        // Since we checked relkind above, this shouldn't happen for "not partitioned"
                        // error.
                        // But if it does happen, we must propagate or handle knowing the tx is dead.
                        throw e;
                    }
                }
            } else {
                log.debug("Partition {} already exists", partitionName);
            }
        }
    }
}
