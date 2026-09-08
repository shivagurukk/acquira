package com.acquira.core.controller;

import com.acquira.common.model.AuditLog;
import com.acquira.common.repository.AuditLogRepository;
import com.acquira.common.config.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AuditLog>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("eventTime").descending());
        Specification<AuditLog> spec = buildSpec(search, category, action, username, startDate, endDate);

        return ResponseEntity.ok(auditLogRepository.findAll(spec, pageRequest));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        Specification<AuditLog> spec = buildSpec(search, category, action, username, startDate, endDate);
        List<AuditLog> logs = auditLogRepository.findAll(spec, Sort.by("eventTime").descending());

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(bos)) {

            writer.println(
                    "Timestamp,Username,Category,Action,Endpoint,StatusCode,IP Address,Duration (ms),User Agent,Details");

            for (AuditLog log : logs) {
                writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,\"%s\"%n",
                        log.getEventTime(),
                        escape(log.getUsername()),
                        escape(log.getCategory()),
                        escape(log.getActionType()),
                        escape(log.getEndpoint()),
                        log.getStatusCode(),
                        escape(log.getIpAddress()),
                        log.getDuration(),
                        escape(log.getUserAgent()),
                        escape(log.getDetails()));
            }
            writer.flush();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.csv")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(bos.toByteArray());

        } catch (Exception e) {
            throw new RuntimeException("Export failed", e);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        // Tenant-isolation fix: scope the "today" count to the active tenant for
        // bank admins; super-admins get the platform-wide count.
        final boolean superAdmin = isSuperAdmin();
        final Long tenantId = TenantContext.getCurrentTenant();
        Specification<AuditLog> todaySpec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.greaterThanOrEqualTo(root.get("eventTime"), startOfDay));
            if (!superAdmin) {
                if (tenantId == null) {
                    preds.add(cb.disjunction());
                } else {
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                }
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        long totalToday = auditLogRepository.count(todaySpec);

        // Errors today: 4xx/5xx responses. Rows without a status (descriptive
        // action rows that never got an HTTP outcome) are simply not errors.
        Specification<AuditLog> errorSpec = todaySpec.and(
                (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("statusCode"), 400));
        long errorsToday = auditLogRepository.count(errorSpec);

        // Distinct usernames seen today, same tenant scoping as above. A
        // non-super-admin with no resolvable tenant sees nothing, matching the
        // list/export behaviour rather than leaking a platform-wide number.
        long activeUsers;
        if (superAdmin) {
            activeUsers = auditLogRepository.countActiveUsersSince(startOfDay);
        } else if (tenantId == null) {
            activeUsers = 0;
        } else {
            activeUsers = auditLogRepository.countActiveUsersSinceForTenant(startOfDay, tenantId);
        }

        double errorRate = totalToday == 0 ? 0.0 : (errorsToday * 100.0) / totalToday;

        // These three were previously hardcoded to 0 in the response (and the
        // remaining two were never sent at all), so three of the four tiles on
        // the Audit Logs screen always read zero regardless of activity.
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalToday", totalToday);
        stats.put("errors", errorsToday);
        stats.put("activeUsers", activeUsers);
        stats.put("errorRate", Math.round(errorRate * 10.0) / 10.0);
        return ResponseEntity.ok(stats);
    }

    private Specification<AuditLog> buildSpec(String search, String category, String action, String username,
            String startDate, String endDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Tenant-isolation fix: a bank admin only sees their active tenant's
            // audit trail. A super-admin sees everything (no tenant predicate).
            // Previously the audit log was unscoped, exposing every tenant's
            // activity (and CSV export) to any bank admin.
            if (!isSuperAdmin()) {
                Long tenantId = TenantContext.getCurrentTenant();
                if (tenantId == null) {
                    // No resolvable tenant for a non-super-admin → return nothing
                    // rather than everything.
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(cb.equal(root.get("tenantId"), tenantId));
                }
            }

            if (StringUtils.hasText(search)) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("details")), pattern),
                        cb.like(cb.lower(root.get("endpoint")), pattern),
                        cb.like(cb.lower(root.get("username")), pattern)));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("actionType"), action));
            }
            if (StringUtils.hasText(username)) {
                predicates.add(cb.equal(root.get("username"), username));
            }
            if (StringUtils.hasText(startDate)) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTime"),
                        LocalDateTime.parse(startDate, DateTimeFormatter.ISO_DATE_TIME)));
            }
            if (StringUtils.hasText(endDate)) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTime"),
                        LocalDateTime.parse(endDate, DateTimeFormatter.ISO_DATE_TIME)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
