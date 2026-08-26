package com.acquira.batch.controller;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.acquira.common.config.TenantContext;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/batch")
public class BatchProgressController {

    private final JobExplorer jobExplorer;

    // Known step counts per job, for a friendly "step N of M" display in the UI.
    // Keep in step with the job definitions in TransactionJobConfig /
    // MerchantMasterJobConfig — a stale count here renders as "step 12 of 9".
    private static final Map<String, Integer> TOTAL_STEPS = Map.of(
        "transactionLoadJob", 12,
        "dbPullTransactionJob", 9,
        "merchantMasterJob", 5);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public BatchProgressController(JobExplorer jobExplorer) {
        this.jobExplorer = jobExplorer;
    }

    // ── Tenant-isolation helpers ────────────────────────────────────────────
    // Mirror BatchJobController: batch jobs carry their tenant in JobParameters
    // ("tenantId"). /api/batch/** is gated to ADMIN/SUPER_ADMIN, but without the
    // filter below a bank admin sees (and live-streams) every tenant's job
    // progress and data-quality details. Super-admins keep the cross-tenant view.
    //
    // NOTE: SSE streams capture the caller's tenant ONCE at subscribe time (the
    // scheduled callback runs on a pool thread with no SecurityContext /
    // TenantContext of its own), so resolve and close over the values before
    // scheduling — see streamJobProgress / streamAllJobs.
    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private static Long tenantOf(JobExecution exec) {
        try {
            return exec.getJobParameters().getLong("tenantId");
        } catch (Exception e) {
            return null;
        }
    }

    /** Request-thread check (SecurityContext available). */
    private boolean canView(JobExecution exec) {
        if (isSuperAdmin()) return true;
        return canView(exec, TenantContext.getCurrentTenant(), false);
    }

    /**
     * Pool-thread-safe check: pass the tenant + super-admin flag captured on the
     * request thread, since the SSE callback has no SecurityContext of its own.
     */
    private static boolean canView(JobExecution exec, Long viewerTenant, boolean viewerIsSuperAdmin) {
        if (viewerIsSuperAdmin) return true;
        Long jobTenant = tenantOf(exec);
        return viewerTenant != null && viewerTenant.equals(jobTenant);
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
        // TENANT ISOLATION: don't reveal another tenant's job. Same "not found"
        // shape so the endpoint doesn't confirm cross-tenant existence.
        if (!canView(jobExec)) {
            return Map.of("error", "Job not found", "executionId", executionId);
        }
        return buildProgressPayload(jobExec);
    }

    /**
     * SSE: Stream progress for a SINGLE job execution.
     * GET /api/batch/jobs/{id}/progress (text/event-stream)
     *
     * Lifetime caps:
     *   - SseEmitter timeout: 30 minutes (was 5 min, too short for big uploads).
     *   - Hard scheduler-level kill at the same 30-min mark, in case the emitter
     *     dies but Spring doesn't fire onCompletion/onError (rare but observed
     *     with mid-stream client disconnects). Without this the scheduler kept
     *     trying to send to a dead emitter, swallowed the IOException via the
     *     "transient error" catch, and leaked work forever.
     *   - Terminates as soon as the job reaches COMPLETED/FAILED/STOPPED/ABANDONED.
     */
    @GetMapping(value = "/jobs/{executionId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobProgress(@PathVariable Long executionId) {
        final long maxStreamMs = 30L * 60_000L;
        SseEmitter emitter = new SseEmitter(maxStreamMs);
        final long startedAt = System.currentTimeMillis();
        // Holder so the lambda can self-cancel cleanly.
        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];

        // TENANT ISOLATION: capture the viewer's identity on the REQUEST thread.
        // The scheduled callback runs on a pool thread that has no SecurityContext
        // or TenantContext, so resolving it inside the lambda would always come
        // back null/anonymous and either over- or under-restrict.
        final boolean viewerIsSuperAdmin = isSuperAdmin();
        final Long viewerTenant = TenantContext.getCurrentTenant();

        futureHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (System.currentTimeMillis() - startedAt > maxStreamMs) {
                    try {
                        emitter.send(SseEmitter.event().name("timeout")
                                .data("{\"error\":\"Stream timed out after 30 minutes\"}"));
                    } catch (Exception ignored) { /* emitter may already be dead */ }
                    emitter.complete();
                    if (futureHolder[0] != null) futureHolder[0].cancel(false);
                    return;
                }

                JobExecution jobExec = jobExplorer.getJobExecution(executionId);
                if (jobExec == null) {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\":\"Job not found\"}"));
                    emitter.complete();
                    if (futureHolder[0] != null) futureHolder[0].cancel(false);
                    return;
                }

                // TENANT ISOLATION: terminate the stream if this job isn't the
                // viewer's. Same "Job not found" shape so it doesn't confirm
                // cross-tenant existence.
                if (!canView(jobExec, viewerTenant, viewerIsSuperAdmin)) {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\":\"Job not found\"}"));
                    emitter.complete();
                    if (futureHolder[0] != null) futureHolder[0].cancel(false);
                    return;
                }

                Map<String, Object> progress = buildProgressPayload(jobExec);
                emitter.send(SseEmitter.event().name("progress").data(progress));

                String status = jobExec.getStatus().toString();
                if ("COMPLETED".equals(status) || "FAILED".equals(status)
                        || "STOPPED".equals(status) || "ABANDONED".equals(status)) {
                    emitter.send(SseEmitter.event().name("complete").data(progress));
                    emitter.complete();
                    if (futureHolder[0] != null) futureHolder[0].cancel(false);
                }
            } catch (IOException e) {
                // Real I/O failure on the emitter — client gone. Bail.
                emitter.completeWithError(e);
                if (futureHolder[0] != null) futureHolder[0].cancel(false);
            } catch (Exception e) {
                // Transient backend issue — keep the stream alive, the next tick
                // will try again. (Stale ResultSet, momentary RDS flake, etc.)
            }
        }, 0, 2, TimeUnit.SECONDS);

        final ScheduledFuture<?> future = futureHolder[0];
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));

        return emitter;
    }

    /**
     * SSE: Stream status of ALL running jobs.
     * GET /api/batch/jobs/live (text/event-stream)
     * For the batch monitoring dashboard.
     *
     * FIX: previously hardcoded to "transactionLoadJob", missing every merchant
     * master run. Now iterates every registered job name.
     */
    @GetMapping(value = "/jobs/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAllJobs() {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 min timeout
        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];

        // TENANT ISOLATION: capture viewer identity on the REQUEST thread (the
        // scheduled callback has no SecurityContext/TenantContext). Non-super-
        // admins only see their own tenant's jobs in the live dashboard.
        final boolean viewerIsSuperAdmin = isSuperAdmin();
        final Long viewerTenant = TenantContext.getCurrentTenant();

        futureHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Map<String, Object>> allJobs = new ArrayList<>();
                List<String> jobNames = jobExplorer.getJobNames();
                if (jobNames == null) jobNames = java.util.Collections.emptyList();

                for (String jobName : jobNames) {
                    // Get running jobs for this name
                    try {
                        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
                        for (JobExecution exec : running) {
                            if (!canView(exec, viewerTenant, viewerIsSuperAdmin)) continue;
                            allJobs.add(buildProgressPayload(exec));
                        }
                    } catch (Exception e) {
                        // Job name might not exist yet — keep iterating
                    }

                    // Also include recently completed jobs (last 5 minutes)
                    try {
                        long count = jobExplorer.getJobInstanceCount(jobName);
                        if (count > 0) {
                            var instances = jobExplorer.getJobInstances(jobName,
                                    (int) Math.max(0, count - 5), 5);
                            for (var instance : instances) {
                                var executions = jobExplorer.getJobExecutions(instance);
                                for (var exec : executions) {
                                    if (!canView(exec, viewerTenant, viewerIsSuperAdmin)) continue;
                                    if (exec.getEndTime() != null) {
                                        Duration sinceEnd = Duration.between(exec.getEndTime(), LocalDateTime.now());
                                        if (sinceEnd.toMinutes() <= 5) {
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
                        // Ignore one job name's failure; keep streaming the rest
                    }
                }

                emitter.send(SseEmitter.event().name("jobs").data(allJobs));
            } catch (IOException e) {
                emitter.completeWithError(e);
                if (futureHolder[0] != null) futureHolder[0].cancel(false);
            } catch (Exception e) {
                // Transient — don't kill
            }
        }, 0, 3, TimeUnit.SECONDS);

        final ScheduledFuture<?> future = futureHolder[0];
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

        // Current / latest step, so the UI can show exactly what's running
        // (e.g. "Building summaries") instead of inferring it from a percentage.
        StepExecution current = null;
        for (StepExecution step : jobExec.getStepExecutions()) {
            if (current == null) { current = step; continue; }
            boolean stepStarted = "STARTED".equals(step.getStatus().toString());
            boolean curStarted = "STARTED".equals(current.getStatus().toString());
            if (stepStarted != curStarted) {
                if (stepStarted) current = step;          // prefer the running step
            } else {
                LocalDateTime a = step.getStartTime(), b = current.getStartTime();
                if (a != null && (b == null || a.isAfter(b))) current = step;  // else latest
            }
        }
        if (current != null) {
            payload.put("currentStep", current.getStepName());
            payload.put("currentStepStatus", current.getStatus().toString());
        }
        payload.put("stepNumber", jobExec.getStepExecutions().size());
        Integer totalSteps = TOTAL_STEPS.get(jobExec.getJobInstance().getJobName());
        if (totalSteps != null) payload.put("totalSteps", totalSteps);

        // Data-quality summary (written by stagingToFactTasklet into the job context).
        // Surfaced so the upload UI can show a post-upload banner.
        try {
            org.springframework.batch.item.ExecutionContext ctx = jobExec.getExecutionContext();
            if (ctx.containsKey("dq.total")) {
                Map<String, Object> dq = new HashMap<>();
                int dqTotal = ctx.getInt("dq.total", 0);
                int dqUnresolved = ctx.getInt("dq.unresolvedMerchant", 0);
                dq.put("total", dqTotal);
                dq.put("unresolvedMerchant", dqUnresolved);
                dq.put("dates", ctx.getInt("dq.dates", 0));
                String schemes = ctx.getString("dq.schemes", "");
                dq.put("schemes", schemes.isEmpty()
                    ? java.util.Collections.emptyList()
                    : java.util.Arrays.asList(schemes.split(",")));
                dq.put("loadMode", ctx.getString("dq.loadMode", "REPLACE"));
                payload.put("dataQuality", dq);
            }
        } catch (Exception ignored) { /* context not populated yet — fine */ }

        // Sales-agent reassignment summary (written by upsertDimensionsTasklet).
        // Tells the uploader how many merchants changed hands and flags the rows
        // whose agent could not be applied — conflicting duplicates — or that named
        // an agent code the tenant has never seen.
        try {
            org.springframework.batch.item.ExecutionContext ctx = jobExec.getExecutionContext();
            if (ctx.containsKey("reassign.count")) {
                Map<String, Object> ra = new HashMap<>();
                ra.put("reassigned", ctx.getInt("reassign.count", 0));
                ra.put("conflicts", ctx.getInt("reassign.conflicts", 0));
                ra.put("unknownAgents", ctx.getInt("reassign.unknownAgents", 0));
                String warnings = ctx.getString("reassign.warnings", "");
                ra.put("warnings", warnings.isEmpty()
                    ? java.util.Collections.emptyList()
                    : java.util.Arrays.asList(warnings.split(" \\| ")));
                payload.put("salesReassignment", ra);
            }
        } catch (Exception ignored) { /* context not populated yet — fine */ }

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
