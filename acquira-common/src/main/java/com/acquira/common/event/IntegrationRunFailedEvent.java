package com.acquira.common.event;

import java.time.LocalDate;

/**
 * Published by the batch module's IntegrationPullService when a scheduled pull
 * reaches its FINAL failed attempt (RETRYING states never publish). The core
 * module listens and emails the schedule's alert recipients through the
 * tenant's own SMTP config — batch has no mail dependency, so this event is
 * the seam between the two.
 */
public record IntegrationRunFailedEvent(
        Long tenantId,
        Long runLogId,
        Long scheduleId,
        String reportName,
        String reportType,
        String connectionName,
        String triggerType,
        String errorMessage,
        int attemptNumber,
        int maxRetries,
        LocalDate dateRangeFrom,
        LocalDate dateRangeTo,
        String alertEmails) {
}
