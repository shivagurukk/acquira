package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges duplicate dim_merchant rows that share one MID code into a single
 * canonical row.
 *
 * WHY: dim_merchant is unique only on (tenant_id, internal_id). When a
 * transaction file lands before the merchant master, autoCreateDimensions mints
 * an 'AUTO_SID_&lt;sid&gt;' placeholder per unknown SID carrying the REAL mid. The
 * master upload then creates the real row under a different internal_id, and
 * its SID-keyed back-fill only reconciles a placeholder when the master also
 * lists that exact SID. Every other placeholder survives, so one MID owns
 * several merchant_ids: every COUNT(DISTINCT merchant_id) over-counts and every
 * per-merchant list shows the MID split across rows.
 *
 * RULE (per tenant, per mid; AUTO_MID_ codes are never grouped):
 *   - exactly one non-AUTO row  → it is canonical; every AUTO_ row merges into it
 *   - no non-AUTO row           → lowest merchant_id is canonical; the other AUTO_ rows merge
 *   - two or more non-AUTO rows → ambiguous (the master itself lists the MID
 *                                 under several internal_ids); left untouched and
 *                                 reported, because a later master upload would
 *                                 recreate them anyway
 *
 * MERGE (one transaction):
 *   1. a placeholder store whose sid already exists under the canonical merchant
 *      is folded into that store (every store_id reference repointed), then deleted
 *   2. remaining placeholder stores move to the canonical merchant
 *   3. every merchant_id reference is repointed to the canonical row
 *   4. the placeholder dim_merchant rows are deleted
 * Referencing tables are discovered from the catalog (bigint merchant_id /
 * store_id with tenant_id, or an FK to the dim) rather than a hand-kept list.
 * Where a repoint would violate a unique key (summaries, one-row-per-merchant
 * scores) the placeholder's rows are deleted instead; the caller rebuilds the
 * summaries for the affected dates, and scores recompute on their next run.
 */
@Service
public class MerchantDedupService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDedupService.class);

    /** dup → canonical for one tenant; AUTO_ = internal_id prefix of auto-created placeholders. */
    private static final String MAP_SQL =
        "WITH g AS ( " +
        "  SELECT m.mid, m.merchant_id, m.internal_id LIKE 'AUTO\\_%' AS is_auto " +
        "  FROM dim_merchant m " +
        "  WHERE m.tenant_id = ? AND NULLIF(TRIM(m.mid), '') IS NOT NULL AND m.mid NOT LIKE 'AUTO\\_MID\\_%' " +
        "), k AS ( " +
        "  SELECT mid, COUNT(*) FILTER (WHERE NOT is_auto) AS real_cnt, " +
        "         COALESCE(MIN(merchant_id) FILTER (WHERE NOT is_auto), MIN(merchant_id)) AS canon_id " +
        "  FROM g GROUP BY mid HAVING COUNT(*) > 1 " +
        ") " +
        "SELECT g.merchant_id AS dup_id, k.canon_id " +
        "FROM g JOIN k ON k.mid = g.mid " +
        "WHERE k.real_cnt <= 1 AND g.is_auto AND g.merchant_id <> k.canon_id";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final SummaryPopulationService summaryPopulationService;

    public MerchantDedupService(JdbcTemplate jdbcTemplate, SummaryPopulationService summaryPopulationService) {
        this.jdbcTemplate = jdbcTemplate;
        // A plain JDBC manager on the JdbcTemplate's own DataSource: the app's
        // primary manager is JPA-based, whose dialect does not support the
        // savepoints repointAll relies on.
        this.tx = new TransactionTemplate(new JdbcTransactionManager(jdbcTemplate.getDataSource()));
        this.summaryPopulationService = summaryPopulationService;
    }

    /** Read-only: what {@link #merge} would do for this tenant. */
    public Map<String, Object> preview(long tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("duplicateMids", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (SELECT mid FROM dim_merchant WHERE tenant_id = ? " +
            "AND NULLIF(TRIM(mid), '') IS NOT NULL GROUP BY mid HAVING COUNT(*) > 1) d",
            Long.class, tenantId));
        out.put("mergeableMids", jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT canon_id) FROM (" + MAP_SQL + ") x", Long.class, tenantId));
        out.put("placeholderRowsToMerge", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (" + MAP_SQL + ") x", Long.class, tenantId));
        out.put("factRowsToMove", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fact_transaction f WHERE f.tenant_id = ? " +
            "AND f.merchant_id IN (SELECT dup_id FROM (" + MAP_SQL + ") x)",
            Long.class, tenantId, tenantId));
        Map<String, Object> range = jdbcTemplate.queryForMap(
            "SELECT CAST(MIN(f.payment_date) AS DATE) AS first_date, CAST(MAX(f.payment_date) AS DATE) AS last_date " +
            "FROM fact_transaction f WHERE f.tenant_id = ? " +
            "AND f.merchant_id IN (SELECT dup_id FROM (" + MAP_SQL + ") x)", tenantId, tenantId);
        out.put("affectedFrom", range.get("first_date"));
        out.put("affectedTo", range.get("last_date"));

        // MIDs the rule will not touch: the master itself carries them twice.
        String ambiguous =
            "SELECT mid, COUNT(*) AS rows, STRING_AGG(internal_id, ', ' ORDER BY merchant_id) AS internal_ids " +
            "FROM dim_merchant WHERE tenant_id = ? AND NULLIF(TRIM(mid), '') IS NOT NULL " +
            "AND internal_id NOT LIKE 'AUTO\\_%' GROUP BY mid HAVING COUNT(*) > 1";
        out.put("ambiguousMids", jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (" + ambiguous + ") a", Long.class, tenantId));
        out.put("ambiguousSample", jdbcTemplate.queryForList(
            ambiguous + " ORDER BY COUNT(*) DESC, mid LIMIT 20", tenantId));
        return out;
    }

    /**
     * Merge every mergeable placeholder for the tenant, then rebuild summaries
     * for the dates whose facts moved. Callers must not run it concurrently with
     * an ingest for the same tenant.
     */
    public Map<String, Object> merge(long tenantId) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        List<java.sql.Date> dates = new ArrayList<>();

        Map<String, Object> applied = tx.execute(status -> {
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_mid_merge");
            jdbcTemplate.execute("CREATE TEMP TABLE tmp_mid_merge (dup_id BIGINT PRIMARY KEY, canon_id BIGINT NOT NULL) ON COMMIT DROP");
            int merchants = jdbcTemplate.update("INSERT INTO tmp_mid_merge " + MAP_SQL, tenantId);
            if (merchants == 0) return Map.<String, Object>of("placeholdersMerged", 0);

            // Dates must be read before the facts move off the placeholder ids.
            dates.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT CAST(f.payment_date AS DATE) FROM fact_transaction f " +
                "JOIN tmp_mid_merge t ON t.dup_id = f.merchant_id WHERE f.tenant_id = ? ORDER BY 1",
                java.sql.Date.class, tenantId));

            // 1. Placeholder stores whose sid already exists under the canonical merchant.
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_sid_merge");
            jdbcTemplate.execute("CREATE TEMP TABLE tmp_sid_merge (dup_id BIGINT PRIMARY KEY, canon_id BIGINT NOT NULL) ON COMMIT DROP");
            int foldedStores = jdbcTemplate.update(
                "INSERT INTO tmp_sid_merge " +
                "SELECT ds.store_id, cs.store_id FROM dim_store ds " +
                "JOIN tmp_mid_merge t ON t.dup_id = ds.merchant_id " +
                "JOIN LATERAL (SELECT c.store_id FROM dim_store c WHERE c.tenant_id = ds.tenant_id " +
                "  AND c.merchant_id = t.canon_id AND c.sid = ds.sid ORDER BY c.store_id LIMIT 1) cs ON TRUE " +
                "WHERE ds.tenant_id = ?", tenantId);
            Map<String, String> storeTables = foldedStores == 0 ? Map.of()
                : repointAll(status, tenantId, "store_id", "dim_store", "tmp_sid_merge");
            if (foldedStores > 0) {
                jdbcTemplate.update("DELETE FROM dim_store WHERE tenant_id = ? " +
                    "AND store_id IN (SELECT dup_id FROM tmp_sid_merge)", tenantId);
            }

            // 2. Remaining placeholder stores move under the canonical merchant.
            int movedStores = jdbcTemplate.update(
                "UPDATE dim_store ds SET merchant_id = t.canon_id FROM tmp_mid_merge t " +
                "WHERE ds.tenant_id = ? AND ds.merchant_id = t.dup_id", tenantId);

            // 3. Every other merchant_id reference (dim_store is done above).
            Map<String, String> merchantTables = repointAll(status, tenantId, "merchant_id", "dim_merchant", "tmp_mid_merge");

            // 4. Drop the placeholders.
            int deleted = jdbcTemplate.update("DELETE FROM dim_merchant WHERE tenant_id = ? " +
                "AND merchant_id IN (SELECT dup_id FROM tmp_mid_merge)", tenantId);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("placeholdersMerged", deleted);
            r.put("storesFolded", foldedStores);
            r.put("storesMoved", movedStores);
            r.put("storeReferences", storeTables);
            r.put("merchantReferences", merchantTables);
            return r;
        });
        out.putAll(applied);

        if (!dates.isEmpty()) {
            log.info("[MID-MERGE] tenant {}: rebuilding summaries for {} date(s) {}..{}",
                tenantId, dates.size(), dates.get(0), dates.get(dates.size() - 1));
            try {
                summaryPopulationService.populateForDates(tenantId, dates);
            } catch (RuntimeException e) {
                // The merge is already committed; only the re-aggregation failed.
                // Say so, rather than letting a bare failure read as "nothing changed".
                log.error("[MID-MERGE] tenant {}: merge committed but summary rebuild failed", tenantId, e);
                out.put("summaryRebuildError", "Merge committed; summary rebuild for " + dates.get(0) + ".."
                    + dates.get(dates.size() - 1) + " failed - run Rebuild Summaries for that range: " + e.getMessage());
            }
        }
        out.put("summaryDatesRebuilt", out.containsKey("summaryRebuildError") ? 0 : dates.size());
        out.put("elapsedMs", System.currentTimeMillis() - start);
        log.info("[MID-MERGE] tenant {}: {}", tenantId, out);
        return out;
    }

    /**
     * Repoint {@code column} from dup → canonical (per {@code mapTable}) in every
     * table that references it, skipping {@code dimTable} and {@code dim_merchant}
     * (handled by the caller). A table whose repoint collides with a unique key
     * has the dup rows deleted instead. Returns table → "updated N" / "deleted N".
     */
    private Map<String, String> repointAll(TransactionStatus status, long tenantId,
                                           String column, String dimTable, String mapTable) {
        // Top-level tables only (partitions are reached through their parent).
        // A bigint column + tenant_id marks a tenant-scoped surrogate reference;
        // an FK to the dim catches any child table without tenant_id.
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
            "SELECT c.relname AS tbl, " +
            "  EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = c.oid AND t.attname = 'tenant_id' " +
            "          AND t.attnum > 0 AND NOT t.attisdropped) AS has_tenant " +
            "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema() " +
            "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = ? AND a.attnum > 0 AND NOT a.attisdropped " +
            "WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition " +
            "  AND c.relname NOT IN (?, 'dim_merchant') AND c.relname NOT LIKE 'tmp\\_%' " +
            "  AND a.atttypid IN ('int8'::regtype, 'int4'::regtype) " +
            "  AND (EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = c.oid AND t.attname = 'tenant_id' " +
            "               AND t.attnum > 0 AND NOT t.attisdropped) " +
            "       OR EXISTS (SELECT 1 FROM pg_constraint fk WHERE fk.conrelid = c.oid AND fk.contype = 'f' " +
            "                  AND fk.confrelid = ?::regclass AND a.attnum = ANY (fk.conkey))) " +
            "ORDER BY c.relname",
            column, dimTable, dimTable);

        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> t : tables) {
            String tbl = (String) t.get("tbl");
            boolean hasTenant = Boolean.TRUE.equals(t.get("has_tenant"));
            String tenantPred = hasTenant ? " AND x.tenant_id = " + tenantId : "";
            Object sp = status.createSavepoint();
            try {
                int n = jdbcTemplate.update("UPDATE " + tbl + " x SET " + column + " = m.canon_id FROM " + mapTable +
                    " m WHERE x." + column + " = m.dup_id" + tenantPred);
                status.releaseSavepoint(sp);
                if (n > 0) result.put(tbl, "updated " + n);
            } catch (DataIntegrityViolationException e) {
                // Fact rows are the source of truth — never delete them to dodge a
                // collision; abort the whole merge (the transaction rolls back).
                if (tbl.startsWith("fact_")) {
                    throw new IllegalStateException("Cannot repoint " + column + " in " + tbl
                        + " without a unique-key collision; merge aborted, nothing changed", e);
                }
                // Unique key already holds a canonical row (a summary / per-merchant
                // score): drop the placeholder's copy; the rebuild re-derives it.
                status.rollbackToSavepoint(sp);
                int n = jdbcTemplate.update("DELETE FROM " + tbl + " x WHERE x." + column +
                    " IN (SELECT dup_id FROM " + mapTable + ")" + tenantPred);
                if (n > 0) result.put(tbl, "deleted " + n);
            }
        }
        return result;
    }
}
