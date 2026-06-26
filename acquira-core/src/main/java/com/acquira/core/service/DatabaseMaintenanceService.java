package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Nightly database maintenance — VACUUM (ANALYZE) on the high-churn tables.
 *
 * <p>A poller wakes every few minutes; it runs the maintenance pass once per day
 * but ONLY when all of these hold:
 * <ul>
 *   <li>maintenance is enabled (db_maintenance_config.enabled),</li>
 *   <li>the current server-local hour is inside the configured window,</li>
 *   <li>it hasn't already run today,</li>
 *   <li>no Spring Batch job is currently running (so vacuum never competes
 *       with an upload/ingestion).</li>
 * </ul>
 *
 * <p>Config lives in {@code db_maintenance_config} (single row, admin-editable
 * via {@code MaintenanceController}); every attempt is recorded in
 * {@code db_maintenance_run}. VACUUM is run through plain JDBC (Hikari
 * auto-commit=true) outside any transaction, which PostgreSQL requires.
 */
@Service
public class DatabaseMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMaintenanceService.class);

    /** Default set if db_maintenance_config.tables_csv is null. Fact parent + summary/staging churn. */
    static final List<String> DEFAULT_TABLES = List.of(
        "fact_transaction",
        "sum_daily_bank", "sum_daily_merchant", "sum_daily_terminal", "sum_daily_finance",
        "sum_daily_insight", "sum_daily_scheme", "sum_daily_channel", "sum_daily_mcc",
        "sum_daily_merchant_attribute", "sum_monthly_bank", "sum_monthly_card",
        "stg_trnx_raw", "stg_merchant_master_raw"
    );

    // Identifier whitelist — defends the VACUUM string-built statement.
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final JdbcTemplate jdbc;

    public DatabaseMaintenanceService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    // ── Poller: every 10 min after a 5 min startup delay ──
    @Scheduled(fixedDelayString = "${maintenance.poll-interval-ms:600000}",
               initialDelayString = "${maintenance.initial-delay-ms:300000}")
    public void poll() {
        try {
            Cfg cfg = loadConfig();
            if (!cfg.enabled) return;
            if (!inWindow(LocalTime.now().getHour(), cfg.startHour, cfg.endHour)) return;
            if (cfg.lastRunDate != null && cfg.lastRunDate.equals(LocalDate.now())) return; // already ran today
            if (isBatchRunning()) {
                log.info("[maintenance] In window but a batch job is running — deferring vacuum to a later tick.");
                return;
            }
            runMaintenance(cfg, "SCHEDULED");
        } catch (Exception e) {
            log.warn("[maintenance] poll failed: {}", e.getMessage());
        }
    }

    /**
     * Run a maintenance pass now.
     * @param force when true, bypasses the window + already-ran-today checks (still refuses
     *              if a batch job is running, unless force is also used to override that).
     * @param overrideBatchGuard when true, runs even if a batch job is active (VACUUM is
     *              concurrent-safe, but not recommended during heavy ingestion).
     */
    public Map<String, Object> runNow(boolean force, boolean overrideBatchGuard) {
        Cfg cfg = loadConfig();
        if (!force) {
            if (!cfg.enabled) return skipped("Maintenance is disabled");
            if (!inWindow(LocalTime.now().getHour(), cfg.startHour, cfg.endHour)) return skipped("Outside maintenance window");
            if (cfg.lastRunDate != null && cfg.lastRunDate.equals(LocalDate.now())) return skipped("Already ran today");
        }
        if (isBatchRunning() && !overrideBatchGuard) {
            return skipped("A batch job is currently running");
        }
        return runMaintenance(cfg, "MANUAL");
    }

    private Map<String, Object> runMaintenance(Cfg cfg, String trigger) {
        List<String> tables = (cfg.tables == null || cfg.tables.isEmpty()) ? DEFAULT_TABLES : cfg.tables;
        Long runId = jdbc.queryForObject(
            "INSERT INTO db_maintenance_run (status, trigger) VALUES ('RUNNING', ?) RETURNING id",
            Long.class, trigger);

        int done = 0;
        List<String> errors = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        long t0 = System.currentTimeMillis();

        for (String table : tables) {
            if (!SAFE_IDENT.matcher(table).matches()) {
                errors.add(table + " (rejected: invalid identifier)");
                continue;
            }
            try {
                long s = System.currentTimeMillis();
                // VACUUM cannot run in a transaction — Hikari auto-commit=true ensures this is fine.
                jdbc.execute("VACUUM (ANALYZE) " + table);
                long ms = System.currentTimeMillis() - s;
                detail.append(table).append('=').append(ms).append("ms; ");
                done++;
            } catch (Exception e) {
                errors.add(table + " (" + e.getMessage() + ")");
                log.warn("[maintenance] VACUUM failed for {}: {}", table, e.getMessage());
            }
        }

        long totalMs = System.currentTimeMillis() - t0;
        String status = errors.isEmpty() ? "SUCCESS" : (done > 0 ? "SUCCESS" : "FAILED");
        if (!errors.isEmpty()) detail.append("ERRORS: ").append(String.join(" | ", errors));

        jdbc.update(
            "UPDATE db_maintenance_run SET finished_at = NOW(), status = ?, tables_done = ?, detail = ? WHERE id = ?",
            status, done, detail.toString(), runId);
        // Mark "ran today" only on a pass that actually executed.
        jdbc.update("UPDATE db_maintenance_config SET last_run_date = CURRENT_DATE, updated_at = NOW() WHERE id = 1");

        log.info("[maintenance] {} pass {}: {} of {} tables in {} ms ({} errors)",
            trigger, status, done, tables.size(), totalMs, errors.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("tablesDone", done);
        out.put("tablesTotal", tables.size());
        out.put("durationMs", totalMs);
        out.put("errors", errors);
        return out;
    }

    // ── Status / config for the controller ──
    public Map<String, Object> status() {
        Cfg cfg = loadConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.enabled);
        out.put("windowStartHour", cfg.startHour);
        out.put("windowEndHour", cfg.endHour);
        out.put("tables", (cfg.tables == null || cfg.tables.isEmpty()) ? DEFAULT_TABLES : cfg.tables);
        out.put("usingDefaultTables", cfg.tables == null || cfg.tables.isEmpty());
        out.put("lastRunDate", cfg.lastRunDate);
        out.put("batchRunning", isBatchRunning());
        out.put("inWindowNow", inWindow(LocalTime.now().getHour(), cfg.startHour, cfg.endHour));
        out.put("recentRuns", jdbc.queryForList(
            "SELECT id, started_at, finished_at, status, trigger, tables_done, detail " +
            "FROM db_maintenance_run ORDER BY started_at DESC LIMIT 10"));
        return out;
    }

    public void updateConfig(Boolean enabled, Integer startHour, Integer endHour, String tablesCsv) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (enabled != null) { sets.add("enabled = ?"); args.add(enabled); }
        if (startHour != null) { sets.add("window_start_hour = ?"); args.add(clampHour(startHour)); }
        if (endHour != null) { sets.add("window_end_hour = ?"); args.add(clampHour(endHour)); }
        if (tablesCsv != null) { sets.add("tables_csv = ?"); args.add(tablesCsv.isBlank() ? null : tablesCsv.trim()); }
        if (sets.isEmpty()) return;
        sets.add("updated_at = NOW()");
        args.add(1);
        jdbc.update("UPDATE db_maintenance_config SET " + String.join(", ", sets) + " WHERE id = ?",
            args.toArray());
    }

    // ── Helpers ──
    private boolean isBatchRunning() {
        try {
            Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_job_execution WHERE status IN ('STARTED','STARTING','STOPPING')",
                Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            // If we can't tell, be conservative and assume a job is running (skip vacuum).
            log.warn("[maintenance] could not check batch state, assuming busy: {}", e.getMessage());
            return true;
        }
    }

    static boolean inWindow(int hour, int start, int end) {
        if (start == end) return false;           // zero-width window = effectively off
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;       // wraps past midnight (e.g. 23 → 4)
    }

    private int clampHour(int h) { return Math.max(0, Math.min(23, h)); }

    private Map<String, Object> skipped(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SKIPPED");
        out.put("reason", reason);
        return out;
    }

    private Cfg loadConfig() {
        try {
            return jdbc.queryForObject(
                "SELECT enabled, window_start_hour, window_end_hour, tables_csv, last_run_date " +
                "FROM db_maintenance_config WHERE id = 1",
                (rs, n) -> {
                    Cfg c = new Cfg();
                    c.enabled = rs.getBoolean("enabled");
                    c.startHour = rs.getInt("window_start_hour");
                    c.endHour = rs.getInt("window_end_hour");
                    String csv = rs.getString("tables_csv");
                    c.tables = (csv == null || csv.isBlank()) ? null
                        : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    java.sql.Date d = rs.getDate("last_run_date");
                    c.lastRunDate = d != null ? d.toLocalDate() : null;
                    return c;
                });
        } catch (Exception e) {
            // Config row missing (e.g. migration not yet applied) — fall back to safe defaults.
            Cfg c = new Cfg();
            c.enabled = false; // don't run until config exists
            return c;
        }
    }

    private static class Cfg {
        boolean enabled = true;
        int startHour = 2;
        int endHour = 5;
        List<String> tables = null;
        LocalDate lastRunDate = null;
    }
}
