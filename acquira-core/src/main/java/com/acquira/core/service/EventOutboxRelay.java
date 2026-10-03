package com.acquira.core.service;

import com.acquira.common.event.EventOutbox;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumer side of {@link EventOutbox}: claims pending rows and re-publishes
 * them as ordinary Spring events inside core, where the listeners live
 * (IntegrationAlertListener, WebhookEventListeners).
 *
 * Lives in core — not common — so that only the pod that owns the listeners
 * drains the table. Rows are claimed with FOR UPDATE SKIP LOCKED, so more than
 * one core replica can run this without delivering an event twice.
 *
 * Delivery is at-most-once after the claim, which matches what the in-process
 * events gave: the listeners are @Async, so a failure inside one was never
 * visible to the publisher either. A row that cannot be deserialized is marked
 * FAILED with the error rather than retried forever.
 */
@Component
public class EventOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxRelay.class);

    private static final int BATCH_LIMIT = 50;
    private static final int RETENTION_DAYS = 14;
    /** Purge processed rows once per this many polls (~hourly at the default interval). */
    private static final int PURGE_EVERY_POLLS = 720;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;
    private final long pollMs;

    private int pollsSincePurge = PURGE_EVERY_POLLS; // purge on the first poll
    private boolean unavailableLogged;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "event-outbox-relay");
        t.setDaemon(true);
        return t;
    });

    public EventOutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            ApplicationEventPublisher publisher,
            @Value("${acquira.event-outbox.poll-ms:5000}") long pollMs) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.publisher = publisher;
        this.pollMs = pollMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (pollMs <= 0) return;
        poller.scheduleWithFixedDelay(this::poll, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        poller.shutdownNow();
    }

    /** Package-visible for tests; normally driven by the poller thread. */
    void poll() {
        try {
            // Loop while full batches come back, so a backlog drains promptly.
            while (relayBatch() == BATCH_LIMIT) { /* keep draining */ }
            if (++pollsSincePurge >= PURGE_EVERY_POLLS) {
                pollsSincePurge = 0;
                jdbc.update("DELETE FROM event_outbox WHERE status <> 'PENDING' "
                        + "AND processed_at < CURRENT_TIMESTAMP - make_interval(days => ?)", RETENTION_DAYS);
            }
            unavailableLogged = false;
        } catch (Throwable t) {
            // Never let an exception escape: scheduleWithFixedDelay would
            // silently cancel every future run.
            if (unavailableLogged) {
                log.debug("Event outbox relay poll failed: {}", t.toString());
            } else {
                unavailableLogged = true;
                log.warn("Event outbox relay poll failed — batch events are NOT being delivered until this "
                        + "recovers (is migration V2026_10_03_01 applied?): {}", t.toString());
            }
        }
    }

    private int relayBatch() {
        Integer n = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, event_type, payload FROM event_outbox WHERE status = 'PENDING' "
                    + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", BATCH_LIMIT);
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                String type = (String) row.get("event_type");
                try {
                    Class<?> clazz = EventOutbox.TYPES.get(type);
                    if (clazz == null) throw new IllegalStateException("Unknown event type " + type);
                    publisher.publishEvent(EventOutbox.MAPPER.readValue((String) row.get("payload"), clazz));
                    jdbc.update("UPDATE event_outbox SET status = 'DONE', processed_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ?", id);
                } catch (Exception e) {
                    log.warn("Event outbox row {} ({}) could not be relayed: {}", id, type, e.toString());
                    jdbc.update("UPDATE event_outbox SET status = 'FAILED', processed_at = CURRENT_TIMESTAMP, "
                            + "last_error = ? WHERE id = ?", e.toString(), id);
                }
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }
}
