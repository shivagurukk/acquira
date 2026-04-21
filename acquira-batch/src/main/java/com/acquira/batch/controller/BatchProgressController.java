package com.acquira.batch.controller;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/batch")
public class BatchProgressController {

    private final JobExplorer jobExplorer;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public BatchProgressController(JobExplorer jobExplorer) {
        this.jobExplorer = jobExplorer;
    }

    /**
     * REST: Get progress snapshot for a job (used by frontend polling).
     * GET /api/batch/jobs/{id}/status
     */
    @GetMapping("/jobs/{executionId}/status")
    public Map<String, Object> getJobStatus(@PathVariable Long executionId) {
        JobExecution jobExec = jobExplorer.getJobExecution(executionId);
        if (jobExec == null) {
            return Map.of("error", "Job not found", "executionId", executionId);
        }
        return buildProgressPayload(jobExec);
    }

    /**
     * SSE: Stream progress for a SINGLE job execution.
     * GET /api/batch/jobs/{id}/progress (text/event-stream)
     */
    @GetMapping(value = "/jobs/{executionId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobProgress(@PathVariable Long executionId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                JobExecution jobExec = jobExplorer.getJobExecution(executionId);
                if (jobExec == null) {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\":\"Job not found\"}"));
                    emitter.complete();
                    return;
                }

                Map<String, Object> progress = buildProgressPayload(jobExec);
                emitter.send(SseEmitter.event().name("progress").data(progress));

                String status = jobExec.getStatus().toString();
                if ("COMPLETED".equals(status) || "FAILED".equals(status)
                        || "STOPPED".equals(status) || "ABANDONED".equals(status)) {
                    emitter.send(SseEmitter.event().name("complete").data(progress));
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                // Transient error — don't kill the stream
            }
        }, 0, 2, TimeUnit.SECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));

        return emitter;
    }

    /**
     * SSE: Stream status of ALL running jobs.
     * GET /api/batch/jobs/live (text/event-stream)
     * For the batch monitoring dashboard.
     */
    @GetMapping(value = "/jobs/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAllJobs() {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 min timeout

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Map<String, Object>> allJobs = new ArrayList<>();

                // Get running jobs
                try {
                    Set<JobExecution> running = jobExplorer.findRunningJobExecutions("transactionLoadJob");
                    for (JobExecution exec : running) {
                        allJobs.add(buildProgressPayload(exec));
                    }
                } catch (Exception e) {
                    // Job name might not exist yet
                }

                // Also include recently completed jobs (last 5 minutes)
                try {
                    long count = jobExplorer.getJobInstanceCount("transactionLoadJob");
                    if (count > 0) {
                        var instances = jobExplorer.getJobInstances("transactionLoadJob",
                                (int) Math.max(0, count - 5), 5);
                        for (var instance : instances) {
                            var executions = jobExplorer.getJobExecutions(instance);
                            for (var exec : executions) {
                                if (exec.getEndTime() != null) {
                                    Duration sinceEnd = Duration.between(exec.getEndTime(), LocalDateTime.now());
                                    if (sinceEnd.toMinutes() <= 5) {
                                        // Don't duplicate if already in running
                                        boolean alreadyAdded = allJobs.stream()
                                                .anyMatch(j -> j.get("executionId").equals(exec.getId()));
                                        if (!alreadyAdded) {
                                            allJobs.add(buildProgressPayload(exec));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }

                emitter.send(SseEmitter.event().name("jobs").data(allJobs));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                // Transient — don't kill
            }
        }, 0, 3, TimeUnit.SECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));

        return emitter;
    }

    private Map<String, Object> buildProgressPayload(JobExecution jobExec) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("executionId", jobExec.getId());
        payload.put("jobName", jobExec.getJobInstance().getJobName());
        payload.put("status", jobExec.getStatus().toString());
        payload.put("exitCode", jobExec.getExitStatus().getExitCode());
        payload.put("startTime", jobExec.getStartTime() != null ? jobExec.getStartTime().toString() : null);
        payload.put("endTime", jobExec.getEndTime() != null ? jobExec.getEndTime().toString() : null);

        // Aggregate step metrics
        int readCount = 0, writeCount = 0, skipCount = 0;
        for (StepExecution step : jobExec.getStepExecutions()) {
            readCount += step.getReadCount();
            writeCount += step.getWriteCount();
            skipCount += step.getSkipCount();
        }
        payload.put("readCount", readCount);
        payload.put("writeCount", writeCount);
        payload.put("skipCount", skipCount);

        // Total rows from execution context
        long totalRows = 0;
        try {
            totalRows = jobExec.getExecutionContext().getLong("totalReqRows", 0);
        } catch (Exception e) { /* ignore */ }
        payload.put("totalRows", totalRows);

        // Progress percentage
        if (totalRows > 0) {
            double pct = Math.min(100.0, ((double) readCount / totalRows) * 100.0);
            payload.put("progress", Math.round(pct));
        } else {
            payload.put("progress", readCount > 0 ? -1 : 0); // -1 = indeterminate
        }

        // Estimated time remaining
        if (totalRows > 0 && readCount > 0 && jobExec.getStartTime() != null) {
            long elapsedMs = Duration.between(jobExec.getStartTime(), LocalDateTime.now()).toMillis();
            if (elapsedMs > 0) {
                double rowsPerMs = (double) readCount / elapsedMs;
                long remainingRows = totalRows - readCount;
                long estimatedMs = (long) (remainingRows / rowsPerMs);
                payload.put("estimatedSecondsRemaining", estimatedMs / 1000);
            }
        }

        return payload;
    }
}
