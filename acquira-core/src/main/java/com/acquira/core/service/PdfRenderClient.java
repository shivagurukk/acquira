package com.acquira.core.service;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.dto.PdfRenderRequest;
import com.acquira.common.security.InternalAuth;
import com.acquira.pdf.service.PlaywrightPdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How core gets a statement PDF rendered (email attachments, campaigns).
 *
 * Same JVM (acquira.role=all): calls PlaywrightPdfService directly, as before.
 * Split pods (acquira.role=core): the pdf module is not loaded here — Chromium
 * lives in the pdf pod — so the render goes over HTTP to that pod's
 * /internal/pdf/render. Mirrors CoreServiceClient on the pdf side.
 */
@Service
public class PdfRenderClient {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderClient.class);

    private final ObjectProvider<PlaywrightPdfService> localRenderer;
    private final InternalAuth internalAuth;
    private final String pdfServiceUrl;
    private final RestTemplate restTemplate;

    /**
     * Fail-fast when the pdf pod is unreachable or hung. Bulk statement runs
     * call this once per merchant from a single thread; without this, a hung
     * pod costs the full read timeout PER MERCHANT (1000 merchants x 3 min).
     * After {@link #OPEN_AFTER_FAILURES} consecutive transport failures every
     * call fails immediately for {@link #OPEN_MS}, then one call is let through.
     */
    private static final int OPEN_AFTER_FAILURES = 3;
    private static final long OPEN_MS = 60_000;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil;

    public PdfRenderClient(ObjectProvider<PlaywrightPdfService> localRenderer, InternalAuth internalAuth,
            @Value("${pdf.service.url:http://localhost:8084}") String pdfServiceUrl,
            @Value("${pdf.service.read-timeout-ms:180000}") int readTimeoutMs) {
        this.localRenderer = localRenderer;
        this.internalAuth = internalAuth;
        this.pdfServiceUrl = pdfServiceUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        // Generous: a render waits up to 30s for a browser slot and retries 3x.
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    public byte[] generatePdf(MerchantInsightsDTO data, String merchantName, String monthYear) {
        PlaywrightPdfService local = localRenderer.getIfAvailable();
        if (local != null) {
            return local.generatePdf(data, merchantName, monthYear);
        }
        if (System.currentTimeMillis() < openUntil) {
            throw new IllegalStateException("pdf service " + pdfServiceUrl + " unavailable ("
                    + consecutiveFailures.get() + " consecutive failures); retrying after "
                    + ((openUntil - System.currentTimeMillis()) / 1000) + "s");
        }
        log.debug("Rendering statement for {} via pdf service {}", merchantName, pdfServiceUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_PDF));
        headers.set(InternalAuth.HEADER, internalAuth.token());
        // Same correlation id on the pdf pod's log lines, so one search by cid
        // shows the whole request across both pods.
        String cid = org.slf4j.MDC.get("correlationId");
        if (cid != null) headers.set("X-Correlation-Id", cid);
        try {
            byte[] pdf = restTemplate.postForObject(pdfServiceUrl + "/internal/pdf/render",
                    new HttpEntity<>(new PdfRenderRequest(data, merchantName, monthYear), headers), byte[].class);
            consecutiveFailures.set(0);
            return pdf;
        } catch (ResourceAccessException e) {
            // Transport-level only (refused, DNS, timeout): a 4xx/5xx answer
            // means the pod is up and is not counted against it.
            if (consecutiveFailures.incrementAndGet() >= OPEN_AFTER_FAILURES) {
                openUntil = System.currentTimeMillis() + OPEN_MS;
                log.error("pdf service {} unreachable {} times in a row — failing fast for {}s",
                        pdfServiceUrl, consecutiveFailures.get(), OPEN_MS / 1000);
            }
            throw e;
        }
    }
}
