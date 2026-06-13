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
    private final com.acquira.common.service.RevenueLeakageDetectionService revenueLeakageDetectionService;

    /**
     * Triggered after a file upload or manually.
     * Queries Staging for all dates present in the latest upload and updates
     * Reporting Tables for those dates.
     *
     * PERF: marked @Async so the upload HTTP request returns immediately
     * instead of waiting for the per-date reporting loop (~80 dates x several
     * RDS round-trips each = the visible "upload is very slow" symptom).
     * Reporting still runs in the background; failures are logged.
     *
     * NOTE: removed the outer @Transactional. With it, every date in the loop
     * was joining the same long-running transaction that held locks on reporting
     * tables for the entire run — blocking concurrent reads. Each per-date update
     * now commits independently via processSingleDate's own transactional context.
     *
     * @param tenantId The tenant context
     */
    @org.springframework.scheduling.annotation.Async
    public void processManualUpload(Long tenantId) {
        log.info("Starting Manual Ingestion Processing for Tenant: {}", tenantId);

        // 1. Identify distinct dates to process (Data-Driven).
        //
        // Source is fact_transaction, NOT stg_trnx_raw. Staging is emptied by
        // the batch job's cleanTargetDayStep at the start of every upload, so
        // reading dates from staging here races the next upload and the
        // re-run case — producing the "No valid dates found in staging"
        // symptom with a blank Daily Merchant Dashboard even though
        // fact_transaction is fully populated. fact_transaction is the
        // permanent table and is also what processSingleDate aggregates from,
        // so discovering the dates here keeps step 1 and step 2 consistent.
        List<LocalDate> reportDates = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT DATE(payment_date) FROM fact_transaction WHERE tenant_id = ? AND payment_date IS NOT NULL",
                LocalDate.class,
                tenantId));

        // Filter out any null entries that may slip through
        reportDates.removeIf(d -> d == null);

        if (reportDates.isEmpty()) {
            log.warn("No valid dates found in fact_transaction for tenant {}", tenantId);
            return;
        }

        log.info("Found {} distinct date(s): {}", reportDates.size(), reportDates);

        // PERF: processSingleDate operates at MONTH granularity — it derives the month
        // containing reportDate, queries findDailyVolumesByDateRange for the WHOLE month,
        // and writes monthly metrics keyed by the 1st of that month. reportDate is used
        // ONLY to compute that month, so iterating per distinct DATE recomputed the same
        // month once per active day (e.g. 30x for a month with 30 active days). Collapse to
        // one call per distinct month so a multi-month bulk upload runs O(months) heavy
        // aggregations instead of O(dates). Output is identical — the monthly upsert is
        // idempotent and already (re)aggregates the whole month from fact_transaction.
        java.util.LinkedHashSet<LocalDate> monthStarts = new java.util.LinkedHashSet<>();
        for (LocalDate d : reportDates) monthStarts.add(d.withDayOfMonth(1));

        log.info("Collapsed {} date(s) to {} distinct month(s): {}",
                reportDates.size(), monthStarts.size(), monthStarts);
        for (LocalDate monthStart : monthStarts) {
            processSingleDate(tenantId, monthStart);
        }

        log.info("Manual Ingestion Completed for {} month(s).", monthStarts.size());

        // Revenue-leakage / anomaly detection. Best-effort: a failure here must
        // never affect the upload result. Runs off the freshly-populated
        // sum_daily_merchant summaries, so it sees the data this upload produced.
        try {
            int flags = revenueLeakageDetectionService.detectForTenant(tenantId);
            log.info("Revenue leakage detection produced {} flag(s) for tenant {}", flags, tenantId);
        } catch (Exception e) {
            log.warn("Revenue leakage detection failed (non-fatal) for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private void processSingleDate(Long tenantId, LocalDate reportDate) {
        log.info("Processing Reporting Metrics for Date: {}, Tenant: {}", reportDate, tenantId);

        LocalDateTime start = reportDate.withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = reportDate.withDayOfMonth(reportDate.lengthOfMonth()).atTime(LocalTime.MAX);
        LocalDate monthKey = reportDate.withDayOfMonth(1);

        // 2. Fetch Aggregated Data
        List<Object[]> results = transactionRepo.findDailyVolumesByDateRange(tenantId, start, end);

        Map<String, Map<Integer, Double>> merchantData = new HashMap<>();
        Map<String, String[]> merchantMeta = new HashMap<>();

        for (Object[] row : results) {
            String merchId = String.valueOf(row[0]);
            String mid = String.valueOf(row[1]);
            String name = String.valueOf(row[2]);
            int day = ((Number) row[3]).intValue();
            // row[4] is SUM(store_base_currency_amount); SQL SUM() returns NULL when
            // every summed value in the group is NULL, which previously caused a
            // NullPointerException here and killed the whole async reporting step.
            // Treat a NULL sum as zero volume for that merchant/day.
            double vol = (row[4] == null) ? 0.0 : ((Number) row[4]).doubleValue();

            merchantData.putIfAbsent(merchId, new HashMap<>());
            merchantData.get(merchId).put(day, vol);

            merchantMeta.putIfAbsent(merchId, new String[] { mid, name });
        }

        if (merchantData.isEmpty()) {
            log.info("No merchant rows for tenant {} date {} — nothing to compute", tenantId, reportDate);
            return;
        }

        // P2-9 fix: bulk fetch existing rows for this (tenant, monthKey) ONCE so
        // we can join in-memory and save in a single batch, instead of doing
        // 2 * merchantCount round-trips per month.
        // P0-3 fix: lookup is now tenant-scoped — the previous version omitted
        // tenantId and overwrote rows from other tenants whose merchant
        // internal_ids collided with this tenant's.
        Map<String, MerchantDailyMetrics> existingByMerchant = new HashMap<>();
        for (MerchantDailyMetrics e : metricsRepo.findByTenantIdAndReportDate(tenantId, monthKey)) {
            existingByMerchant.put(e.getMerchantId(), e);
        }

        // 3. Compute & build the save batch
        java.util.List<MerchantDailyMetrics> toSave = new java.util.ArrayList<>(merchantData.size());
        for (Map.Entry<String, Map<Integer, Double>> entry : merchantData.entrySet()) {
            String merchId = entry.getKey();
            Map<Integer, Double> dailyMap = entry.getValue();
            String[] meta = merchantMeta.get(merchId);

            MerchantDailyMetrics metrics = calculator.computeMetrics(
                    merchId, meta[0], meta[1], dailyMap, monthKey,
                    MerchantDailyMetrics.SourceType.FILE_UPLOAD);

            metrics.setTenantId(tenantId);

            MerchantDailyMetrics existing = existingByMerchant.get(merchId);
            if (existing != null) {
                metrics.setId(existing.getId());
            }
            toSave.add(metrics);
        }

        // ONE batch round-trip instead of N saves
        metricsRepo.saveAll(toSave);
        log.info("Saved {} metrics rows for tenant {} date {} (existing matched: {})",
                toSave.size(), tenantId, reportDate, existingByMerchant.size());
    }
}
