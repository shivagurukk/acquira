package com.acquira.batch.service;
import com.acquira.common.service.MetricCalculatorService;

import com.acquira.common.model.DataSourceConfig;
import com.acquira.common.model.MerchantDailyMetrics;
import com.acquira.common.model.ReportQueryConfig;
import com.acquira.common.model.ReportRunLog;
import com.acquira.common.repository.DataSourceConfigRepository;
import com.acquira.common.repository.MerchantDailyMetricsRepository;
import com.acquira.common.repository.ReportQueryConfigRepository;
import com.acquira.common.repository.ReportRunLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledDbPullJob {

    private final DataSourceConfigRepository dataSourceRepo;
    private final ReportQueryConfigRepository queryRepo;
    private final ReportRunLogRepository logRepo;
    private final MerchantDailyMetricsRepository metricsRepo;

    private final UniversalDatabaseClient dbClient;
    private final MetricCalculatorService calculator;

    /**
     * Runs daily at 02:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM Daily
    @Transactional
    public void runDailyIngestion() {
        log.info("Starting Daily Ingestion Job...");

        List<ReportQueryConfig> queries = queryRepo.findByIsActiveTrue();

        for (ReportQueryConfig query : queries) {
            executeReport(query);
        }
    }

    private void executeReport(ReportQueryConfig query) {
        ReportRunLog runLog = new ReportRunLog();
        runLog.setQuery(query);
        runLog.setStartTime(LocalDateTime.now());
        runLog.setStatus(ReportRunLog.Status.RUNNING);
        logRepo.save(runLog);

        try {
            DataSourceConfig ds = query.getDataSource();

            // 1. Prepare Params (e.g. Current Month)
            Map<String, Object> params = new HashMap<>();
            LocalDate now = LocalDate.now();
            params.put("year", now.getYear());
            params.put("month", now.getMonthValue());
            // Add more dynamic params if needed

            // 2. Fetch Raw Data via Universal Client
            List<Map<String, Object>> rawRows = dbClient.executeQuery(ds, query.getSqlText(), params);

            // 3. Process Data: Group by Merchant
            processRawData(rawRows, now.withDayOfMonth(1));

            // 4. Success Log
            runLog.setStatus(ReportRunLog.Status.SUCCESS);
            runLog.setRowCount(rawRows.size());

        } catch (Exception e) {
            log.error("Job Failed: " + query.getReportName(), e);
            runLog.setStatus(ReportRunLog.Status.FAILED);
            runLog.setErrorMessage(e.getMessage());
        } finally {
            runLog.setEndTime(LocalDateTime.now());
            logRepo.save(runLog);
        }
    }

    private void processRawData(List<Map<String, Object>> rows, LocalDate reportDate) {
        // Assume SQL returns: MERCHANT_ID, MID, MERCHANT_NAME, DAY, VOLUME

        // Group by Merchant ID
        Map<String, List<Map<String, Object>>> grouped = rows.stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.get("MERCHANT_ID"))));

        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            String merchId = entry.getKey();
            List<Map<String, Object>> merchRows = entry.getValue();

            // Extract Meta
            // Warning: Ensure query returns these columns or handle errors
            if (merchRows.isEmpty())
                continue;

            String mid = String.valueOf(merchRows.get(0).get("MID"));
            String name = String.valueOf(merchRows.get(0).get("MERCHANT_NAME"));

            // Build Daily Map
            Map<Integer, Double> dailyMap = new HashMap<>();
            for (Map<String, Object> row : merchRows) {
                // Determine Day. If it's a date object, extract day. If int, parse.
                int day;
                Object dayObj = row.get("DAY");
                if (dayObj instanceof Number) {
                    day = ((Number) dayObj).intValue();
                } else {
                    day = 1; // Default or Parse Logic for safety
                }

                double vol = 0.0;
                Object volObj = row.get("VOLUME");
                if (volObj instanceof Number) {
                    vol = ((Number) volObj).doubleValue();
                }

                dailyMap.put(day, vol);
            }

            // Calculate BI Metrics
            MerchantDailyMetrics metrics = calculator.computeMetrics(
                    merchId, mid, name, dailyMap, reportDate, MerchantDailyMetrics.SourceType.DB_PULL);

            // Save (Upsert logic)
            metricsRepo.findByMerchantIdAndReportDate(merchId, reportDate)
                    .ifPresent(existing -> metrics.setId(existing.getId()));

            metricsRepo.save(metrics);
        }
    }
}
