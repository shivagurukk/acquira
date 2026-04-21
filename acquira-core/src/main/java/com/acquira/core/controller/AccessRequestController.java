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
        long count = accessRequestRepo.findByStatus("PENDING").size();
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

        if (!"PENDING".equals(request.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request is not pending"));
        }

        // Extract admin-provided values
        Number groupIdNum = (Number) payload.get("groupId");
        Number tenantIdNum = (Number) payload.get("tenantId");
        String roleInTenant = (String) payload.get("roleInTenant");
        String reviewNotes = (String) payload.get("reviewNotes");

        Long groupId = groupIdNum != null ? groupIdNum.longValue() : null;
        Long tenantId = tenantIdNum != null ? tenantIdNum.longValue()
            : (request.getRequestedTenantId() != null ? Long.valueOf(request.getRequestedTenantId()) : null);

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant and Group are required for approval"));
        }

        // Create user
        String email = request.getEmail();
        String username = email.contains("@") ? email.substring(0, email.indexOf("@")) : email;

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
