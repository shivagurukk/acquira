package com.acquira.core.controller;

import com.acquira.common.service.AuditService;
import com.acquira.core.service.TenantService;
import com.acquira.core.service.feeoptimizer.FeeLineClassifier;
import com.acquira.core.service.feeoptimizer.FindingRouter;
import com.acquira.core.service.feeoptimizer.InvoiceParser;
import com.acquira.core.service.feeoptimizer.SavingsDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheme Fee Optimizer API (/mis/fee-optimizer).
 *
 * Reads the scheme's billing invoices, classifies every line, and raises
 * findings that can be tracked from flag to recovery.
 *
 * <h2>The import is a two-step on purpose</h2>
 * {@code POST /uploads} parses and STAGES; nothing reaches the ledger until
 * {@code POST /uploads/{id}/confirm}. The operator sees what was detected,
 * what could not be, and whether the lines reconcile against the printed
 * invoice total — and agrees before any of it counts. A fee invoice that
 * imports itself under the wrong period or currency poisons every trend and
 * every finding downstream, and does it quietly.
 *
 * <h2>What this never does</h2>
 * An invoice is evidence for a dispute, never a source of pricing. Nothing
 * here writes to a rate card.
 */
@RestController
@RequestMapping("/api/mis/fee-optimizer")
@PreAuthorize("@menuAccess.canAccess('/mis/fee-optimizer')")
public class SchemeFeeOptimizerController {

    /** Invoices are not big files; anything larger is not an invoice. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;
    private final InvoiceParser parser;
    private final FeeLineClassifier classifier;
    private final SavingsDetector detector;
    private final FindingRouter router;
    private final AuditService auditService;
    private final ObjectMapper json = new ObjectMapper();

    public SchemeFeeOptimizerController(JdbcTemplate jdbc, TenantService tenantService,
                                        InvoiceParser parser, FeeLineClassifier classifier,
                                        SavingsDetector detector, FindingRouter router,
                                        AuditService auditService) {
        this.jdbc = jdbc;
        this.tenantService = tenantService;
        this.parser = parser;
        this.classifier = classifier;
        this.detector = detector;
        this.router = router;
        this.auditService = auditService;
    }

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null) throw new IllegalStateException("Tenant context not resolved");
        return tenantId;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : a.getName();
    }

    // ── Filters ────────────────────────────────────────────────────────────

    /** What the filter bar can offer, drawn from what has actually loaded. */
    @GetMapping("/filters")
    public ResponseEntity<Map<String, Object>> filters() {
        Long t = requireTenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemes", jdbc.queryForList(
                "SELECT DISTINCT scheme FROM sfo_fee_line WHERE tenant_id = ? ORDER BY scheme",
                String.class, t));
        out.put("entities", jdbc.queryForList(
                "SELECT DISTINCT entity_code FROM sfo_fee_line " +
                "WHERE tenant_id = ? AND entity_code IS NOT NULL ORDER BY entity_code",
                String.class, t));
        out.put("years", jdbc.queryForList(
                "SELECT DISTINCT period_year FROM sfo_fee_line WHERE tenant_id = ? ORDER BY period_year DESC",
                Integer.class, t));
        out.put("currencies", jdbc.queryForList(
                "SELECT currency_code, usd_rate, note FROM sfo_fx_rate ORDER BY currency_code"));
        out.put("categories", FeeLineClassifier.CATEGORIES);
        return ResponseEntity.ok(out);
    }

    // ── Overview ───────────────────────────────────────────────────────────

    /**
     * The landing dashboard.
     *
     * <p>Year totals are reported ONLY for years with all twelve months
     * loaded; anything else is labelled partial and carries the month count.
     * A year that is missing three invoices is not a smaller year — comparing
     * it to a complete one would read as a saving nobody made.
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam(required = false) String scheme,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "USD") String currency) {

        Long t = requireTenant();
        double fx = fxRate(currency);

        Filter f = new Filter(t, scheme, entity, year, null);

        // Monthly series across every year held, so the trend and the year
        // comparison are cut from one read.
        List<Map<String, Object>> monthly = jdbc.queryForList(
                "SELECT period_year, period_month, SUM(amount_usd) AS total_usd, COUNT(*) AS lines " +
                "  FROM sfo_fee_line WHERE tenant_id = ?" + f.whereNoYear() +
                " GROUP BY period_year, period_month ORDER BY period_year, period_month",
                f.argsNoYear());

        Map<Integer, List<Map<String, Object>>> byYear = new LinkedHashMap<>();
        for (Map<String, Object> r : monthly) {
            byYear.computeIfAbsent(((Number) r.get("period_year")).intValue(),
                    k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> years = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byYear.entrySet()) {
            int months = e.getValue().size();
            double total = e.getValue().stream().mapToDouble(r -> num(r.get("total_usd"))).sum();
            List<Integer> present = e.getValue().stream()
                    .map(r -> ((Number) r.get("period_month")).intValue()).sorted().toList();
            List<Integer> missing = new ArrayList<>();
            for (int mth = 1; mth <= 12; mth++) if (!present.contains(mth)) missing.add(mth);

            Map<String, Object> y = new LinkedHashMap<>();
            y.put("year",     e.getKey());
            y.put("months",   months);
            y.put("complete", months == 12);
            y.put("total",    conv(total, fx));
            // A run rate on a part year, clearly labelled, is more useful than
            // a total that silently understates it.
            y.put("projected", months == 12 ? null : conv(total / months * 12, fx));
            y.put("missingMonths", missing);
            years.add(y);
        }

        // Like-for-like: only the months every year in the set actually has.
        List<Integer> common = new ArrayList<>();
        for (int mth = 1; mth <= 12; mth++) {
            final int mm = mth;
            boolean inAll = !byYear.isEmpty() && byYear.values().stream().allMatch(
                    rows -> rows.stream().anyMatch(r -> ((Number) r.get("period_month")).intValue() == mm));
            if (inAll) common.add(mth);
        }
        List<Map<String, Object>> likeForLike = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byYear.entrySet()) {
            double total = e.getValue().stream()
                    .filter(r -> common.contains(((Number) r.get("period_month")).intValue()))
                    .mapToDouble(r -> num(r.get("total_usd"))).sum();
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("year",  e.getKey());
            y.put("total", conv(total, fx));
            likeForLike.add(y);
        }

        // KPI window: the requested year, else everything.
        List<Map<String, Object>> kpiRows = year == null ? monthly
                : byYear.getOrDefault(year, List.of());
        double totalFees = kpiRows.stream().mapToDouble(r -> num(r.get("total_usd"))).sum();
        long lineCount   = kpiRows.stream().mapToLong(r -> (long) num(r.get("lines"))).sum();

        // Period movement: the latest month against the one before it.
        double movement = 0, movementPct = 0;
        if (kpiRows.size() >= 2) {
            double last = num(kpiRows.get(kpiRows.size() - 1).get("total_usd"));
            double prev = num(kpiRows.get(kpiRows.size() - 2).get("total_usd"));
            movement = last - prev;
            movementPct = prev == 0 ? 0 : movement / prev * 100;
        }

        // UNEXPECTED_EVENT is deliberately excluded. A charge appearing for
        // the first time is something to look at, not money to claim back —
        // counting it here would inflate the headline with the value of
        // services the acquirer may well have agreed to.
        Double flagged = jdbc.queryForObject(
                "SELECT COALESCE(SUM(annualised_value_usd), 0) FROM sfo_opportunity " +
                "WHERE tenant_id = ? AND status IN ('FLAGGED','UNDER_REVIEW','DISPUTED') " +
                "  AND finding_type <> 'UNEXPECTED_EVENT'",
                Double.class, t);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalFees",      conv(totalFees, fx));
        kpis.put("lineCount",      lineCount);
        kpis.put("flaggedSavings", conv(flagged == null ? 0 : flagged, fx));
        kpis.put("movement",       conv(movement, fx));
        kpis.put("movementPct",    round(movementPct, 2));

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> r : (year == null ? monthly : kpiRows)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year",  r.get("period_year"));
            m.put("month", r.get("period_month"));
            // %d takes an integer: handing it the double that num() returns
            // throws IllegalFormatConversionException and 400s the dashboard.
            m.put("label", r.get("period_year") + "-"
                    + String.format("%02d", (long) num(r.get("period_month"))));
            m.put("total", conv(num(r.get("total_usd")), fx));
            trend.add(m);
        }

        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT category, SUM(amount_usd) AS total_usd, COUNT(*) AS lines " +
                "  FROM sfo_fee_line WHERE tenant_id = ?" + f.where() +
                " GROUP BY category ORDER BY SUM(amount_usd) DESC", f.args())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", r.get("category"));
            m.put("total",    conv(num(r.get("total_usd")), fx));
            m.put("lines",    r.get("lines"));
            m.put("share",    totalFees == 0 ? 0 : round(num(r.get("total_usd")) / totalFees * 100, 2));
            byCategory.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency",   currency.toUpperCase());
        out.put("kpis",       kpis);
        out.put("trend",      trend);
        out.put("byCategory", byCategory);
        out.put("years",      years);
        out.put("likeForLike", Map.of("months", common, "years", likeForLike));
        return ResponseEntity.ok(out);
    }

    // ── Breakdown ──────────────────────────────────────────────────────────

    /** Category → billing event, with period-on-period movement per event. */
    @GetMapping("/breakdown")
    public ResponseEntity<List<Map<String, Object>>> breakdown(
            @RequestParam(required = false) String scheme,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "USD") String currency) {

        Long t = requireTenant();
        double fx = fxRate(currency);
        Filter f = new Filter(t, scheme, entity, year, category);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT category, event_code, MIN(description) AS description, scheme, " +
                "       SUM(amount_usd) AS total_usd, SUM(COALESCE(quantity,0)) AS quantity, " +
                "       COUNT(*) AS lines, AVG(confidence) AS confidence, " +
                "       MIN(classified_by) AS classified_by " +
                "  FROM sfo_fee_line WHERE tenant_id = ?" + f.where() +
                " GROUP BY category, event_code, scheme " +
                " ORDER BY category, SUM(amount_usd) DESC", f.args())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category",    r.get("category"));
            m.put("eventCode",   r.get("event_code"));
            m.put("description", r.get("description"));
            m.put("scheme",      r.get("scheme"));
            m.put("total",       conv(num(r.get("total_usd")), fx));
            m.put("quantity",    num(r.get("quantity")));
            m.put("lines",       r.get("lines"));
            m.put("confidence",  round(num(r.get("confidence")), 0));
            m.put("classifiedBy", r.get("classified_by"));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    // ── Fee lines ──────────────────────────────────────────────────────────

    /** The raw ledger, filtered and paged. */
    @GetMapping("/lines")
    public ResponseEntity<Map<String, Object>> lines(
            @RequestParam(required = false) String scheme,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        Long t = requireTenant();
        Filter f = new Filter(t, scheme, entity, year, category);

        StringBuilder where = new StringBuilder(f.where());
        List<Object> args = new ArrayList<>(List.of(f.args()));
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(COALESCE(description,'')) LIKE ? " +
                         "   OR LOWER(COALESCE(event_code,'')) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sfo_fee_line WHERE tenant_id = ?" + where,
                Integer.class, args.toArray());

        int limit = Math.min(Math.max(size, 1), 500);
        List<Object> paged = new ArrayList<>(args);
        paged.add(limit);
        paged.add(Math.max(page, 0) * limit);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT line_id, upload_id, period_year, period_month, scheme, entity_code, " +
                "       event_code, description, quantity, unit_rate, amount, currency_code, " +
                "       amount_usd, category, confidence, classified_by, source_row " +
                "  FROM sfo_fee_line WHERE tenant_id = ?" + where +
                " ORDER BY period_year DESC, period_month DESC, amount_usd DESC " +
                " LIMIT ? OFFSET ?", paged.toArray());

        return ResponseEntity.ok(Map.of(
                "total", total == null ? 0 : total,
                "page", page, "size", limit, "rows", rows));
    }

    // ── Event dictionary ───────────────────────────────────────────────────

    /** Every event seen, with what we believe it is and how we decided. */
    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> events(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String scheme) {
        Long t = requireTenant();

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(t);
        args.add(t);
        if (scheme != null && !scheme.isBlank()) { where.append(" AND l.scheme = ?"); args.add(scheme); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(COALESCE(l.description,'')) LIKE ? " +
                         "   OR LOWER(COALESCE(l.event_code,'')) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }

        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT l.scheme, l.event_code, MIN(l.description) AS description, " +
                "       MIN(l.category) AS category, AVG(l.confidence) AS confidence, " +
                "       MIN(l.classified_by) AS classified_by, COUNT(*) AS lines, " +
                "       SUM(l.amount_usd) AS total_usd, " +
                "       MAX(d.explanation) AS explanation, " +
                "       BOOL_OR(o.override_id IS NOT NULL) AS overridden " +
                "  FROM sfo_fee_line l " +
                "  LEFT JOIN sfo_event_dictionary d " +
                "         ON d.scheme = l.scheme AND d.event_code = l.event_code " +
                "        AND (d.tenant_id IS NULL OR d.tenant_id = ?) " +
                "  LEFT JOIN sfo_classification_override o " +
                "         ON o.tenant_id = l.tenant_id AND o.scheme = l.scheme " +
                "        AND o.event_code = l.event_code " +
                " WHERE l.tenant_id = ?" + where +
                " GROUP BY l.scheme, l.event_code " +
                " ORDER BY SUM(l.amount_usd) DESC LIMIT 1000",
                args.toArray()));
    }

    // ── Overrides ──────────────────────────────────────────────────────────

    @GetMapping("/overrides")
    public ResponseEntity<List<Map<String, Object>>> overrides() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT override_id, scheme, event_code, category, note, created_by, created_at " +
                "FROM sfo_classification_override WHERE tenant_id = ? ORDER BY scheme, event_code", t));
    }

    /**
     * Sets how this tenant reads a billing event, and reclassifies what has
     * already loaded.
     *
     * Re-running the import would be the alternative, which would mean
     * re-uploading every file — so the correction is applied in place.
     */
    @PostMapping("/overrides")
    @Transactional
    public ResponseEntity<?> addOverride(@RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String scheme   = str(body.get("scheme"), "").trim().toUpperCase();
        String code     = str(body.get("eventCode"), "").trim();
        String category = str(body.get("category"), "").trim().toUpperCase();

        if (scheme.isEmpty() || code.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Scheme and event code are required"));
        if (!FeeLineClassifier.CATEGORIES.contains(category))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown category: " + category));

        jdbc.update(
                "INSERT INTO sfo_classification_override " +
                "(tenant_id, scheme, event_code, category, note, created_by) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, scheme, event_code) " +
                "DO UPDATE SET category = EXCLUDED.category, note = EXCLUDED.note",
                t, scheme, code, category, str(body.get("note"), null), currentUser());

        int reclassified = jdbc.update(
                "UPDATE sfo_fee_line SET category = ?, confidence = 100, classified_by = 'OVERRIDE' " +
                "WHERE tenant_id = ? AND scheme = ? AND event_code = ?",
                category, t, scheme, code);

        // The dictionary is settled too, and marked as no longer a guess so a
        // later import cannot quietly learn over the correction.
        jdbc.update(
                "UPDATE sfo_event_dictionary SET category = ?, confidence = 100, learned = FALSE, " +
                "       updated_at = CURRENT_TIMESTAMP, updated_by = ? " +
                " WHERE scheme = ? AND event_code = ? AND (tenant_id IS NULL OR tenant_id = ?)",
                category, currentUser(), scheme, code, t);

        try { auditService.log("SFO_OVERRIDE", "Classified " + scheme + " " + code + " as " + category); }
        catch (Exception ignored) { }

        return ResponseEntity.ok(Map.of("message", "Override saved", "reclassified", reclassified));
    }

    @DeleteMapping("/overrides/{id}")
    @Transactional
    public ResponseEntity<?> deleteOverride(@PathVariable Long id) {
        Long t = requireTenant();
        int n = jdbc.update("DELETE FROM sfo_classification_override " +
                "WHERE tenant_id = ? AND override_id = ?", t, id);
        if (n == 0) return ResponseEntity.notFound().build();
        try { auditService.log("SFO_OVERRIDE_DELETED", "Removed classification override " + id); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Override removed"));
    }

    // ── Savings centre ─────────────────────────────────────────────────────

    @GetMapping("/savings")
    public ResponseEntity<List<Map<String, Object>>> savings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String team,
            @RequestParam(required = false) Boolean overdue) {
        Long t = requireTenant();
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(t);
        if (status != null && !status.isBlank()) { where.append(" AND status = ?"); args.add(status.toUpperCase()); }
        if (type   != null && !type.isBlank())   { where.append(" AND finding_type = ?"); args.add(type.toUpperCase()); }
        if (team   != null && !team.isBlank()) {
            // "UNROUTED" is a real thing to ask for: it means the rules have a
            // hole in them, which is worth seeing rather than hiding.
            if ("UNROUTED".equalsIgnoreCase(team)) where.append(" AND team_code IS NULL");
            else { where.append(" AND team_code = ?"); args.add(team.toUpperCase()); }
        }
        if (Boolean.TRUE.equals(overdue))
            where.append(" AND due_date < CURRENT_DATE AND status IN ('FLAGGED','UNDER_REVIEW','DISPUTED')");

        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT opportunity_id, finding_type, severity, scheme, entity_code, event_code, " +
                "       description, category, period_year, period_month, currency_code, " +
                "       monthly_value, annualised_value, monthly_value_usd, annualised_value_usd, " +
                "       status, evidence, note, detected_at, updated_at, updated_by, " +
                "       team_code, assignee, due_date, routed_by, " +
                "       (due_date < CURRENT_DATE " +
                "         AND status IN ('FLAGGED','UNDER_REVIEW','DISPUTED')) AS is_overdue, " +
                "       (CURRENT_DATE - due_date) AS days_past_due " +
                "  FROM sfo_opportunity WHERE tenant_id = ?" + where +
                // Overdue first: the queue's job is to surface what has been
                // sitting, not to rank purely by size.
                " ORDER BY (due_date < CURRENT_DATE " +
                "           AND status IN ('FLAGGED','UNDER_REVIEW','DISPUTED')) DESC NULLS LAST, " +
                "          CASE severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, " +
                "          annualised_value_usd DESC LIMIT 1000",
                args.toArray()));
    }

    /** Open work per team: what each queue is carrying, and how late. */
    @GetMapping("/savings/by-team")
    public ResponseEntity<List<Map<String, Object>>> byTeam() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT COALESCE(o.team_code, 'UNROUTED') AS team_code, " +
                "       MAX(tm.team_name) AS team_name, " +
                "       COUNT(*) AS open_count, " +
                "       COUNT(*) FILTER (WHERE o.due_date < CURRENT_DATE) AS overdue_count, " +
                "       COALESCE(SUM(o.annualised_value_usd) " +
                "                FILTER (WHERE o.finding_type <> 'UNEXPECTED_EVENT'), 0) AS claimable_usd, " +
                "       MIN(o.due_date) AS next_due " +
                "  FROM sfo_opportunity o " +
                "  LEFT JOIN sfo_team tm ON tm.tenant_id = o.tenant_id AND tm.team_code = o.team_code " +
                " WHERE o.tenant_id = ? AND o.status IN ('FLAGGED','UNDER_REVIEW','DISPUTED') " +
                " GROUP BY COALESCE(o.team_code, 'UNROUTED') " +
                " ORDER BY overdue_count DESC, claimable_usd DESC", t));
    }

    /** Hands a finding to a team or a person. */
    @PutMapping("/savings/{id}/assign")
    @Transactional
    public ResponseEntity<?> assign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String team = str(body.get("teamCode"), "").trim().toUpperCase();

        if (!team.isEmpty()) {
            Integer known = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sfo_team WHERE tenant_id = ? AND team_code = ?",
                    Integer.class, t, team);
            if (known == null || known == 0)
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown team: " + team));
        }

        Object due = body.get("dueDate");
        java.sql.Date dueDate = null;
        if (due != null && !String.valueOf(due).isBlank()) {
            try { dueDate = java.sql.Date.valueOf(String.valueOf(due)); }
            catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Due date must be yyyy-mm-dd"));
            }
        }

        // routed_by = MANUAL is what stops a later re-scan moving this back.
        int n = jdbc.update(
                "UPDATE sfo_opportunity " +
                "   SET team_code = COALESCE(NULLIF(?,''), team_code), " +
                "       assignee  = COALESCE(?, assignee), " +
                "       due_date  = COALESCE(?, due_date), " +
                "       routed_at = CURRENT_TIMESTAMP, routed_by = 'MANUAL', " +
                "       updated_at = CURRENT_TIMESTAMP, updated_by = ? " +
                " WHERE tenant_id = ? AND opportunity_id = ?",
                team, str(body.get("assignee"), null), dueDate, currentUser(), t, id);
        if (n == 0) return ResponseEntity.notFound().build();

        try {
            auditService.log("SFO_FINDING_ASSIGNED",
                    "Finding " + id + " -> " + (team.isEmpty() ? "(team unchanged)" : team)
                    + (body.get("assignee") == null ? "" : " / " + body.get("assignee")));
        } catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Finding assigned"));
    }

    // ── Teams and routing rules ────────────────────────────────────────────

    @GetMapping("/teams")
    public ResponseEntity<List<Map<String, Object>>> teams() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT tm.team_id, tm.team_code, tm.team_name, tm.description, " +
                "       tm.default_sla_days, tm.is_active, tm.display_order, " +
                "       (SELECT COUNT(*) FROM sfo_opportunity o " +
                "         WHERE o.tenant_id = tm.tenant_id AND o.team_code = tm.team_code " +
                "           AND o.status IN ('FLAGGED','UNDER_REVIEW','DISPUTED')) AS open_count " +
                "  FROM sfo_team tm WHERE tm.tenant_id = ? ORDER BY tm.display_order, tm.team_name", t));
    }

    @PostMapping("/teams")
    @Transactional
    public ResponseEntity<?> saveTeam(@RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String code = str(body.get("teamCode"), "").trim().toUpperCase();
        String name = str(body.get("teamName"), "").trim();
        if (code.isEmpty() || name.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Team code and name are required"));

        Integer sla = intOf(body.get("defaultSlaDays"), 14);
        jdbc.update(
                "INSERT INTO sfo_team (tenant_id, team_code, team_name, description, default_sla_days) " +
                "VALUES (?,?,?,?,?) ON CONFLICT (tenant_id, team_code) " +
                "DO UPDATE SET team_name = EXCLUDED.team_name, description = EXCLUDED.description, " +
                "              default_sla_days = EXCLUDED.default_sla_days",
                t, code, name, str(body.get("description"), null), sla);
        return ResponseEntity.ok(Map.of("message", "Team saved"));
    }

    @GetMapping("/routing-rules")
    public ResponseEntity<List<Map<String, Object>>> routingRules() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT r.rule_id, r.finding_type, r.category, r.scheme, r.match_text, " +
                "       r.team_code, tm.team_name, r.sla_days, r.priority, r.note, r.is_active, " +
                "       r.created_by " +
                "  FROM sfo_routing_rule r " +
                "  LEFT JOIN sfo_team tm ON tm.tenant_id = r.tenant_id AND tm.team_code = r.team_code " +
                " WHERE r.tenant_id = ? ORDER BY r.priority DESC, r.rule_id", t));
    }

    @PostMapping("/routing-rules")
    @Transactional
    public ResponseEntity<?> addRoutingRule(@RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String team = str(body.get("teamCode"), "").trim().toUpperCase();
        if (team.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "A team is required"));

        Integer known = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sfo_team WHERE tenant_id = ? AND team_code = ?",
                Integer.class, t, team);
        if (known == null || known == 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown team: " + team));

        String findingType = blankToNull(str(body.get("findingType"), null));
        String category    = blankToNull(str(body.get("category"), null));
        if (category != null && !FeeLineClassifier.CATEGORIES.contains(category.toUpperCase()))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown category: " + category));

        jdbc.update(
                "INSERT INTO sfo_routing_rule (tenant_id, finding_type, category, scheme, " +
                " match_text, team_code, sla_days, priority, note, created_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                t,
                findingType == null ? null : findingType.toUpperCase(),
                category == null ? null : category.toUpperCase(),
                blankToNull(str(body.get("scheme"), null)),
                blankToNull(str(body.get("matchText"), null)),
                team, intOf(body.get("slaDays"), null), intOf(body.get("priority"), 50),
                str(body.get("note"), null), currentUser());

        try { auditService.log("SFO_ROUTING_RULE", "Added a routing rule to " + team); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Rule added"));
    }

    @DeleteMapping("/routing-rules/{id}")
    @Transactional
    public ResponseEntity<?> deleteRoutingRule(@PathVariable Long id) {
        Long t = requireTenant();
        int n = jdbc.update("DELETE FROM sfo_routing_rule WHERE tenant_id = ? AND rule_id = ?", t, id);
        if (n == 0) return ResponseEntity.notFound().build();
        try { auditService.log("SFO_ROUTING_RULE_DELETED", "Removed routing rule " + id); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Rule removed"));
    }

    /**
     * Re-routes findings against the current rules.
     *
     * Only places findings that have no team yet, so changing a rule and
     * running this places the backlog without disturbing anyone's queue. To
     * move findings that are already routed, clear them first with
     * {@code ?reset=true} — which deliberately leaves hand-assigned ones alone.
     */
    @PostMapping("/routing-rules/apply")
    @Transactional
    public ResponseEntity<?> applyRouting(@RequestParam(defaultValue = "false") boolean reset) {
        Long t = requireTenant();
        int cleared = 0;
        if (reset) {
            cleared = jdbc.update(
                    "UPDATE sfo_opportunity SET team_code = NULL, due_date = NULL, routed_by = NULL " +
                    " WHERE tenant_id = ? AND COALESCE(routed_by,'RULE') = 'RULE'", t);
        }
        int routed = router.route(t);
        try { auditService.log("SFO_ROUTING_APPLIED", "Routed " + routed + " findings"); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("routed", routed, "cleared", cleared));
    }

    @PutMapping("/savings/{id}")
    @Transactional
    public ResponseEntity<?> updateFinding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String status = str(body.get("status"), "").trim().toUpperCase();
        List<String> allowed = List.of("FLAGGED", "UNDER_REVIEW", "DISPUTED", "RECOVERED", "DISMISSED");
        if (!status.isEmpty() && !allowed.contains(status))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown status: " + status));

        int n = jdbc.update(
                "UPDATE sfo_opportunity " +
                "   SET status = COALESCE(NULLIF(?,''), status), " +
                "       note = COALESCE(?, note), " +
                "       updated_at = CURRENT_TIMESTAMP, updated_by = ? " +
                " WHERE tenant_id = ? AND opportunity_id = ?",
                status, str(body.get("note"), null), currentUser(), t, id);
        if (n == 0) return ResponseEntity.notFound().build();

        try { auditService.log("SFO_FINDING_UPDATED", "Finding " + id + " -> " + status); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Finding updated"));
    }

    /** Re-runs detection. Safe to repeat — workflow state is preserved. */
    @PostMapping("/savings/scan")
    public ResponseEntity<?> scan(@RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) Integer month) {
        Long t = requireTenant();
        SavingsDetector.ScanResult r = detector.scan(t, year, month);
        try {
            auditService.log("SFO_SCAN",
                    "Scanned " + r.scanned() + " lines: " + r.raised() + " new, " + r.updated() + " refreshed");
        } catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of(
                "raised", r.raised(), "updated", r.updated(),
                "scanned", r.scanned(), "retired", r.retired(), "routed", r.routed()));
    }

    // ── Uploads ────────────────────────────────────────────────────────────

    @GetMapping("/uploads")
    public ResponseEntity<List<Map<String, Object>>> uploads() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT upload_id, file_name, file_size, scheme, entity_code, period_year, " +
                "       period_month, invoice_ref, currency_code, invoice_total, parsed_total, " +
                "       line_count, status, error_message, uploaded_by, uploaded_at, " +
                "       confirmed_by, confirmed_at " +
                "  FROM sfo_upload WHERE tenant_id = ? ORDER BY uploaded_at DESC LIMIT 200", t));
    }

    /**
     * Parses a file and stages it for review. Writes NOTHING to the ledger.
     *
     * Returns the detection so the screen can show what was read, how sure it
     * is, and what it could not work out.
     */
    @PostMapping("/uploads")
    @Transactional
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        Long t = requireTenant();
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No file was uploaded"));
        if (file.getSize() > MAX_UPLOAD_BYTES)
            return ResponseEntity.badRequest().body(Map.of("error",
                    "That file is larger than 25 MB — a billing invoice should not be."));

        byte[] content;
        try { content = file.getBytes(); }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the upload"));
        }

        String fileName = file.getOriginalFilename() == null ? "invoice" : file.getOriginalFilename();
        String hash = sha256(content);

        // The same file loaded twice would double every figure on every screen
        // without changing a single number that looks wrong.
        List<Map<String, Object>> already = jdbc.queryForList(
                "SELECT upload_id, file_name, status FROM sfo_upload " +
                "WHERE tenant_id = ? AND content_hash = ? AND status = 'DONE'", t, hash);
        if (!already.isEmpty())
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This exact file has already been imported as upload #"
                           + already.get(0).get("upload_id") + " (" + already.get(0).get("file_name") + ").",
                    "duplicateOf", already.get(0).get("upload_id")));

        InvoiceParser.Detection d = parser.parse(fileName, content);

        String detectionJson = writeJson(Map.of(
                "fields", d.fields(), "columns", d.columns(),
                "headerRow", d.headerRow(), "warnings", d.warnings()));
        String stagedJson = writeJson(d.lines());

        boolean usable = !d.lines().isEmpty();
        String status = usable ? "NEEDS_REVIEW" : "FAILED";

        jdbc.update(
                "INSERT INTO sfo_upload (tenant_id, file_name, file_size, content_hash, scheme, " +
                " entity_code, period_year, period_month, invoice_ref, currency_code, invoice_total, " +
                " parsed_total, line_count, status, detection, staged_lines, error_message, uploaded_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t, fileName, file.getSize(), hash, d.scheme(), d.entityCode(), d.year(), d.month(),
                d.invoiceRef(), d.currency(), d.invoiceTotal(), d.parsedTotal(), d.lines().size(),
                status, detectionJson, stagedJson,
                usable ? null : String.join(" ", d.warnings()), currentUser());

        Long uploadId = jdbc.queryForObject(
                "SELECT MAX(upload_id) FROM sfo_upload WHERE tenant_id = ? AND content_hash = ?",
                Long.class, t, hash);

        try { auditService.log("SFO_UPLOAD", "Staged " + fileName + " (" + d.lines().size() + " lines)"); }
        catch (Exception ignored) { }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId",    uploadId);
        out.put("status",      status);
        out.put("fields",      d.fields());
        out.put("columns",     d.columns());
        out.put("headerRow",   d.headerRow());
        out.put("lineCount",   d.lines().size());
        out.put("parsedTotal", d.parsedTotal());
        out.put("invoiceTotal", d.invoiceTotal());
        out.put("warnings",    d.warnings());
        out.put("sample",      d.lines().stream().limit(15).toList());
        return ResponseEntity.ok(out);
    }

    /**
     * Commits a staged upload, classifying every line as it lands.
     *
     * The operator's corrections win over the detector's guesses — that is the
     * whole point of the review step.
     */
    @PostMapping("/uploads/{id}/confirm")
    @Transactional
    public ResponseEntity<?> confirm(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long t = requireTenant();

        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT * FROM sfo_upload WHERE tenant_id = ? AND upload_id = ?", t, id);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> up = found.get(0);
        if ("DONE".equals(up.get("status")))
            return ResponseEntity.badRequest().body(Map.of("error", "That upload has already been imported"));

        String scheme   = str(body.get("scheme"),   str(up.get("scheme"), "")).trim().toUpperCase();
        String entity   = str(body.get("entityCode"), str(up.get("entity_code"), null));
        String currency = str(body.get("currency"), str(up.get("currency_code"), "")).trim().toUpperCase();
        Integer year    = intOf(body.get("periodYear"),  up.get("period_year"));
        Integer month   = intOf(body.get("periodMonth"), up.get("period_month"));

        List<String> missing = new ArrayList<>();
        if (scheme.isEmpty())   missing.add("scheme");
        if (currency.isEmpty()) missing.add("currency");
        if (year == null)       missing.add("period year");
        if (month == null)      missing.add("period month");
        if (!missing.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Still needed before this can be imported: " + String.join(", ", missing)));

        double fx = fxRate(currency);
        List<Map<String, Object>> staged = readJsonList(up.get("staged_lines"));
        if (staged.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "There are no staged lines to import"));

        // Re-confirming after a failure must not leave half an invoice behind.
        jdbc.update("DELETE FROM sfo_fee_line WHERE tenant_id = ? AND upload_id = ?", t, id);

        FeeLineClassifier.RuleBook book = classifier.ruleBook(t, scheme);
        int imported = 0;
        for (Map<String, Object> line : staged) {
            String code = str(line.get("eventCode"), "");
            String desc = str(line.get("description"), "");
            BigDecimal amount = bd(line.get("amount"));
            if (amount == null) continue;

            FeeLineClassifier.Verdict v = classifier.classify(book, code, desc);
            classifier.learn(t, scheme, code, desc, v);

            jdbc.update(
                    "INSERT INTO sfo_fee_line (tenant_id, upload_id, period_year, period_month, " +
                    " scheme, entity_code, event_code, description, quantity, unit_rate, amount, " +
                    " currency_code, amount_usd, category, confidence, classified_by, source_row) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    t, id, year, month, scheme, entity,
                    code.isEmpty() ? null : code, desc.isEmpty() ? null : desc,
                    bd(line.get("quantity")), bd(line.get("unitRate")), amount,
                    currency, amount.multiply(BigDecimal.valueOf(fx)).setScale(4, RoundingMode.HALF_UP),
                    v.category(), v.confidence(), v.source(),
                    intOf(line.get("sourceRow"), null));
            imported++;
        }

        jdbc.update(
                "UPDATE sfo_upload SET status = 'DONE', scheme = ?, entity_code = ?, " +
                "  period_year = ?, period_month = ?, currency_code = ?, line_count = ?, " +
                "  confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP, error_message = NULL " +
                "WHERE tenant_id = ? AND upload_id = ?",
                scheme, entity, year, month, currency, imported, currentUser(), t, id);

        // An entity named on an invoice is a real entity; registering it here
        // saves somebody typing it in a settings screen to see it in a filter.
        if (entity != null && !entity.isBlank()) {
            jdbc.update(
                    "INSERT INTO sfo_entity (tenant_id, entity_code, entity_name, scheme) " +
                    "VALUES (?,?,?,?) ON CONFLICT (tenant_id, entity_code) DO NOTHING",
                    t, entity, entity, scheme);
        }

        SavingsDetector.ScanResult scan = detector.scan(t, year, month);

        try { auditService.log("SFO_IMPORT", "Imported " + imported + " lines from upload " + id); }
        catch (Exception ignored) { }

        return ResponseEntity.ok(Map.of(
                "message", "Imported", "imported", imported,
                "findingsRaised", scan.raised(), "findingsUpdated", scan.updated()));
    }

    @DeleteMapping("/uploads/{id}")
    @Transactional
    public ResponseEntity<?> deleteUpload(@PathVariable Long id) {
        Long t = requireTenant();
        // The fee lines go with it via ON DELETE CASCADE, which is the point:
        // removing an invoice has to remove what it contributed, or the
        // dashboard keeps counting a file that is no longer there.
        int n = jdbc.update("DELETE FROM sfo_upload WHERE tenant_id = ? AND upload_id = ?", t, id);
        if (n == 0) return ResponseEntity.notFound().build();
        try { auditService.log("SFO_UPLOAD_DELETED", "Deleted upload " + id + " and its fee lines"); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Upload and its lines removed"));
    }

    // ── Entities ───────────────────────────────────────────────────────────

    @GetMapping("/entities")
    public ResponseEntity<List<Map<String, Object>>> entities() {
        Long t = requireTenant();
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT entity_id, entity_code, entity_name, scheme, is_active, created_at " +
                "FROM sfo_entity WHERE tenant_id = ? ORDER BY entity_code", t));
    }

    @PostMapping("/entities")
    @Transactional
    public ResponseEntity<?> addEntity(@RequestBody Map<String, Object> body) {
        Long t = requireTenant();
        String code = str(body.get("entityCode"), "").trim();
        String name = str(body.get("entityName"), "").trim();
        if (code.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Entity code is required"));

        jdbc.update(
                "INSERT INTO sfo_entity (tenant_id, entity_code, entity_name, scheme) VALUES (?,?,?,?) " +
                "ON CONFLICT (tenant_id, entity_code) " +
                "DO UPDATE SET entity_name = EXCLUDED.entity_name, scheme = EXCLUDED.scheme",
                t, code, name.isEmpty() ? code : name, str(body.get("scheme"), null));
        return ResponseEntity.ok(Map.of("message", "Entity saved"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Builds the shared WHERE for the filter bar. */
    private record Filter(Long tenantId, String scheme, String entity, Integer year, String category) {

        String where() {
            StringBuilder w = new StringBuilder();
            if (notBlank(scheme))   w.append(" AND scheme = ?");
            if (notBlank(entity))   w.append(" AND entity_code = ?");
            if (year != null)       w.append(" AND period_year = ?");
            if (notBlank(category)) w.append(" AND category = ?");
            return w.toString();
        }

        Object[] args() {
            List<Object> a = new ArrayList<>();
            a.add(tenantId);
            if (notBlank(scheme))   a.add(scheme);
            if (notBlank(entity))   a.add(entity);
            if (year != null)       a.add(year);
            if (notBlank(category)) a.add(category);
            return a.toArray();
        }

        /** The trend needs every year, so the year filter is dropped. */
        String whereNoYear() {
            StringBuilder w = new StringBuilder();
            if (notBlank(scheme))   w.append(" AND scheme = ?");
            if (notBlank(entity))   w.append(" AND entity_code = ?");
            if (notBlank(category)) w.append(" AND category = ?");
            return w.toString();
        }

        Object[] argsNoYear() {
            List<Object> a = new ArrayList<>();
            a.add(tenantId);
            if (notBlank(scheme))   a.add(scheme);
            if (notBlank(entity))   a.add(entity);
            if (notBlank(category)) a.add(category);
            return a.toArray();
        }

        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    }

    /**
     * Local currency per USD.
     *
     * Lines are stored in USD, so converting to a display currency DIVIDES by
     * that currency's USD rate. An unknown currency falls back to 1 rather
     * than failing the whole dashboard — and the screen says the rates are
     * indicative, which is the honest description of a pegged table.
     */
    private double fxRate(String currency) {
        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) return 1.0;
        try {
            Double r = jdbc.queryForObject(
                    "SELECT usd_rate FROM sfo_fx_rate WHERE currency_code = ?",
                    Double.class, currency.toUpperCase());
            return r == null || r == 0 ? 1.0 : r;
        } catch (Exception e) {
            return 1.0;
        }
    }

    private static double conv(double usd, double fxRate) {
        return round(usd / fxRate, 2);
    }

    private static double round(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }

    private static String str(Object v, String dflt) {
        if (v == null) return dflt;
        String s = String.valueOf(v);
        return s.isBlank() ? dflt : s;
    }

    /** A blank from a form field means "any", which the rule table stores as NULL. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static Integer intOf(Object v, Object dflt) {
        Object use = (v == null || String.valueOf(v).isBlank()) ? dflt : v;
        if (use == null) return null;
        if (use instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(use).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        try { return new BigDecimal(String.valueOf(v)).setScale(4, RoundingMode.HALF_UP); }
        catch (NumberFormatException e) { return null; }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (Exception e) {
            // Without a hash the duplicate check simply never matches, which
            // is a lost safeguard, not a failed upload.
            return "";
        }
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(Object v) {
        if (v == null) return List.of();
        try {
            Object parsed = json.readValue(String.valueOf(v), Object.class);
            return parsed instanceof List ? (List<Map<String, Object>>) parsed : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
