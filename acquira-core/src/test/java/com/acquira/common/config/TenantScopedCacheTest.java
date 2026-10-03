package com.acquira.common.config;

import com.acquira.common.service.ReportCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-tenant eviction: one tenant's ingest must drop that tenant's entries
 * (and anything that may read its data) while other tenants stay warm.
 */
class TenantScopedCacheTest {

    private final CacheManager manager = new ReportCacheConfig().cacheManager();
    private final Cache cache = manager.getCache(ReportCacheConfig.CACHE_REPORT_DATA);

    private final ReportCache reportCache = new ReportCache(manager,
            new StaticListableBeanFactory().getBeanProvider(com.acquira.common.service.ReportCacheWarmup.class),
            new StaticListableBeanFactory().getBeanProvider(com.acquira.common.service.ReportCacheSync.class));

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void putAs(Long tenant, List<Long> visible, String key) {
        TenantContext.clear();
        if (tenant != null) TenantContext.setCurrentTenant(tenant);
        if (visible != null) TenantContext.setVisibleTenants(visible);
        cache.put(key, "v");
        TenantContext.clear();
    }

    private boolean presentAs(Long tenant, List<Long> visible, String key) {
        TenantContext.clear();
        if (tenant != null) TenantContext.setCurrentTenant(tenant);
        if (visible != null) TenantContext.setVisibleTenants(visible);
        try {
            return cache.get(key) != null;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void evictingOneTenantKeepsOthersWarm() {
        putAs(1L, null, "k:1");
        putAs(2L, null, "k:2");

        reportCache.evict("test", 1L);

        assertFalse(presentAs(1L, null, "k:1"));
        assertTrue(presentAs(2L, null, "k:2"));
    }

    @Test
    void groupViewReadingTheEvictedTenantIsDropped() {
        putAs(2L, List.of(1L, 2L), "group");

        reportCache.evict("test", 1L);

        assertFalse(presentAs(2L, List.of(1L, 2L), "group"));
    }

    @Test
    void entriesWrittenWithoutTenantAreAlwaysDropped() {
        putAs(null, null, "global");

        reportCache.evict("test", 5L);

        assertFalse(presentAs(null, null, "global"));
    }

    @Test
    void nullTenantClearsEverything() {
        putAs(1L, null, "k:1");
        putAs(2L, null, "k:2");

        reportCache.evict("test", null);

        assertFalse(presentAs(1L, null, "k:1"));
        assertFalse(presentAs(2L, null, "k:2"));
    }

    @Test
    void sameKeyUnderAnotherTenantIsAMiss() {
        // Defence in depth: a key that forgot the tenant id still can't leak.
        putAs(1L, null, "no-tenant-in-key");

        assertTrue(presentAs(1L, null, "no-tenant-in-key"));
        assertFalse(presentAs(2L, null, "no-tenant-in-key"));
    }

    @Test
    void reportCacheGetComputesOncePerTenantScope() {
        int[] loads = {0};
        TenantContext.setCurrentTenant(1L);
        reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, "x", () -> ++loads[0]);
        reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, "x", () -> ++loads[0]);
        assertEquals(1, loads[0]);
    }

    @Test
    void ifNoneMatchAcceptsWeakStrongAndLists() {
        assertTrue(ReportResponseEtagAdvice.matches("W/\"abc\"", "W/\"abc\""));
        assertTrue(ReportResponseEtagAdvice.matches("\"abc\"", "W/\"abc\""));
        assertTrue(ReportResponseEtagAdvice.matches("\"x\", W/\"abc\"", "W/\"abc\""));
        assertFalse(ReportResponseEtagAdvice.matches("W/\"abd\"", "W/\"abc\""));
        assertFalse(ReportResponseEtagAdvice.matches("*", "W/\"abc\""));
        assertFalse(ReportResponseEtagAdvice.matches(null, "W/\"abc\""));
    }
}
