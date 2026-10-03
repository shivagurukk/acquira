package com.acquira.common.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Cross-pod replacement for {@code ApplicationEventPublisher.publishEvent} on
 * the batch -> core seam.
 *
 * The events in this package are raised in the batch module and handled in
 * core (alert emails, webhooks). As in-process Spring events they only reach a
 * listener in the SAME JVM, so with batch in its own pod they would vanish
 * without an error. Publishing through here writes the event to
 * {@code event_outbox}; core's EventOutboxRelay claims the row and re-publishes
 * it as a normal Spring event, so the listeners themselves are unchanged.
 *
 * Adding an event: add the record to this package and register it in
 * {@link #TYPES}.
 *
 * Fallback: if the insert fails (e.g. migration V2026_10_03_01 not applied) the
 * event is published in-process instead — correct for a single-JVM deployment,
 * and no worse than before for a split one.
 */
@Service
public class EventOutbox {

    private static final Logger log = LoggerFactory.getLogger(EventOutbox.class);

    /** event_type column value -> payload class. The relay rejects anything else. */
    public static final Map<String, Class<?>> TYPES = Map.of(
            "IngestRunFinishedEvent", IngestRunFinishedEvent.class,
            "IntegrationRunFailedEvent", IntegrationRunFailedEvent.class);

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // records with derived accessors (isSuccess) serialize an extra property
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher localPublisher;

    public EventOutbox(JdbcTemplate jdbc, ApplicationEventPublisher localPublisher) {
        this.jdbc = jdbc;
        this.localPublisher = localPublisher;
    }

    public void publish(Long tenantId, Object event) {
        String type = event.getClass().getSimpleName();
        if (!TYPES.containsKey(type)) {
            throw new IllegalArgumentException("Event type not registered in EventOutbox.TYPES: " + type);
        }
        try {
            jdbc.update("INSERT INTO event_outbox (event_type, tenant_id, payload) VALUES (?, ?, ?)",
                    type, tenantId, MAPPER.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("event_outbox insert failed for {} — publishing in-process instead "
                    + "(is migration V2026_10_03_01 applied?): {}", type, e.toString());
            localPublisher.publishEvent(event);
        }
    }
}
