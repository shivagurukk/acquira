package com.acquira.core.webhook;

import com.acquira.common.service.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Outbound webhook delivery engine — everything is tenant-scoped.
 *
 * WRITE PATH: {@link #dispatch(Long, String, Map)} looks up the tenant's
 * active endpoints subscribed to the event type and inserts one PENDING
 * webhook_delivery row per endpoint. Insertion is the durability boundary:
 * a crash after dispatch() loses nothing, the poller picks the row up.
 *
 * DELIVERY: a dedicated daemon thread (same starvation argument as
 * ApiUsageRecorder — the shared TaskScheduler serves multi-minute jobs) polls
 * due PENDING/FAILED rows every {@link #POLL_INTERVAL_MS} and POSTs the JSON
 * payload with an HMAC-SHA256 signature computed from the endpoint's secret:
 *   X-Acquira-Signature: sha256=&lt;hex hmac of the raw body&gt;
 *   X-Acquira-Event / X-Acquira-Event-Id / X-Acquira-Delivery-Id
 * Integrators verify by recomputing the HMAC over the exact bytes received.
 *
 * RETRY: 2xx marks SUCCESS; anything else (or a network error) schedules the
 * next attempt on a fixed backoff ladder and marks DEAD after
 * {@link #MAX_ATTEMPTS}. An endpoint that fails {@link #DISABLE_AFTER}
 * consecutive deliveries is auto-disabled so a dead integration cannot queue
 * work forever; re-enabling it from the admin UI resets the counter.
 *
 * Single-replica safe (in-process poller, no row claim needed); when the app
 * goes multi-replica the poll must claim rows with FOR UPDATE SKIP LOCKED.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private static final long POLL_INTERVAL_MS = 15_000;
    private static final int MAX_ATTEMPTS = 6;
    /** Backoff (minutes) before attempt N+1; index = attempts already made. */
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60, 360};
    private static final int DISABLE_AFTER = 25;
    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER) // a redirect on a signed POST is a misconfig, not something to chase
            .build();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-delivery");
        t.setDaemon(true);
        return t;
    });

    public WebhookDispatcher(JdbcTemplate jdbc, CryptoService cryptoService) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
        poller.scheduleWithFixedDelay(this::deliverDue, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ─── Enqueue ───────────────────────────────────────────────────────

    /**
     * Fan an event out to the tenant's subscribed endpoints. Never throws —
     * webhook side effects must not fail the business operation that raised
     * the event.
     *
     * @return number of deliveries enqueued
     */
    public int dispatch(Long tenantId, String eventType, Map<String, Object> data) {
        if (tenantId == null || eventType == null) return 0;
        try {
            List<Map<String, Object>> endpoints = jdbc.queryForList(
                    "SELECT endpoint_id FROM webhook_endpoint " +
                    "WHERE tenant_id = ? AND is_active = true AND events LIKE ?",
                    tenantId, "%\"" + eventType + "\"%");
            if (endpoints.isEmpty()) return 0;

            String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", eventId);
            envelope.put("type", eventType);
            envelope.put("createdAt", LocalDateTime.now().toString());
            envelope.put("tenantId", tenantId);
            envelope.put("data", data == null ? Map.of() : data);
            String payload = mapper.writeValueAsString(envelope);

            for (Map<String, Object> ep : endpoints) {
                jdbc.update(
                        "INSERT INTO webhook_delivery (tenant_id, endpoint_id, event_type, event_id, payload) " +
                        "VALUES (?,?,?,?,?)",
                        tenantId, ((Number) ep.get("endpoint_id")).longValue(), eventType, eventId, payload);
            }
            return endpoints.size();
        } catch (Exception e) {
            log.warn("[Webhook] dispatch of {} for tenant {} failed: {}", eventType, tenantId, e.getMessage());
            return 0;
        }
    }

    /** Re-queue an existing delivery for immediate retry (admin "redeliver"). */
    public boolean redeliver(Long tenantId, Long deliveryId) {
        int n = jdbc.update(
                "UPDATE webhook_delivery SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP " +
                "WHERE delivery_id = ? AND tenant_id = ?", deliveryId, tenantId);
        return n > 0;
    }

    // ─── Delivery loop ─────────────────────────────────────────────────

    void deliverDue() {
        List<Map<String, Object>> due;
        try {
            due = jdbc.queryForList(
                    "SELECT d.delivery_id, d.tenant_id, d.endpoint_id, d.event_type, d.event_id, d.payload, " +
                    "d.attempts, e.url, e.secret, e.is_active " +
                    "FROM webhook_delivery d JOIN webhook_endpoint e ON e.endpoint_id = d.endpoint_id " +
                    "WHERE d.status IN ('PENDING','FAILED') AND d.next_attempt_at <= CURRENT_TIMESTAMP " +
                    "ORDER BY d.next_attempt_at LIMIT " + BATCH_LIMIT);
        } catch (Exception e) {
            log.warn("[Webhook] due-delivery query failed: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : due) {
            try {
                deliverOne(row);
            } catch (Exception e) {
                // deliverOne handles its own failures; this catch is belt-and-braces
                log.warn("[Webhook] delivery {} crashed: {}", row.get("delivery_id"), e.getMessage());
            }
        }
    }

    private void deliverOne(Map<String, Object> row) {
        long deliveryId = ((Number) row.get("delivery_id")).longValue();
        long endpointId = ((Number) row.get("endpoint_id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        String payload = (String) row.get("payload");

        // Endpoint deactivated after the row was queued — park the delivery.
        Object active = row.get("is_active");
        if (active instanceof Boolean b && !b) {
            jdbc.update("UPDATE webhook_delivery SET status = 'DEAD', response_body = 'Endpoint disabled' " +
                        "WHERE delivery_id = ?", deliveryId);
            return;
        }

        Integer status = null;
        String responseBody;
        try {
            String secret = cryptoService.decrypt((String) row.get("secret"));
            String signature = hmacHex(secret, payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create((String) row.get("url")))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Acquira-Webhooks/1.0")
                    .header("X-Acquira-Event", (String) row.get("event_type"))
                    .header("X-Acquira-Event-Id", (String) row.get("event_id"))
                    .header("X-Acquira-Delivery-Id", String.valueOf(deliveryId))
                    .header("X-Acquira-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = resp.statusCode();
            responseBody = truncate(resp.body(), 500);
        } catch (Exception e) {
            responseBody = truncate("DELIVERY ERROR: " + e.getMessage(), 500);
        }

        boolean success = status != null && status >= 200 && status < 300;
        attempts++;

        if (success) {
            jdbc.update("UPDATE webhook_delivery SET status = 'SUCCESS', attempts = ?, response_status = ?, " +
                        "response_body = ?, delivered_at = CURRENT_TIMESTAMP WHERE delivery_id = ?",
                    attempts, status, responseBody, deliveryId);
            jdbc.update("UPDATE webhook_endpoint SET last_success_at = CURRENT_TIMESTAMP, consecutive_failures = 0 " +
                        "WHERE endpoint_id = ?", endpointId);
            return;
        }

        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE webhook_delivery SET status = 'DEAD', attempts = ?, response_status = ?, " +
                        "response_body = ? WHERE delivery_id = ?",
                    attempts, status, responseBody, deliveryId);
        } else {
            long backoffMin = BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)];
            jdbc.update("UPDATE webhook_delivery SET status = 'FAILED', attempts = ?, response_status = ?, " +
                        "response_body = ?, next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 minute') " +
                        "WHERE delivery_id = ?",
                    attempts, status, responseBody, backoffMin, deliveryId);
        }

        jdbc.update("UPDATE webhook_endpoint SET last_failure_at = CURRENT_TIMESTAMP, " +
                    "consecutive_failures = consecutive_failures + 1 WHERE endpoint_id = ?", endpointId);
        Integer fails = jdbc.queryForObject(
                "SELECT consecutive_failures FROM webhook_endpoint WHERE endpoint_id = ?", Integer.class, endpointId);
        if (fails != null && fails >= DISABLE_AFTER) {
            jdbc.update("UPDATE webhook_endpoint SET is_active = false WHERE endpoint_id = ?", endpointId);
            log.warn("[Webhook] endpoint {} auto-disabled after {} consecutive failures", endpointId, fails);
        }
    }

    /** Public so integrator docs/tests can reproduce the exact signature scheme. */
    public static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdown();
    }
}
