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
 * Freshness contract: entries expire after the configured TTL AND every write
 * path clears the caches on completion — the ingest jobs via
 * CacheEvictionJobListener (transaction + merchant master + db-pull), and the
 * non-job writers (BulkMigrationService, BackfillIngestionService) via their
 * own evictReportCaches(). Post-ingest staleness is therefore bounded by
 * seconds; the TTL is only the backstop for out-of-band writes (manual SQL),
 * which is why it can safely be hours rather than minutes.
 *
 * SIZING: entry counts are deliberately small because report payloads are
 * large (multi-MB JSON per the gzip note in application.properties). The caps
 * below bound worst-case heap, not typical usage — with 6 tenants the working
 * set is far smaller than the caps.
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

    /**
     * Backstop TTL in minutes (6h). Safe at hours because every write path
     * evicts on completion — see the freshness contract above. Drop it back
     * down (e.g. 10) if a new write path is added before its eviction is.
     *
     * DEPLOYMENT DEPENDENCY: that contract holds only while ONE JVM serves web
     * AND runs the batch jobs (replicas: 1 in deploy/k8s/05-core.yaml). The
     * planned web/worker pod split breaks it — the eviction fires in the worker
     * JVM while web replicas keep serving stale data for the full TTL. Before
     * that split lands, either drop this back to ~10 or move the cache to a
     * shared backend (Redis) / add a cross-pod eviction signal.
     */
    private static final long TTL_MINUTES = 360;

    /**
     * reportData holds the big payloads — the properties file's gzip note calls
     * report JSON "multiple MB", and the core pod's k8s memory limit is 4Gi
     * shared with Chromium and batch. Worst case 128 × ~5MB ≈ 640MB, but the
     * typical executive payload is well under 1MB so the realistic ceiling is
     * far lower. Raised from 64 when ReportCacheWarmup landed: warming ~10
     * default views × 6 tenants seeds ~60 entries by itself, and at 64 any
     * real navigation immediately evicted them (LRU churn made the warm pass
     * pointless).
     */
    private static final long REPORT_DATA_MAX_ENTRIES = 128;

    /** Lookups/bounds entries are small (id lists, date pairs). */
    private static final long LOOKUP_MAX_ENTRIES = 2000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // setCacheNames BEFORE registerCustomCache: it sets dynamic=false, so a
        // typo'd cache name in a future @Cacheable gets null (a loud no-op)
        // instead of silently creating an UNBOUNDED, never-expiring cache that
        // no eviction path (CacheEvictionJobListener, ReportCache.evictAll)
        // knows about. The custom registrations below then replace the default
        // caches this call creates for the three known names.
        manager.setCacheNames(ALL_CACHES);
        manager.registerCustomCache(CACHE_REPORT_DATA, Caffeine.newBuilder()
                .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(REPORT_DATA_MAX_ENTRIES)
                .recordStats()
                .build());
        manager.registerCustomCache(CACHE_LOOKUPS, Caffeine.newBuilder()
                .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(LOOKUP_MAX_ENTRIES)
                .recordStats()
                .build());
        manager.registerCustomCache(CACHE_DATA_BOUNDS, Caffeine.newBuilder()
                .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(LOOKUP_MAX_ENTRIES)
                .recordStats()
                .build());
        return manager;
    }
}
