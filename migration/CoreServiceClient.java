package com.acquira.pdf.controller;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Client for fetching data from the Core service.
 * In microservice mode: calls Core via HTTP.
 * Can also be enhanced to use direct service call if in same JVM.
 */
@Service
public class CoreServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CoreServiceClient.class);

    @Value("${core.service.url:http://localhost:8081}")
    private String coreServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public MerchantInsightsDTO fetchInsights(Long merchantId, int year, int month) {
        log.debug("Fetching insights from Core service: {}", coreServiceUrl);
        return restTemplate.getForObject(
                coreServiceUrl + "/api/business/insights/overview?merchantId={id}&year={y}&month={m}",
                MerchantInsightsDTO.class, merchantId, year, month);
    }
}
