package com.acquira.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
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
}
