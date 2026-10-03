package com.acquira.common.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The outbox is the only path batch events take to core once they are separate
 * pods, so what is written must read back as the same event.
 */
class EventOutboxTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
    private final EventOutbox outbox = new EventOutbox(jdbc, local);

    private Object roundTrip(Long tenantId, Object event) throws Exception {
        outbox.publish(tenantId, event);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), type.capture(), eq(tenantId), payload.capture());
        verifyNoInteractions(local);
        return EventOutbox.MAPPER.readValue(payload.getValue(), EventOutbox.TYPES.get(type.getValue()));
    }

    @Test
    @DisplayName("IngestRunFinishedEvent survives the outbox unchanged")
    void ingestRunFinished() throws Exception {
        IngestRunFinishedEvent ev = new IngestRunFinishedEvent(
                8L, 42L, "transactionJob", "txn_2026_10.csv", "UPLOAD", "FAILED", "bad row 17");
        assertEquals(ev, roundTrip(8L, ev));
    }

    @Test
    @DisplayName("IntegrationRunFailedEvent survives the outbox unchanged (dates included)")
    void integrationRunFailed() throws Exception {
        IntegrationRunFailedEvent ev = new IntegrationRunFailedEvent(
                8L, 7L, 3L, "Daily txn pull", "TRANSACTION", "ams-prod", "SCHEDULED", "timeout",
                3, 3, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2), "ops@example.com");
        assertEquals(ev, roundTrip(8L, ev));
    }

    @Test
    @DisplayName("every registered type is keyed by its simple class name")
    void registryKeys() {
        EventOutbox.TYPES.forEach((key, type) -> assertEquals(type.getSimpleName(), key));
    }

    @Test
    @DisplayName("insert failure falls back to an in-process event instead of dropping it")
    void fallsBackToLocalPublish() {
        when(jdbc.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("relation \"event_outbox\" does not exist"));
        IngestRunFinishedEvent ev = new IngestRunFinishedEvent(1L, 1L, "j", "f", "UPLOAD", "COMPLETED", null);
        outbox.publish(1L, ev);
        verify(local).publishEvent(ev);
    }

    @Test
    @DisplayName("an unregistered event type is rejected at publish time")
    void rejectsUnregisteredType() {
        assertThrows(IllegalArgumentException.class, () -> outbox.publish(1L, "not an event"));
        verifyNoInteractions(jdbc, local);
    }
}
