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

    public MerchantInsightsDTO fetchInsights(Long merchantId, int year, int month) {
        // Direct call if MerchantInsightService is available (same JVM)
        if (insightService != null) {
            log.debug("Fetching insights directly via MerchantInsightService (same JVM)");
            return insightService.getInsights(merchantId, year, month);
        }

        // Fallback: HTTP call to core service (standalone mode)
        log.debug("Fetching insights via HTTP from Core service: {}", coreServiceUrl);
        return restTemplate.getForObject(
                coreServiceUrl + "/api/business/insights/overview?merchantId={id}&year={y}&month={m}",
                MerchantInsightsDTO.class, merchantId, year, month);
    }
}
