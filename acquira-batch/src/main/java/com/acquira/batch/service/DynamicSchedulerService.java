package com.acquira.batch.service;

import com.acquira.common.model.IntegrationRunLog;
import com.acquira.common.model.IntegrationSchedule;
import com.acquira.common.repository.IntegrationScheduleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /**
     * How many days back a SCHEDULED pull re-pulls, ending at "today" in the
     * schedule's own timezone.
     *
     * WHY THIS EXISTS: scheduled runs used to pass (null, null), which made
     * IntegrationPullService.buildParams default to month-to-date
     * (1st-of-current-month .. today). That leaves the LAST DAY OF EVERY MONTH
     * permanently partial: the 02:00 run on the 31st loads ~2 hours of that day,
     * and on the 1st the window resets to the new month, so the 31st is never
     * pulled again. A rolling lookback always spans the month boundary, so the
     * previous month's final day is re-pulled (and, because transaction loads are
     * REPLACE-by-staged-date, corrected) on the next run.
     *
     * Sizing: 2 days is the minimum that covers the boundary; the default of 3
     * gives one run of slack for a missed/failed night. Raise it for sources that
     * restate history; lower it for very high-volume tenants — the window must
     * stay under IntegrationPullService.MAX_PULL_ROWS (2M rows), because a pull
     * that hits the cap now fails rather than loading a partial extract.
     */
    @Value("${acquira.integration.lookback-days:3}")
    private int lookbackDays;

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
            ZoneId zone = resolveZone(schedule);
            TimeZone tz = TimeZone.getTimeZone(zone);

            CronTrigger trigger = new CronTrigger(schedule.getCronExpression(), tz);

            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                try {
                    // The window is computed HERE, at fire time, in the SAME zone the
                    // cron fired in. Computing it inside the pull service used the JVM
                    // default zone instead: a 02:00 Asia/Dubai schedule fires at 22:00
                    // UTC the PREVIOUS day, so on a UTC server "today" — and on the 1st
                    // of a month, the whole month — was resolved for the wrong date.
                    PullWindow window = rollingWindow(Clock.system(zone), lookbackDays, null, null);

                    log.info("[Scheduler] Firing scheduled pull for report '{}' (schedule #{}) — window {}..{} ({})",
                            schedule.getReport().getName(), schedule.getId(),
                            window.from(), window.to(), zone.getId());
                    pullService.executePull(
                            schedule.getReport(),
                            schedule,
                            IntegrationRunLog.TriggerType.SCHEDULED,
                            window.from(), window.to(),
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

    /** The inclusive date range a pull covers. */
    public record PullWindow(LocalDate from, LocalDate to) {}

    /**
     * Resolve the window a pull should cover: the caller's explicit dates where
     * given, otherwise a rolling {@code [today - lookbackDays, today]} where
     * "today" comes from {@code clock} (which carries the schedule's zone).
     *
     * Static and Clock-driven so the month-boundary and timezone behaviour is
     * unit-testable — this is the logic whose month-to-date predecessor left the
     * last day of every month permanently partial.
     */
    public static PullWindow rollingWindow(Clock clock, int lookbackDays,
                                           LocalDate explicitFrom, LocalDate explicitTo) {
        LocalDate to = explicitTo != null ? explicitTo : LocalDate.now(clock);
        LocalDate from = explicitFrom != null ? explicitFrom : to.minusDays(Math.max(0, lookbackDays));
        return new PullWindow(from, to);
    }

    /**
     * Resolve the schedule's timezone, falling back to the JVM default.
     *
     * Uses ZoneId.of (which THROWS on an unknown id) rather than
     * TimeZone.getTimeZone (which silently returns GMT). Both the cron fire time
     * and the pull's date window now depend on this value, so a typo like
     * "Asia/Dubain" quietly shifting a bank's daily pull by 4 hours — and its
     * window by a day — must not pass unnoticed.
     */
    private ZoneId resolveZone(IntegrationSchedule schedule) {
        String configured = schedule.getTimezone();
        if (configured == null || configured.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(configured.trim());
        } catch (Exception e) {
            log.warn("[Scheduler] Schedule #{} has an unrecognised timezone '{}' — falling back to {}. "
                    + "Fix integration_schedule.timezone; the cron time AND the pull's date window depend on it.",
                    schedule.getId(), configured, ZoneId.systemDefault());
            return ZoneId.systemDefault();
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
        // When the operator supplies no dates, run exactly the window this
        // schedule would run on its own — same lookback, same timezone — rather
        // than a different default. "Run Now" then means "fire tonight's pull
        // now", which is what an operator clicking it during an incident expects.
        ZoneId zone = resolveZone(schedule);
        PullWindow window = rollingWindow(Clock.system(zone), lookbackDays, dateFrom, dateTo);

        log.info("[Scheduler] Manual Run Now triggered for report '{}' (schedule #{}) — window {}..{} ({})",
                schedule.getReport().getName(), schedule.getId(),
                window.from(), window.to(), zone.getId());
        pullService.executePull(
                schedule.getReport(),
                schedule,
                IntegrationRunLog.TriggerType.MANUAL,
                window.from(), window.to(),
                1
        );
    }
}
