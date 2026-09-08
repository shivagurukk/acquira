package com.acquira.core.webhook;

import java.util.List;
import java.util.Map;

/**
 * Catalog of outbound webhook event types. A webhook_endpoint row subscribes
 * to a subset of these (its {@code events} JSON array); the dispatcher matches
 * on exact type. Kept as constants so listeners, the controller (which serves
 * the catalog to the admin UI) and integration docs can never drift apart.
 */
public final class WebhookEvents {

    public static final String INGEST_RUN_COMPLETED   = "ingest.run.completed";
    public static final String INGEST_RUN_FAILED      = "ingest.run.failed";
    public static final String INTEGRATION_PULL_FAILED = "integration.pull.failed";
    public static final String API_KEY_EXPIRING       = "apikey.expiring";
    public static final String TEST                   = "webhook.test";

    /** type → human description, served to the admin UI subscription picker. */
    public static final List<Map<String, String>> CATALOG = List.of(
            Map.of("type", INGEST_RUN_COMPLETED,
                   "description", "A data ingestion run (file upload, server file, or DB pull) completed successfully"),
            Map.of("type", INGEST_RUN_FAILED,
                   "description", "A data ingestion run failed"),
            Map.of("type", INTEGRATION_PULL_FAILED,
                   "description", "A scheduled Integration Hub pull failed its final retry"),
            Map.of("type", API_KEY_EXPIRING,
                   "description", "An API key expires within the next 7 days (sent at 7, 3, and 1 day(s) before expiry)"),
            Map.of("type", TEST,
                   "description", "Manual test event fired from the admin console"));

    private WebhookEvents() {}
}
