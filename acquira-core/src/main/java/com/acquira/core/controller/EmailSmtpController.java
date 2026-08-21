package com.acquira.core.controller;

import com.acquira.common.model.EmailSmtpConfig;
import com.acquira.core.service.SmtpConfigService;
import com.acquira.common.config.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for per-tenant SMTP configuration (the SMTP Settings page).
 *
 * Routes match the existing frontend (frontend/src/pages/SmtpSettings.jsx):
 *   GET    /api/email/smtp-configs               list
 *   POST   /api/email/smtp-configs               create
 *   PUT    /api/email/smtp-configs/{id}          update
 *   DELETE /api/email/smtp-configs/{id}          delete
 *   POST   /api/email/smtp-configs/{id}/activate
 *   POST   /api/email/smtp-configs/{id}/test     test a SAVED config
 *   POST   /api/email/smtp-configs/test-config   test an UNSAVED config (pre-save)
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

    private Long tenantId() {
        // Tenant-isolation fix: use the header-aware TenantContext (set by
        // JwtRequestFilter from X-Tenant-Id) instead of TenantService
        // .getCurrentTenantId(), which always returned the user's DEFAULT
        // tenant and ignored tenant switching — a multi-tenant admin would
        // otherwise edit the wrong tenant's SMTP config after switching.
        Long t = TenantContext.getCurrentTenant();
        if (t == null) {
            throw new IllegalStateException("No tenant context for the current user");
        }
        return t;
    }

    /** Null-safe test result payload (Map.of throws NPE on a null message). */
    private static Map<String, Object> resultBody(String status, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status != null ? status : "FAILED");
        m.put("message", message != null ? message : "");
        return m;
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

    /**
     * Test a SAVED config. Always returns HTTP 200 with a {status,message} body —
     * never a bare 500 — so the UI shows the real SMTP error rather than the
     * GlobalExceptionHandler's generic "An unexpected error occurred." Any
     * unexpected throwable is caught, logged with full stack, and surfaced as a
     * FAILED result carrying the root-cause message.
     */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> test(@PathVariable Long id) {
        try {
            SmtpConfigService.TestResult result = smtpConfigService.testConnection(tenantId(), id);
            return ResponseEntity.ok(resultBody(result.status(), result.message()));
        } catch (Exception e) {
            log.error("[SMTP] /{}/test threw unexpectedly", id, e);
            return ResponseEntity.ok(resultBody("FAILED", rootMessage(e)));
        }
    }

    /**
     * Pre-save test: validate an UNSAVED config (straight from the form) without
     * persisting anything. The password in the body is the raw value the admin
     * typed; if it's blank or the "__UNCHANGED__" sentinel and an id is present,
     * the service uses the stored password for that id. Nothing is written.
     *
     * Always returns HTTP 200 with a {status,message} body. Catching every
     * throwable here is deliberate: a bare 500 would be rewritten by
     * GlobalExceptionHandler to a generic "An unexpected error occurred." and the
     * admin would never see the actual cause (535, cert error, deserialization,
     * decryption, etc.). We log the full stack server-side and return the real
     * root-cause message to the UI.
     */
    @PostMapping("/test-config")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> testConfig(@RequestBody EmailSmtpConfig body) {
        try {
            SmtpConfigService.TestResult result = smtpConfigService.testRawConfig(tenantId(), body);
            return ResponseEntity.ok(resultBody(result.status(), result.message()));
        } catch (Exception e) {
            log.error("[SMTP] /test-config threw unexpectedly (host={} username={})",
                    body != null ? body.getHost() : null,
                    body != null ? body.getUsername() : null, e);
            return ResponseEntity.ok(resultBody("FAILED", rootMessage(e)));
        }
    }

    /** Deepest cause message so nested MessagingException/AuthFailed surfaces to the UI. */
    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m != null ? m : c.getClass().getSimpleName());
    }
}
