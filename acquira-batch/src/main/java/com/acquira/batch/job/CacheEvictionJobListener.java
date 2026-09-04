package com.acquira.batch.job;

import com.acquira.common.config.ReportCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Clears the report caches when an ingest job finishes, so dashboards reflect
 * newly landed data within one request rather than waiting out the cache TTL.
 *
 * Runs on ANY terminal status, not just COMPLETED: a job that failed after
 * stagingToFactStep has already changed fact/summary data, so serving
 * pre-ingest cached numbers would be wrong exactly when accuracy matters most.
 * Clearing on failure costs one extra cold load; serving stale data costs
 * correctness.
 */
@Component
public class CacheEvictionJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionJobListener.class);

    private final CacheManager cacheManager;
    private final org.springframework.beans.factory.ObjectProvider<com.acquira.common.service.ReportCacheWarmup> warmup;

    public CacheEvictionJobListener(CacheManager cacheManager,
            org.springframework.beans.factory.ObjectProvider<com.acquira.common.service.ReportCacheWarmup> warmup) {
        this.cacheManager = cacheManager;
        this.warmup = warmup;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        for (String name : ReportCacheConfig.ALL_CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
        log.info("Report caches cleared after job {} ({})",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus());
        warmup.ifAvailable(w -> w.requestWarm(
                "job " + jobExecution.getJobInstance().getJobName()));
    }
}
