package com.acquira.common.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Explicit-key wrapper over the report caches (ReportCacheConfig) for code
 * whose queries live inline in controllers, where @Cacheable would force a
 * method extraction to a separate bean just to get a proxy.
 *
 * Uses Cache.get(key, valueLoader), which on Caffeine computes under the
 * entry's lock — concurrent requests for the same key after an eviction wait
 * for one DB load instead of stampeding Postgres (all tenants hit cold caches
 * at once right after an ingest clears them).
 *
 * KEY CONTRACT (same as ReportCacheConfig): the key MUST start with the
 * tenant id and include every request parameter that changes the result.
 * Never build a key from TenantContext inside the supplier — resolve the
 * tenant first, put it in the key.
 *
 * NESTING CONTRACT: the supplier must NEVER write to the same cache it is
 * being computed into — no reportCache.get on the same cacheName, and no call
 * into a method that is @Cacheable on that cacheName. The computation runs
 * inside Caffeine's per-entry compute on a ConcurrentHashMap, and a nested
 * write to the same map is a recursive update: IllegalStateException when the
 * two keys share a hash bin, a stall otherwise. (This is why
 * BusinessAnalyticsController.getFilterOptions is NOT wrapped — the
 * repository method underneath it is already @Cacheable on CACHE_LOOKUPS.)
 */
@Service
public class ReportCache {

    private final CacheManager cacheManager;
    /** Provider, not a direct dependency: ReportCacheWarmup itself must be
     *  constructible without ReportCache, and test slices may omit it. */
    private final org.springframework.beans.factory.ObjectProvider<ReportCacheWarmup> warmup;
    /** Cross-pod signal; a provider for the same reason as warmup. */
    private final org.springframework.beans.factory.ObjectProvider<ReportCacheSync> sync;

    public ReportCache(CacheManager cacheManager,
            org.springframework.beans.factory.ObjectProvider<ReportCacheWarmup> warmup,
            org.springframework.beans.factory.ObjectProvider<ReportCacheSync> sync) {
        this.cacheManager = cacheManager;
        this.warmup = warmup;
        this.sync = sync;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Supplier<T> loader) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) return loader.get();
        try {
            return (T) cache.get(key, loader::get);
        } catch (Cache.ValueRetrievalException e) {
            // Spring wraps every loader exception in ValueRetrievalException.
            // Rethrow the original so the global exception handler still maps
            // it by type (AccessDeniedException -> 403, IllegalArgumentException
            // -> 400, ...) instead of everything degrading to a generic 500.
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Drop every report cache for every tenant. For writes whose tenant is
     * unknown or that change data shared by all tenants (reference tables,
     * bulk migration). Prefer {@link #evict(String, Long)} when the tenant is
     * known.
     */
    public void evictAll() {
        evict("evictAll", null);
    }

    /**
     * The ONE eviction entry point: drops this JVM's entries that may depend on
     * {@code tenantId}'s data (see TenantScopedCache#evictTenant), warms that
     * tenant first, and signals every other instance (core / pdf / batch pods)
     * to do the same via {@link ReportCacheSync}. Other tenants stay warm.
     * {@code tenantId == null} means "unknown / all tenants" — a full clear.
     * Clearing the CacheManager directly only reaches the local JVM.
     */
    public void evict(String reason, Long tenantId) {
        if (tenantId == null) clearAll(cacheManager);
        else clearTenant(cacheManager, tenantId);
        warmup.ifAvailable(w -> w.requestWarm(reason, tenantId));
        sync.ifAvailable(s -> s.signal(tenantId));
    }

    /** Local clear only — no warm, no cross-instance signal. */
    static void clearAll(CacheManager cacheManager) {
        for (String name : com.acquira.common.config.ReportCacheConfig.ALL_CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
    }

    /**
     * Local, one-tenant clear — no warm, no signal. A cache that is not a
     * TenantScopedCache (a test double, a future manager) is cleared whole:
     * over-evicting costs a cold load, under-evicting serves stale data.
     */
    static void clearTenant(CacheManager cacheManager, Long tenantId) {
        for (String name : com.acquira.common.config.ReportCacheConfig.ALL_CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache instanceof com.acquira.common.config.TenantScopedCache scoped) scoped.evictTenant(tenantId);
            else if (cache != null) cache.clear();
        }
    }
}
