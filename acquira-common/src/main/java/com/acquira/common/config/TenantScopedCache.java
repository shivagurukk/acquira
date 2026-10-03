package com.acquira.common.config;

import org.springframework.cache.caffeine.CaffeineCache;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Report cache that files every entry under the tenant scope of the thread
 * that wrote it, so one tenant's ingest can drop that tenant's entries and
 * leave the other tenants warm.
 *
 * Every key the callers pass (ReportCache string keys, @Cacheable SpEL keys)
 * is wrapped in a {@link ScopedKey} carrying {@link TenantContext#peekScope()}
 * — the sorted set of tenants the request can read. Lookups wrap the same way,
 * so a hit also requires the same scope: a key that forgot to include the
 * tenant still cannot serve one tenant's payload to another.
 *
 * {@link #evictTenant} removes every entry whose scope CONTAINS the tenant —
 * that covers multi-tenant (group) views that read the evicted tenant's rows
 * from another tenant's context — plus every entry written with no tenant in
 * context (empty scope), since nothing says which tenant those depend on.
 *
 * Extends CaffeineCache (rather than decorating Cache) so getNativeCache()
 * still returns the Caffeine cache for metrics binding and stats.
 */
public class TenantScopedCache extends CaffeineCache {

    /** Cache key as stored: caller key + the tenant scope it was computed under. */
    record ScopedKey(List<Long> scope, Object key) {}

    public TenantScopedCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache,
                             boolean allowNullValues) {
        super(name, cache, allowNullValues);
    }

    private static Object scoped(Object key) {
        return new ScopedKey(TenantContext.peekScope(), key);
    }

    @Override
    protected Object lookup(Object key) {
        return super.lookup(scoped(key));
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return super.get(scoped(key), valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        super.put(scoped(key), value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return super.putIfAbsent(scoped(key), value);
    }

    @Override
    public void evict(Object key) {
        super.evict(scoped(key));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return super.evictIfPresent(scoped(key));
    }

    /**
     * Drop every entry that may depend on {@code tenantId}'s data: entries
     * whose scope contains it, and entries written with no tenant in context.
     */
    public void evictTenant(Long tenantId) {
        getNativeCache().asMap().keySet().removeIf(k ->
                !(k instanceof ScopedKey sk) || sk.scope().isEmpty() || sk.scope().contains(tenantId));
    }
}
