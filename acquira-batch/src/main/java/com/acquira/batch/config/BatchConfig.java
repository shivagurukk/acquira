package com.acquira.batch.config;

import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableAsync
public class BatchConfig {

    /**
     * TaskScheduler for dynamic cron jobs (Integration DB Pull).
     * Used by DynamicSchedulerService to register/cancel schedules at runtime.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5); // Max 5 concurrent scheduled pulls
        scheduler.setThreadNamePrefix("integration-cron-");
        scheduler.setErrorHandler(t ->
            org.slf4j.LoggerFactory.getLogger("IntegrationScheduler")
                .error("Scheduled task error: {}", t.getMessage(), t));
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Dedicated executor for Spring Batch job launches.
     *
     * Without this bean, Spring Batch's auto-configured JobLauncher uses
     * SyncTaskExecutor, which means jobLauncher.run() blocks the calling
     * thread (the HTTP request thread for uploads) until the entire job
     * finishes. For a 250 MB transaction file that means the upload request
     * stays open for several minutes, the user sees "stuck" with no progress
     * after the "File scanned in Nms" log line, and many browsers eventually
     * time out the request even though the job runs to completion.
     *
     * With this bean wired into the Primary JobLauncher below, the call
     * returns within milliseconds carrying a JobExecution in STARTING state,
     * and the actual work happens on a background thread. The frontend can
     * then poll /api/batch/jobs/{id}/status (or use the SSE
     * /api/batch/jobs/{id}/progress stream) for live progress.
     *
     * Pool sizing rationale:
     *  - core size 2: typical day-to-day — one user upload, one scheduled pull
     *  - max  size 6: bursts of concurrent uploads from multiple users; a
     *    Spring Batch job already uses several threads internally for parallel
     *    steps, so we don't want too many concurrent JOBS or we exhaust the
     *    DB connection pool (Hikari max=30).
     *  - queue capacity 25: most overflow scenarios are short bursts; queueing
     *    is preferable to rejecting since the user has already uploaded the
     *    file and a rejection would lose work.
     */
    @Bean("batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("batch-job-");
        // Wait for in-flight jobs on shutdown so we don't lose half-processed uploads.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    /**
     * Override the auto-configured JobLauncher with an async one backed by
     * batchTaskExecutor above. @Primary so any @Autowired JobLauncher
     * (FileUploadService, etc.) gets this one rather than the default.
     *
     * Behaviour change:
     *  - jobLauncher.run(...) returns IMMEDIATELY after persisting JobExecution
     *    in STARTING state. Status transitions to STARTED/COMPLETED on the
     *    background thread.
     *  - Existing callers expecting COMPLETED on return WILL see STARTING.
     *    All known callers in this codebase use the returned JobExecution.getId()
     *    only — they don't inspect the status — so this is a safe change.
     *  - To poll for completion, use JobExplorer.getJobExecution(id) or hit
     *    /api/batch/jobs/{id}/status.
     */
    @Bean("jobLauncher")
    @Primary
    public org.springframework.batch.core.launch.JobLauncher jobLauncher(
            JobRepository jobRepository,
            @org.springframework.beans.factory.annotation.Qualifier("batchTaskExecutor")
            TaskExecutor taskExecutor) throws Exception {
        // Bean is named exactly "jobLauncher" to win against Spring Boot's
        // auto-configured bean of the same name. The previous version was named
        // "asyncJobLauncher" and the auto-configured one (with SyncTaskExecutor)
        // was still being injected into FileUploadService. Confirmed via stack
        // trace showing job execution on http-nio-8081-exec-N thread.
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(taskExecutor);
        launcher.afterPropertiesSet();
        // Visible-at-startup proof that THIS bean is being used. If you see this
        // line in the log on app start, async is wired. If you don't, Spring Boot
        // auto-configuration is winning over our @Primary.
        org.slf4j.LoggerFactory.getLogger(BatchConfig.class).warn(
            "✓✓✓ Async JobLauncher initialized with batchTaskExecutor (corePool=2, maxPool=6). Batch jobs will run on background threads.");
        return launcher;
    }
}
