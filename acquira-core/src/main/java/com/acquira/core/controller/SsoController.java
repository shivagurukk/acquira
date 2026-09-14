package com.acquira.core.controller;

import com.acquira.common.model.AccessRequest;
import com.acquira.common.model.User;
import com.acquira.common.repository.AccessRequestRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.security.JwtUtil;
import com.acquira.core.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

// GAP-19: Audit logging
import com.acquira.common.service.AuditService;

// DB-based SSO config
import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.TenantSettingRepository;
import com.acquira.common.repository.TenantRepository;

/**
 * Microsoft SSO (Azure AD / Entra ID) OAuth2 Controller.
 *
 * Flow:
 * 1. Frontend calls GET /api/sso/microsoft/config → gets clientId + authUrl (or sso_enabled=false)
 * 2. Frontend redirects user to Microsoft login
 * 3. Microsoft redirects back to frontend with ?code=...
 * 4. Frontend calls POST /api/sso/microsoft/callback with { code }
 * 5. Backend exchanges code for token, extracts email, looks up user
 *    - If user exists + approved → issue JWT
 *    - If user exists + pending → return "pending" status
 *    - If user doesn't exist → create access_request, return "request_submitted"
 */
@RestController
@RequestMapping("/api/sso")
@Slf4j
public class SsoController {

    private final UserRepository userRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TenantService tenantService;
    private final com.acquira.common.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository;
    private final TenantSettingRepository tenantSettingRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // GAP-22: Store OAuth state tokens for CSRF protection (in-memory, short-lived).
    // Each state token is BOUND to the tenant whose SSO config minted the auth URL,
    // so the callback exchanges the code against the SAME client/secret/IdP tenant.
    private record StateEntry(long createdAt, Long tenantId) {}
    private final java.util.concurrent.ConcurrentHashMap<String, StateEntry> stateTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long STATE_TTL_MS = 600_000; // 10 minutes

    @Value("${sso.microsoft.client-id:}")
    private String clientId;

    @Value("${sso.microsoft.client-secret:}")
    private String clientSecret;

    @Value("${sso.microsoft.tenant-id:common}")
    private String azureTenantId;

    @Value("${sso.microsoft.redirect-uri:http://localhost:5173/auth/sso/callback}")
    private String redirectUri;

    @Value("${sso.microsoft.enabled:false}")
    private boolean ssoEnabled;

    private final AuditService auditService;

    public SsoController(UserRepository userRepository,
                         AccessRequestRepository accessRequestRepository,
                         JwtUtil jwtUtil,
                         UserDetailsService userDetailsService,
                         TenantService tenantService,
                         com.acquira.common.repository.SysUserGroupRepository groupRepository,
                         com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository,
                         TenantSettingRepository tenantSettingRepository,
                         TenantRepository tenantRepository,
                         AuditService auditService) {
        this.userRepository = userRepository;
        this.accessRequestRepository = accessRequestRepository;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.groupRepository = groupRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.tenantSettingRepository = tenantSettingRepository;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    /**
     * PER-TENANT SSO (2026-07-11). Replaces the old getEffectiveSsoConfig() which
     * iterated ALL tenants and merged the first non-blank sso_* value — one bank's
     * Entra config silently won for the whole platform and two banks could never
     * have different IdPs. Resolution now picks exactly ONE tenant:
     *   1. explicit tenantId hint (future bank-picker on the login page),
     *   2. email-domain match against that tenant's 'sso_email_domains' setting
     *      (comma/space-separated list, e.g. "acmebank.com, acme.co"),
     *   3. if exactly ONE tenant has sso_enabled=true in tenant_setting, use it
     *      (covers the common single-IdP deployment with zero extra config),
     *   4. otherwise null → application.properties values only.
     */
    private Long resolveSsoTenantId(String emailHint, Long tenantIdHint) {
        try {
            if (tenantIdHint != null) return tenantIdHint;

            List<com.acquira.common.model.Tenant> tenants = tenantRepository.findAll();

            // 2. email-domain match
            if (emailHint != null && emailHint.contains("@")) {
                String domain = emailHint.substring(emailHint.indexOf('@') + 1).toLowerCase().trim();
                for (var t : tenants) {
                    String domains = settingFor(t.getTenantId(), "sso_email_domains");
                    if (domains == null) continue;
                    for (String d : domains.split("[,;\\s]+")) {
                        if (!d.isBlank() && domain.equalsIgnoreCase(d.trim())) return t.getTenantId();
                    }
                }
            }

            // 3. single enabled tenant
            Long only = null;
            for (var t : tenants) {
                if ("true".equalsIgnoreCase(settingFor(t.getTenantId(), "sso_enabled"))) {
                    if (only != null) { only = null; break; } // more than one → ambiguous
                    only = t.getTenantId();
                }
            }
            return only;
        } catch (Exception e) {
            log.debug("[SSO] Tenant resolution failed: {}", e.getMessage());
            return null;
        }
    }

    private String settingFor(Long tenantId, String key) {
        try {
            for (TenantSetting s : tenantSettingRepository.findByTenant_TenantId(tenantId)) {
                if (key.equals(s.getKey())) {
                    String v = s.getValue();
                    return (v == null || v.isBlank()) ? null : v;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Effective SSO config for ONE tenant: application.properties defaults
     * overlaid with ONLY that tenant's sso_* settings. tenantId null = properties
     * only (no DB overlay at all — never another tenant's values).
     */
    private Map<String, String> ssoConfigForTenant(Long tenantId) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("sso_enabled", String.valueOf(ssoEnabled));
        cfg.put("sso_client_id", clientId);
        cfg.put("sso_client_secret", clientSecret);
        cfg.put("sso_tenant_id", azureTenantId);
        cfg.put("sso_redirect_uri", redirectUri);

        if (tenantId == null) return cfg;
        try {
            for (TenantSetting s : tenantSettingRepository.findByTenant_TenantId(tenantId)) {
                String k = s.getKey();
                String v = s.getValue();
                if (k != null && k.startsWith("sso_") && v != null && !v.isBlank()) {
                    cfg.put(k, v);
                }
            }
        } catch (Exception e) {
            log.debug("[SSO] Could not load SSO settings for tenant {}: {}", tenantId, e.getMessage());
        }
        return cfg;
    }

    /**
     * GET /api/sso/microsoft/config
     * Returns SSO configuration for the login page.
     * If SSO is disabled, returns { enabled: false }.
     */
    @GetMapping("/microsoft/config")
    public ResponseEntity<?> getSsoConfig(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Long tenantId) {
        Long ssoTenantId = resolveSsoTenantId(email, tenantId);
        Map<String, String> cfg = ssoConfigForTenant(ssoTenantId);
        boolean enabled = "true".equalsIgnoreCase(cfg.get("sso_enabled"));
        String cid = cfg.get("sso_client_id");

        if (!enabled || cid == null || cid.isBlank()) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }

        String tid = cfg.getOrDefault("sso_tenant_id", "common");
        // GAP-22: Generate state token for CSRF protection, bound to the resolved tenant
        String state = UUID.randomUUID().toString();
        stateTokens.put(state, new StateEntry(System.currentTimeMillis(), ssoTenantId));
        // Cleanup expired tokens
        stateTokens.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAt() > STATE_TTL_MS);

        String authUrl = "https://login.microsoftonline.com/" + tid + "/oauth2/v2.0/authorize"
            + "?client_id=" + cid
            + "&response_type=code"
            + "&redirect_uri=" + cfg.getOrDefault("sso_redirect_uri", redirectUri)
            + "&response_mode=query"
            + "&scope=openid+profile+email+User.Read"
            + "&prompt=select_account"
            + "&state=" + state;

        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "provider", "MICROSOFT",
            "authUrl", authUrl,
            "clientId", cid
        ));
    }

    /**
     * POST /api/sso/microsoft/callback
     * Exchanges authorization code for tokens, extracts user info,
     * and either logs them in or creates an access request.
     */
    @PostMapping("/microsoft/callback")
    public ResponseEntity<?> handleCallback(@RequestBody Map<String, String> payload) {
        String code = payload.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Authorization code is required"));
        }

        // GAP-22: Validate state token for CSRF protection. The entry also carries the
        // tenant whose config minted the auth URL, so the code exchange below uses the
        // SAME client/secret — mandatory for correctness with per-tenant SSO.
        Long ssoTenantId = null;
        String state = payload.get("state");
        if (state != null && !state.isBlank()) {
            StateEntry entry = stateTokens.remove(state);
            if (entry == null || System.currentTimeMillis() - entry.createdAt() > STATE_TTL_MS) {
                log.warn("[SSO] Invalid or expired state token");
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired SSO request. Please try again."));
            }
            ssoTenantId = entry.tenantId();
        } else {
            // Legacy client without state: best-effort resolution (no email known yet).
            ssoTenantId = resolveSsoTenantId(null, null);
        }

        Map<String, String> cfg = ssoConfigForTenant(ssoTenantId);
        boolean enabled = "true".equalsIgnoreCase(cfg.get("sso_enabled"));
        if (!enabled) {
            return ResponseEntity.badRequest().body(Map.of("error", "SSO is not enabled"));
        }

        String effClientId = cfg.get("sso_client_id");
        String effClientSecret = cfg.get("sso_client_secret");
        String effTenantId = cfg.getOrDefault("sso_tenant_id", "common");

        try {
            // 1. Exchange code for token
            String tokenUrl = "https://login.microsoftonline.com/" + effTenantId + "/oauth2/v2.0/token";

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", effClientId);
            form.add("client_secret", effClientSecret);
            form.add("code", code);
            // GAP-11: Use effective redirect URI (DB overrides properties)
            String effRedirectUri = cfg.getOrDefault("sso_redirect_uri", redirectUri);
            form.add("redirect_uri", effRedirectUri);
            form.add("grant_type", "authorization_code");
            form.add("scope", "openid profile email User.Read");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<String> tokenResponse = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);

            JsonNode tokenJson = objectMapper.readTree(tokenResponse.getBody());

            if (tokenJson.has("error")) {
                log.error("[SSO] Token exchange failed: {}", tokenJson.get("error_description").asText("Unknown"));
                return ResponseEntity.badRequest().body(Map.of("error", "Microsoft authentication failed"));
            }

            String accessToken = tokenJson.get("access_token").asText();

            // 2. Get user profile from Microsoft Graph
            HttpHeaders graphHeaders = new HttpHeaders();
            graphHeaders.setBearerAuth(accessToken);
            ResponseEntity<String> profileResponse = restTemplate.exchange(
                "https://graph.microsoft.com/v1.0/me", HttpMethod.GET,
                new HttpEntity<>(graphHeaders), String.class);

            JsonNode profile = objectMapper.readTree(profileResponse.getBody());
            String email = profile.has("mail") && !profile.get("mail").isNull()
                ? profile.get("mail").asText()
                : profile.has("userPrincipalName") ? profile.get("userPrincipalName").asText() : null;
            String displayName = profile.has("displayName") ? profile.get("displayName").asText() : "";
            String ssoId = profile.has("id") ? profile.get("id").asText() : "";

            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not retrieve email from Microsoft account"));
            }

            email = email.toLowerCase().trim();
            log.info("[SSO] Microsoft login for: {} ({})", email, displayName);

            // 3. Look up user by email
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // Check approval status
                if ("PENDING".equals(user.getApprovalStatus())) {
                    return ResponseEntity.ok(Map.of(
                        "status", "pending",
                        "message", "Your access request is pending admin approval."
                    ));
                }
                if ("REJECTED".equals(user.getApprovalStatus())) {
                    return ResponseEntity.ok(Map.of(
                        "status", "rejected",
                        "message", "Your access request was not approved. Contact your administrator."
                    ));
                }
                if (!user.isActive()) {
                    return ResponseEntity.status(403).body(Map.of("error", "Account is deactivated"));
                }

                // GAP-19: Audit SSO login
                try { auditService.log("SSO_LOGIN", "SSO login for: " + email, user.getUsername()); } catch (Exception ignored) {}

                // Link SSO if first time
                if (user.getSsoProvider() == null) {
                    user.setSsoProvider("MICROSOFT");
                    user.setSsoId(ssoId);
                    if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
                        user.setDisplayName(displayName);
                    }
                    userRepository.save(user);
                    log.info("[SSO] Linked Microsoft SSO to existing user: {}", user.getUsername());
                }

                // Issue JWT
                return issueJwtResponse(user);

            } else {
                // User not found — check if there's already a pending request
                if (accessRequestRepository.existsByEmailAndStatus(email, "PENDING")) {
                    return ResponseEntity.ok(Map.of(
                        "status", "pending",
                        "message", "Your access request is already pending. Please wait for admin approval."
                    ));
                }

                // Return info to frontend so they can submit a request with tenant selection
                // GAP-8: Only expose tenantId + bankName, not full Tenant entity
                List<com.acquira.common.model.Tenant> tenants = tenantService.getAllTenants();
                List<Map<String, Object>> safeTenants = new java.util.ArrayList<>();
                for (com.acquira.common.model.Tenant t : tenants) {
                    safeTenants.add(Map.of("tenantId", t.getTenantId(), "bankName", t.getBankName()));
                }
                return ResponseEntity.ok(Map.of(
                    "status", "not_registered",
                    "email", email,
                    "displayName", displayName,
                    "ssoId", ssoId,
                    "message", "No account found for this email. You can request access below.",
                    "availableTenants", safeTenants
                ));
            }

        } catch (Exception e) {
            log.error("[SSO] Callback failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "SSO authentication failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/sso/request-access
     * Called when an SSO user doesn't have an account — submits access request.
     */
    @PostMapping("/request-access")
    public ResponseEntity<?> requestAccess(@RequestBody Map<String, Object> payload) {
        String email = (String) payload.get("email");
        String displayName = (String) payload.get("displayName");
        String ssoId = (String) payload.get("ssoId");
        String message = (String) payload.get("message");
        Number tenantIdNum = (Number) payload.get("tenantId");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        // Check if already exists
        if (userRepository.existsByEmail(email.toLowerCase().trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "An account with this email already exists"));
        }
        if (accessRequestRepository.existsByEmailAndStatus(email.toLowerCase().trim(), "PENDING")) {
            return ResponseEntity.badRequest().body(Map.of("error", "A pending request already exists for this email"));
        }

        AccessRequest request = new AccessRequest();
        request.setEmail(email.toLowerCase().trim());
        request.setDisplayName(displayName);
        request.setSsoProvider("MICROSOFT");
        request.setSsoId(ssoId);
        request.setMessage(message);
        request.setRequestedTenantId(tenantIdNum != null ? tenantIdNum.intValue() : null);
        request.setStatus("PENDING");
        accessRequestRepository.save(request);

        log.info("[SSO] Access request created for: {} (tenant: {})", email, tenantIdNum);

        // GAP-19: Audit access request
        // No account exists yet for this requester, so the email is the only
        // identity available — record it so the row isn't anonymous.
        try { auditService.log("SSO_ACCESS_REQUEST", "Access request from: " + email, email); } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
            "status", "request_submitted",
            "message", "Your access request has been submitted. You will be notified once approved."
        ));
    }

    /**
     * Issue JWT response — same structure as AuthController login response.
     */
    // Injected RefreshTokenService for SSO login consistency with AuthController
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.core.service.RefreshTokenService refreshTokenService;

    private ResponseEntity<?> issueJwtResponse(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String accessToken = jwtUtil.generateToken(userDetails);
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

        // Store refresh token in DB for rotation tracking (same as AuthController)
        if (refreshTokenService != null) {
            refreshTokenService.storeToken(user.getUsername(), refreshToken,
                java.time.LocalDateTime.now().plusDays(7), "SSO-LOGIN", "SSO");
        }

        List<com.acquira.common.model.Tenant> allowedTenants = tenantService.getAllowedTenantsForUser(user.getUsername());
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(user.getUsername());
        Long effectiveTenantId = defaultTenantId;
        if (effectiveTenantId == null && !allowedTenants.isEmpty()) {
            effectiveTenantId = allowedTenants.get(0).getTenantId();
        }

        final Set<com.acquira.common.model.SysMenu> menus = new HashSet<>();
        if (effectiveTenantId != null) {
            Optional<com.acquira.common.model.UserTenantAccess> access = userTenantAccessRepository
                .findByUserAndTenant_TenantId(user, effectiveTenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null
                    && access.get().getSysUserGroup().getMenus() != null) {
                menus.addAll(access.get().getSysUserGroup().getMenus());
            }
            if (menus.isEmpty() && "ROLE_SUPER_ADMIN".equals(user.getRole())) {
                Optional<com.acquira.common.model.SysUserGroup> superGroup = groupRepository.findByGroupName("Super Admin");
                superGroup.ifPresent(g -> { if (g.getMenus() != null) menus.addAll(g.getMenus()); });
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "authenticated");
        response.put("jwt", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", effectiveTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);
        response.put("username", user.getUsername());
        response.put("userRole", user.getRole());
        response.put("displayName", user.getDisplayName());
        response.put("ssoProvider", user.getSsoProvider());
        // GAP-9: Include mustChangePassword for SSO users
        if (user.isMustChangePassword()) {
            response.put("mustChangePassword", true);
        }

        return ResponseEntity.ok(response);
    }
}
