package com.acquira.common.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cross-pod eviction signal for the in-process report caches.
 *
 * The caches (ReportCacheConfig) live in each JVM, but the writers that
 * invalidate them (ingest jobs, backfill, bulk migration, merchant dedup) run
 * in the batch pod while the readers run in core and pdf. A local clear in
 * batch never reaches them, so every eviction also bumps a stamp in
 * {@code report_cache_version} ({@link #signal}) and every JVM polls that
 * table and drops its own caches when a stamp moves — the same idea as
 * {@code rbac_version} in PermissionEvaluator.
 *
 * tenant_id 0 means "all tenants / unknown" and triggers a full clear; any
 * other row clears only the entries that may depend on that tenant
 * (TenantScopedCache#evictTenant), mirroring ReportCache#evict, and warms that
 * tenant first.
 *
 * Degrades to the old single-JVM behaviour if the table is missing (migration
 * V2026_10_03_01 not applied): the local clear still happens, only the
 * cross-pod signal is lost, and that is logged once.
 */
@Service
public class ReportCacheSync {

    private static final Logger log = LoggerFactory.getLogger(ReportCacheSync.class);

    private static final int ALL_TENANTS = 0;

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final ObjectProvider<ReportCacheWarmup> warmup;
    private final long pollMs;

    /** Last stamp this JVM has acted on, per tenant row. */
    private final Map<Integer, Long> seen = new ConcurrentHashMap<>();
    private volatile boolean primed;
    private volatile boolean unavailableLogged;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "report-cache-sync");
        t.setDaemon(true);
        return t;
    });

    public ReportCacheSync(JdbcTemplate jdbc, CacheManager cacheManager,
            ObjectProvider<ReportCacheWarmup> warmup,
            @Value("${acquira.report-cache.sync.poll-ms:5000}") long pollMs) {
        this.jdbc = jdbc;
        this.cacheManager = cacheManager;
        this.warmup = warmup;
        this.pollMs = pollMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (pollMs <= 0) return;
        poller.scheduleWithFixedDelay(this::poll, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        poller.shutdownNow();
    }

    /**
     * Tell the other JVMs the report caches are stale. Call AFTER the local
     * clear. Never throws — a lost signal must not fail the write that
     * triggered it.
     */
    public void signal(Long tenantId) {
        int key = tenantId == null ? ALL_TENANTS : tenantId.intValue();
        try {
            Long version = jdbc.queryForObject(
                    "INSERT INTO report_cache_version (tenant_id, version, updated_at) " +
                    "VALUES (?, 1, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (tenant_id) DO UPDATE SET version = report_cache_version.version + 1, " +
                    "updated_at = CURRENT_TIMESTAMP RETURNING version",
                    Long.class, key);
            if (version == null) return;
            // Record our own bump so the poller doesn't clear a second time.
            // Only when it is exactly the next stamp: a bigger jump means
            // another JVM bumped in between, and that one we must still act on.
            if (version == 1L) {
                seen.putIfAbsent(key, version);
            } else {
                seen.computeIfPresent(key, (k, old) -> version == old + 1 ? version : old);
            }
        } catch (Exception e) {
            logUnavailable("signal", e);
        }
    }

    /** Package-visible for tests; normally driven by the poller thread. */
    void poll() {
        try {
            Map<Integer, Long> current = new HashMap<>();
            jdbc.query("SELECT tenant_id, version FROM report_cache_version",
                    rs -> { current.put(rs.getInt(1), rs.getLong(2)); });
            unavailableLogged = false;
            if (!primed) {
                seen.putAll(current);
                primed = true;
                return;
            }
            boolean all = false;
            java.util.List<Long> tenants = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Long> e : current.entrySet()) {
                if (Objects.equals(seen.put(e.getKey(), e.getValue()), e.getValue())) continue;
                if (e.getKey() == ALL_TENANTS) all = true;
                else tenants.add(e.getKey().longValue());
            }
            if (!all && tenants.isEmpty()) return;

            if (all) ReportCache.clearAll(cacheManager);
            else for (Long t : tenants) ReportCache.clearTenant(cacheManager, t);
            log.info("Report caches cleared: evicted by another instance (tenants {})",
                    all ? "all" : tenants);
            ReportCacheWarmup w = warmup.getIfAvailable();
            if (w == null) return;
            if (all) w.requestWarm("remote evict");
            for (Long t : tenants) w.requestWarm("remote evict", t);
        } catch (Throwable t) {
            // Never let an exception escape: scheduleWithFixedDelay would
            // silently cancel every future run.
            logUnavailable("poll", t);
        }
    }

    private void logUnavailable(String op, Throwable t) {
        if (unavailableLogged) {
            log.debug("Report cache sync {} failed: {}", op, t.toString());
            return;
        }
        unavailableLogged = true;
        log.warn("Report cache sync {} failed — cross-instance cache eviction is OFF until this recovers "
                + "(is migration V2026_10_03_01 applied?): {}", op, t.toString());
    }
}
