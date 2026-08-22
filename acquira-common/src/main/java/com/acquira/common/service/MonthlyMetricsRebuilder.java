package com.acquira.common.service;

import com.acquira.common.model.SumDailyMerchant;
import com.acquira.common.model.SumMonthlyMerchantMetrics;
import com.acquira.common.repository.SumDailyMerchantRepository;
import com.acquira.common.repository.SumMonthlyMerchantMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rebuilds sum_monthly_merchant_metrics for one tenant + month, in bulk.
 *
 * WHY THIS EXISTS: three ingest paths derive the same monthly metrics —
 * TransactionJobConfig (file/pull ingest), BulkMigrationService (migration and
 * summary rebuild) and BackfillIngestionService (day-range backfill). Only the
 * first had been converted from the original per-merchant
 * "findByMerchantAndMonth + save" loop; the other two still issued 2 queries
 * per merchant per month (a 10k-merchant tenant = 20k round trips, and the
 * backfill did that once per DAY while aggregating the whole month, so a
 * 31-day run repeated it 31 times). Rather than copy the fix a third time,
 * the logic lives here once.
 *
 * COST: 2 queries + 1 batched write per tenant+month, independent of merchant
 * count (hibernate.jdbc.batch_size must be set in the calling module for the
 * write to actually batch — see acquira-batch/application.properties).
 *
 * The caller owns the clean-slate DELETE (semantics differ per path) and the
 * choice of which months to rebuild.
 */
@Service
public class MonthlyMetricsRebuilder {

    private static final Logger log = LoggerFactory.getLogger(MonthlyMetricsRebuilder.class);

    private final SumDailyMerchantRepository dailyMerchantRepo;
    private final SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;
    private final MerchantMetricCalculator merchantMetricCalculator;

    public MonthlyMetricsRebuilder(SumDailyMerchantRepository dailyMerchantRepo,
                                   SumMonthlyMerchantMetricsRepository monthlyMetricsRepo,
                                   MerchantMetricCalculator merchantMetricCalculator) {
        this.dailyMerchantRepo = dailyMerchantRepo;
        this.monthlyMetricsRepo = monthlyMetricsRepo;
        this.merchantMetricCalculator = merchantMetricCalculator;
    }

    /**
     * @param monthYear the YYYY-MM key stored on the metrics row
     * @return number of metric rows written
     */
    public int rebuildMonth(Integer tenantId, String monthYear) {
        return rebuildMonth(tenantId, monthYear, null);
    }

    /**
     * @param beforeWrite optional hook run ONLY when the month actually has
     *        daily rows to rebuild from — used by the ingest job for its
     *        clean-slate DELETE, which must not fire for a month with no data
     *        (that would drop existing metrics instead of refreshing them).
     */
    public int rebuildMonth(Integer tenantId, String monthYear, Runnable beforeWrite) {
        YearMonth ym = YearMonth.parse(monthYear);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        List<SumDailyMerchant> dailyRecs =
                dailyMerchantRepo.findByTenantIdAndDateRange(tenantId, monthStart, monthEnd);
        if (dailyRecs.isEmpty()) return 0;

        if (beforeWrite != null) beforeWrite.run();

        Map<Long, List<SumDailyMerchant>> grouped = dailyRecs.stream()
                .collect(Collectors.groupingBy(SumDailyMerchant::getMerchantId));

        // Bulk-fetch the existing rows so the per-merchant loop below is pure
        // in-memory work. Preserving metricId/createdAt keeps this an UPDATE of
        // the existing row rather than an orphan insert.
        Map<Long, SumMonthlyMerchantMetrics> existingByMerchant = new HashMap<>();
        try {
            for (SumMonthlyMerchantMetrics e : monthlyMetricsRepo.findAllByTenantAndMonth(tenantId, monthYear)) {
                existingByMerchant.put(e.getMerchantId(), e);
            }
        } catch (RuntimeException ex) {
            // Degrade to per-merchant lookups rather than losing the rebuild.
            log.warn("bulk fetch of monthly metrics failed for {} {}, falling back: {}",
                    tenantId, monthYear, ex.getMessage());
            for (Long mId : grouped.keySet()) {
                monthlyMetricsRepo.findByMerchantAndMonth(tenantId, mId, monthYear)
                        .ifPresent(e -> existingByMerchant.put(mId, e));
            }
        }

        List<SumMonthlyMerchantMetrics> toSave = new ArrayList<>(grouped.size());
        for (Map.Entry<Long, List<SumDailyMerchant>> entry : grouped.entrySet()) {
            Long merchantId = entry.getKey();
            SumMonthlyMerchantMetrics metrics = merchantMetricCalculator.calculateMetrics(
                    entry.getValue(), tenantId, merchantId, monthYear);
            SumMonthlyMerchantMetrics existing = existingByMerchant.get(merchantId);
            if (existing != null) {
                metrics.setMetricId(existing.getMetricId());
                metrics.setCreatedAt(existing.getCreatedAt());
            }
            toSave.add(metrics);
        }

        if (toSave.isEmpty()) return 0;
        monthlyMetricsRepo.saveAll(toSave);
        return toSave.size();
    }
}
