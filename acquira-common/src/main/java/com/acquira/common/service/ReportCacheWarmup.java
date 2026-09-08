package com.acquira.common.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pre-computes each dashboard's DEFAULT first-load view into the report caches
 * so the first person to open a page after a cache clear gets a warm hit
 * instead of paying the full aggregation cost.
 *
 * WHY: every ingest (any tenant's) clears ALL report caches for ALL tenants,
 * and a pod restart starts cold — so the first open of each executive page
 * each morning ran multi-second aggregations even though every endpoint is
 * cached. Warmers close that gap by recomputing the default views right after
 * the clear, before anyone asks.
 *
 * CONTRACT for warmers:
 *  - Each warmer receives a tenantId and must populate the SAME cache keys the
 *    live endpoint would build for that page's default first request. A warmer
 *    whose key drifts from the endpoint is harmless (the entry is simply never
 *    read) but useless — keep them next to the endpoint code they mirror.
 *  - Warmers run on a single background thread with TenantContext set to the
 *    tenant being warmed and NO authenticated user — they must never rely on
 *    the SecurityContext, and must only compute data the page itself shows.
 *  - Throwing is fine (logged, skipped); a tenant with no data must no-op.
 *
 * Registration happens in each controller's @PostConstruct, so the warm set
 * lives beside the endpoints it mirrors. Apps that scan acquira-common but
 * register no warmers (batch-only contexts) get a silent no-op.
 *
 * Triggers: application startup, and every report-cache clear
 * (CacheEvictionJobListener / BulkMigrationService / BackfillIngestionService /
 * ReportCache.evictAll). Runs are COALESCED: a request landing mid-run marks a
 * rerun instead of queueing — the rerun recomputes on the post-clear state, so
 * nothing warms stale.
 */
@Service
public class ReportCacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(ReportCacheWarmup.class);

    /** One page's default-view warmer. */
    public interface Warmer {
        void warm(Long tenantId);
    }

    private record Registration(String name, Warmer warmer) {}

    private final List<Registration> warmers = new CopyOnWriteArrayList<>();
    private final TenantRepository tenantRepository;
    private final boolean enabled;

    /** Single daemon thread: warming self-throttles to one query at a time. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-cache-warmup");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);

    public ReportCacheWarmup(TenantRepository tenantRepository,
            @Value("${acquira.report-cache.warmup.enabled:true}") boolean enabled) {
        this.tenantRepository = tenantRepository;
        this.enabled = enabled;
    }

    public void register(String name, Warmer warmer) {
        warmers.add(new Registration(name, warmer));
    }

    /** Schedule a warm pass; safe from any thread, never blocks the caller. */
    public void requestWarm(String reason) {
        if (!enabled || warmers.isEmpty()) return;
        rerunRequested.set(true);
        log.info("Report cache warmup requested ({})", reason);
        scheduleDrainIfIdle();
    }

    private void scheduleDrainIfIdle() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::drain);
        }
    }

    private void drain() {
        try {
            while (rerunRequested.getAndSet(false)) {
                runOnce();
            }
        } finally {
            running.set(false);
            // A request that landed between getAndSet(false) and running.set(false)
            // found running=true and scheduled nothing — pick it up here.
            if (rerunRequested.get()) scheduleDrainIfIdle();
        }
    }

    private void runOnce() {
        long start = System.currentTimeMillis();
        int ok = 0, failed = 0;
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (Exception e) {
            log.warn("Report cache warmup skipped: could not list tenants", e);
            return;
        }
        for (Tenant tenant : tenants) {
            if (tenant.getTenantId() == null) continue;
            if (tenant.getStatus() != null && !"ACTIVE".equalsIgnoreCase(tenant.getStatus())) continue;
            for (Registration reg : warmers) {
                // A mid-run clear means whatever we warmed so far is gone —
                // abandon this pass; the drain loop starts a fresh one.
                if (rerunRequested.get()) {
                    log.info("Report cache warmup restarting: caches cleared mid-run");
                    return;
                }
                try {
                    TenantContext.setCurrentTenant(tenant.getTenantId());
                    reg.warmer().warm(tenant.getTenantId());
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Report cache warmer '{}' failed for tenant {}: {}",
                            reg.name(), tenant.getTenantId(), e.toString());
                } finally {
                    TenantContext.clear();
                }
            }
        }
        log.info("Report cache warmup done: {} warmed, {} failed, {} tenants, {} ms",
                ok, failed, tenants.size(), System.currentTimeMillis() - start);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        requestWarm("startup");
    }
}
