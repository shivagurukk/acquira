package com.acquira.common.event;

/**
 * Published by the batch module's IngestRunJobListener after the ingest ledger
 * row for a batch job is closed — success OR failure, every source (upload,
 * server file, DB pull). The core module listens and fans it out to the
 * tenant's subscribed webhook endpoints; batch knows nothing about webhooks,
 * so this event is the seam between the two (same pattern as
 * {@link IntegrationRunFailedEvent}).
 */
public record IngestRunFinishedEvent(
        Long tenantId,
        Long runId,
        String jobName,
        String fileName,
        String source,
        String status,
        String errorMessage) {

    public boolean isSuccess() {
        return "COMPLETED".equalsIgnoreCase(status);
    }
}
