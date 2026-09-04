package com.acquira.common.config;

import com.acquira.common.service.ReportCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Drops the report caches after any successful mutating call to the sales
 * admin APIs (team leads, country leads, agent profiles, targets).
 *
 * The cached Sales Executive tree is built from those tables, and unlike
 * fact/summary data they change through the UI rather than through the batch
 * jobs — so CacheEvictionJobListener never fires for them. Registered only on
 * the sales admin path patterns (see WebMvcConfig), so ordinary report reads
 * never pay for this check.
 */
@Component
public class SalesAdminCacheEvictionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SalesAdminCacheEvictionInterceptor.class);

    private final ReportCache reportCache;

    public SalesAdminCacheEvictionInterceptor(ReportCache reportCache) {
        this.reportCache = reportCache;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
        // Evict on ANY outcome of a mutating call, not just 2xx — same
        // reasoning as CacheEvictionJobListener: a request that failed halfway
        // (e.g. a bulk target upload writing 40 of 100 rows before erroring)
        // has already changed the data the caches were built from. Clearing on
        // failure costs one cold load; serving stale data costs correctness.
        if (mutating) {
            reportCache.evictAll();
            log.info("Report caches cleared after sales admin mutation {} {} (status {})",
                    method, request.getRequestURI(), response.getStatus());
        }
    }
}
