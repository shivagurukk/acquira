package com.acquira.batch.service;
import com.acquira.common.service.MetricCalculatorService;

import com.acquira.common.model.MerchantDailyMetrics;
import com.acquira.common.repository.MerchantDailyMetricsRepository;
import com.acquira.common.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManualIngestionService {

    private final TransactionRepository transactionRepo;
    private final MetricCalculatorService calculator;
    private final MerchantDailyMetricsRepository metricsRepo;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * Triggered after a file upload or manually.
     * Queries Staging for all dates present in the latest upload and updates
     * Reporting Tables for those dates.
     * 
     * @param tenantId The tenant context
     */
    @Transactional
    public void processManualUpload(Long tenantId) {
        log.info("Starting Manual Ingestion Processing for Tenant: {}", tenantId);

        // 1. Identify distinct dates from Staging (Data-Driven)
        List<LocalDate> reportDates = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ? AND payment_date IS NOT NULL",
                LocalDate.class,
                tenantId));

        // Filter out any null entries that may slip through
        reportDates.removeIf(d -> d == null);

        if (reportDates.isEmpty()) {
            log.warn("No valid dates found in staging for tenant {}", tenantId);
            return;
        }

        log.info("Found {} distinct dates to process: {}", reportDates.size(), reportDates);

        for (LocalDate reportDate : reportDates) {
            processSingleDate(tenantId, reportDate);
        }

        log.info("Manual Ingestion Completed for all dates.");
    }

    private void processSingleDate(Long tenantId, LocalDate reportDate) {
        log.info("Processing Reporting Metrics for Date: {}, Tenant: {}", reportDate, tenantId);

        LocalDateTime start = reportDate.withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = reportDate.withDayOfMonth(reportDate.lengthOfMonth()).atTime(LocalTime.MAX);

        // 2. Fetch Aggregated Data
        List<Object[]> results = transactionRepo.findDailyVolumesByDateRange(tenantId, start, end);

        Map<String, Map<Integer, Double>> merchantData = new HashMap<>();
        Map<String, String[]> merchantMeta = new HashMap<>();

        for (Object[] row : results) {
            String merchId = String.valueOf(row[0]);
            String mid = String.valueOf(row[1]);
            String name = String.valueOf(row[2]);
            int day = ((Number) row[3]).intValue();
            double vol = ((Number) row[4]).doubleValue();

            merchantData.putIfAbsent(merchId, new HashMap<>());
            merchantData.get(merchId).put(day, vol);

            merchantMeta.putIfAbsent(merchId, new String[] { mid, name });
        }

        // 3. Compute & Save
        for (Map.Entry<String, Map<Integer, Double>> entry : merchantData.entrySet()) {
            String merchId = entry.getKey();
            Map<Integer, Double> dailyMap = entry.getValue();
            String[] meta = merchantMeta.get(merchId);

            MerchantDailyMetrics metrics = calculator.computeMetrics(
                    merchId, meta[0], meta[1], dailyMap, reportDate.withDayOfMonth(1),
                    MerchantDailyMetrics.SourceType.FILE_UPLOAD);

            metrics.setTenantId(tenantId);

            metricsRepo.findByMerchantIdAndReportDate(merchId, reportDate.withDayOfMonth(1))
                    .ifPresent(existing -> metrics.setId(existing.getId()));

            metricsRepo.save(metrics);
        }
    }
}
