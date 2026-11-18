package com.acquira.core.service.feeoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds what should not be on the invoice.
 *
 * Six detections, each written as the question a person would ask if they had
 * the time to read every line:
 *
 * <ul>
 *   <li><b>Duplicate charge</b> — the same event, quantity and amount billed
 *       more than once in one period. The surplus copies are the saving.</li>
 *   <li><b>Rate anomaly</b> — the unit rate on an event has moved away from
 *       what that same event has cost over the preceding periods.</li>
 *   <li><b>Zero-quantity fixed fee</b> — a charge with an amount but no
 *       quantity behind it.</li>
 *   <li><b>Duplicate minimum</b> — a minimum top-up billed more than once for
 *       one entity in one period.</li>
 *   <li><b>Penalty</b> — anything classified as a penalty, surfaced because it
 *       is disputable and because it points at something operational.</li>
 *   <li><b>Unexpected event</b> — an event code that has never appeared on any
 *       earlier invoice.</li>
 * </ul>
 *
 * <h2>Re-running is safe</h2>
 * Every finding carries a fingerprint, and a re-run upserts on it. The value
 * and the evidence are refreshed; the WORKFLOW STATE IS NOT. A finding a
 * person has moved to disputed or dismissed keeps that state — otherwise a
 * routine re-scan would silently undo somebody's week of chasing.
 *
 * <h2>On confidence</h2>
 * A finding is a prompt to look, not a proof. A duplicate can be legitimate
 * (two genuine billings of the same fee in a month), a new event code can be a
 * real new service. That is what the review workflow is for, and why the
 * evidence travels with the finding.
 */
@Service
public class SavingsDetector {

    /** Unit rate must move by more than this to be worth raising. */
    private static final double RATE_TOLERANCE = 0.05;      // 5%

    /** Ignore findings below this, in USD — noise, not money. */
    private static final double MIN_VALUE_USD = 1.00;

    private final JdbcTemplate jdbc;
    private final FindingRouter router;
    private final ObjectMapper json = new ObjectMapper();

    public SavingsDetector(JdbcTemplate jdbc, FindingRouter router) {
        this.jdbc = jdbc;
        this.router = router;
    }

    /** What a scan did. */
    public record ScanResult(int raised, int updated, int scanned, int retired, int routed) { }

    /**
     * Scans one period, or every period the tenant holds when year and month
     * are null.
     */
    @Transactional
    public ScanResult scan(long tenantId, Integer year, Integer month) {
        List<Map<String, Object>> found = new ArrayList<>();
        found.addAll(duplicateCharges(tenantId, year, month));
        found.addAll(rateAnomalies(tenantId, year, month));
        found.addAll(zeroQuantityFixedFees(tenantId, year, month));
        found.addAll(duplicateMinimums(tenantId, year, month));
        found.addAll(penalties(tenantId, year, month));
        found.addAll(unexpectedEvents(tenantId, year, month));
        found = dropOverlaps(found);

        int raised = 0, updated = 0;
        List<String> live = new ArrayList<>();
        for (Map<String, Object> f : found) {
            double usd = num(f.get("monthlyValueUsd"));
            if (Math.abs(usd) < MIN_VALUE_USD) continue;
            live.add(String.valueOf(f.get("fingerprint")));
            if (upsert(tenantId, f)) raised++; else updated++;
        }

        int retired = retireStale(tenantId, year, month, live);

        // Routed last, and tenant-wide rather than period-scoped: a finding
        // raised before the rules existed should pick one up too, so changing
        // a rule and re-scanning is how the backlog gets placed.
        int routed = router.route(tenantId);

        Integer scanned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sfo_fee_line WHERE tenant_id = ?" + periodClause(year, month),
                Integer.class, periodArgs(tenantId, year, month));

        return new ScanResult(raised, updated, scanned == null ? 0 : scanned, retired, routed);
    }

    /**
     * Closes findings this scan no longer detects.
     *
     * Without this, correcting a classification or deleting an upload leaves
     * findings behind that point at lines no longer there, and the open value
     * never comes down.
     *
     * ONLY touches findings still sitting at FLAGGED — untouched by a person.
     * Anything someone has moved to under review, disputed, recovered or
     * dismissed is their work in progress and is left exactly as it is, even
     * when the underlying lines have gone.
     */
    private int retireStale(long tenantId, Integer year, Integer month, List<String> live) {
        StringBuilder sql = new StringBuilder(
                "UPDATE sfo_opportunity SET status = 'DISMISSED', " +
                "       note = COALESCE(note || ' · ', '') || 'No longer detected on re-scan', " +
                "       updated_at = CURRENT_TIMESTAMP, updated_by = 'detector' " +
                " WHERE tenant_id = ? AND status = 'FLAGGED'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (year != null) {
            sql.append(" AND period_year = ?");
            args.add(year);
            if (month != null) { sql.append(" AND period_month = ?"); args.add(month); }
        }

        if (!live.isEmpty()) {
            sql.append(" AND fingerprint NOT IN (")
               .append("?,".repeat(live.size() - 1)).append("?)");
            args.addAll(live);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * Removes the generic reading when a more specific one covers the same
     * money.
     *
     * A minimum top-up billed twice is caught both as a duplicate charge and
     * as a duplicate minimum. They are the same surplus seen twice, so adding
     * both to the claimable total would ask the scheme for it twice — and the
     * headline figure is the number somebody takes into that conversation.
     * The duplicate-minimum reading is kept, because it names what actually
     * went wrong.
     */
    private List<Map<String, Object>> dropOverlaps(List<Map<String, Object>> found) {
        java.util.Set<String> minimums = new java.util.HashSet<>();
        for (Map<String, Object> f : found) {
            if ("DUPLICATE_MINIMUM".equals(f.get("findingType"))) minimums.add(overlapKey(f));
        }
        if (minimums.isEmpty()) return found;

        List<Map<String, Object>> kept = new ArrayList<>(found.size());
        for (Map<String, Object> f : found) {
            boolean shadowed = "DUPLICATE_CHARGE".equals(f.get("findingType"))
                    && minimums.contains(overlapKey(f));
            if (!shadowed) kept.add(f);
        }
        return kept;
    }

    private static String overlapKey(Map<String, Object> f) {
        return String.join("|",
                String.valueOf(f.get("scheme")), String.valueOf(f.get("entityCode")),
                String.valueOf(f.get("eventCode")), String.valueOf(f.get("periodYear")),
                String.valueOf(f.get("periodMonth")));
    }

    // ── Detections ─────────────────────────────────────────────────────────

    /**
     * The same event, quantity and amount billed more than once in a period.
     *
     * Grouped on the amount as well as the code so that two genuinely
     * different charges under one event code — a common, legitimate pattern —
     * are not reported as duplicates of each other.
     */
    private List<Map<String, Object>> duplicateCharges(long tenantId, Integer y, Integer m) {
        String sql =
            "SELECT scheme, entity_code, event_code, MIN(description) AS description, " +
            "       MIN(category) AS category, " +
            "       period_year, period_month, currency_code, " +
            "       COUNT(*) AS n, MIN(amount) AS amount, " +
            "       SUM(amount) - MIN(amount) AS surplus, " +
            "       SUM(amount_usd) - MIN(amount_usd) AS surplus_usd, " +
            "       STRING_AGG(CAST(line_id AS TEXT), ',') AS line_ids " +
            "  FROM sfo_fee_line WHERE tenant_id = ?" + periodClause(y, m) +
            " GROUP BY scheme, entity_code, event_code, period_year, period_month, " +
            "          currency_code, amount, COALESCE(quantity, -1) " +
            "HAVING COUNT(*) > 1 AND MIN(amount) <> 0";

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, periodArgs(tenantId, y, m))) {
            long n = (long) num(r.get("n"));
            out.add(finding("DUPLICATE_CHARGE", n > 2 ? "HIGH" : "MEDIUM", r,
                    num(r.get("surplus")), num(r.get("surplus_usd")),
                    String.format("%s billed %d times at the same amount in one period — "
                                + "%d of them look like copies.",
                            label(r), n, n - 1),
                    Map.of("occurrences", n,
                           "unitAmount", r.get("amount"),
                           "lineIds", String.valueOf(r.get("line_ids")))));
        }
        return out;
    }

    /**
     * A unit rate that has drifted upward from its own history.
     *
     * <p>Two decisions worth stating, because the obvious implementation of
     * each is wrong:
     *
     * <ul>
     *   <li><b>The baseline excludes the period being tested.</b> A median
     *       taken over all periods INCLUDING the current one sits between the
     *       old rate and the new one, so both the month that rose and the
     *       months that did not all look equally far from "normal" — and with
     *       two periods loaded, every rate on both invoices flags.</li>
     *   <li><b>Only increases are raised.</b> Being charged LESS than
     *       historically is not a saving to claim; reporting it as one puts a
     *       negative number in a column headed "value" and invites somebody to
     *       ring the scheme about money they are already keeping.</li>
     * </ul>
     *
     * The baseline is a median rather than a mean: one freak month would drag
     * a mean and then hide the very drift this is looking for.
     */
    private List<Map<String, Object>> rateAnomalies(long tenantId, Integer y, Integer m) {
        String sql =
            "WITH current AS ( " +
            "  SELECT line_id, scheme, entity_code, event_code, description, category, period_year, " +
            "         period_month, currency_code, quantity, unit_rate, amount, amount_usd, " +
            "         (period_year * 100 + period_month) AS p " +
            "    FROM sfo_fee_line " +
            "   WHERE tenant_id = ? AND unit_rate IS NOT NULL AND unit_rate <> 0" + periodClause(y, m) +
            ") " +
            "SELECT c.*, b.median_rate, b.periods " +
            "  FROM current c " +
            "  CROSS JOIN LATERAL ( " +
            "    SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY h.unit_rate) AS median_rate, " +
            "           COUNT(DISTINCT (h.period_year * 100 + h.period_month)) AS periods " +
            "      FROM sfo_fee_line h " +
            "     WHERE h.tenant_id = ? AND h.scheme = c.scheme AND h.event_code = c.event_code " +
            "       AND COALESCE(h.entity_code,'') = COALESCE(c.entity_code,'') " +
            "       AND h.unit_rate IS NOT NULL AND h.unit_rate <> 0 " +
            "       AND (h.period_year * 100 + h.period_month) < c.p " +
            "  ) b " +
            " WHERE b.periods >= 1 " +
            "   AND c.unit_rate - b.median_rate > ABS(b.median_rate) * ?";

        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (y != null) { args.add(y); if (m != null) args.add(m); }
        args.add(tenantId);
        args.add(RATE_TOLERANCE);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            double rate     = num(r.get("unit_rate"));
            double median   = num(r.get("median_rate"));
            double qty      = num(r.get("quantity"));
            // The money at stake is what the drift costs over the quantity
            // actually billed — not the rate difference on its own.
            double overpay  = (rate - median) * (qty == 0 ? 1 : qty);
            double amount   = num(r.get("amount"));
            double usdRatio = amount == 0 ? 1 : num(r.get("amount_usd")) / amount;

            out.add(finding("RATE_ANOMALY",
                    Math.abs(rate - median) > Math.abs(median) * 0.25 ? "HIGH" : "MEDIUM", r,
                    overpay, overpay * usdRatio,
                    String.format("%s is billed at %s against a median of %s over %s earlier periods.",
                            label(r), trim(rate), trim(median), r.get("periods")),
                    Map.of("unitRate", rate, "medianRate", median,
                           "quantity", qty, "periodsInBaseline", r.get("periods"))));
        }
        return out;
    }

    /** A fixed fee with an amount but nothing billed behind it. */
    private List<Map<String, Object>> zeroQuantityFixedFees(long tenantId, Integer y, Integer m) {
        String sql =
            "SELECT scheme, entity_code, event_code, description, category, period_year, period_month, " +
            "       currency_code, amount, amount_usd, quantity, line_id " +
            "  FROM sfo_fee_line " +
            " WHERE tenant_id = ? AND amount <> 0 " +
            "   AND COALESCE(quantity, 0) = 0 " +
            "   AND category IN ('FIXED_FEES','ASSESSMENTS','CLEARING','AUTHORISATION')" +
            periodClause(y, m);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, periodArgs(tenantId, y, m))) {
            out.add(finding("ZERO_QTY_FIXED_FEE", "MEDIUM", r,
                    num(r.get("amount")), num(r.get("amount_usd")),
                    String.format("%s carries an amount but no quantity — there is nothing on the "
                                + "invoice showing what was billed for.", label(r)),
                    Map.of("lineId", r.get("line_id"), "quantity", String.valueOf(r.get("quantity")))));
        }
        return out;
    }

    /** A minimum top-up billed more than once for one entity in one period. */
    private List<Map<String, Object>> duplicateMinimums(long tenantId, Integer y, Integer m) {
        String sql =
            "SELECT scheme, entity_code, period_year, period_month, currency_code, " +
            "       MIN(event_code) AS event_code, MIN(description) AS description, " +
            "       MIN(category) AS category, " +
            "       COUNT(*) AS n, SUM(amount) AS total, SUM(amount_usd) AS total_usd, " +
            "       MIN(amount) AS amount, MIN(amount_usd) AS amount_usd " +
            "  FROM sfo_fee_line " +
            " WHERE tenant_id = ? AND LOWER(COALESCE(description,'')) LIKE '%minimum%'" +
            periodClause(y, m) +
            " GROUP BY scheme, entity_code, period_year, period_month, currency_code " +
            "HAVING COUNT(*) > 1";

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, periodArgs(tenantId, y, m))) {
            long n = (long) num(r.get("n"));
            double surplus    = num(r.get("total")) - num(r.get("amount"));
            double surplusUsd = num(r.get("total_usd")) - num(r.get("amount_usd"));
            out.add(finding("DUPLICATE_MINIMUM", "HIGH", r, surplus, surplusUsd,
                    String.format("%d minimum charges in one period for %s — a minimum should "
                                + "top up to a floor once.",
                            n, r.get("entity_code") == null ? "this entity" : r.get("entity_code")),
                    Map.of("occurrences", n)));
        }
        return out;
    }

    /** Every penalty, because every penalty is worth a conversation. */
    private List<Map<String, Object>> penalties(long tenantId, Integer y, Integer m) {
        String sql =
            "SELECT scheme, entity_code, event_code, description, category, period_year, period_month, " +
            "       currency_code, SUM(amount) AS amount, SUM(amount_usd) AS amount_usd, " +
            "       COUNT(*) AS n " +
            "  FROM sfo_fee_line " +
            " WHERE tenant_id = ? AND category = 'PENALTIES'" + periodClause(y, m) +
            " GROUP BY scheme, entity_code, event_code, description, category, period_year, " +
            "          period_month, currency_code";

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, periodArgs(tenantId, y, m))) {
            out.add(finding("PENALTY", "HIGH", r,
                    num(r.get("amount")), num(r.get("amount_usd")),
                    String.format("%s is a penalty charge. Penalties are disputable and usually "
                                + "point at something to fix upstream.", label(r)),
                    Map.of("occurrences", r.get("n"))));
        }
        return out;
    }

    /**
     * An event code that has never been billed before.
     *
     * <p>Skipped entirely for the EARLIEST period a scheme holds. On that
     * invoice every code is new, so the detection would raise a finding for
     * every line on it and bury the five that mean something. "Unexpected"
     * only has meaning once there is something to have expected.
     */
    private List<Map<String, Object>> unexpectedEvents(long tenantId, Integer y, Integer m) {
        String sql =
            "WITH current AS ( " +
            "  SELECT scheme, entity_code, event_code, MIN(description) AS description, " +
            "         MIN(category) AS category, " +
            "         period_year, period_month, currency_code, " +
            "         SUM(amount) AS amount, SUM(amount_usd) AS amount_usd " +
            "    FROM sfo_fee_line WHERE tenant_id = ? AND event_code IS NOT NULL" + periodClause(y, m) +
            "   GROUP BY scheme, entity_code, event_code, period_year, period_month, currency_code " +
            "), baseline AS ( " +
            "  SELECT scheme, MIN(period_year * 100 + period_month) AS first_period " +
            "    FROM sfo_fee_line WHERE tenant_id = ? GROUP BY scheme " +
            ") " +
            "SELECT c.* FROM current c " +
            "  JOIN baseline b ON b.scheme = c.scheme " +
            " WHERE (c.period_year * 100 + c.period_month) > b.first_period " +
            "   AND NOT EXISTS ( " +
            "   SELECT 1 FROM sfo_fee_line p " +
            "    WHERE p.tenant_id = ? AND p.scheme = c.scheme AND p.event_code = c.event_code " +
            "      AND (p.period_year * 100 + p.period_month) < (c.period_year * 100 + c.period_month))";

        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (y != null) { args.add(y); if (m != null) args.add(m); }
        args.add(tenantId);
        args.add(tenantId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            out.add(finding("UNEXPECTED_EVENT", "LOW", r,
                    num(r.get("amount")), num(r.get("amount_usd")),
                    String.format("%s has not appeared on any earlier invoice. Confirm it is a "
                                + "service that was agreed.", label(r)),
                    Map.of()));
        }
        return out;
    }

    // ── Writing ────────────────────────────────────────────────────────────

    private Map<String, Object> finding(String type, String severity, Map<String, Object> r,
                                        double monthly, double monthlyUsd,
                                        String description, Map<String, Object> evidence) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("findingType",  type);
        f.put("severity",     severity);
        f.put("scheme",       r.get("scheme"));
        f.put("entityCode",   r.get("entity_code"));
        f.put("eventCode",    r.get("event_code"));
        // Carried onto the finding so routing reads one row, and so the queue
        // can be filtered by the kind of cost without touching the ledger.
        f.put("category",     r.get("category"));
        f.put("description",  description);
        f.put("periodYear",   r.get("period_year"));
        f.put("periodMonth",  r.get("period_month"));
        f.put("currencyCode", r.get("currency_code"));
        f.put("monthlyValue",        monthly);
        f.put("annualisedValue",     monthly * 12);
        f.put("monthlyValueUsd",     monthlyUsd);
        f.put("annualisedValueUsd",  monthlyUsd * 12);
        f.put("evidence", evidence);
        f.put("fingerprint", String.join("|", type,
                String.valueOf(r.get("scheme")), String.valueOf(r.get("entity_code")),
                String.valueOf(r.get("event_code")), String.valueOf(r.get("period_year")),
                String.valueOf(r.get("period_month")),
                // The amount is part of the identity: two different duplicate
                // groups under one code are two different findings.
                String.format("%.2f", monthly)));
        return f;
    }

    /** @return true when this is a new finding rather than a refresh. */
    private boolean upsert(long tenantId, Map<String, Object> f) {
        String evidence;
        try { evidence = json.writeValueAsString(f.get("evidence")); }
        catch (Exception e) { evidence = "{}"; }

        // Workflow state is deliberately absent from the SET list.
        int updated = jdbc.update(
                "UPDATE sfo_opportunity " +
                "   SET severity = ?, description = ?, category = ?, monthly_value = ?, " +
                "       annualised_value = ?, monthly_value_usd = ?, annualised_value_usd = ?, " +
                "       evidence = ?, updated_at = CURRENT_TIMESTAMP " +
                " WHERE tenant_id = ? AND fingerprint = ?",
                f.get("severity"), f.get("description"), f.get("category"),
                bd(f.get("monthlyValue")), bd(f.get("annualisedValue")),
                bd(f.get("monthlyValueUsd")), bd(f.get("annualisedValueUsd")),
                evidence, tenantId, f.get("fingerprint"));
        if (updated > 0) return false;

        jdbc.update(
                "INSERT INTO sfo_opportunity " +
                "(tenant_id, fingerprint, finding_type, severity, scheme, entity_code, event_code, " +
                " description, category, period_year, period_month, currency_code, monthly_value, " +
                " annualised_value, monthly_value_usd, annualised_value_usd, evidence) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, f.get("fingerprint"), f.get("findingType"), f.get("severity"),
                f.get("scheme"), f.get("entityCode"), f.get("eventCode"), f.get("description"),
                f.get("category"), f.get("periodYear"), f.get("periodMonth"),
                f.get("currencyCode"), bd(f.get("monthlyValue")), bd(f.get("annualisedValue")),
                bd(f.get("monthlyValueUsd")), bd(f.get("annualisedValueUsd")), evidence);
        return true;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String periodClause(Integer y, Integer m) {
        if (y == null) return "";
        return m == null ? " AND period_year = ?" : " AND period_year = ? AND period_month = ?";
    }

    private static Object[] periodArgs(long tenantId, Integer y, Integer m) {
        if (y == null) return new Object[]{ tenantId };
        if (m == null) return new Object[]{ tenantId, y };
        return new Object[]{ tenantId, y, m };
    }

    private static String label(Map<String, Object> r) {
        Object code = r.get("event_code");
        Object desc = r.get("description");
        String c = code == null ? "" : String.valueOf(code);
        String d = desc == null ? "" : String.valueOf(desc);
        if (!c.isEmpty() && !d.isEmpty()) return c + " (" + d + ")";
        return !c.isEmpty() ? c : (!d.isEmpty() ? d : "An unnamed charge");
    }

    private static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal bd(Object v) {
        return BigDecimal.valueOf(num(v)).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static String trim(double v) {
        return String.format("%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
