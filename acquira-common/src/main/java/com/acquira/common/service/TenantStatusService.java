package com.acquira.common.service;

import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Single authority for "is this tenant switched off?" used by every background
 * producer — PDF generation, report schedules, outbound email, external DB
 * pulls. Before this existed, tenant.status was only honoured by
 * ChurnRetrainScheduler; a deactivated tenant kept getting PDFs rendered,
 * emails queued and its source database pulled nightly.
 *
 * Semantics:
 *   - Only an EXPLICIT off-status (INACTIVE / SUSPENDED / DISABLED /
 *     DEACTIVATED, case-insensitive) counts as inactive.
 *   - null / unknown status, a missing tenant row, or a lookup error all count
 *     as ACTIVE — fail open, so a status typo or a transient DB hiccup can
 *     never silently stop every tenant's reports and pulls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantStatusService {

    private static final Set<String> INACTIVE_STATUSES =
            Set.of("INACTIVE", "SUSPENDED", "DISABLED", "DEACTIVATED");

    private final TenantRepository tenantRepository;

    /** True only when the tenant exists and carries an explicit off-status. */
    public boolean isInactive(Long tenantId) {
        if (tenantId == null) return false;
        try {
            return tenantRepository.findById(tenantId)
                    .map(Tenant::getStatus)
                    .map(s -> INACTIVE_STATUSES.contains(s.trim().toUpperCase(Locale.ROOT)))
                    .orElse(false);
        } catch (Exception e) {
            log.debug("Could not resolve status for tenant {} — treating as active: {}",
                    tenantId, e.getMessage());
            return false;
        }
    }
}
