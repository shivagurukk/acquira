package com.acquira.pdf.controller;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.service.MerchantInsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Client for fetching merchant insight data.
 * When running inside acquira-core (same JVM), calls MerchantInsightService directly.
 * When running standalone, falls back to HTTP call to core service.
 */
@Service
public class CoreServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CoreServiceClient.class);

    @Value("${core.service.url:http://localhost:8081}")
    private String coreServiceUrl;

    @Autowired(required = false)
    private MerchantInsightService insightService;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * @deprecated Unscoped — does not verify the merchant belongs to the caller's
     * tenant. Use {@link #fetchInsights(Long, int, int, Long)}.
     */
    @Deprecated
    public MerchantInsightsDTO fetchInsights(Long merchantId, int year, int month) {
        return fetchInsights(merchantId, year, month, null);
    }

    /**
     * Tenant-scoped insight fetch. When {@code tenantId} is non-null the requested
     * merchant must belong to that tenant or a SecurityException is thrown
     * (closes the cross-tenant IDOR on the PDF module's /overview endpoint).
     */
    public MerchantInsightsDTO fetchInsights(Long merchantId, int year, int month, Long tenantId) {
        // Direct call if MerchantInsightService is available (same JVM)
        if (insightService != null) {
            log.debug("Fetching insights directly via MerchantInsightService (same JVM)");
            return insightService.getInsights(merchantId, year, month, tenantId);
        }

        // Fallback: HTTP call to core service (standalone mode). The X-Tenant-Id
        // header lets core's JwtRequestFilter / TenantContext re-establish scope
        // on the remote side.
        log.debug("Fetching insights via HTTP from Core service: {}", coreServiceUrl);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (tenantId != null) headers.set("X-Tenant-Id", String.valueOf(tenantId));
        org.springframework.http.HttpEntity<Void> entity =
                new org.springframework.http.HttpEntity<>(headers);
        return restTemplate.exchange(
                coreServiceUrl + "/api/business/insights/overview?merchantId={id}&year={y}&month={m}",
                org.springframework.http.HttpMethod.GET, entity,
                MerchantInsightsDTO.class, merchantId, year, month).getBody();
    }
}
