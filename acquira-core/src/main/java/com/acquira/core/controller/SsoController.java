package com.acquira.core.controller;

import com.acquira.common.model.AccessRequest;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.AccessRequestRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.security.ClientIp;
import com.acquira.common.security.CustomUserDetailsService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * 3. Microsoft redirects back to frontend with ?code=...&state=...
 * 4. Frontend calls POST /api/sso/microsoft/callback with { code, state }
 * 5. Backend exchanges code for tokens, VALIDATES the id_token, resolves the account
 *    - If user exists + approved → issue JWT
 *    - If user exists + pending → return "pending" status
 *    - If user doesn't exist → return "not_registered" so an access request can be submitted
 *
 * <h3>Pentest 2026-10-04 — what changed and why</h3>
 * <ul>
 *   <li><b>C2 — identity was an unverified e-mail.</b> The callback read Graph
 *       {@code /me.mail} and looked it up globally. An Entra admin anywhere (with the
 *       default {@code tenant-id=common}) could set {@code mail} on one of their users
 *       to a victim's address and sign in as them. The {@code id_token} returned by the
 *       token endpoint is now parsed and its {@code aud}, {@code iss}, {@code tid} and
 *       {@code exp} are checked; the account is linked on the immutable {@code oid},
 *       and {@code common}/{@code organizations}/{@code consumers} are refused unless
 *       the tenant pins an explicit {@code sso_allowed_tenant_ids} list.</li>
 *   <li><b>C1 — a bank admin could re-point the tenant's IdP.</b> Writing {@code sso_*}
 *       is now super-admin only (AdminController), and a tenant-scoped IdP can only
 *       sign in users who hold an access row in THAT tenant — never a platform super
 *       admin.</li>
 *   <li><b>F11</b> — lockout / expiry are enforced on this path too; linking an
 *       existing local account no longer disables anything (MFA on password login
 *       is decided by policy, not by {@code ssoProvider}).</li>
 *   <li><b>F13</b> — {@code state} is mandatory, DB-backed and tenant-bound
 *       ({@link com.acquira.core.service.SsoStateTokenService}); error bodies are generic.</li>
 *   <li><b>F12</b> — {@code /request-access} is rate-limited, answers identically whether
 *       or not the address is known, and stores no caller-asserted identity.</li>
 * </ul>
 * The id_token signature is not verified here: it is received directly from
 * {@code login.microsoftonline.com} over TLS in the code exchange, which OIDC Core
 * §3.1.3.7 accepts as sufficient for the code flow.
 */
@RestController
@RequestMapping("/api/sso")
@Slf4j
public class SsoController {

    private static final String PROVIDER = "MICROSOFT";
    private static final String GENERIC_ERROR = "SSO authentication failed. Please try again or contact your administrator.";
    private static final Set<String> UNPINNED_TENANTS = Set.of("common", "organizations", "consumers");
    private static final int REQUEST_ACCESS_PER_MINUTE = 5;
    private static final long WINDOW_MS = 60_000;

    private final UserRepository userRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TenantService tenantService;
    private final com.acquira.common.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository;
    private final TenantSettingRepository tenantSettingRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final com.acquira.core.service.SsoStateTokenService stateTokens;
    private final com.acquira.core.service.RefreshTokenService refreshTokenService;
    private final com.acquira.core.service.SecurityPolicyService securityPolicyService;
    private final com.acquira.core.service.MenuVisibilityService menuVisibilityService;
    private final com.acquira.common.security.PermissionEvaluator permissionEvaluator;
    private final com.acquira.common.service.CryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /** ip -> [count, windowStart] for /request-access. */
    private final ConcurrentHashMap<String, long[]> requestAccessAttempts = new ConcurrentHashMap<>();

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

    /** Platform-level allow-list of Entra tenant ids when sso.microsoft.tenant-id is not pinned. */
    @Value("${sso.microsoft.allowed-tenant-ids:}")
    private String allowedTenantIds;

    public SsoController(UserRepository userRepository,
                         AccessRequestRepository accessRequestRepository,
                         JwtUtil jwtUtil,
                         UserDetailsService userDetailsService,
                         TenantService tenantService,
                         com.acquira.common.repository.SysUserGroupRepository groupRepository,
                         com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository,
                         TenantSettingRepository tenantSettingRepository,
                         TenantRepository tenantRepository,
                         AuditService auditService,
                         com.acquira.core.service.SsoStateTokenService stateTokens,
                         com.acquira.core.service.RefreshTokenService refreshTokenService,
                         com.acquira.core.service.SecurityPolicyService securityPolicyService,
                         com.acquira.core.service.MenuVisibilityService menuVisibilityService,
                         com.acquira.common.security.PermissionEvaluator permissionEvaluator,
                         com.acquira.common.service.CryptoService cryptoService) {
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
        this.stateTokens = stateTokens;
        this.refreshTokenService = refreshTokenService;
        this.securityPolicyService = securityPolicyService;
        this.menuVisibilityService = menuVisibilityService;
        this.permissionEvaluator = permissionEvaluator;
        this.cryptoService = cryptoService;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Per-tenant SSO config resolution
    // ════════════════════════════════════════════════════════════════════════

    /**
     * PER-TENANT SSO (2026-07-11). Resolution picks exactly ONE tenant:
     *   1. explicit tenantId hint,
     *   2. email-domain match against that tenant's 'sso_email_domains' setting,
     *   3. if exactly ONE tenant has sso_enabled=true, use it,
     *   4. otherwise null → application.properties values only.
     */
    private Long resolveSsoTenantId(String emailHint, Long tenantIdHint) {
        try {
            if (tenantIdHint != null) return tenantIdHint;

            List<com.acquira.common.model.Tenant> tenants = tenantRepository.findAll();

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
     * only. The client secret is stored encrypted by AdminController and is
     * decrypted here (CryptoService passes legacy plaintext rows through).
     */
    private Map<String, String> ssoConfigForTenant(Long tenantId) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("sso_enabled", String.valueOf(ssoEnabled));
        cfg.put("sso_client_id", clientId);
        cfg.put("sso_client_secret", clientSecret);
        cfg.put("sso_tenant_id", azureTenantId);
        cfg.put("sso_redirect_uri", redirectUri);
        cfg.put("sso_allowed_tenant_ids", allowedTenantIds);

        if (tenantId == null) return cfg;
        try {
            for (TenantSetting s : tenantSettingRepository.findByTenant_TenantId(tenantId)) {
                String k = s.getKey();
                String v = s.getValue();
                if (k != null && k.startsWith("sso_") && v != null && !v.isBlank()) {
                    cfg.put(k, "sso_client_secret".equals(k) ? cryptoService.decrypt(v) : v);
                }
            }
        } catch (Exception e) {
            log.debug("[SSO] Could not load SSO settings for tenant {}: {}", tenantId, e.getMessage());
        }
        return cfg;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /api/sso/microsoft/config
    // ════════════════════════════════════════════════════════════════════════

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
        // State is bound to the resolved tenant so the callback exchanges the code
        // against the SAME client/secret/IdP.
        String state = stateTokens.generateState(ssoTenantId);

        String authUrl = "https://login.microsoftonline.com/" + urlEncode(tid) + "/oauth2/v2.0/authorize"
            + "?client_id=" + urlEncode(cid)
            + "&response_type=code"
            + "&redirect_uri=" + urlEncode(cfg.getOrDefault("sso_redirect_uri", redirectUri))
            + "&response_mode=query"
            + "&scope=" + urlEncode("openid profile email User.Read")
            + "&prompt=select_account"
            + "&state=" + urlEncode(state);

        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "provider", PROVIDER,
            "authUrl", authUrl,
            "clientId", cid
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  POST /api/sso/microsoft/callback
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/microsoft/callback")
    public ResponseEntity<?> handleCallback(@RequestBody Map<String, String> payload,
                                            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String code = payload.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Authorization code is required"));
        }

        // state is mandatory: it is the CSRF binding AND the tenant binding.
        var consumed = stateTokens.validateAndConsume(payload.get("state"));
        if (!consumed.valid()) {
            log.warn("[SSO] Invalid, expired or missing state token");
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired SSO request. Please try again."));
        }
        Long ssoTenantId = consumed.tenantId();

        Map<String, String> cfg = ssoConfigForTenant(ssoTenantId);
        if (!"true".equalsIgnoreCase(cfg.get("sso_enabled"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "SSO is not enabled"));
        }

        String effClientId = cfg.get("sso_client_id");
        String effClientSecret = cfg.get("sso_client_secret");
        String effTenantId = cfg.getOrDefault("sso_tenant_id", "common");

        try {
            // 1. Exchange code for tokens (server-to-server, over TLS, with the client secret)
            String tokenUrl = "https://login.microsoftonline.com/" + urlEncode(effTenantId) + "/oauth2/v2.0/token";

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", effClientId);
            form.add("client_secret", effClientSecret);
            form.add("code", code);
            form.add("redirect_uri", cfg.getOrDefault("sso_redirect_uri", redirectUri));
            form.add("grant_type", "authorization_code");
            form.add("scope", "openid profile email User.Read");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<String> tokenResponse = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
            JsonNode tokenJson = objectMapper.readTree(tokenResponse.getBody());

            if (tokenJson.has("error")) {
                log.error("[SSO] Token exchange failed: {}", tokenJson.path("error_description").asText("Unknown"));
                return ResponseEntity.badRequest().body(Map.of("error", "Microsoft authentication failed"));
            }

            // 2. Validate the id_token — this, not Graph, is the identity assertion.
            JsonNode idClaims = parseIdToken(tokenJson.path("id_token").asText(null));
            String rejection = validateIdToken(idClaims, effClientId, effTenantId, cfg.get("sso_allowed_tenant_ids"));
            if (rejection != null) {
                log.warn("[SSO] id_token rejected: {}", rejection);
                auditService.log("SSO_REJECTED", "id_token rejected: " + rejection, "anonymous");
                return ResponseEntity.status(403).body(Map.of("error", GENERIC_ERROR));
            }
            String oid = idClaims.path("oid").asText(null);
            String tid = idClaims.path("tid").asText(null);
            String email = firstNonBlank(idClaims.path("email").asText(null),
                                         idClaims.path("preferred_username").asText(null));
            String displayName = idClaims.path("name").asText("");

            // 3. Graph /me only for profile enrichment; fall back to it for the address.
            if (email == null || email.isBlank() || displayName.isBlank()) {
                try {
                    HttpHeaders graphHeaders = new HttpHeaders();
                    graphHeaders.setBearerAuth(tokenJson.path("access_token").asText(""));
                    ResponseEntity<String> profileResponse = restTemplate.exchange(
                        "https://graph.microsoft.com/v1.0/me", HttpMethod.GET,
                        new HttpEntity<>(graphHeaders), String.class);
                    JsonNode profile = objectMapper.readTree(profileResponse.getBody());
                    if (email == null || email.isBlank()) {
                        email = firstNonBlank(profile.path("mail").asText(null),
                                              profile.path("userPrincipalName").asText(null));
                    }
                    if (displayName.isBlank()) displayName = profile.path("displayName").asText("");
                } catch (Exception e) {
                    log.debug("[SSO] Graph profile lookup failed: {}", e.getMessage());
                }
            }
            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not retrieve email from Microsoft account"));
            }
            email = email.toLowerCase().trim();
            log.info("[SSO] Microsoft login for oid={} tid={} ({})", oid, tid, email);

            // 4. Resolve the account: by immutable oid first, then by e-mail for first-time linking.
            Optional<User> userOpt = userRepository.findBySsoProviderAndSsoId(PROVIDER, oid);
            if (userOpt.isEmpty()) {
                userOpt = userRepository.findByEmail(email);
                if (userOpt.isPresent() && userOpt.get().getSsoId() != null
                        && !userOpt.get().getSsoId().isBlank() && !oid.equals(userOpt.get().getSsoId())) {
                    // Address matches an account already bound to a DIFFERENT IdP object.
                    auditService.log("SSO_REJECTED", "oid mismatch for " + email, email);
                    return ResponseEntity.status(403).body(Map.of("error", GENERIC_ERROR));
                }
            }

            if (userOpt.isEmpty()) {
                if (accessRequestRepository.existsByEmailAndStatus(email, "PENDING")) {
                    return ResponseEntity.ok(Map.of(
                        "status", "pending",
                        "message", "Your access request is already pending. Please wait for admin approval."
                    ));
                }
                // GAP-8: Only expose tenantId + bankName, not full Tenant entity. When the
                // IdP is tenant-scoped, only that tenant is offered.
                List<Map<String, Object>> safeTenants = new ArrayList<>();
                for (com.acquira.common.model.Tenant t : tenantService.getAllTenants()) {
                    if (ssoTenantId != null && !ssoTenantId.equals(t.getTenantId())) continue;
                    safeTenants.add(Map.of("tenantId", t.getTenantId(), "bankName", t.getBankName()));
                }
                return ResponseEntity.ok(Map.of(
                    "status", "not_registered",
                    "email", email,
                    "displayName", displayName,
                    "message", "No account found for this email. You can request access below.",
                    "availableTenants", safeTenants
                ));
            }

            User user = userOpt.get();

            // 5. Account state — same gates as password login.
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
            if (!user.isActive() || user.isAccountExpired() || user.isAccountLocked()) {
                auditService.log("SSO_DENIED", "SSO login denied (inactive/expired/locked) for " + email, user.getUsername());
                return ResponseEntity.status(403).body(Map.of("error", "Account is not available. Contact your administrator."));
            }

            // 6. A TENANT's IdP may only sign in members of that tenant — and never a
            //    platform super admin, whose identity no single bank may assert.
            if (ssoTenantId != null) {
                if (CustomUserDetailsService.isSuperAdmin(user)) {
                    auditService.log("SSO_REJECTED", "Tenant IdP attempted to assert super admin " + email, user.getUsername());
                    return ResponseEntity.status(403).body(Map.of("error", GENERIC_ERROR));
                }
                boolean member = userTenantAccessRepository.findByUserAndTenant_TenantId(user, ssoTenantId).isPresent();
                if (!member) {
                    auditService.log("SSO_REJECTED", "User " + email + " is not a member of SSO tenant " + ssoTenantId, user.getUsername());
                    return ResponseEntity.status(403).body(Map.of("error", GENERIC_ERROR));
                }
            }

            // 7. Link on first SSO login (immutable oid, not the e-mail).
            if (user.getSsoId() == null || user.getSsoId().isBlank()) {
                user.setSsoProvider(PROVIDER);
                user.setSsoId(oid);
                if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
                    user.setDisplayName(displayName);
                }
                userRepository.save(user);
                log.info("[SSO] Linked Microsoft identity to user: {}", user.getUsername());
            }

            try { auditService.log("SSO_LOGIN", "SSO login for: " + email, user.getUsername()); } catch (Exception ignored) {}
            return issueJwtResponse(user, httpRequest);

        } catch (Exception e) {
            // Generic to the client; the real cause is in the server log.
            log.error("[SSO] Callback failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", GENERIC_ERROR));
        }
    }

    /** Decode the payload of a compact JWS without verifying it (see class javadoc). */
    private JsonNode parseIdToken(String idToken) throws Exception {
        if (idToken == null || idToken.isBlank()) return objectMapper.createObjectNode();
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return objectMapper.createObjectNode();
        byte[] json = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
    }

    /**
     * @return null when the token is acceptable, otherwise a short reason.
     */
    private String validateIdToken(JsonNode c, String expectedClientId, String configuredTenant, String allowedTids) {
        if (c == null || c.isEmpty()) return "no id_token in token response";
        String aud = c.path("aud").asText(null);
        String iss = c.path("iss").asText(null);
        String tid = c.path("tid").asText(null);
        String oid = c.path("oid").asText(null);
        long exp = c.path("exp").asLong(0);

        if (aud == null || !aud.equals(expectedClientId)) return "aud mismatch";
        if (oid == null || oid.isBlank()) return "missing oid";
        if (tid == null || tid.isBlank()) return "missing tid";
        if (exp > 0 && exp * 1000L < System.currentTimeMillis() - 60_000L) return "expired";
        // v2 issuer is always https://login.microsoftonline.com/{tid}/v2.0
        if (iss == null || !iss.equals("https://login.microsoftonline.com/" + tid + "/v2.0")) return "iss mismatch";

        String configured = configuredTenant == null ? "" : configuredTenant.trim().toLowerCase();
        if (UNPINNED_TENANTS.contains(configured) || configured.isEmpty()) {
            // Multi-tenant app registration: ANY Entra directory can mint a valid token,
            // so an explicit allow-list of directory ids is mandatory.
            Set<String> allowed = new HashSet<>();
            if (allowedTids != null) {
                for (String s : allowedTids.split("[,;\\s]+")) if (!s.isBlank()) allowed.add(s.trim().toLowerCase());
            }
            if (allowed.isEmpty()) {
                return "sso tenant is '" + configured + "' and no sso_allowed_tenant_ids is configured — pin the directory";
            }
            if (!allowed.contains(tid.toLowerCase())) return "tid " + tid + " not in allow-list";
        } else if (!configured.equalsIgnoreCase(tid)) {
            return "tid " + tid + " does not match configured directory";
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  POST /api/sso/request-access
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/request-access")
    public ResponseEntity<?> requestAccess(@RequestBody Map<String, Object> payload,
                                           jakarta.servlet.http.HttpServletRequest httpRequest) {
        String email = payload.get("email") instanceof String s ? s.toLowerCase().trim() : null;
        String displayName = payload.get("displayName") instanceof String s ? s : null;
        String message = payload.get("message") instanceof String s ? s : null;
        Number tenantIdNum = payload.get("tenantId") instanceof Number n ? n : null;

        if (email == null || email.isBlank() || !email.contains("@")) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid email is required"));
        }

        // Unauthenticated and writes a row every admin sees: throttle per IP.
        String ip = ClientIp.of(httpRequest);
        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many requests. Please try again later."));
        }

        // Always the same answer (pentest F12): "already registered" vs "already
        // pending" vs "submitted" let anyone enumerate the user base.
        Map<String, String> ok = Map.of(
            "status", "request_submitted",
            "message", "Your request has been received. You will be notified once it is reviewed.");

        if (userRepository.existsByEmail(email) || accessRequestRepository.existsByEmailAndStatus(email, "PENDING")) {
            return ResponseEntity.ok(ok);
        }

        AccessRequest request = new AccessRequest();
        request.setEmail(email);
        request.setDisplayName(displayName);
        request.setSsoProvider(PROVIDER);
        request.setSsoId(null); // never trust a caller-asserted IdP id; the real one is bound at first SSO login
        request.setMessage(message);
        request.setRequestedTenantId(tenantIdNum != null ? tenantIdNum.intValue() : null);
        request.setStatus("PENDING");
        accessRequestRepository.save(request);

        log.info("[SSO] Access request created for: {} (tenant: {})", email, tenantIdNum);
        try { auditService.log("SSO_ACCESS_REQUEST", "Access request from: " + email + " (" + ip + ")", email); } catch (Exception ignored) {}

        return ResponseEntity.ok(ok);
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] d = requestAccessAttempts.compute(ip, (k, v) -> {
            if (v == null || now - v[1] > WINDOW_MS) return new long[]{1, now};
            v[0]++;
            return v;
        });
        if (requestAccessAttempts.size() > 50_000) requestAccessAttempts.clear(); // bound memory under abuse
        return d[0] > REQUEST_ACCESS_PER_MINUTE;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Session issue — same policy as AuthController.issueSession
    // ════════════════════════════════════════════════════════════════════════

    private ResponseEntity<?> issueJwtResponse(User user, jakarta.servlet.http.HttpServletRequest httpRequest) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

        Long policyTenantId = tenantService.getDefaultTenantIdForUser(user.getUsername());
        long accessTtlMs = securityPolicyService.accessTokenMillis(policyTenantId);
        long refreshTtlMs = securityPolicyService.refreshTokenMillis(policyTenantId);

        String accessToken = jwtUtil.generateToken(userDetails, accessTtlMs);
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername(), refreshTtlMs);

        refreshTokenService.storeToken(user.getUsername(), refreshToken,
            LocalDateTime.now().plusSeconds(refreshTtlMs / 1000),
            httpRequest.getHeader("User-Agent"), ClientIp.of(httpRequest));
        refreshTokenService.enforceSessionLimit(user.getUsername(),
            securityPolicyService.maxConcurrentSessions(policyTenantId));

        List<com.acquira.common.model.Tenant> allowedTenants = tenantService.getAllowedTenantsForUser(user.getUsername());
        Long effectiveTenantId = policyTenantId;
        if (effectiveTenantId == null && !allowedTenants.isEmpty()) {
            effectiveTenantId = allowedTenants.get(0).getTenantId();
        }

        boolean superAdmin = CustomUserDetailsService.isSuperAdmin(user);
        List<com.acquira.common.model.SysMenu> menus = List.of();
        Set<String> permissions = Set.of();
        if (effectiveTenantId != null) {
            menus = menuVisibilityService.visibleMenus(user.getUsername(), effectiveTenantId.intValue(), superAdmin);
            permissions = permissionEvaluator.permissionsFor(user.getUsername(), effectiveTenantId.intValue(), superAdmin);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "authenticated");
        response.put("jwt", accessToken);
        // refresh token: HttpOnly cookie only (pentest H6)
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", effectiveTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);
        response.put("permissions", permissions);
        response.put("username", user.getUsername());
        response.put("userRole", user.getRole());
        response.put("displayName", user.getDisplayName());
        response.put("ssoProvider", user.getSsoProvider());
        if (user.isMustChangePassword()) {
            response.put("mustChangePassword", true);
        }

        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true).secure(true).path("/api/auth")
                .maxAge(refreshTtlMs / 1000).sameSite("Strict").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
