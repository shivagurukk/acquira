package com.acquira.controller;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/batch/jobs")
@CrossOrigin(origins = "http://localhost:5173")
public class BatchJobController {

    private final JobExplorer jobExplorer;

    public BatchJobController(JobExplorer jobExplorer) {
        this.jobExplorer = jobExplorer;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getJobHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        try {
            long count = jobExplorer.getJobInstanceCount("transactionLoadJob");
            int start = (int) Math.max(0, count - ((page + 1) * size));

            List<JobInstance> instances = jobExplorer.getJobInstances("transactionLoadJob", start, size);

            List<Map<String, Object>> history = instances.stream()
                    .map(instance -> {
                        JobExecution lastExec = jobExplorer.getJobExecutions(instance).stream()
                                .reduce((first, second) -> second).orElse(null);

                        if (lastExec == null)
                            return null;

                        Map<String, Object> map = new HashMap<>();
                        map.put("jobName", instance.getJobName());
                        map.put("jobId", instance.getInstanceId());
                        map.put("executionId", lastExec.getId());
                        map.put("status", lastExec.getStatus().toString());
                        map.put("startTime", lastExec.getStartTime() != null ? lastExec.getStartTime().toString() : "");
                        map.put("endTime", lastExec.getEndTime() != null ? lastExec.getEndTime().toString() : "");
                        map.put("exitCode", lastExec.getExitStatus().getExitCode());
                        return map;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

            java.util.Collections.reverse(history);

            return ResponseEntity.ok(history);
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
