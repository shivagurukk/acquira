package com.acquira.core.controller;

import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.acquira.common.service.AuditService;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Admin endpoints for managing access requests and user-tenant assignments.
 */
@RestController
@RequestMapping("/api/admin/access-requests")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AccessRequestController {

    private final AccessRequestRepository accessRequestRepo;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SysUserGroupRepository groupRepository;
    private final UserTenantAccessRepository accessRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    /** Lenient numeric id from a JSON body value (browsers send select values as strings). */
    private static Long parseId(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private boolean isSuperAdmin() {
        org.springframework.security.core.Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /**
     * SECURITY: access requests carry requested_tenant_id, but this controller is
     * gated on hasAnyRole('ADMIN','SUPER_ADMIN') — and ADMIN is a per-BANK role, not
     * a platform one. Without a tenant predicate a bank admin read (and approved,
     * and rejected) every other bank's pending requests. A super admin is genuinely
     * platform-wide and keeps the unfiltered view.
     *
     * Returns true when the caller may act on this request.
     */
    private boolean canAccessRequest(AccessRequest r) {
        if (isSuperAdmin()) return true;
        Long active = com.acquira.common.config.TenantContext.getCurrentTenant();
        if (active == null || r.getRequestedTenantId() == null) return false;
        return active.equals(Long.valueOf(r.getRequestedTenantId()));
    }

    /**
     * GET /api/admin/access-requests — list all requests (with optional status filter)
     */
    @GetMapping
    public ResponseEntity<?> listRequests(@RequestParam(defaultValue = "") String status) {
        List<AccessRequest> requests;
        if (status.isBlank()) {
            requests = accessRequestRepo.findAllByOrderByCreatedAtDesc();
        } else {
            requests = accessRequestRepo.findByStatusOrderByCreatedAtDesc(status);
        }
        requests = requests.stream().filter(this::canAccessRequest).toList();

        // Enrich with tenant name
        List<Map<String, Object>> result = new ArrayList<>();
        for (AccessRequest r : requests) {
            Map<String, Object> map = new HashMap<>();
            map.put("requestId", r.getRequestId());
            map.put("email", r.getEmail());
            map.put("displayName", r.getDisplayName());
            map.put("ssoProvider", r.getSsoProvider());
            map.put("status", r.getStatus());
            map.put("message", r.getMessage());
            map.put("createdAt", r.getCreatedAt());
            map.put("reviewedAt", r.getReviewedAt());
            map.put("reviewNotes", r.getReviewNotes());
            if (r.getRequestedTenantId() != null) {
                tenantRepository.findById(Long.valueOf(r.getRequestedTenantId()))
                    .ifPresent(t -> map.put("tenantName", t.getBankName()));
                map.put("tenantId", r.getRequestedTenantId());
            }
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/admin/access-requests/count — pending count for badge
     */
    @GetMapping("/count")
    public ResponseEntity<?> pendingCount() {
        long count = accessRequestRepo.findByStatus("PENDING").stream()
            .filter(this::canAccessRequest)
            .count();
        return ResponseEntity.ok(Map.of("pending", count));
    }

    /**
     * POST /api/admin/access-requests/{id}/approve
     * Creates user, assigns tenant+group, marks request as APPROVED.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveRequest(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload) {

        AccessRequest request = accessRequestRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("Request not found"));

        // SECURITY: 404 (not 403) so a bank admin cannot probe which request ids exist
        // in other tenants.
        if (!canAccessRequest(request)) {
            return ResponseEntity.status(404).body(Map.of("error", "Request not found"));
        }

        if (!"PENDING".equals(request.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request is not pending"));
        }

        // Extract admin-provided values.
        // These arrive from HTML <select> elements, so the UI sends them as JSON
        // STRINGS ("3"), not numbers. The previous `(Number) payload.get(...)`
        // cast threw ClassCastException on every approval from the Users screen
        // and surfaced as a bare 500. Parse leniently instead.
        Long groupId = parseId(payload.get("groupId"));
        Long tenantId = parseId(payload.get("tenantId"));
        if (tenantId == null && request.getRequestedTenantId() != null) {
            tenantId = Long.valueOf(request.getRequestedTenantId());
        }
        String roleInTenant = payload.get("roleInTenant") != null ? payload.get("roleInTenant").toString() : null;
        String reviewNotes = payload.get("reviewNotes") != null ? payload.get("reviewNotes").toString() : null;

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant and Group are required for approval"));
        }

        // SECURITY: tenantId arrives in the request BODY, so JwtRequestFilter (which only
        // validates the X-Tenant-Id header) never sees it. Without this guard a bank admin
        // could approve a request and mint a UserTenantAccess row granting the new account
        // access to ANY tenant on the platform. Mirrors AdminController.createUser:97-103.
        if (!isSuperAdmin()) {
            Long activeTenant = com.acquira.common.config.TenantContext.getCurrentTenant();
            if (activeTenant == null || !activeTenant.equals(tenantId)) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "You may only approve requests into your active tenant"));
            }
        }

        // Create user
        String email = request.getEmail() != null ? request.getEmail().trim() : "";
        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "This request has no email address and cannot be approved"));
        }

        // An address that already has an account must not get a second one:
        // duplicate accounts split a person's tenant grants and make the SSO
        // lookup (findByEmailAndSsoProviderIsNotNull) ambiguous.
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "A user with email '" + email + "' already exists. "
                       + "Grant that account tenant access instead of approving a duplicate."));
        }

        String username = email.contains("@") ? email.substring(0, email.indexOf("@")) : email;
        if (username.isBlank()) username = "user";

        // Handle username collision
        int suffix = 1;
        String baseUsername = username;
        while (userRepository.existsByUsername(username)) {
            username = baseUsername + suffix++;
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(request.getDisplayName());
        user.setSsoProvider(request.getSsoProvider());
        user.setSsoId(request.getSsoId());
        user.setApprovalStatus("APPROVED");
        user.setRole("ROLE_USER");
        user.setActive(true);
        user.setMustChangePassword(false);
        // SSO-only users get a random unusable password
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        User savedUser = userRepository.save(user);

        // Assign tenant + group
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new RuntimeException("Tenant not found"));
        SysUserGroup group = groupRepository.findById(groupId)
            .orElseThrow(() -> new RuntimeException("Group not found"));

        UserTenantAccess access = new UserTenantAccess();
        access.setUser(savedUser);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        access.setRoleInTenant(roleInTenant);
        access.setIsDefaultTenant(true);
        accessRepository.save(access);

        // Update request
        String adminUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        User admin = userRepository.findByUsername(adminUsername).orElse(null);
        request.setStatus("APPROVED");
        request.setReviewedBy(admin != null ? admin.getId() : null);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewNotes(reviewNotes);
        accessRequestRepo.save(request);

        log.info("[AccessRequest] APPROVED request #{} — created user '{}' for tenant {}", id, username, tenantId);

        // GAP-19: Audit
        try { auditService.log("ACCESS_REQUEST_APPROVED", "Approved request for: " + email + " → user: " + username); } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
            "message", "Request approved. User '" + username + "' created and assigned.",
            "userId", savedUser.getId(),
            "username", username
        ));
    }

    /**
     * POST /api/admin/access-requests/{id}/reject
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectRequest(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        AccessRequest request = accessRequestRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("Request not found"));

        if (!canAccessRequest(request)) {
            return ResponseEntity.status(404).body(Map.of("error", "Request not found"));
        }

        if (!"PENDING".equals(request.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request is not pending"));
        }

        String adminUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        User admin = userRepository.findByUsername(adminUsername).orElse(null);

        request.setStatus("REJECTED");
        request.setReviewedBy(admin != null ? admin.getId() : null);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewNotes(payload.get("reviewNotes"));
        accessRequestRepo.save(request);

        log.info("[AccessRequest] REJECTED request #{} for {}", id, request.getEmail());

        // GAP-19: Audit
        try { auditService.log("ACCESS_REQUEST_REJECTED", "Rejected request for: " + request.getEmail()); } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("message", "Request rejected"));
    }
}
