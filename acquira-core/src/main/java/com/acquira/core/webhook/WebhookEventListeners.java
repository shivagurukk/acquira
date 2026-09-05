package com.acquira.core.webhook;

import com.acquira.common.event.IngestRunFinishedEvent;
import com.acquira.common.event.IntegrationRunFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges in-process application events onto the tenant's outbound webhooks.
 * Async so the publishing thread (a batch job listener, the pull service)
 * never waits on the enqueue INSERTs. Email alerts stay untouched —
 * IntegrationAlertListener keeps its own subscription to the same event.
 */
@Component
@RequiredArgsConstructor
public class WebhookEventListeners {

    private final WebhookDispatcher dispatcher;

    @Async
    @EventListener
    public void onIngestRunFinished(IngestRunFinishedEvent ev) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", ev.runId());
        data.put("jobName", ev.jobName());
        data.put("fileName", ev.fileName());
        data.put("source", ev.source());
        data.put("status", ev.status());
        if (ev.errorMessage() != null) data.put("errorMessage", ev.errorMessage());
        dispatcher.dispatch(ev.tenantId(),
                ev.isSuccess() ? WebhookEvents.INGEST_RUN_COMPLETED : WebhookEvents.INGEST_RUN_FAILED,
                data);
    }

    @Async
    @EventListener
    public void onIntegrationPullFailed(IntegrationRunFailedEvent ev) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runLogId", ev.runLogId());
        data.put("scheduleId", ev.scheduleId());
        data.put("reportName", ev.reportName());
        data.put("reportType", ev.reportType());
        data.put("connectionName", ev.connectionName());
        data.put("triggerType", ev.triggerType());
        data.put("errorMessage", ev.errorMessage());
        data.put("attemptNumber", ev.attemptNumber());
        data.put("maxRetries", ev.maxRetries());
        dispatcher.dispatch(ev.tenantId(), WebhookEvents.INTEGRATION_PULL_FAILED, data);
    }
}
