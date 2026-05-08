package com.acquira.batch.controller;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batch/jobs")
public class BatchJobController {

    private final JobExplorer jobExplorer;

    public BatchJobController(JobExplorer jobExplorer) {
        this.jobExplorer = jobExplorer;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getJobHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        // FIX: previously hardcoded to "transactionLoadJob", which made the
        // Batch Monitoring screen blind to every merchant master upload. We now
        // pull instances for ALL registered job names so the operator sees
        // BOTH transaction and merchant runs in chronological order.
        try {
            java.util.List<String> jobNames = jobExplorer.getJobNames();
            if (jobNames == null || jobNames.isEmpty()) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }

            // Collect a recent window for every job name, then sort by start
            // time and slice. We over-fetch by `(page+1)*size` per job, then
            // sort + page in-memory — cheap because Spring Batch metadata is
            // small and indexed, and the alternative (a single union query)
            // requires a custom DAO.
            int perJob = Math.max(size * (page + 1), size);
            java.util.List<Map<String, Object>> history = new java.util.ArrayList<>();

            for (String jobName : jobNames) {
                long count = jobExplorer.getJobInstanceCount(jobName);
                int start = (int) Math.max(0, count - perJob);
                List<JobInstance> instances = jobExplorer.getJobInstances(jobName, start, perJob);
                for (JobInstance instance : instances) {
                    JobExecution lastExec = jobExplorer.getJobExecutions(instance).stream()
                            .reduce((first, second) -> second).orElse(null);
                    if (lastExec == null) continue;

                    Map<String, Object> map = new HashMap<>();
                    map.put("jobName", instance.getJobName());
                    map.put("jobId", instance.getInstanceId());
                    map.put("executionId", lastExec.getId());
                    map.put("status", lastExec.getStatus().toString());
                    map.put("startTime", lastExec.getStartTime() != null ? lastExec.getStartTime().toString() : "");
                    map.put("endTime", lastExec.getEndTime() != null ? lastExec.getEndTime().toString() : "");
                    map.put("exitCode", lastExec.getExitStatus().getExitCode());
                    history.add(map);
                }
            }

            // Sort newest first by startTime (string sort works because ISO-8601),
            // then page.
            history.sort((a, b) -> {
                String ta = String.valueOf(a.getOrDefault("startTime", ""));
                String tb = String.valueOf(b.getOrDefault("startTime", ""));
                return tb.compareTo(ta);
            });

            int from = Math.min(page * size, history.size());
            int to   = Math.min(from + size, history.size());
            return ResponseEntity.ok(history.subList(from, to));
        } catch (org.springframework.batch.core.launch.NoSuchJobException e) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJobStatus(@PathVariable Long id) {
        JobExecution jobExecution = jobExplorer.getJobExecution(id);

        if (jobExecution == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobExecution.getId());
        response.put("jobName", jobExecution.getJobInstance().getJobName());
        response.put("status", jobExecution.getStatus().toString());
        response.put("exitCode", jobExecution.getExitStatus().getExitCode());
        response.put("startTime", jobExecution.getStartTime());
        response.put("endTime", jobExecution.getEndTime());

        // Step Details
        int readCount = 0;
        int writeCount = 0;
        int skipCount = 0;

        for (org.springframework.batch.core.StepExecution step : jobExecution.getStepExecutions()) {
            readCount += step.getReadCount();
            writeCount += step.getWriteCount();
            skipCount += step.getSkipCount();
        }

        response.put("readCount", readCount);
        response.put("writeCount", writeCount);
        response.put("skipCount", skipCount);

        // Calculate Progress
        long totalRows = 0;
        try {
            totalRows = jobExecution.getExecutionContext().getLong("totalReqRows", 0);
        } catch (Exception e) {
            // ignore
        }

        response.put("totalRows", totalRows);
        if (totalRows > 0) {
            // Cap at 100% just in case
            double progress = Math.min(100.0, ((double) readCount / totalRows) * 100.0);
            response.put("progress", Math.round(progress)); // Integer percentage
        } else {
            response.put("progress", 0);
        }

        return ResponseEntity.ok(response);
    }
}
