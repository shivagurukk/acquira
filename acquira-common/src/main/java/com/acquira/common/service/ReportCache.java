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

    public ReportCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
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
     * Drop every report cache. Call after any write that changes what a cached
     * report would show but does not go through the batch jobs' eviction
     * listener — e.g. admin edits to sales hierarchy/targets. Full clear, not
     * key-targeted: these writes are rare and repopulation is one query per
     * screen, so precision isn't worth the bug surface.
     */
    public void evictAll() {
        for (String name : com.acquira.common.config.ReportCacheConfig.ALL_CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
    }
}
