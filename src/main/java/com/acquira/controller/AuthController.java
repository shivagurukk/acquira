package com.acquira.controller;

import com.acquira.security.JwtUtil;
import com.acquira.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:5173") // Allow React Frontend
public class AuthController {

    private final org.springframework.security.authentication.AuthenticationManager authenticationManager;
    private final com.acquira.security.JwtUtil jwtUtil;
    private final org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    private final com.acquira.service.TenantService tenantService;
    private final com.acquira.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.repository.UserRepository userRepository;
    private final com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository;

    public AuthController(JwtUtil jwtUtil, UserDetailsService userDetailsService, TenantService tenantService,
            AuthenticationManager authenticationManager,
            com.acquira.repository.SysUserGroupRepository groupRepository,
            com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.authenticationManager = authenticationManager;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(@RequestBody AuthRequest authenticationRequest)
            throws Exception {

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authenticationRequest.getUsername(),
                            authenticationRequest.getPassword()));
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            throw new Exception("Incorrect username or password", e);
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(authenticationRequest.getUsername());
        final String jwt = jwtUtil.generateToken(userDetails);

        // Get allowed tenants for the user
        List<com.acquira.model.Tenant> allowedTenants = tenantService
                .getAllowedTenantsForUser(userDetails.getUsername());
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(userDetails.getUsername());

        // Resolve Effective Tenant (Default or First Allowed)
        Long effectiveTenantId = defaultTenantId;
        if (effectiveTenantId == null && !allowedTenants.isEmpty()) {
            effectiveTenantId = allowedTenants.get(0).getTenantId();
        }

        // Get User Group and Menus for Effective Tenant
        com.acquira.model.User user = userRepository.findByUsername(authenticationRequest.getUsername()).orElse(null);
        java.util.Set<com.acquira.model.SysMenu> menus = new java.util.HashSet<>();

        if (user != null && effectiveTenantId != null) {
            java.util.Optional<com.acquira.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, effectiveTenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            }
        }

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("jwt", jwt);
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        // Note: roles in userDetails might be stale or generic, menus drive the UI now.
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/switch-context")
    public ResponseEntity<?> switchContext(@RequestBody java.util.Map<String, Long> payload) {
        Long tenantId = payload.get("tenantId");
        String username = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getName();
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        java.util.Set<com.acquira.model.SysMenu> menus = new java.util.HashSet<>();
        if (tenantId != null) {
            java.util.Optional<com.acquira.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            }
        }

        return ResponseEntity.ok(java.util.Map.of("menus", menus));
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSessionData() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String username = auth.getName();

        List<com.acquira.model.Tenant> allowedTenants = tenantService.getAllowedTenantsForUser(username);
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(username);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        response.put("username", username);
        response.put("roles", auth.getAuthorities());

        return ResponseEntity.ok(response);
    }
}

class AuthRequest {
    private String username;
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

class AuthResponse {
    private final String jwt;
    private final List<com.acquira.model.Tenant> allowedTenants;
    private final Long defaultTenant;
    private final Object roles;

    public AuthResponse(String jwt, List<com.acquira.model.Tenant> allowedTenants, Long defaultTenant, Object roles) {
        this.jwt = jwt;
        this.allowedTenants = allowedTenants;
        this.defaultTenant = defaultTenant;
        this.roles = roles;
    }

    public String getJwt() {
        return jwt;
    }

    public List<com.acquira.model.Tenant> getAllowedTenants() {
        return allowedTenants;
    }

    public Long getDefaultTenant() {
        return defaultTenant;
    }

    public Object getRoles() {
        return roles;
    }
}
