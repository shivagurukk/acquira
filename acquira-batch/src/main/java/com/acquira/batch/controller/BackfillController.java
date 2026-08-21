package com.acquira.batch.controller;

import com.acquira.batch.service.BackfillIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// SUPER_ADMIN only: the request body carries an arbitrary tenantId and the job
// destructively rebuilds that tenant's staging/fact/summary tables — a bank
// ADMIN must not be able to point it at another tenant. Mirrors the guard on
// MigrationController's equivalent arbitrary-tenant endpoints.
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
@RestController
@RequestMapping("/api/batch/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillIngestionService backfillService;

    @PostMapping
    public ResponseEntity<String> startBackfill(@RequestBody BackfillIngestionService.BackfillRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            return ResponseEntity.badRequest().body("Start and End dates are required");
        }
        if (request.getSourceQueries() == null || request.getSourceQueries().isEmpty()) {
            return ResponseEntity.badRequest().body("At least one source query is required");
        }

        backfillService.startBackfill(request);
        return ResponseEntity
                .ok("Backfill started for range: " + request.getStartDate() + " to " + request.getEndDate());
    }

    @GetMapping("/status")
    public ResponseEntity<BackfillIngestionService.BackfillProgress> getStatus() {
        return ResponseEntity.ok(backfillService.getProgress());
    }
}
