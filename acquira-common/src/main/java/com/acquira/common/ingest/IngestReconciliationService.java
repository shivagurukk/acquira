package com.acquira.common.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciles the four tiers a transaction passes through, and classifies every
 * drop between them.
 *
 *   file rows -> staged -> facted -> summarised
 *
 * WHAT EACH GAP MEANS
 * -------------------
 *   file -> staged      parser drops: encoding, unreadable rows, bad columns
 *   staged -> facted    unresolved merchants, rejected/filtered transaction types
 *   facted -> summarised SUMMARY DRIFT — the known hazard where
 *                       BulkMigrationService's rebuild SQL and
 *                       TransactionJobConfig.populateSummary stop mirroring each
 *                       other and a rebuilt month silently loses columns
 *
 * Plus two assertions that each map to a defect that reached UAT:
 *
 *   FEE_COVERAGE   % of loaded fact rows carrying a non-zero MSF. A dead or
 *                  unmatched rate card prices nothing and every fee reads zero
 *                  while the load still goes green. Catching it here means it
 *                  surfaces at load time instead of during a demo.
 *
 *   DESTRUCTIVE_REPLACE  REPLACE deletes fact rows by whole DATE, so a 200-row
 *                  resend can destroy a 400k-row day. Comparing what was
 *                  deleted against what was written makes that visible.
 */
@Service
public class IngestReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(IngestReconciliationService.class);

    public static final String OK = "OK";
    public static final String GAP = "GAP";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * Rows may legitimately differ by a hair between tiers (pre-authorisations
     * are filtered on the way to fact by design). Anything at or below this is
     * not worth waking someone for; anything above is a real gap.
     */
    private static final long TOLERANCE_ROWS = 0L;

    /** Default fee-coverage floor when a tenant has no ingest_expectation row. */
    private static final BigDecimal DEFAULT_FEE_FLOOR = new BigDecimal("95.00");

    /** A REPLACE that deletes this many times more than it writes is destructive. */
    private static final BigDecimal DESTRUCTIVE_RATIO = new BigDecimal("1.5");

    private final JdbcTemplate jdbc;
    private final IngestRunRecorder recorder;

    public IngestReconciliationService(JdbcTemplate jdbc, IngestRunRecorder recorder) {
        this.jdbc = jdbc;
        this.recorder = recorder;
    }

    /**
     * Computes the funnel for one run and writes recon_status / recon_detail /
     * fee_priced_pct back onto it. Never throws — a reconciliation failure must
     * not turn a successful ingestion into a failed one.
     */
    public void reconcile(Long runId) {
        if (runId == null) return;
        try {
            Map<String, Object> run = jdbc.queryForMap(
                "SELECT tenant_id, load_mode, rows_file, rows_staged, rows_facted, rows_summarised, " +
                "fact_rows_deleted, min_txn_date, max_txn_date FROM ingest_run WHERE id = ?", runId);

            Long tenantId = asLong(run.get("tenant_id"));
            String loadMode = (String) run.get("load_mode");
            Long rowsFile = asLong(run.get("rows_file"));
            Long rowsStaged = asLong(run.get("rows_staged"));
            Long rowsFacted = asLong(run.get("rows_facted"));
            Long deleted = asLong(run.get("fact_rows_deleted"));
            Date minDate = (Date) run.get("min_txn_date");
            Date maxDate = (Date) run.get("max_txn_date");

            List<String> gaps = new ArrayList<>();
            boolean anyKnown = false;

            // ── file -> staged ──────────────────────────────────────────────
            if (rowsFile != null && rowsStaged != null) {
                anyKnown = true;
                long delta = rowsFile - rowsStaged;
                if (delta > TOLERANCE_ROWS) {
                    gaps.add(String.format("FILE_VS_STAGE: %d of %d file row(s) never reached staging "
                            + "(parser drops, encoding, or unreadable rows).", delta, rowsFile));
                }
            }

            // ── staged -> facted ────────────────────────────────────────────
            if (rowsStaged != null && rowsFacted != null) {
                anyKnown = true;
                long delta = rowsStaged - rowsFacted;
                if (delta > TOLERANCE_ROWS) {
                    gaps.add(String.format("STAGE_VS_FACT: %d of %d staged row(s) did not become fact rows "
                            + "(unresolved merchants, rejected or filtered types).", delta, rowsStaged));
                }
            }

            // ── facted -> summarised ────────────────────────────────────────
            Long summarised = null;
            if (tenantId != null && minDate != null && maxDate != null) {
                summarised = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_txns), 0) FROM sum_daily_full " +
                    "WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                    Long.class, tenantId, minDate, maxDate);

                Long factInRange = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fact_transaction " +
                    "WHERE tenant_id = ? AND payment_date >= ? AND payment_date < ? + INTERVAL '1 day'",
                    Long.class, tenantId, minDate, maxDate);

                if (factInRange != null && summarised != null) {
                    anyKnown = true;
                    long delta = factInRange - summarised;
                    if (Math.abs(delta) > TOLERANCE_ROWS) {
                        gaps.add(String.format(
                            "FACT_VS_SUMMARY: %d fact row(s) in range vs %d summarised (delta %+d). "
                            + "Summary drift — check that the rebuild SQL still mirrors populateSummary.",
                            factInRange, summarised, -delta));
                    }
                }
                recorder.updateCounts(runId, null, null, null, summarised, null, null, null, null, null, null);
            }

            // ── fee coverage ────────────────────────────────────────────────
            Double feePct = null;
            if (tenantId != null && minDate != null && maxDate != null) {
                Map<String, Object> fee = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, " +
                    "COUNT(*) FILTER (WHERE msf IS NOT NULL AND msf <> 0) AS priced " +
                    "FROM fact_transaction WHERE tenant_id = ? " +
                    "AND payment_date >= ? AND payment_date < ? + INTERVAL '1 day'",
                    tenantId, minDate, maxDate);
                long total = asLong(fee.get("total")) == null ? 0 : asLong(fee.get("total"));
                long priced = asLong(fee.get("priced")) == null ? 0 : asLong(fee.get("priced"));
                if (total > 0) {
                    BigDecimal pct = BigDecimal.valueOf(priced)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
                    feePct = pct.doubleValue();
                    BigDecimal floor = feeFloorFor(tenantId);
                    if (pct.compareTo(floor) < 0) {
                        anyKnown = true;
                        gaps.add(String.format(
                            "FEE_COVERAGE_DROP: only %s%% of %d loaded row(s) carry a non-zero MSF "
                            + "(floor %s%%). A rate card is missing, expired, or not matching.",
                            pct.toPlainString(), total, floor.toPlainString()));
                    }
                }
            }

            // ── destructive replace ─────────────────────────────────────────
            if (deleted != null && deleted > 0 && !"APPEND".equalsIgnoreCase(loadMode)) {
                long written = rowsFacted == null ? 0 : rowsFacted;
                BigDecimal threshold = BigDecimal.valueOf(written).multiply(DESTRUCTIVE_RATIO);
                if (BigDecimal.valueOf(deleted).compareTo(threshold) > 0) {
                    anyKnown = true;
                    gaps.add(String.format(
                        "DESTRUCTIVE_REPLACE: deleted %d existing fact row(s) but wrote only %d. "
                        + "A partial-day file replaced a fuller day.", deleted, written));
                }
            }

            String status = !anyKnown ? UNKNOWN : (gaps.isEmpty() ? OK : GAP);
            String detail = gaps.isEmpty() ? null : String.join("\n", gaps);
            recorder.setReconResult(runId, status, detail, feePct);

            if (!gaps.isEmpty()) {
                log.warn("[INGEST-RECON] run {} -> {} | {}", runId, status, String.join(" | ", gaps));
            } else {
                log.info("[INGEST-RECON] run {} -> {}", runId, status);
            }
        } catch (Exception e) {
            log.warn("Reconciliation of run {} failed (non-fatal): {}", runId, e.toString());
            recorder.setReconResult(runId, UNKNOWN, "Reconciliation could not run: " + e.getMessage(), null);
        }
    }

    private BigDecimal feeFloorFor(Long tenantId) {
        try {
            BigDecimal f = jdbc.queryForObject(
                "SELECT fee_coverage_pct FROM ingest_expectation WHERE tenant_id = ?",
                BigDecimal.class, tenantId);
            return f == null ? DEFAULT_FEE_FLOOR : f;
        } catch (Exception e) {
            return DEFAULT_FEE_FLOOR;
        }
    }

    private static Long asLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
