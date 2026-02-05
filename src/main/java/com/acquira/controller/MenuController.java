package com.acquira.controller;

import com.acquira.config.TenantContext;
import com.acquira.model.SysMenu;
import com.acquira.model.User;
import com.acquira.model.UserTenantAccess;
import com.acquira.repository.UserRepository;
import com.acquira.repository.UserTenantAccessRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/users/me")
public class MenuController {

    private final UserRepository userRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;

    public MenuController(UserRepository userRepository, UserTenantAccessRepository userTenantAccessRepository) {
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
    }

    @GetMapping("/menus")
    public ResponseEntity<?> getMyMenus() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        Long tenantId = TenantContext.getCurrentTenant();

        if (tenantId == null) {
            // If no tenant selected, maybe return a default set or empty
            return ResponseEntity.ok(Collections.emptyList());
        }

        // Find access for this tenant
        Optional<UserTenantAccess> accessOpt = userTenantAccessRepository.findByUserAndTenant_TenantId(user, tenantId);

        if (accessOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied for this tenant"));
        }

        UserTenantAccess access = accessOpt.get();
        if (access.getSysUserGroup() == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        Set<SysMenu> menus = access.getSysUserGroup().getMenus();

        // Convert to List and Sort
        List<SysMenu> sortedMenus = new ArrayList<>(menus);
        sortedMenus.sort(Comparator.comparingInt(m -> m.getDisplayOrder() != null ? m.getDisplayOrder() : 999));

        return ResponseEntity.ok(sortedMenus);
    }
}
