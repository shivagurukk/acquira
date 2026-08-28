package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.ingest.WorkingWeekResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Read API behind the Ingest Trust board (/ops/ingest-trust).
 *
 * TENANT ISOLATION: every query is filtered by the caller's tenant unless the
 * caller is a super-admin, mirroring BatchProgressController. The whole
 * controller is gated — the 2026-08-15 improvement audit counted ~25 ungated
 * controllers and this is deliberately not the 26th.
 *
 * CACHING: none, except a short window on /overview. The board's entire value is
 * being current; putting it behind the 6h report cache would mean an operator
 * looking at a stale "last good load" during an actual outage.
 */
@RestController
@RequestMapping("/api/ops/ingest")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPS')")
public class IngestTrustController {

    private final JdbcTemplate jdbc;
    private final WorkingWeekResolver workingWeek;

    public IngestTrustController(JdbcTemplate jdbc, WorkingWeekResolver workingWeek) {
        this.jdbc = jdbc;
        this.workingWeek = workingWeek;
    }

    // ── Tenant scoping ──────────────────────────────────────────────────────

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Tenant the caller may see, or null for a super-admin (meaning "all").
     * A non-super-admin with no tenant in context sees nothing rather than
     * everything — failing closed is the only safe default here.
     */
    private Long scopeTenant(Long requested) {
        if (isSuperAdmin()) return requested;              // may be null = all tenants
        Long own = TenantContext.getCurrentTenant();
        return own == null ? -1L : own;                    // -1 matches no tenant
    }

    private boolean allTenants(Long scoped) {
        return scoped == null;
    }

    // ── Overview ────────────────────────────────────────────────────────────

    /**
     * One freshness tile per visible tenant: when data last landed, whether it is
     * overdue against the tenant's cutoff, and whether anything is currently
     * failing. This is the panel that answers the question actually asked every
     * morning — "is everyone's data in?"
     */
    @GetMapping("/overview")
    public List<Map<String, Object>> overview() {
        Long scoped = scopeTenant(null);

        StringBuilder sql = new StringBuilder(
            "SELECT t.tenant_id, t.institution_id, COALESCE(t.home_country_code,'AE') AS country, " +
            "  e.enabled, e.cutoff_local_time, e.timezone, e.sla_minutes, e.expected_daily, " +
            "  (SELECT MAX(r.ended_at) FROM ingest_run r " +
            "     WHERE r.tenant_id = t.tenant_id AND r.status = 'COMPLETED') AS last_good_at, " +
            // COALESCE to rows_summary: days backfilled by the migration predate
            // the ledger and know only their summary count. Keying on rows_fact
            // alone made every tenant read NO_DATA on day one despite years of
            // data being present.
            "  (SELECT MAX(c.txn_date) FROM ingest_day_coverage c " +
            "     WHERE c.tenant_id = t.tenant_id " +
            "       AND COALESCE(c.rows_fact, c.rows_summary, 0) > 0) AS latest_data_date, " +
            "  (SELECT COUNT(*) FROM ingest_run r WHERE r.tenant_id = t.tenant_id " +
            "     AND r.status = 'RUNNING') AS running_now, " +
            "  (SELECT COUNT(*) FROM ingest_run r WHERE r.tenant_id = t.tenant_id " +
            "     AND r.status = 'FAILED' AND r.started_at > CURRENT_TIMESTAMP - INTERVAL '7 days' " +
            "     AND r.acknowledged_at IS NULL) AS failures_7d, " +
            "  (SELECT COUNT(*) FROM ingest_run r WHERE r.tenant_id = t.tenant_id " +
            "     AND r.recon_status = 'GAP' AND r.started_at > CURRENT_TIMESTAMP - INTERVAL '7 days' " +
            "     AND r.acknowledged_at IS NULL) AS gaps_7d " +
            "FROM tenant t LEFT JOIN ingest_expectation e ON e.tenant_id = t.tenant_id ");

        List<Object> args = new ArrayList<>();
        if (!allTenants(scoped)) {
            sql.append("WHERE t.tenant_id = ? ");
            args.add(scoped);
        }
        sql.append("ORDER BY t.institution_id");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> tile = new LinkedHashMap<>(r);
            Long tenantId = ((Number) r.get("tenant_id")).longValue();
            tile.put("weekendDays", workingWeek.weekendDaysForTenant(tenantId)
                    .stream().map(Enum::name).sorted().toList());
            tile.put("state", freshnessState(r, tenantId));
            out.add(tile);
        }
        return out;
    }

    /**
     * Classifies a tenant's freshness.
     *
     * WORKING WEEK MATTERS HERE: a Bahraini tenant is not late on a Friday. Using
     * a Mon-Fri assumption would raise a missing-data flag every weekend for
     * every Gulf tenant, and a board that cries wolf weekly stops being read.
     */
    private String freshnessState(Map<String, Object> r, Long tenantId) {
        Boolean enabled = (Boolean) r.get("enabled");
        if (enabled == null || !enabled) return "NOT_MONITORED";
        if (((Number) r.get("running_now")).longValue() > 0) return "RUNNING";
        if (((Number) r.get("failures_7d")).longValue() > 0) return "FAILING";

        java.sql.Date latest = (java.sql.Date) r.get("latest_data_date");
        if (latest == null) return "NO_DATA";

        LocalDate expected = LocalDate.now().minusDays(1);
        while (!workingWeek.isWorkingDay(tenantId, expected)) {
            expected = expected.minusDays(1);
        }
        if (!latest.toLocalDate().isBefore(expected)) {
            return ((Number) r.get("gaps_7d")).longValue() > 0 ? "OK_WITH_GAPS" : "OK";
        }
        return "STALE";
    }

    // ── Runs ────────────────────────────────────────────────────────────────

    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(required = false) Long tenantId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Long scoped = scopeTenant(tenantId);
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (!allTenants(scoped)) { where.append(" AND r.tenant_id = ? "); args.add(scoped); }
        if (from != null && !from.isBlank())   { where.append(" AND r.started_at >= ? "); args.add(java.sql.Date.valueOf(from)); }
        if (to != null && !to.isBlank())       { where.append(" AND r.started_at < ? + INTERVAL '1 day' "); args.add(java.sql.Date.valueOf(to)); }
        if (status != null && !status.isBlank()){ where.append(" AND r.status = ? "); args.add(status.toUpperCase()); }
        if (source != null && !source.isBlank()){ where.append(" AND r.source = ? "); args.add(source.toUpperCase()); }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ingest_run r" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT r.id, r.tenant_id, t.institution_id, r.source, r.job_name, r.job_execution_id, " +
            "  r.file_name, r.file_bytes, r.load_mode, r.status, r.started_at, r.ended_at, r.duration_ms, " +
            "  r.rows_file, r.rows_staged, r.rows_facted, r.rows_summarised, r.fact_rows_deleted, " +
            "  r.unresolved_merchants, r.fee_priced_pct, r.recon_status, r.recon_detail, " +
            "  r.min_txn_date, r.max_txn_date, r.distinct_days, r.error_message, r.triggered_by, " +
            "  r.acknowledged_by, r.acknowledged_at " +
            "FROM ingest_run r LEFT JOIN tenant t ON t.tenant_id = r.tenant_id" + where +
            " ORDER BY r.started_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", limit);
        out.put("items", items);
        return out;
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable Long id) {
        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT r.*, t.institution_id FROM ingest_run r " +
            "LEFT JOIN tenant t ON t.tenant_id = r.tenant_id WHERE r.id = ?", id);
        if (found.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> run = found.get(0);
        Long scoped = scopeTenant(null);
        if (!allTenants(scoped) && !scoped.equals(((Number) run.get("tenant_id")).longValue())) {
            // Same "not found" shape as BatchProgressController so the endpoint
            // does not confirm that another tenant's run exists.
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> out = new LinkedHashMap<>(run);
        out.put("stages", jdbc.queryForList(
            "SELECT stage_name, seq, status, started_at, ended_at, duration_ms, " +
            "rows_in, rows_out, rows_skipped, note FROM ingest_run_stage " +
            "WHERE run_id = ? ORDER BY seq", id));
        out.put("funnel", funnelOf(run));
        return ResponseEntity.ok(out);
    }

    /** The four tiers, in order, so the UI does not have to know the column names. */
    private List<Map<String, Object>> funnelOf(Map<String, Object> run) {
        String[][] tiers = {
            {"rows_file", "File"},
            {"rows_staged", "Staged"},
            {"rows_facted", "Facted"},
            {"rows_summarised", "Summarised"}};
        List<Map<String, Object>> out = new ArrayList<>();
        Long previous = null;
        for (String[] tier : tiers) {
            Object v = run.get(tier[0]);
            Long value = v == null ? null : ((Number) v).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", tier[0]);
            m.put("label", tier[1]);
            m.put("value", value);
            m.put("dropFromPrevious", (previous != null && value != null) ? previous - value : null);
            out.add(m);
            if (value != null) previous = value;
        }
        return out;
    }

    // ── Coverage calendar ───────────────────────────────────────────────────

    @GetMapping("/coverage")
    public Map<String, Object> coverage(@RequestParam(required = false) Long tenantId,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        Long scoped = scopeTenant(tenantId);
        LocalDate end = (to == null || to.isBlank()) ? LocalDate.now() : LocalDate.parse(to);
        LocalDate start = (from == null || from.isBlank()) ? end.minusDays(89) : LocalDate.parse(from);

        StringBuilder sql = new StringBuilder(
            "SELECT c.tenant_id, c.txn_date, c.rows_fact, c.rows_summary, c.gross_amount, " +
            "  c.fee_priced_rows, c.last_run_id, c.last_loaded_at, c.load_count " +
            "FROM ingest_day_coverage c WHERE c.txn_date BETWEEN ? AND ? ");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Date.valueOf(start));
        args.add(java.sql.Date.valueOf(end));
        if (!allTenants(scoped)) { sql.append(" AND c.tenant_id = ? "); args.add(scoped); }
        sql.append(" ORDER BY c.tenant_id, c.txn_date");

        List<Map<String, Object>> days = jdbc.queryForList(sql.toString(), args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("days", days);

        // Weekend days per tenant so the calendar can grey them out rather than
        // colouring them as missing data.
        Map<Long, List<String>> weekends = new LinkedHashMap<>();
        Set<Long> tenants = new LinkedHashSet<>();
        for (Map<String, Object> d : days) tenants.add(((Number) d.get("tenant_id")).longValue());
        if (!allTenants(scoped)) tenants.add(scoped);
        for (Long t : tenants) {
            weekends.put(t, workingWeek.weekendDaysForTenant(t).stream().map(Enum::name).sorted().toList());
        }
        out.put("weekendDays", weekends);
        return out;
    }

    // ── Duration trend ──────────────────────────────────────────────────────

    /**
     * p50/p95 duration per stage over the window, with the tenant's SLA. This is
     * where a stage that quietly grew from four minutes to 1.7 hours becomes one
     * obviously wrong bar instead of a log line nobody read.
     */
    @GetMapping("/duration-trend")
    public Map<String, Object> durationTrend(@RequestParam(required = false) Long tenantId,
                                             @RequestParam(required = false) String job,
                                             @RequestParam(defaultValue = "30") int days) {
        Long scoped = scopeTenant(tenantId);
        int window = Math.min(Math.max(days, 1), 180);

        StringBuilder where = new StringBuilder(
            " WHERE r.started_at > CURRENT_TIMESTAMP - (? || ' days')::INTERVAL ");
        List<Object> args = new ArrayList<>();
        args.add(window);
        if (!allTenants(scoped)) { where.append(" AND r.tenant_id = ? "); args.add(scoped); }
        if (job != null && !job.isBlank()) { where.append(" AND r.job_name = ? "); args.add(job); }

        List<Map<String, Object>> stages = jdbc.queryForList(
            "SELECT s.stage_name, COUNT(*) AS runs, " +
            "  ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY s.duration_ms)) AS p50_ms, " +
            "  ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY s.duration_ms)) AS p95_ms, " +
            "  MAX(s.duration_ms) AS max_ms " +
            "FROM ingest_run_stage s JOIN ingest_run r ON r.id = s.run_id" + where +
            " AND s.duration_ms IS NOT NULL GROUP BY s.stage_name ORDER BY p95_ms DESC NULLS LAST",
            args.toArray());

        List<Map<String, Object>> runs = jdbc.queryForList(
            "SELECT r.id, r.started_at, r.duration_ms, r.job_name, r.status, r.tenant_id " +
            "FROM ingest_run r" + where + " AND r.duration_ms IS NOT NULL ORDER BY r.started_at",
            args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("runs", runs);
        out.put("slaMinutes", slaMinutesFor(scoped));
        out.put("windowDays", window);
        return out;
    }

    private Integer slaMinutesFor(Long tenantId) {
        if (tenantId == null) return null;
        try {
            return jdbc.queryForObject("SELECT sla_minutes FROM ingest_expectation WHERE tenant_id = ?",
                    Integer.class, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Acknowledge ─────────────────────────────────────────────────────────

    /**
     * Operator sign-off on a run they have looked at. Acknowledged runs drop out
     * of the failure/gap counters on the overview tiles, so a known-and-accepted
     * problem stops masking a new one.
     */
    @PostMapping("/runs/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, String> body) {
        Long owner;
        try {
            owner = jdbc.queryForObject("SELECT tenant_id FROM ingest_run WHERE id = ?", Long.class, id);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
        Long scoped = scopeTenant(null);
        if (!allTenants(scoped) && !scoped.equals(owner)) return ResponseEntity.notFound().build();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String who = auth == null ? "unknown" : auth.getName();
        String note = body == null ? null : body.get("note");

        jdbc.update("UPDATE ingest_run SET acknowledged_by = ?, acknowledged_at = CURRENT_TIMESTAMP, " +
                    "ack_note = ? WHERE id = ?", who, note, id);
        return ResponseEntity.ok(Map.of("id", id, "acknowledgedBy", who));
    }

    // ── Expectations ────────────────────────────────────────────────────────

    @GetMapping("/expectations")
    public List<Map<String, Object>> expectations() {
        Long scoped = scopeTenant(null);
        String sql = "SELECT e.*, t.institution_id FROM ingest_expectation e " +
                     "JOIN tenant t ON t.tenant_id = e.tenant_id " +
                     (allTenants(scoped) ? "" : "WHERE e.tenant_id = ? ") +
                     "ORDER BY t.institution_id";
        return allTenants(scoped) ? jdbc.queryForList(sql) : jdbc.queryForList(sql, scoped);
    }

    @PutMapping("/expectations/{tenantId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> updateExpectation(@PathVariable Long tenantId,
                                                  @RequestBody Map<String, Object> body) {
        Long scoped = scopeTenant(tenantId);
        if (!allTenants(scoped) && !scoped.equals(tenantId)) return ResponseEntity.notFound().build();

        jdbc.update(
            "INSERT INTO ingest_expectation (tenant_id, expected_daily, cutoff_local_time, timezone, " +
            "  sla_minutes, min_rows_warn, variance_pct, fee_coverage_pct, enabled) " +
            "VALUES (?,?,?::time,?,?,?,?,?,?) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET " +
            "  expected_daily    = EXCLUDED.expected_daily, " +
            "  cutoff_local_time = EXCLUDED.cutoff_local_time, " +
            "  timezone          = EXCLUDED.timezone, " +
            "  sla_minutes       = EXCLUDED.sla_minutes, " +
            "  min_rows_warn     = EXCLUDED.min_rows_warn, " +
            "  variance_pct      = EXCLUDED.variance_pct, " +
            "  fee_coverage_pct  = EXCLUDED.fee_coverage_pct, " +
            "  enabled           = EXCLUDED.enabled",
            tenantId,
            body.getOrDefault("expectedDaily", Boolean.TRUE),
            body.get("cutoffLocalTime"),
            body.getOrDefault("timezone", "Asia/Bahrain"),
            body.getOrDefault("slaMinutes", 45),
            body.get("minRowsWarn"),
            body.getOrDefault("variancePct", 40),
            body.getOrDefault("feeCoveragePct", 95),
            body.getOrDefault("enabled", Boolean.FALSE));
        return ResponseEntity.noContent().build();
    }
}
