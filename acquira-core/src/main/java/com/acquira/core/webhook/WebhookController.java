package com.acquira.core.webhook;

import com.acquira.common.config.TenantContext;
import com.acquira.common.service.AuditService;
import com.acquira.common.service.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;

/**
 * Admin surface for a tenant's outbound webhooks. Everything is scoped to
 * TenantContext — an endpoint, its secret and its delivery log are visible
 * only to the owning tenant's admins.
 *
 * The signing secret is generated server-side (never user-supplied — a weak
 * secret would make signature verification decorative), shown ONCE on create
 * or rotate, and stored encrypted with CryptoService.
 */
@RestController
@RequestMapping("/api/admin/webhooks")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class WebhookController {

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final AuditService auditService;
    private final WebhookDispatcher dispatcher;

    /** Dev convenience only — production must never POST to private ranges (SSRF). */
    @Value("${webhooks.allow-private-urls:false}")
    private boolean allowPrivateUrls;

    public WebhookController(JdbcTemplate jdbc, CryptoService cryptoService,
                             AuditService auditService, WebhookDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
        this.dispatcher = dispatcher;
    }

    /** Event-type catalog for the subscription picker. */
    @GetMapping("/events")
    public ResponseEntity<?> events() {
        return ResponseEntity.ok(WebhookEvents.CATALOG);
    }

    @GetMapping
    public ResponseEntity<?> list() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT endpoint_id, name, url, events, is_active, created_by, created_at, updated_at, " +
                "last_success_at, last_failure_at, consecutive_failures FROM webhook_endpoint " +
                "WHERE tenant_id = ? ORDER BY created_at DESC", tenantId);
        return ResponseEntity.ok(rows);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        String name = str(body.get("name"));
        String url = str(body.get("url"));
        String eventsJson = eventsJson(body.get("events"));
        if (name == null || name.isBlank()) return bad("Name is required");
        if (eventsJson == null) return bad("At least one subscribed event is required");
        String urlProblem = validateUrl(url);
        if (urlProblem != null) return bad(urlProblem);

        String secret = "whsec_" + randomToken(32);
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Long id = jdbc.queryForObject(
                "INSERT INTO webhook_endpoint (tenant_id, name, url, secret, events, created_by) " +
                "VALUES (?,?,?,?,?,?) RETURNING endpoint_id",
                Long.class, tenantId, name, url, cryptoService.encrypt(secret), eventsJson, username);

        auditService.log("CREATE_WEBHOOK", "Created webhook endpoint: " + name);
        // The plaintext secret leaves the server exactly once.
        return ResponseEntity.ok(Map.of("id", id, "name", name, "secret", secret));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (!owned(tenantId, id)) return ResponseEntity.notFound().build();

        StringBuilder set = new StringBuilder("UPDATE webhook_endpoint SET updated_at = CURRENT_TIMESTAMP");
        List<Object> params = new ArrayList<>();
        if (body.containsKey("name")) { set.append(", name = ?"); params.add(str(body.get("name"))); }
        if (body.containsKey("url")) {
            String url = str(body.get("url"));
            String problem = validateUrl(url);
            if (problem != null) return bad(problem);
            set.append(", url = ?"); params.add(url);
        }
        if (body.containsKey("events")) {
            String eventsJson = eventsJson(body.get("events"));
            if (eventsJson == null) return bad("At least one subscribed event is required");
            set.append(", events = ?"); params.add(eventsJson);
        }
        if (body.containsKey("isActive")) {
            boolean active = Boolean.parseBoolean(String.valueOf(body.get("isActive")));
            set.append(", is_active = ?"); params.add(active);
            // Re-enabling wipes the failure streak so auto-disable starts fresh.
            if (active) set.append(", consecutive_failures = 0");
        }
        set.append(" WHERE endpoint_id = ? AND tenant_id = ?");
        params.add(id); params.add(tenantId);
        jdbc.update(set.toString(), params.toArray());

        auditService.log("UPDATE_WEBHOOK", "Updated webhook endpoint ID: " + id);
        return ResponseEntity.ok(Map.of("message", "Webhook updated"));
    }

    /** Issue a new signing secret; the old one stops working immediately. */
    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<?> rotateSecret(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (!owned(tenantId, id)) return ResponseEntity.notFound().build();

        String secret = "whsec_" + randomToken(32);
        jdbc.update("UPDATE webhook_endpoint SET secret = ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE endpoint_id = ? AND tenant_id = ?",
                cryptoService.encrypt(secret), id, tenantId);
        auditService.log("ROTATE_WEBHOOK_SECRET", "Rotated signing secret for webhook ID: " + id);
        return ResponseEntity.ok(Map.of("secret", secret));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        int n = jdbc.update("DELETE FROM webhook_endpoint WHERE endpoint_id = ? AND tenant_id = ?", id, tenantId);
        if (n == 0) return ResponseEntity.notFound().build();
        auditService.log("DELETE_WEBHOOK", "Deleted webhook endpoint ID: " + id);
        return ResponseEntity.ok(Map.of("message", "Webhook deleted"));
    }

    /** Fire a webhook.test event at this endpoint only (bypasses subscription matching). */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (!owned(tenantId, id)) return ResponseEntity.notFound().build();

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        // Enqueue directly against the endpoint (a test must reach an endpoint
        // even if webhook.test is not in its subscription list).
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"" + WebhookEvents.TEST + "\"," +
                "\"tenantId\":" + tenantId + ",\"data\":{\"message\":\"Test delivery from Acquira\"," +
                "\"firedBy\":\"" + username.replace("\"", "'") + "\"}}";
        jdbc.update("INSERT INTO webhook_delivery (tenant_id, endpoint_id, event_type, event_id, payload) " +
                    "VALUES (?,?,?,?,?)", tenantId, id, WebhookEvents.TEST, eventId, payload);
        return ResponseEntity.ok(Map.of("message", "Test event queued — check Deliveries in ~15 seconds"));
    }

    /** Delivery log — most recent first, optionally filtered to one endpoint. */
    @GetMapping("/deliveries")
    public ResponseEntity<?> deliveries(@RequestParam(required = false) Long endpointId,
                                        @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        int capped = Math.min(Math.max(limit, 1), 200);

        StringBuilder sql = new StringBuilder(
                "SELECT d.delivery_id, d.endpoint_id, e.name AS endpoint_name, d.event_type, d.event_id, " +
                "d.status, d.attempts, d.response_status, d.response_body, d.created_at, d.delivered_at, " +
                "d.next_attempt_at FROM webhook_delivery d " +
                "JOIN webhook_endpoint e ON e.endpoint_id = d.endpoint_id WHERE d.tenant_id = ?");
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (endpointId != null) { sql.append(" AND d.endpoint_id = ?"); params.add(endpointId); }
        sql.append(" ORDER BY d.created_at DESC LIMIT ").append(capped);
        return ResponseEntity.ok(jdbc.queryForList(sql.toString(), params.toArray()));
    }

    @PostMapping("/deliveries/{deliveryId}/redeliver")
    public ResponseEntity<?> redeliver(@PathVariable Long deliveryId) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (!dispatcher.redeliver(tenantId, deliveryId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("message", "Delivery re-queued"));
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private boolean owned(Long tenantId, Long id) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_endpoint WHERE endpoint_id = ? AND tenant_id = ?",
                Integer.class, id, tenantId);
        return cnt != null && cnt > 0;
    }

    /**
     * HTTPS-only, and (outside dev) no private/loopback/link-local targets —
     * the dispatcher POSTs from inside the cluster, so an internal URL here
     * is an SSRF primitive, not an integration.
     */
    private String validateUrl(String url) {
        if (url == null || url.isBlank()) return "URL is required";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return "URL is not valid";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPrivateUrls && scheme.equals("http"))) {
            return "Webhook URLs must use https";
        }
        if (uri.getHost() == null) return "URL must include a host";
        if (!allowPrivateUrls) {
            try {
                InetAddress addr = InetAddress.getByName(uri.getHost());
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()) {
                    return "Webhook URLs may not target private or internal addresses";
                }
            } catch (Exception e) {
                return "Webhook host could not be resolved";
            }
        }
        return null;
    }

    private String eventsJson(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        Set<String> valid = new LinkedHashSet<>();
        for (Object o : list) {
            String type = String.valueOf(o).trim();
            boolean known = WebhookEvents.CATALOG.stream().anyMatch(c -> c.get("type").equals(type));
            if (known) valid.add(type);
        }
        if (valid.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (String v : valid) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(v).append("\"");
        }
        return sb.append("]").toString();
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o).trim(); }

    private static ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
