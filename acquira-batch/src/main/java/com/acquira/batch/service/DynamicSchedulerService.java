package com.acquira.batch.service;

import com.acquira.common.model.IntegrationRunLog;
import com.acquira.common.model.IntegrationSchedule;
import com.acquira.common.repository.IntegrationScheduleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages dynamic cron schedules at runtime.
 * Each IntegrationSchedule gets its own cron task that can be started/stopped independently.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@EnableScheduling
public class DynamicSchedulerService {

    private final IntegrationScheduleRepository scheduleRepo;
    private final IntegrationPullService pullService;
    private final TaskScheduler taskScheduler;

    // Track active cron tasks so we can cancel/restart them
    private final Map<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    /**
     * On startup, load all enabled schedules and register them.
     */
    @PostConstruct
    public void initializeSchedules() {
        log.info("[Scheduler] Loading enabled schedules...");
        try {
            var schedules = scheduleRepo.findByIsEnabledTrue();
            for (IntegrationSchedule schedule : schedules) {
                registerSchedule(schedule);
            }
            log.info("[Scheduler] Loaded {} active schedules", schedules.size());
        } catch (Exception e) {
            log.warn("[Scheduler] Could not load schedules (tables may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * Register a cron schedule. Replaces any existing schedule for the same ID.
     */
    public void registerSchedule(IntegrationSchedule schedule) {
        // Cancel existing if any
        cancelSchedule(schedule.getId());

        if (!Boolean.TRUE.equals(schedule.getIsEnabled())) {
            log.info("[Scheduler] Schedule #{} '{}' is disabled, skipping", schedule.getId(), schedule.getReport().getName());
            return;
        }

        try {
            TimeZone tz = schedule.getTimezone() != null
                    ? TimeZone.getTimeZone(schedule.getTimezone())
                    : TimeZone.getDefault();

            CronTrigger trigger = new CronTrigger(schedule.getCronExpression(), tz);

            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                try {
                    log.info("[Scheduler] Firing scheduled pull for report '{}' (schedule #{})",
                            schedule.getReport().getName(), schedule.getId());
                    pullService.executePull(
                            schedule.getReport(),
                            schedule,
                            IntegrationRunLog.TriggerType.SCHEDULED,
                            null, null, // date range — null means "use default (current month)"
                            1 // first attempt
                    );
                } catch (Exception e) {
                    log.error("[Scheduler] Error in scheduled pull for #{}: {}", schedule.getId(), e.getMessage(), e);
                }
            }, trigger);

            activeTasks.put(schedule.getId(), future);
            log.info("[Scheduler] Registered schedule #{} — cron: '{}' tz: {} for report '{}'",
                    schedule.getId(), schedule.getCronExpression(), tz.getID(), schedule.getReport().getName());

        } catch (Exception e) {
            log.error("[Scheduler] Failed to register schedule #{}: {}", schedule.getId(), e.getMessage());
        }
    }

    /**
     * Cancel a running schedule.
     */
    public void cancelSchedule(Long scheduleId) {
        ScheduledFuture<?> existing = activeTasks.remove(scheduleId);
        if (existing != null) {
            existing.cancel(false);
            log.info("[Scheduler] Cancelled schedule #{}", scheduleId);
        }
    }

    /**
     * Reload a specific schedule (after update).
     */
    public void reloadSchedule(IntegrationSchedule schedule) {
        cancelSchedule(schedule.getId());
        registerSchedule(schedule);
    }

    /**
     * Reload all schedules (after bulk changes).
     */
    public void reloadAll() {
        // Cancel all
        activeTasks.forEach((id, future) -> future.cancel(false));
        activeTasks.clear();

        // Re-register enabled ones
        initializeSchedules();
    }

    /**
     * Get count of active schedules.
     */
    public int getActiveCount() {
        return activeTasks.size();
    }

    /**
     * Trigger a manual "Run Now" — bypasses cron, runs immediately.
     */
    public void runNow(IntegrationSchedule schedule, LocalDate dateFrom, LocalDate dateTo) {
        log.info("[Scheduler] Manual Run Now triggered for report '{}' (schedule #{})",
                schedule.getReport().getName(), schedule.getId());
        pullService.executePull(
                schedule.getReport(),
                schedule,
                IntegrationRunLog.TriggerType.MANUAL,
                dateFrom, dateTo,
                1
        );
    }
}
