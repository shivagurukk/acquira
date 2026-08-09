package com.acquira.batch.controller;

import com.acquira.batch.service.InterchangeNormalizationService;
import com.acquira.common.config.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SUPER-ADMIN API for Interchange Fee Normalization.
 *
 * Apply OVERWRITES fact_transaction.interchange_fee for the chosen month and
 * rebuilds every summary table — all screens then show only the normalized
 * figures. Pre-normalization values survive only in the run/detail history.
 * Tenant is always the active one (X-Tenant-Id via TenantContext), never a
 * body parameter — same posture as /rebuild-summaries.
 */
@RestController
@RequestMapping("/api/admin/interchange-normalization")
public class InterchangeNormalizationController {

    private final InterchangeNormalizationService service;

    public InterchangeNormalizationController(InterchangeNormalizationService service) {
        this.service = service;
    }

    private Long tenantOr400() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("No active tenant. Switch into the tenant you want to normalize first.");
        }
        return tenantId;
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private static boolean validMonthKey(Integer mk) {
        return mk != null && mk >= 200001 && mk % 100 >= 1 && mk % 100 <= 12;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> summary(@RequestParam int year) {
        if (year < 2000 || year > 2100) {
            return ResponseEntity.badRequest().body(Map.of("error", "year must be a 4-digit year"));
        }
        return ResponseEntity.ok(service.summary(tenantOr400(), year));
    }

    /** Body: { "monthKey": 202601, "target": 100000.000 } */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body) {
        Integer monthKey = body.get("monthKey") == null ? null : Integer.valueOf(body.get("monthKey").toString());
        if (!validMonthKey(monthKey)) {
            return ResponseEntity.badRequest().body(Map.of("error", "monthKey must be YYYYMM (e.g. 202601)"));
        }
        BigDecimal target;
        try {
            target = new BigDecimal(String.valueOf(body.get("target")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "target must be a number"));
        }
        if (target.signum() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "target must not be negative"));
        }
        try {
            return ResponseEntity.ok(service.preview(tenantOr400(), monthKey, target, currentUser()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Body: { "runId": 1, "confirm": true } — irreversible except by re-running a new normalization. */
    @PostMapping("/apply")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body) {
        Object confirm = body.get("confirm");
        boolean confirmed = Boolean.TRUE.equals(confirm) || "true".equalsIgnoreCase(String.valueOf(confirm));
        if (!confirmed) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "This overwrites the month's interchange fees on every transaction. Resend with \"confirm\": true."));
        }
        if (body.get("runId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId is required"));
        }
        long runId = Long.parseLong(body.get("runId").toString());
        try {
            return ResponseEntity.ok(service.apply(tenantOr400(), runId, currentUser()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> runStatus(@PathVariable long runId) {
        try {
            return ResponseEntity.ok(service.runStatus(tenantOr400(), runId));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "Run not found for the active tenant"));
        }
    }

    @GetMapping("/runs/{runId}/details")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> runDetails(@PathVariable long runId) {
        try {
            return ResponseEntity.ok(service.runDetails(tenantOr400(), runId));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "Run not found for the active tenant"));
        }
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> history(@RequestParam(required = false) Integer monthKey) {
        if (monthKey != null && !validMonthKey(monthKey)) {
            return ResponseEntity.badRequest().body(Map.of("error", "monthKey must be YYYYMM"));
        }
        return ResponseEntity.ok(service.history(tenantOr400(), monthKey));
    }

    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> cancel(@PathVariable long runId) {
        try {
            service.cancel(tenantOr400(), runId);
            return ResponseEntity.ok(Map.of("status", "CANCELLED", "runId", runId));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
