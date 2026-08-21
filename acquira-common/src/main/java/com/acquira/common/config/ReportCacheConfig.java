package com.acquira.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-process report cache (Caffeine).
 *
 * Report data mutates only when a batch ingest completes (at most daily), yet
 * every dashboard open was re-running the same aggregations against Postgres.
 * This tier serves repeat opens from memory.
 *
 * Freshness contract: entries expire after {@link #TTL_MINUTES} minutes AND the
 * batch jobs clear all caches on completion (CacheEvictionJobListener in
 * acquira-batch), so post-ingest staleness is bounded by seconds, not the TTL.
 * The TTL is the backstop for out-of-band writes (bulk migration, day
 * correction, manual SQL).
 *
 * SAFETY: every @Cacheable key on these caches MUST include the tenant id.
 * Methods that resolve the tenant from TenantContext instead of a parameter
 * must not be annotated directly — wrap them in a method that takes tenantId.
 */
@Configuration
@EnableCaching
public class ReportCacheConfig {

    /** Filter dropdowns, available-years, explorer filter values. */
    public static final String CACHE_LOOKUPS = "reportLookups";
    /** Per-tenant earliest/latest data dates — gates first fetch of most report pages. */
    public static final String CACHE_DATA_BOUNDS = "dataBounds";
    /** Aggregated report/dashboard payloads. */
    public static final String CACHE_REPORT_DATA = "reportData";

    public static final List<String> ALL_CACHES =
            List.of(CACHE_LOOKUPS, CACHE_DATA_BOUNDS, CACHE_REPORT_DATA);

    private static final long TTL_MINUTES = 10;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(ALL_CACHES);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .recordStats());
        return manager;
    }
}
