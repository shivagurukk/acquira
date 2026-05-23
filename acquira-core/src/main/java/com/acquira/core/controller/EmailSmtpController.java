package com.acquira.core.controller;

import com.acquira.common.model.EmailSmtpConfig;
import com.acquira.core.service.SmtpConfigService;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin API for per-tenant SMTP configuration (the SMTP Settings page).
 *
 * Routes match the existing frontend (frontend/src/pages/SmtpSettings.jsx):
 *   GET    /api/email/smtp-configs            list
 *   POST   /api/email/smtp-configs            create
 *   PUT    /api/email/smtp-configs/{id}       update
 *   DELETE /api/email/smtp-configs/{id}       delete
 *   POST   /api/email/smtp-configs/{id}/activate
 *   POST   /api/email/smtp-configs/{id}/test
 *
 * The SMTP password is encrypted at rest and is NEVER returned by these
 * endpoints \u2014 the service blanks it (sends back an "__UNCHANGED__" sentinel).
 * On update, the client echoes that sentinel to mean "keep the stored password".
 *
 * Admin-only. /api/email/** is not in the public URL allow-list; @PreAuthorize
 * adds method-level enforcement.
 */
@RestController
@RequestMapping("/api/email/smtp-configs")
@RequiredArgsConstructor
@Slf4j
public class EmailSmtpController {

    private final SmtpConfigService smtpConfigService;
    private final TenantService tenantService;

    private Long tenantId() {
        Long t = tenantService.getCurrentTenantId();
        if (t == null) {
            throw new IllegalStateException("No tenant context for the current user");
        }
        return t;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<EmailSmtpConfig>> list() {
        return ResponseEntity.ok(smtpConfigService.listForTenant(tenantId()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> create(@RequestBody EmailSmtpConfig body) {
        try {
            return ResponseEntity.ok(smtpConfigService.create(tenantId(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody EmailSmtpConfig body) {
        try {
            return ResponseEntity.ok(smtpConfigService.update(tenantId(), id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            smtpConfigService.delete(tenantId(), id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(smtpConfigService.activate(tenantId(), id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> test(@PathVariable Long id) {
        try {
            SmtpConfigService.TestResult result = smtpConfigService.testConnection(tenantId(), id);
            return ResponseEntity.ok(Map.of(
                    "status", result.status(),
                    "message", result.message()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED", "message", e.getMessage()));
        }
    }
}
