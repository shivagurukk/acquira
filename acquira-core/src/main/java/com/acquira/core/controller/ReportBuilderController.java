package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.ReportTemplate;
import com.acquira.common.model.ReportSchedule;
import com.acquira.common.repository.ReportTemplateRepository;
import com.acquira.common.repository.ReportScheduleRepository;
import com.acquira.core.service.ReportExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("@menuAccess.canAccess('/explorer')")
public class ReportBuilderController {

    private final ReportTemplateRepository templateRepo;
    private final ReportScheduleRepository scheduleRepo;
    private final ReportExportService exportService;
    private final JdbcTemplate jdbcTemplate;

    // ═══════════════════════════════════════════════════════════
    //  TEMPLATES
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/templates")
    public ResponseEntity<List<ReportTemplate>> getTemplates() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(templateRepo.findByTenantIdOrderByNameAsc(tenantId));
    }

    @PostMapping("/templates")
    public ResponseEntity<?> createTemplate(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        ReportTemplate t = new ReportTemplate();
        t.setTenantId(tenantId);
        t.setUserId(Long.valueOf(body.getOrDefault("userId", 0).toString()));
        t.setName((String) body.get("name"));
        t.setDescription((String) body.get("description"));
        t.setConfigJson((String) body.get("configJson"));
        t.setIsShared(body.get("isShared") != null ? (Boolean) body.get("isShared") : false);
        t.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(templateRepo.save(t));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return templateRepo.findById(id)
            .filter(t -> t.getTenantId().equals(tenantId))
            .map(t -> {
                if (body.containsKey("name")) t.setName((String) body.get("name"));
                if (body.containsKey("description")) t.setDescription((String) body.get("description"));
                if (body.containsKey("configJson")) t.setConfigJson((String) body.get("configJson"));
                if (body.containsKey("isShared")) t.setIsShared((Boolean) body.get("isShared"));
                t.setUpdatedAt(LocalDateTime.now());
                return ResponseEntity.ok(templateRepo.save(t));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return templateRepo.findById(id)
            .filter(t -> t.getTenantId().equals(tenantId))
            .map(t -> { templateRepo.delete(t); return ResponseEntity.ok(Map.of("message", "Deleted")); })
            .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    //  EXPORTS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(@RequestBody Map<String, Object> body) {
        String reportName = (String) body.getOrDefault("reportName", "Report");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");

        byte[] bytes = exportService.exportExcel(rows, reportName, body);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportName.replaceAll("[^a-zA-Z0-9]", "_") + ".xlsx\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes);
    }

    @PostMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestBody Map<String, Object> body) {
        String reportName = (String) body.getOrDefault("reportName", "Report");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");

        byte[] bytes = exportService.exportCsv(rows);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportName.replaceAll("[^a-zA-Z0-9]", "_") + ".csv\"")
            .contentType(new MediaType("text", "csv"))
            .body(bytes);
    }

    // ═══════════════════════════════════════════════════════════
    //  SCHEDULES
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/schedules")
    public ResponseEntity<List<ReportSchedule>> getSchedules() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @PostMapping("/templates/{templateId}/schedule")
    public ResponseEntity<?> createSchedule(@PathVariable Long templateId, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        // Same tenant guard as update/delete above — without it a foreign template
        // gets attached to the schedule and its config_json leaks in the response.
        ReportTemplate template = templateRepo.findById(templateId)
            .filter(t -> t.getTenantId().equals(tenantId))
            .orElseThrow(() -> new RuntimeException("Template not found"));

        ReportSchedule s = new ReportSchedule();
        s.setTenantId(tenantId);
        s.setTemplate(template);
        s.setCronExpression((String) body.get("cronExpression"));
        s.setFrequencyLabel((String) body.get("frequencyLabel"));
        s.setTimezone(body.getOrDefault("timezone", "UTC").toString());
        s.setDeliveryMethod(ReportSchedule.DeliveryMethod.valueOf(
            body.getOrDefault("deliveryMethod", "EMAIL").toString()));
        s.setRecipientEmails((String) body.get("recipientEmails"));
        s.setExportFormat(ReportSchedule.ExportFormat.valueOf(
            body.getOrDefault("exportFormat", "EXCEL").toString()));
        s.setIsEnabled(body.get("isEnabled") != null ? (Boolean) body.get("isEnabled") : true);
        s.setCreatedAt(LocalDateTime.now());

        return ResponseEntity.ok(scheduleRepo.save(s));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return scheduleRepo.findById(id)
            .filter(s -> s.getTenantId().equals(tenantId))
            .map(s -> { scheduleRepo.delete(s); return ResponseEntity.ok(Map.of("message", "Deleted")); })
            .orElse(ResponseEntity.notFound().build());
    }
}
