package com.acquira.batch.job;

import com.acquira.common.service.ReportCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
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

    private final ReportCache reportCache;

    public CacheEvictionJobListener(ReportCache reportCache) {
        this.reportCache = reportCache;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        // Through ReportCache, not the CacheManager: the dashboards are served
        // by other instances (core / pdf pods) whose caches a local clear here
        // would never reach.
        Long tenantId = jobExecution.getJobParameters().getLong("tenantId");
        reportCache.evict("job " + jobExecution.getJobInstance().getJobName(), tenantId);
        log.info("Report caches cleared after job {} ({})",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus());
    }
}
