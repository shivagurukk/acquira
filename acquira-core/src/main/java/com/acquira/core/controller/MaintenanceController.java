package com.acquira.core.controller;

import com.acquira.core.service.DatabaseMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin control for the nightly database-maintenance job.
 * Status is readable by admins; changing config / triggering a manual run is
 * super-admin only, since it touches global DB health and can run VACUUM.
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class MaintenanceController {

    private final DatabaseMaintenanceService maintenanceService;

    public MaintenanceController(DatabaseMaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(maintenanceService.status());
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> body) {
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
        Integer startHour = body.get("windowStartHour") instanceof Number ? ((Number) body.get("windowStartHour")).intValue() : null;
        Integer endHour = body.get("windowEndHour") instanceof Number ? ((Number) body.get("windowEndHour")).intValue() : null;
        // tables: accept a CSV string or a JSON array
        String tablesCsv = null;
        Object t = body.get("tables");
        if (t instanceof String) tablesCsv = (String) t;
        else if (t instanceof java.util.List) tablesCsv = String.join(",", ((java.util.List<?>) t).stream().map(String::valueOf).toList());

        maintenanceService.updateConfig(enabled, startHour, endHour, tablesCsv);
        return ResponseEntity.ok(maintenanceService.status());
    }

    /**
     * Trigger a maintenance pass now.
     *  - force=true (default): ignore the window + already-ran-today guard.
     *  - overrideBatch=true: run even if a batch job is active (default false — refuse if busy).
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> run(@RequestParam(defaultValue = "true") boolean force,
                                 @RequestParam(defaultValue = "false") boolean overrideBatch) {
        return ResponseEntity.ok(maintenanceService.runNow(force, overrideBatch));
    }
}
