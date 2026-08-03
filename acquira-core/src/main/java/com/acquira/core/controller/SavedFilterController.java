package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.SavedFilter;
import com.acquira.common.repository.SavedFilterRepository;
import com.acquira.common.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/filters/views")
public class SavedFilterController {

    private final SavedFilterRepository savedFilterRepository;
    private final UserRepository userRepository;

    public SavedFilterController(SavedFilterRepository savedFilterRepository,
                                  UserRepository userRepository) {
        this.savedFilterRepository = savedFilterRepository;
        this.userRepository = userRepository;
    }

    private Long getCurrentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getId();
    }

    private Long getTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw new RuntimeException("Tenant context is missing");
        return tenantId;
    }

    /**
     * Owner AND tenant check. findByIdAndUserId alone proves only ownership: a user with
     * access to tenants A and B, acting in A, could otherwise edit, re-share or delete
     * their own filter that belongs to B — including flipping isShared, which republishes
     * it to every user of B.
     */
    private SavedFilter findOwnFilterInTenant(Long id) {
        return savedFilterRepository.findByIdAndUserId(id, getCurrentUserId())
                .filter(f -> getTenantId().equals(f.getTenantId()))
                .orElse(null);
    }

    @GetMapping("/{dashboardType}")
    public ResponseEntity<List<SavedFilter>> getViews(@PathVariable String dashboardType) {
        return ResponseEntity.ok(
            savedFilterRepository.findAccessibleViews(getTenantId(), getCurrentUserId(), dashboardType)
        );
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createView(@RequestBody SavedFilter filter) {
        Long userId = getCurrentUserId();
        Long tenantId = getTenantId();

        // Validation
        String name = filter.getName();
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "View name is required"));
        }
        String filterJson = filter.getFilterJson();
        if (filterJson == null || filterJson.length() > 10240) {
            return ResponseEntity.badRequest().body(Map.of("error", "Filter data is invalid or too large"));
        }
        if (savedFilterRepository.countByUserIdAndTenantId(userId, tenantId) >= 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 50 views allowed"));
        }

        filter.setUserId(userId);
        filter.setTenantId(tenantId);
        filter.setCreatedAt(LocalDateTime.now());
        filter.setUpdatedAt(LocalDateTime.now());

        if (filter.getIsDefault()) {
            savedFilterRepository.clearDefaults(userId, tenantId, filter.getDashboardType());
        }

        return ResponseEntity.ok(savedFilterRepository.save(filter));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateView(@PathVariable Long id, @RequestBody SavedFilter update) {
        Long userId = getCurrentUserId();
        SavedFilter existing = findOwnFilterInTenant(id);
        if (existing == null) {
            return ResponseEntity.status(403).body(Map.of("error", "View not found or access denied"));
        }

        existing.setName(update.getName());
        existing.setFilterJson(update.getFilterJson());
        existing.setIsShared(update.getIsShared());
        existing.setUpdatedAt(LocalDateTime.now());

        if (update.getIsDefault()) {
            savedFilterRepository.clearDefaults(userId, existing.getTenantId(), existing.getDashboardType());
            existing.setIsDefault(true);
        }

        return ResponseEntity.ok(savedFilterRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteView(@PathVariable Long id) {
        SavedFilter existing = findOwnFilterInTenant(id);
        if (existing == null) {
            return ResponseEntity.status(403).body(Map.of("error", "View not found or access denied"));
        }
        savedFilterRepository.delete(existing);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @PutMapping("/{id}/default")
    @Transactional
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        SavedFilter existing = findOwnFilterInTenant(id);
        if (existing == null) {
            return ResponseEntity.status(403).body(Map.of("error", "View not found or access denied"));
        }
        savedFilterRepository.clearDefaults(userId, existing.getTenantId(), existing.getDashboardType());
        existing.setIsDefault(true);
        existing.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(savedFilterRepository.save(existing));
    }
}
