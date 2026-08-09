package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interchange Fee Normalization (Super Admin correction tool).
 *
 * Finance supplies the CORRECT interchange total for a month. Every
 * transaction KEEPS its existing interchange; the extra (target - current
 * total) is added on top weighted by VOLUME — merchant share of month volume,
 * then transaction share of the merchant's volume — i.e.
 * new = old + volumeShare * extra — with largest-remainder reconciliation so
 * the merchant amounts sum EXACTLY to the target. On apply,
 * fact_transaction.interchange_fee is updated for the month and every summary
 * table is rebuilt via BulkMigrationService.rebuildSummaries, so all screens
 * show only the normalized figures. Pre-normalization values survive ONLY in
 * interchange_normalization_detail (per-merchant snapshot per run/version).
 */
@Service
public class InterchangeNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(InterchangeNormalizationService.class);
    private static final MathContext MC = new MathContext(28, RoundingMode.HALF_UP);
    // Allocation happens at the fee columns' storage precision (NUMERIC(18,4)) so
    // "old + extra" sums stay exact even when existing fees carry more decimals
    // than the display currency (e.g. BHD at 3dp over 4dp-stored fees).
    private static final int ALLOC_SCALE = 4;

    private final JdbcTemplate jdbcTemplate;
    private final BulkMigrationService migrationService;
    private final TransactionTemplate txTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.common.service.AuditService auditService;

    public InterchangeNormalizationService(JdbcTemplate jdbcTemplate,
                                           BulkMigrationService migrationService,
                                           PlatformTransactionManager txManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrationService = migrationService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /* ─────────────────────────── month summary ─────────────────────────── */

    /**
     * One row per month of the year that has transaction data: system
     * interchange total, merchant count, and the latest normalization run.
     */
    public List<Map<String, Object>> summary(Long tenantId, int year) {
        int fromKey = year * 100 + 1, toKey = year * 100 + 12;

        Map<Integer, Map<String, Object>> byMonth = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT CAST(TO_CHAR(payment_date,'YYYYMM') AS INTEGER) mk, " +
            "       SUM(COALESCE(interchange_fee,0)) ic, COUNT(DISTINCT merchant_id) mc, COUNT(*) txns " +
            "FROM fact_transaction WHERE tenant_id = ? " +
            "  AND payment_date >= ? AND payment_date < ? " +
            "GROUP BY TO_CHAR(payment_date,'YYYYMM') ORDER BY 1",
            rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                int mk = rs.getInt("mk");
                row.put("monthKey", mk);
                row.put("originalInterchange", rs.getBigDecimal("ic"));
                row.put("merchantCount", rs.getInt("mc"));
                row.put("txnCount", rs.getLong("txns"));
                byMonth.put(mk, row);
            },
            tenantId, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));

        // Latest run per month (any status) + the currently APPLIED one.
        jdbcTemplate.query(
            "SELECT DISTINCT ON (month_key) month_key, run_id, version_no, status, " +
            "       target_normalized_total, applied_by, applied_at, created_by, created_at " +
            "FROM interchange_normalization_run " +
            "WHERE tenant_id = ? AND month_key BETWEEN ? AND ? " +
            "ORDER BY month_key, run_id DESC",
            rs -> {
                Map<String, Object> row = byMonth.get(rs.getInt("month_key"));
                if (row == null) return;
                row.put("lastRunId", rs.getLong("run_id"));
                row.put("lastVersion", rs.getInt("version_no"));
                row.put("lastStatus", rs.getString("status"));
                row.put("lastTarget", rs.getBigDecimal("target_normalized_total"));
                row.put("lastNormalizedBy",
                    rs.getString("applied_by") != null ? rs.getString("applied_by") : rs.getString("created_by"));
                row.put("lastNormalizedAt",
                    rs.getTimestamp("applied_at") != null ? rs.getTimestamp("applied_at") : rs.getTimestamp("created_at"));
            },
            tenantId, fromKey, toKey);

        return new ArrayList<>(byMonth.values());
    }

    /* ───────────────────────────── preview ─────────────────────────────── */

    /**
     * Computes the pro-rata distribution for one month and persists it as a
     * PREVIEW run (new version). Nothing in fact_transaction is touched.
     */
    public Map<String, Object> preview(Long tenantId, int monthKey, BigDecimal target, String username) {
        YearMonth ym = YearMonth.of(monthKey / 100, monthKey % 100);
        LocalDate start = ym.atDay(1), end = ym.plusMonths(1).atDay(1);
        int scale = currencyScale(tenantId);
        target = target.setScale(scale, RoundingMode.HALF_UP);
        if (target.signum() < 0) throw new IllegalArgumentException("Target must not be negative");

        // Per-merchant base from fact (source of truth, not the summaries).
        List<MerchantRow> rows = jdbcTemplate.query(
            "SELECT f.merchant_id, COALESCE(m.name,'(unknown)') name, COALESCE(m.mid,'') mid, " +
            "       COUNT(*) txns, SUM(COALESCE(f.store_base_currency_amount,0)) vol, " +
            "       SUM(COALESCE(f.interchange_fee,0)) ic " +
            "FROM fact_transaction f " +
            "LEFT JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = f.tenant_id " +
            "WHERE f.tenant_id = ? AND f.merchant_id IS NOT NULL " +
            "  AND f.payment_date >= ? AND f.payment_date < ? " +
            "GROUP BY f.merchant_id, m.name, m.mid ORDER BY vol DESC",
            (rs, i) -> {
                MerchantRow r = new MerchantRow();
                r.merchantId = rs.getLong("merchant_id");
                r.name = rs.getString("name");
                r.mid = rs.getString("mid");
                r.txns = rs.getLong("txns");
                r.volume = rs.getBigDecimal("vol");
                r.originalInterchange = rs.getBigDecimal("ic");
                return r;
            },
            tenantId, start, end);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No merchant transactions found for " + ym + " — nothing to normalize");
        }

        Map<String, Object> unattr = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) cnt, COALESCE(SUM(COALESCE(store_base_currency_amount,0)),0) vol, " +
            "       COALESCE(SUM(COALESCE(interchange_fee,0)),0) ic " +
            "FROM fact_transaction " +
            "WHERE tenant_id = ? AND merchant_id IS NULL AND payment_date >= ? AND payment_date < ?",
            tenantId, start, end);
        BigDecimal unattributed = (BigDecimal) unattr.get("ic");
        BigDecimal unattributedVol = (BigDecimal) unattr.get("vol");
        long unattributedCnt = ((Number) unattr.get("cnt")).longValue();

        // Every transaction KEEPS its existing interchange; only the EXTRA
        // (target - current total) is distributed, weighted by VOLUME share.
        // Unattributed rows join the split as one pseudo participant.
        for (MerchantRow r : rows) {
            r.base = r.volume;
        }
        MerchantRow unattributedRow = null;
        if (unattributedCnt > 0) {
            unattributedRow = new MerchantRow();
            unattributedRow.merchantId = -1L;
            unattributedRow.name = "(Unattributed transactions)";
            unattributedRow.mid = "";
            unattributedRow.txns = unattributedCnt;
            unattributedRow.volume = unattributedVol;
            unattributedRow.originalInterchange = unattributed;
            unattributedRow.base = unattributedVol;
            rows.add(unattributedRow);
        }

        BigDecimal totalBase = rows.stream().map(r -> r.base).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalBase.signum() <= 0) {
            throw new IllegalStateException("Total settlement volume for " + ym + " is zero — cannot weight merchants");
        }

        BigDecimal originalTotal = rows.stream().map(r -> r.originalInterchange).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delta = target.subtract(originalTotal);

        allocateExtra(rows, delta, totalBase);

        for (MerchantRow r : rows) {
            if (r.normalized.signum() < 0) {
                throw new IllegalStateException("The reduction is larger than " + r.name + "'s volume-weighted share " +
                    "can absorb (its normalized value would be " + r.normalized + "). A volume-weighted decrease of " +
                    "this size cannot keep all merchants non-negative — check the target amount.");
            }
        }

        // Persist as a new PREVIEW version; any older un-applied previews for the
        // month are cancelled so only one actionable preview exists at a time.
        jdbcTemplate.update(
            "UPDATE interchange_normalization_run SET status='CANCELLED', status_detail='Replaced by a newer preview' " +
            "WHERE tenant_id=? AND month_key=? AND status='PREVIEW'", tenantId, monthKey);
        BigDecimal unattributedNormalized = unattributedRow != null ? unattributedRow.normalized : BigDecimal.ZERO;
        List<MerchantRow> merchantRows = rows.stream().filter(r -> r.merchantId > 0).toList();

        Long runId = jdbcTemplate.queryForObject(
            "INSERT INTO interchange_normalization_run " +
            "(tenant_id, month_key, version_no, original_interchange_total, target_normalized_total, difference, " +
            " unattributed_original, unattributed_normalized, weighting_base, residual_method, currency_scale, " +
            " merchant_count, status, created_by) " +
            "VALUES (?,?, COALESCE((SELECT MAX(version_no) FROM interchange_normalization_run WHERE tenant_id=? AND month_key=?),0)+1, " +
            "        ?,?,?,?,?, 'VOLUME_EXTRA','LARGEST_REMAINDER', ?, ?, 'PREVIEW', ?) RETURNING run_id",
            Long.class,
            tenantId, monthKey, tenantId, monthKey,
            originalTotal, target, target.subtract(originalTotal), unattributed, unattributedNormalized,
            scale, merchantRows.size(), username);

        jdbcTemplate.batchUpdate(
            "INSERT INTO interchange_normalization_detail " +
            "(run_id, merchant_id, merchant_name, txn_count, txn_volume, original_interchange, weight_pct, " +
            " normalized_interchange, difference) VALUES (?,?,?,?,?,?,?,?,?)",
            merchantRows, 200, (ps, r) -> {
                ps.setLong(1, runId);
                ps.setLong(2, r.merchantId);
                ps.setString(3, r.name);
                ps.setLong(4, r.txns);
                ps.setBigDecimal(5, r.volume);
                ps.setBigDecimal(6, r.originalInterchange);
                ps.setBigDecimal(7, r.weightPct);
                ps.setBigDecimal(8, r.normalized);
                ps.setBigDecimal(9, r.normalized.subtract(r.originalInterchange));
            });

        BigDecimal proposedTotal = rows.stream().map(r -> r.normalized).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("monthKey", monthKey);
        out.put("currencyScale", scale);
        out.put("merchantCount", merchantRows.size());
        out.put("originalTotal", originalTotal);
        out.put("unattributedOriginal", unattributed);
        out.put("unattributedNormalized", unattributedNormalized);
        out.put("target", target);
        out.put("proposedTotal", proposedTotal);
        out.put("remainingDifference", target.subtract(proposedTotal)); // must be 0.00 by construction
        out.put("rows", rows.stream().map(MerchantRow::toMap).toList());
        return out;
    }

    /**
     * Distributes the EXTRA (delta, can be negative) across rows pro-rata by
     * volume with largest-remainder reconciliation, in minor units at the
     * storage precision, so old + extra sums EXACTLY to the target.
     * Deterministic: remainder ordering ties break on merchant_id.
     * Sets r.extra and r.normalized (= originalInterchange + extra).
     */
    private void allocateExtra(List<MerchantRow> rows, BigDecimal delta, BigDecimal totalBase) {
        BigDecimal minor = BigDecimal.ONE.movePointLeft(ALLOC_SCALE);
        long deltaMinor = delta.movePointRight(ALLOC_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact();

        long floorSum = 0;
        for (MerchantRow r : rows) {
            r.weightPct = r.base.multiply(BigDecimal.valueOf(100)).divide(totalBase, 10, RoundingMode.HALF_UP);
            BigDecimal rawMinor = BigDecimal.valueOf(deltaMinor).multiply(r.base, MC).divide(totalBase, MC);
            r.floorMinor = rawMinor.setScale(0, RoundingMode.FLOOR).longValueExact();
            r.remainder = rawMinor.subtract(BigDecimal.valueOf(r.floorMinor));
            floorSum += r.floorMinor;
        }
        long residual = deltaMinor - floorSum; // 0 <= residual < rows.size(), also for negative delta
        rows.stream()
            .sorted(Comparator.comparing((MerchantRow r) -> r.remainder).reversed()
                .thenComparing(r -> r.merchantId))
            .limit(Math.max(0, residual))
            .forEach(r -> r.floorMinor++);
        for (MerchantRow r : rows) {
            r.extra = BigDecimal.valueOf(r.floorMinor).multiply(minor).setScale(ALLOC_SCALE, RoundingMode.UNNECESSARY);
            r.normalized = r.originalInterchange.add(r.extra);
        }
    }

    /* ─────────────────────────────── apply ─────────────────────────────── */

    /**
     * Applies a PREVIEW run: every transaction KEEPS its existing interchange
     * and receives its volume-weighted slice of the merchant's extra
     * (new = old + merchantExtra * txnVolume / merchantVolume), residual on
     * the merchant's largest transaction. Verifies the month total equals the
     * target EXACTLY (else rollback), marks the previous APPLIED version
     * SUPERSEDED, then rebuilds the summaries in the background.
     */
    public Map<String, Object> apply(Long tenantId, long runId, String username) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
            "SELECT * FROM interchange_normalization_run WHERE run_id = ?", runId);
        if (!tenantId.equals(((Number) run.get("tenant_id")).longValue())) {
            throw new IllegalArgumentException("Run does not belong to the active tenant");
        }
        if (!"PREVIEW".equals(run.get("status"))) {
            throw new IllegalStateException("Run is " + run.get("status") + " — only a PREVIEW run can be applied");
        }
        if (migrationService.isRunning()) {
            throw new IllegalStateException("A migration or summary rebuild is running — try again when it finishes");
        }

        int monthKey = ((Number) run.get("month_key")).intValue();
        int scale = ((Number) run.get("currency_scale")).intValue();
        BigDecimal target = (BigDecimal) run.get("target_normalized_total");
        YearMonth ym = YearMonth.of(monthKey / 100, monthKey % 100);
        LocalDate start = ym.atDay(1), end = ym.plusMonths(1).atDay(1);

        // Guard 1: detail (+ the unattributed share) must still sum exactly to the target.
        BigDecimal unattributedNormalized = run.get("unattributed_normalized") != null
            ? (BigDecimal) run.get("unattributed_normalized") : BigDecimal.ZERO;
        BigDecimal detailSum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(normalized_interchange),0) FROM interchange_normalization_detail WHERE run_id=?",
            BigDecimal.class, runId);
        if (detailSum.add(unattributedNormalized).compareTo(target) != 0) {
            throw new IllegalStateException("Allocations (" + detailSum.add(unattributedNormalized) +
                ") do not sum to the target (" + target + ") — recalculate the preview");
        }
        // Guard 2: fact data must not have changed since the preview was built.
        BigDecimal currentOriginal = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(interchange_fee),0) FROM fact_transaction " +
            "WHERE tenant_id=? AND payment_date >= ? AND payment_date < ?",
            BigDecimal.class, tenantId, start, end);
        BigDecimal previewOriginal = (BigDecimal) run.get("original_interchange_total");
        if (previewOriginal != null && currentOriginal.compareTo(previewOriginal) != 0) {
            throw new IllegalStateException("Transaction data changed since this preview was calculated (" +
                previewOriginal + " -> " + currentOriginal + ") — recalculate the preview");
        }

        jdbcTemplate.update("UPDATE interchange_normalization_run SET status='APPLYING', status_detail='Updating transactions' " +
            "WHERE run_id=?", runId);
        if (auditService != null) {
            auditService.log("INTERCHANGE_NORMALIZATION_APPLY",
                "Interchange normalization applied: tenant=" + tenantId + " month=" + monthKey +
                " version=" + run.get("version_no") + " target=" + target + " runId=" + runId);
        }

        BigDecimal unattributedOriginal = run.get("unattributed_original") != null
            ? (BigDecimal) run.get("unattributed_original") : BigDecimal.ZERO;
        Thread worker = new Thread(() -> runApply(tenantId, runId, monthKey, scale, target,
            unattributedOriginal, unattributedNormalized, start, end, username),
            "interchange-normalization");
        worker.setDaemon(true);
        worker.start();

        return Map.of("status", "STARTED", "runId", runId,
            "message", "Normalization started. Poll the run status until COMPLETED.");
    }

    private void runApply(Long tenantId, long runId, int monthKey, int scale, BigDecimal target,
                          BigDecimal unattributedOriginal, BigDecimal unattributedNormalized,
                          LocalDate start, LocalDate end, String username) {
        try {
            txTemplate.executeWithoutResult(tx -> {
                // 1. Each transaction KEEPS its existing interchange and gains its
                //    volume-weighted slice of the merchant's extra:
                //    new = old + (merchantNormalized - merchantOldTotal) * txnVol / merchantVol.
                //    A zero-volume merchant got no extra (volume weighting), so its
                //    rows stay at their old values.
                jdbcTemplate.update(
                    "UPDATE fact_transaction f SET interchange_fee = x.alloc FROM (" +
                    "  SELECT f2.transaction_id, " +
                    "         COALESCE(f2.interchange_fee,0) + " +
                    "         CASE WHEN mv.vol > 0 THEN ROUND((d.normalized_interchange - mv.ic) * COALESCE(f2.store_base_currency_amount,0) / mv.vol, " + ALLOC_SCALE + ") " +
                    "              ELSE 0 END alloc " +
                    "  FROM fact_transaction f2 " +
                    "  JOIN interchange_normalization_detail d ON d.run_id = ? AND d.merchant_id = f2.merchant_id " +
                    "  JOIN (SELECT merchant_id, SUM(COALESCE(store_base_currency_amount,0)) vol, SUM(COALESCE(interchange_fee,0)) ic " +
                    "        FROM fact_transaction WHERE tenant_id = ? AND merchant_id IS NOT NULL " +
                    "          AND payment_date >= ? AND payment_date < ? GROUP BY merchant_id) mv " +
                    "    ON mv.merchant_id = f2.merchant_id " +
                    "  WHERE f2.tenant_id = ? AND f2.payment_date >= ? AND f2.payment_date < ? " +
                    ") x WHERE f.transaction_id = x.transaction_id",
                    runId, tenantId, start, end, tenantId, start, end);

                // 2. Per-merchant rounding residual onto the merchant's largest
                //    transaction (deterministic tie-break on transaction_id), so
                //    each merchant's transactions sum EXACTLY to its allocation.
                jdbcTemplate.update(
                    "UPDATE fact_transaction f SET interchange_fee = COALESCE(f.interchange_fee,0) + r.residual FROM (" +
                    "  SELECT DISTINCT ON (s.merchant_id) f2.transaction_id, s.residual " +
                    "  FROM (SELECT f3.merchant_id, d.normalized_interchange - SUM(COALESCE(f3.interchange_fee,0)) residual " +
                    "        FROM fact_transaction f3 " +
                    "        JOIN interchange_normalization_detail d ON d.run_id = ? AND d.merchant_id = f3.merchant_id " +
                    "        WHERE f3.tenant_id = ? AND f3.payment_date >= ? AND f3.payment_date < ? " +
                    "        GROUP BY f3.merchant_id, d.normalized_interchange " +
                    "        HAVING d.normalized_interchange <> SUM(COALESCE(f3.interchange_fee,0))) s " +
                    "  JOIN fact_transaction f2 ON f2.merchant_id = s.merchant_id AND f2.tenant_id = ? " +
                    "    AND f2.payment_date >= ? AND f2.payment_date < ? " +
                    "  ORDER BY s.merchant_id, COALESCE(f2.store_base_currency_amount,0) DESC, f2.transaction_id " +
                    ") r WHERE f.transaction_id = r.transaction_id",
                    runId, tenantId, start, end, tenantId, start, end);

                // 3. Merchant-less rows keep their values too and receive their
                //    volume-weighted slice of the bucket's extra, residual on the
                //    largest such transaction. No-ops when there are no such rows.
                BigDecimal unattributedExtra = unattributedNormalized.subtract(unattributedOriginal);
                jdbcTemplate.update(
                    "UPDATE fact_transaction f SET interchange_fee = COALESCE(f.interchange_fee,0) + " +
                    "  CASE WHEN t.vol > 0 THEN ROUND(?::numeric * COALESCE(f.store_base_currency_amount,0) / t.vol, " + ALLOC_SCALE + ") ELSE 0 END " +
                    "FROM (SELECT COALESCE(SUM(COALESCE(store_base_currency_amount,0)),0) vol FROM fact_transaction " +
                    "      WHERE tenant_id = ? AND merchant_id IS NULL AND payment_date >= ? AND payment_date < ?) t " +
                    "WHERE f.tenant_id = ? AND f.merchant_id IS NULL AND f.payment_date >= ? AND f.payment_date < ?",
                    unattributedExtra, tenantId, start, end, tenantId, start, end);
                jdbcTemplate.update(
                    "UPDATE fact_transaction f SET interchange_fee = COALESCE(f.interchange_fee,0) + (? - t.total) FROM (" +
                    "  SELECT COALESCE(SUM(interchange_fee),0) total FROM fact_transaction " +
                    "  WHERE tenant_id = ? AND merchant_id IS NULL AND payment_date >= ? AND payment_date < ?) t " +
                    "WHERE f.transaction_id = (" +
                    "  SELECT transaction_id FROM fact_transaction " +
                    "  WHERE tenant_id = ? AND merchant_id IS NULL AND payment_date >= ? AND payment_date < ? " +
                    "  ORDER BY COALESCE(store_base_currency_amount,0) DESC, transaction_id LIMIT 1) " +
                    "  AND (? - t.total) <> 0",
                    unattributedNormalized, tenantId, start, end, tenantId, start, end, unattributedNormalized);

                // 4. Hard verification — any drift rolls the whole month back.
                BigDecimal newTotal = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(interchange_fee),0) FROM fact_transaction " +
                    "WHERE tenant_id = ? AND payment_date >= ? AND payment_date < ?",
                    BigDecimal.class, tenantId, start, end);
                if (newTotal.compareTo(target) != 0) {
                    throw new IllegalStateException("Post-update month total " + newTotal +
                        " does not equal target " + target + " — rolled back");
                }

                // 5. Version bookkeeping: previous APPLIED run -> SUPERSEDED.
                jdbcTemplate.update(
                    "UPDATE interchange_normalization_run SET status='SUPERSEDED', " +
                    "  status_detail='Superseded by run ' || ? " +
                    "WHERE tenant_id=? AND month_key=? AND status='APPLIED' AND run_id <> ?",
                    runId, tenantId, monthKey, runId);
                jdbcTemplate.update(
                    "UPDATE interchange_normalization_run SET status='APPLIED', " +
                    "  status_detail='Rebuilding summaries', applied_by=?, applied_at=CURRENT_TIMESTAMP " +
                    "WHERE run_id=?",
                    username, runId);
            });

            // Fact is committed; now replicate into every summary table so all
            // screens show the normalized figures. Failure here leaves fact
            // correct but summaries stale — surfaced via status_detail.
            YearMonth ym = YearMonth.of(monthKey / 100, monthKey % 100);
            try {
                migrationService.rebuildSummaries(tenantId, ym, ym);
                jdbcTemplate.update("UPDATE interchange_normalization_run SET status_detail='COMPLETED' WHERE run_id=?", runId);
                log.info("[IC-NORM] run {} applied: tenant={} month={} target={}", runId, tenantId, monthKey, target);
            } catch (Exception e) {
                jdbcTemplate.update("UPDATE interchange_normalization_run SET " +
                    "status_detail='APPLIED but summary rebuild failed: ' || ? || ' — run Rebuild Summaries for the month' " +
                    "WHERE run_id=?", truncate(e.getMessage()), runId);
                log.error("[IC-NORM] run {} applied but summary rebuild failed", runId, e);
            }
        } catch (Exception e) {
            log.error("[IC-NORM] run {} failed (rolled back)", runId, e);
            jdbcTemplate.update("UPDATE interchange_normalization_run SET status='FAILED', status_detail=? WHERE run_id=?",
                truncate(e.getMessage()), runId);
        }
    }

    /* ─────────────────────────── history / status ──────────────────────── */

    public Map<String, Object> runStatus(Long tenantId, long runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
            "SELECT run_id, tenant_id, month_key, version_no, status, status_detail, " +
            "       original_interchange_total, target_normalized_total, difference, unattributed_original, unattributed_normalized, " +
            "       merchant_count, currency_scale, created_by, created_at, applied_by, applied_at " +
            "FROM interchange_normalization_run WHERE run_id = ?", runId);
        if (!tenantId.equals(((Number) run.get("tenant_id")).longValue())) {
            throw new IllegalArgumentException("Run does not belong to the active tenant");
        }
        run.put("rebuildProgress", migrationService.getProgress());
        return run;
    }

    public List<Map<String, Object>> history(Long tenantId, Integer monthKey) {
        String sql = "SELECT run_id, month_key, version_no, status, status_detail, " +
            "original_interchange_total, target_normalized_total, difference, merchant_count, " +
            "created_by, created_at, applied_by, applied_at " +
            "FROM interchange_normalization_run WHERE tenant_id = ? " +
            (monthKey != null ? "AND month_key = ? " : "") +
            "ORDER BY month_key DESC, run_id DESC LIMIT 200";
        return monthKey != null
            ? jdbcTemplate.queryForList(sql, tenantId, monthKey)
            : jdbcTemplate.queryForList(sql, tenantId);
    }

    public List<Map<String, Object>> runDetails(Long tenantId, long runId) {
        runStatus(tenantId, runId); // tenant ownership check
        return jdbcTemplate.queryForList(
            "SELECT d.merchant_id, COALESCE(m.mid,'') mid, d.merchant_name, d.txn_count, d.txn_volume, " +
            "       d.original_interchange, d.weight_pct, d.normalized_interchange, d.difference " +
            "FROM interchange_normalization_detail d " +
            "LEFT JOIN dim_merchant m ON m.merchant_id = d.merchant_id AND m.tenant_id = ? " +
            "WHERE d.run_id = ? ORDER BY d.txn_volume DESC", tenantId, runId);
    }

    public void cancel(Long tenantId, long runId) {
        int n = jdbcTemplate.update(
            "UPDATE interchange_normalization_run SET status='CANCELLED', status_detail='Cancelled by user' " +
            "WHERE run_id=? AND tenant_id=? AND status='PREVIEW'", runId, tenantId);
        if (n == 0) throw new IllegalStateException("Only a PREVIEW run of the active tenant can be cancelled");
    }

    /* ─────────────────────────────── helpers ───────────────────────────── */

    /** Currency minor-unit scale from ref_country.decimal_notation_value (a divisor: 100 -> 2dp, 1000 -> 3dp). */
    private int currencyScale(Long tenantId) {
        try {
            Integer divisor = jdbcTemplate.queryForObject(
                "SELECT MAX(rc.decimal_notation_value) FROM tenant t " +
                "JOIN ref_country rc ON rc.currency_code = t.base_currency " +
                "WHERE t.tenant_id = ?", Integer.class, tenantId);
            if (divisor != null && divisor > 1) return (int) Math.round(Math.log10(divisor));
        } catch (Exception e) {
            log.warn("currencyScale lookup failed for tenant {} — defaulting to 2", tenantId);
        }
        return 2;
    }

    private static String truncate(String s) {
        if (s == null) return "unknown error";
        return s.length() > 450 ? s.substring(0, 450) : s;
    }

    private static class MerchantRow {
        long merchantId;
        String name;
        String mid;
        long txns;
        BigDecimal volume;
        BigDecimal originalInterchange;
        BigDecimal base;
        BigDecimal weightPct;
        long floorMinor;
        BigDecimal remainder;
        BigDecimal extra;
        BigDecimal normalized;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", merchantId);
            m.put("mid", mid);
            m.put("merchantName", name);
            m.put("txnCount", txns);
            m.put("txnVolume", volume);
            m.put("originalInterchange", originalInterchange);
            m.put("weightPct", weightPct);
            m.put("extraAdded", extra);
            m.put("normalizedInterchange", normalized);
            m.put("difference", normalized.subtract(originalInterchange));
            return m;
        }
    }
}
