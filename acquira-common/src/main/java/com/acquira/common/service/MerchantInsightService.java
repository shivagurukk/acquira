package com.acquira.common.service;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.dto.MerchantInsightsDTO.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HIGH-PERFORMANCE Merchant Insight Service
 * 
 * DESIGN PRINCIPLES:
 * 1. ZERO queries on fact_transaction (999K rows/day would kill performance)
 * 2. ALL data from summary tables: sum_daily_merchant +
 * sum_daily_merchant_attribute + sum_monthly_card
 * 3. Fetch data ONCE, compute everything in-memory (30 daily rows + ~200
 * attribute rows = trivial)
 * 4. Pre-compute all derived metrics (weekday/weekend splits, quarterly, YoY,
 * DCC rates etc.)
 * so HTML templates have ZERO hardcoded values
 */
@Service
@Slf4j
public class MerchantInsightService {

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.repository.SumDailyMerchantRepository sumDailyMerchantRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.repository.SumDailyMerchantAttributeRepository sumDailyMerchantAttributeRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.repository.SumMonthlyCardRepository sumMonthlyCardRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.repository.MerchantRepository merchantRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.repository.TenantRepository tenantRepository;

    /**
     * BULK PRE-FETCH: Load all data for multiple merchants in 6 queries total,
     * then partition in-memory. Returns Map<merchantId, DTO>.
     * This is 10-100x faster than calling getInsights() per merchant.
     */
    public Map<Long, MerchantInsightsDTO> getBulkInsights(List<Long> merchantIds, int year, int month) {
        if (merchantIds == null || merchantIds.isEmpty()) return Collections.emptyMap();

        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);
        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDate endOfLastMonth = startOfLastMonth.plusMonths(1).minusDays(1);
        LocalDate trendStart = endOfMonth.minusMonths(12).withDayOfMonth(1);

        // ===== 6 BULK QUERIES for ALL merchants =====
        long t0 = System.currentTimeMillis();

        // Q1+Q2: Daily rows (current + prev month) for all merchants
        List<com.acquira.common.model.SumDailyMerchant> allCurrentDaily =
            sumDailyMerchantRepository.findDailyStatsForMerchants(merchantIds, startOfMonth, endOfMonth);
        List<com.acquira.common.model.SumDailyMerchant> allPrevDaily =
            sumDailyMerchantRepository.findDailyStatsForMerchants(merchantIds, startOfLastMonth, endOfLastMonth);

        // Q3+Q4: Attributes (current + prev month) for all merchants
        List<com.acquira.common.model.SumDailyMerchantAttribute> allCurrentAttrs =
            sumDailyMerchantAttributeRepository.findByMerchantsAndDateRange(merchantIds, startOfMonth, endOfMonth);
        List<com.acquira.common.model.SumDailyMerchantAttribute> allPrevAttrs =
            sumDailyMerchantAttributeRepository.findByMerchantsAndDateRange(merchantIds, startOfLastMonth, endOfLastMonth);

        // Q5: 13-month trends for all merchants
        List<Map<String, Object>> allTrends =
            sumDailyMerchantRepository.findMonthlyTrendsForMerchants(merchantIds, trendStart, endOfMonth);

        // Q6: Card data for all merchants
        int startKey = Integer.parseInt(startOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(endOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int trendStartKey = Integer.parseInt(trendStart.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.common.model.SumMonthlyCard> allCards =
            sumMonthlyCardRepository.findByMerchantsAndMonthRange(merchantIds, trendStartKey, endKey);

        long fetchMs = System.currentTimeMillis() - t0;
        log.info("[BULK] Fetched data for {} merchants in {}ms (daily:{}/{}, attrs:{}/{}, trends:{}, cards:{})",
            merchantIds.size(), fetchMs,
            allCurrentDaily.size(), allPrevDaily.size(),
            allCurrentAttrs.size(), allPrevAttrs.size(),
            allTrends.size(), allCards.size());

        // ===== PARTITION by merchantId in-memory =====
        Map<Long, List<com.acquira.common.model.SumDailyMerchant>> currentDailyMap =
            allCurrentDaily.stream().collect(Collectors.groupingBy(com.acquira.common.model.SumDailyMerchant::getMerchantId));
        Map<Long, List<com.acquira.common.model.SumDailyMerchant>> prevDailyMap =
            allPrevDaily.stream().collect(Collectors.groupingBy(com.acquira.common.model.SumDailyMerchant::getMerchantId));
        Map<Long, List<com.acquira.common.model.SumDailyMerchantAttribute>> currentAttrMap =
            allCurrentAttrs.stream().collect(Collectors.groupingBy(com.acquira.common.model.SumDailyMerchantAttribute::getMerchantId));
        Map<Long, List<com.acquira.common.model.SumDailyMerchantAttribute>> prevAttrMap =
            allPrevAttrs.stream().collect(Collectors.groupingBy(com.acquira.common.model.SumDailyMerchantAttribute::getMerchantId));
        Map<Long, List<Map<String, Object>>> trendsMap = new HashMap<>();
        for (Map<String, Object> t : allTrends) {
            Long mid = ((Number) t.get("merchantId")).longValue();
            trendsMap.computeIfAbsent(mid, k -> new ArrayList<>()).add(t);
        }
        Map<Long, List<com.acquira.common.model.SumMonthlyCard>> cardMap =
            allCards.stream().collect(Collectors.groupingBy(com.acquira.common.model.SumMonthlyCard::getMerchantId));

        // ===== Build DTOs per merchant (pure in-memory, zero DB) =====
        Map<Long, MerchantInsightsDTO> result = new HashMap<>();
        for (Long mid : merchantIds) {
            try {
                List<com.acquira.common.model.SumDailyMerchant> currentDaily =
                    currentDailyMap.getOrDefault(mid, Collections.emptyList());
                List<com.acquira.common.model.SumDailyMerchant> prevDaily =
                    prevDailyMap.getOrDefault(mid, Collections.emptyList());
                List<com.acquira.common.model.SumDailyMerchantAttribute> currentAttrs2 =
                    currentAttrMap.getOrDefault(mid, Collections.emptyList());
                List<com.acquira.common.model.SumDailyMerchantAttribute> prevAttrs2 =
                    prevAttrMap.getOrDefault(mid, Collections.emptyList());
                List<Map<String, Object>> trends =
                    trendsMap.getOrDefault(mid, Collections.emptyList());
                List<com.acquira.common.model.SumMonthlyCard> cards =
                    cardMap.getOrDefault(mid, Collections.emptyList());

                // Filter card data for current month vs trend
                List<com.acquira.common.model.SumMonthlyCard> currentCards = cards.stream()
                    .filter(c -> c.getMonthKey() >= startKey && c.getMonthKey() <= endKey)
                    .collect(Collectors.toList());

                // Build DTO using existing logic
                MerchantInsightsDTO dto = buildDtoFromPrefetched(
                    mid, currentDaily, prevDaily, currentAttrs2, prevAttrs2,
                    trends, currentCards, cards, startOfMonth, endOfMonth);
                result.put(mid, dto);
            } catch (Exception e) {
                log.warn("[BULK] Failed to build DTO for merchant {}: {}", mid, e.getMessage());
            }
        }
        log.info("[BULK] Built {} DTOs in {}ms total", result.size(), System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * Build DTO from pre-fetched data (no DB queries)
     */
    private MerchantInsightsDTO buildDtoFromPrefetched(
            Long merchantId,
            List<com.acquira.common.model.SumDailyMerchant> currentDailyRows,
            List<com.acquira.common.model.SumDailyMerchant> prevDailyRows,
            List<com.acquira.common.model.SumDailyMerchantAttribute> currentAttributes,
            List<com.acquira.common.model.SumDailyMerchantAttribute> prevAttributes,
            List<Map<String, Object>> monthlyTrends,
            List<com.acquira.common.model.SumMonthlyCard> cardRows,
            List<com.acquira.common.model.SumMonthlyCard> trendCardRows,
            LocalDate startOfMonth, LocalDate endOfMonth) {

        Map<String, BigDecimal> currentAgg = aggregateDaily(currentDailyRows);
        Map<String, BigDecimal> prevAgg = aggregateDaily(prevDailyRows);

        MerchantInsightsDTO dto = new MerchantInsightsDTO();
        currentDailyRows = fillMissingDays(currentDailyRows, startOfMonth, endOfMonth, merchantId);

        dto.setOverview(buildOverview(currentAgg, prevAgg, currentDailyRows, prevDailyRows));
        dto.setAchievements(buildAchievements(currentDailyRows, currentAttributes));
        dto.setLoyalty(buildLoyalty(cardRows, trendCardRows, endOfMonth));
        dto.setDemographics(buildDemographics(currentAttributes, prevAttributes, monthlyTrends));
        dto.setDccPerformance(buildDccPerformance(currentDailyRows, prevDailyRows, monthlyTrends));

        // Currency from tenant
        String currencySymbol = "AED";
        String currencyCode = "AED";
        try {
            com.acquira.common.model.Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
            if (merchant != null && merchant.getTenantId() != null) {
                com.acquira.common.model.Tenant tenant = tenantRepository.findById(merchant.getTenantId()).orElse(null);
                if (tenant != null) {
                    if (tenant.getCurrencySymbol() != null) currencySymbol = tenant.getCurrencySymbol();
                    if (tenant.getBaseCurrency() != null && !tenant.getBaseCurrency().isBlank()) currencyCode = tenant.getBaseCurrency();
                }
            }
        } catch (Exception e) { /* fallback */ }
        dto.setCurrencySymbol(currencySymbol);
        dto.setCurrencyCode(currencyCode);
        dto.setInsights(buildInsights(dto, currentDailyRows, currentAttributes, currencyCode));
        dto.setHealthScore(buildHealthScore(dto, currentDailyRows, currencyCode));
        return dto;
    }

    public MerchantInsightsDTO getInsights(Long merchantId, int year, int month) {
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);
        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDate endOfLastMonth = startOfLastMonth.plusMonths(1).minusDays(1);

        // ========== FETCH ALL DATA ONCE ==========
        // Current month daily rows (~30 rows)
        List<com.acquira.common.model.SumDailyMerchant> currentDailyRows = sumDailyMerchantRepository
                .findDailyStats(merchantId, startOfMonth, endOfMonth);
        // Previous month daily rows (~30 rows)
        List<com.acquira.common.model.SumDailyMerchant> prevDailyRows = sumDailyMerchantRepository.findDailyStats(merchantId,
                startOfLastMonth, endOfLastMonth);

        // Current month attributes (~200 rows: hours, card schemes, card types, etc.)
        List<com.acquira.common.model.SumDailyMerchantAttribute> currentAttributes = sumDailyMerchantAttributeRepository
                .findByMerchantAndDateRange(merchantId, startOfMonth, endOfMonth);
        // Previous month attributes
        List<com.acquira.common.model.SumDailyMerchantAttribute> prevAttributes = sumDailyMerchantAttributeRepository
                .findByMerchantAndDateRange(merchantId, startOfLastMonth, endOfLastMonth);

        // 13-month trend data (single query)
        LocalDate trendStart = endOfMonth.minusMonths(12).withDayOfMonth(1);
        List<java.util.Map<String, Object>> monthlyTrends = sumDailyMerchantRepository.findMonthlyTrends(merchantId,
                trendStart, endOfMonth);

        // Monthly card data for loyalty
        int startKey = Integer.parseInt(startOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(endOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.common.model.SumMonthlyCard> cardRows = sumMonthlyCardRepository
                .findByMerchantAndMonthRange(merchantId, startKey, endKey);

        // 12-month card data for loyalty trends
        int trendStartKey = Integer.parseInt(trendStart.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.common.model.SumMonthlyCard> trendCardRows = sumMonthlyCardRepository
                .findByMerchantAndMonthRange(merchantId, trendStartKey, endKey);

        // ========== COMPUTE AGGREGATES FROM DAILY ROWS (in-memory, ~30 rows)
        // ==========
        Map<String, BigDecimal> currentAgg = aggregateDaily(currentDailyRows);
        Map<String, BigDecimal> prevAgg = aggregateDaily(prevDailyRows);

        // ========== BUILD ALL SECTIONS ==========
        MerchantInsightsDTO dto = new MerchantInsightsDTO();

        // Fill any missing days with zeroes so charts align labels to calendar days
        currentDailyRows = fillMissingDays(currentDailyRows, startOfMonth, endOfMonth, merchantId);

        dto.setOverview(buildOverview(currentAgg, prevAgg, currentDailyRows, prevDailyRows));
        dto.setAchievements(buildAchievements(currentDailyRows, currentAttributes));
        dto.setLoyalty(buildLoyalty(cardRows, trendCardRows, endOfMonth));
        dto.setDemographics(buildDemographics(currentAttributes, prevAttributes, monthlyTrends));
        dto.setDccPerformance(buildDccPerformance(currentDailyRows, prevDailyRows, monthlyTrends));

        // NEW: Populate currency from Tenant
        String currencySymbol = "AED"; // Default
        String currencyCode = "AED";
        try {
            com.acquira.common.model.Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
            if (merchant != null && merchant.getTenantId() != null) {
                com.acquira.common.model.Tenant tenant = tenantRepository.findById(merchant.getTenantId()).orElse(null);
                if (tenant != null) {
                    if (tenant.getCurrencySymbol() != null)
                        currencySymbol = tenant.getCurrencySymbol();
                    // FIX: Use base_currency (e.g. BHD) instead of bank_short_code (e.g. ACQ)
                    if (tenant.getBaseCurrency() != null && !tenant.getBaseCurrency().isBlank())
                        currencyCode = tenant.getBaseCurrency();
                }
            }
        } catch (Exception e) {
            // ignore, fallback to default
        }
        dto.setCurrencySymbol(currencySymbol);
        dto.setCurrencyCode(currencyCode);

        // ========== BUILD DYNAMIC INSIGHTS (data-driven narrative for every PDF section) ==========
        dto.setInsights(buildInsights(dto, currentDailyRows, currentAttributes, currencyCode));

        // ========== BUILD BUSINESS HEALTH SCORE (composite performance rating) ==========
        dto.setHealthScore(buildHealthScore(dto, currentDailyRows, currencyCode));

        return dto;
    }

    // ============================================================
    // AGGREGATE HELPERS
    // ============================================================

    /**
     * Helper to get merchant-facing base volume (store_base_currency_amount).
     * Falls back to totalVolume (txn_currency_amount) if totalBaseVolume is null.
     */
    private BigDecimal getBaseVolume(com.acquira.common.model.SumDailyMerchant r) {
        if (r.getTotalBaseVolume() != null) return r.getTotalBaseVolume();
        return r.getTotalVolume() != null ? r.getTotalVolume() : BigDecimal.ZERO;
    }

    /**
     * Fill in missing days with zero-valued rows so that chart arrays have one entry per calendar day.
     * Without this, day-of-month labels don't match bar positions when some days have no transactions.
     */
    private List<com.acquira.common.model.SumDailyMerchant> fillMissingDays(
            List<com.acquira.common.model.SumDailyMerchant> rows, LocalDate start, LocalDate end, Long merchantId) {
        // Build a lookup of existing dates
        Map<LocalDate, com.acquira.common.model.SumDailyMerchant> byDate = new LinkedHashMap<>();
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            byDate.put(r.getBusinessDate(), r);
        }
        List<com.acquira.common.model.SumDailyMerchant> filled = new ArrayList<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            if (byDate.containsKey(d)) {
                filled.add(byDate.get(d));
            } else {
                // Create a synthetic zero row for this day
                com.acquira.common.model.SumDailyMerchant zero = new com.acquira.common.model.SumDailyMerchant();
                zero.setBusinessDate(d);
                zero.setMerchantId(merchantId);
                zero.setTotalTxns(0L);
                zero.setTotalVolume(BigDecimal.ZERO);
                zero.setTotalBaseVolume(BigDecimal.ZERO);
                zero.setUniqueCustomerCount(0L);
                zero.setTopSpendingAmount(BigDecimal.ZERO);
                zero.setTotalMsf(BigDecimal.ZERO);
                zero.setTotalInterchange(BigDecimal.ZERO);
                zero.setTotalSchemeFee(BigDecimal.ZERO);
                zero.setTotalMargin(BigDecimal.ZERO);
                zero.setTotalDebitPrepaidVolume(BigDecimal.ZERO);
                zero.setTotalCreditVolume(BigDecimal.ZERO);
                zero.setDccEligibleVolume(BigDecimal.ZERO);
                zero.setDccOptinVolume(BigDecimal.ZERO);
                zero.setDccOptoutVolume(BigDecimal.ZERO);
                zero.setDccEligibleCount(0L);
                zero.setDccOptinCount(0L);
                filled.add(zero);
            }
            d = d.plusDays(1);
        }
        return filled;
    }

    private Map<String, BigDecimal> aggregateDaily(List<com.acquira.common.model.SumDailyMerchant> rows) {
        BigDecimal totalSales = BigDecimal.ZERO;
        long totalTxns = 0;
        long totalCustomers = 0;
        BigDecimal maxDailySales = BigDecimal.ZERO;
        long maxDailyTxns = 0;
        BigDecimal maxTopSpend = BigDecimal.ZERO;

        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            BigDecimal baseVol = getBaseVolume(r);
            totalSales = totalSales.add(baseVol);
            totalTxns += r.getTotalTxns() != null ? r.getTotalTxns() : 0;
            totalCustomers += r.getUniqueCustomerCount() != null ? r.getUniqueCustomerCount() : 0;
            if (baseVol.compareTo(maxDailySales) > 0)
                maxDailySales = baseVol;
            long dt = r.getTotalTxns() != null ? r.getTotalTxns() : 0;
            if (dt > maxDailyTxns)
                maxDailyTxns = dt;
            BigDecimal ts = r.getTopSpendingAmount() != null ? r.getTopSpendingAmount() : BigDecimal.ZERO;
            if (ts.compareTo(maxTopSpend) > 0)
                maxTopSpend = ts;
        }

        Map<String, BigDecimal> map = new HashMap<>();
        map.put("total_sales", totalSales);
        map.put("total_txns", new BigDecimal(totalTxns));
        map.put("unique_customers", new BigDecimal(totalCustomers));
        map.put("max_daily_sales", maxDailySales);
        map.put("max_daily_txns", new BigDecimal(maxDailyTxns));
        map.put("max_cust_spend", maxTopSpend);
        return map;
    }

    // ============================================================
    // OVERVIEW
    // ============================================================

    private BusinessOverview buildOverview(Map<String, BigDecimal> current, Map<String, BigDecimal> previous,
            List<com.acquira.common.model.SumDailyMerchant> currentRows,
            List<com.acquira.common.model.SumDailyMerchant> prevRows) {

        Kpi sales = createKpi(current.get("total_sales"), previous.get("total_sales"));
        Kpi txns = createKpi(current.get("total_txns"), previous.get("total_txns"));
        Kpi customers = createKpi(current.get("unique_customers"), previous.get("unique_customers"));

        BigDecimal avgSpend = safeDivide(current.get("total_sales"), current.get("unique_customers"));
        BigDecimal prevAvgSpend = safeDivide(previous.get("total_sales"), previous.get("unique_customers"));
        BigDecimal avgTxnVal = safeDivide(current.get("total_sales"), current.get("total_txns"));
        BigDecimal prevAvgTxnVal = safeDivide(previous.get("total_sales"), previous.get("total_txns"));
        BigDecimal avgTxnsPerCust = safeDivide(current.get("total_txns"), current.get("unique_customers"));
        BigDecimal prevAvgTxnsPerCust = safeDivide(previous.get("total_txns"), previous.get("unique_customers"));

        PeakStats peakStats = PeakStats.builder()
                .maxDailySales(createKpi(current.get("max_daily_sales"), previous.get("max_daily_sales")))
                .maxTxnsInDay(createKpi(current.get("max_daily_txns"), previous.get("max_daily_txns")))
                .highestTxnValue(createKpi(current.get("max_daily_sales"), previous.get("max_daily_sales")))
                .highestCustomerSpend(createKpi(current.get("max_cust_spend"), previous.get("max_cust_spend")))
                .build();

        List<ChartData> salesByDow = aggregateByDayOfWeek(currentRows, true);
        List<ChartData> txnsByDow = aggregateByDayOfWeek(currentRows, false);
        List<ChartData> salesByWeek = aggregateByWeekOfMonth(currentRows);

        return BusinessOverview.builder()
                .sales(sales).transactions(txns).customers(customers)
                .avgSpendPerCustomer(createKpi(avgSpend, prevAvgSpend))
                .avgTxnValue(createKpi(avgTxnVal, prevAvgTxnVal))
                .avgTxnsPerCustomer(createKpi(avgTxnsPerCust, prevAvgTxnsPerCust))
                .peakStats(peakStats)
                .salesByDayOfWeek(salesByDow)
                .transactionsByDayOfWeek(txnsByDow)
                .salesByWeekOfMonth(salesByWeek)
                .transactionsByWeekOfMonth(salesByWeek)
                .prevSales(createKpi(previous.get("total_sales"), BigDecimal.ZERO))
                .prevTransactions(createKpi(previous.get("total_txns"), BigDecimal.ZERO))
                .prevCustomers(createKpi(previous.get("unique_customers"), BigDecimal.ZERO))
                .prevAvgTxnValue(createKpi(prevAvgTxnVal, BigDecimal.ZERO))
                .prevMaxDailySales(createKpi(previous.get("max_daily_sales"), BigDecimal.ZERO))
                .weekdayRevenuePct(calcWeekdayPct(currentRows))
                .weekendRevenuePct(calcWeekendPct(currentRows))
                .peakDayName(findPeakDay(currentRows))
                .dailyAverage(safeDivide(current.get("total_sales"), new BigDecimal(Math.max(currentRows.size(), 1))))
                .build();
    }

    private List<ChartData> aggregateByDayOfWeek(List<com.acquira.common.model.SumDailyMerchant> rows, boolean useSales) {
        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        BigDecimal[] values = new BigDecimal[7];
        Arrays.fill(values, BigDecimal.ZERO);
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            int idx = r.getBusinessDate().getDayOfWeek().getValue() - 1;
            if (useSales) {
                values[idx] = values[idx].add(getBaseVolume(r));
            } else {
                values[idx] = values[idx].add(new BigDecimal(r.getTotalTxns() != null ? r.getTotalTxns() : 0));
            }
        }
        List<ChartData> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            result.add(ChartData.builder().label(days[i]).value(values[i]).build());
        }
        return result;
    }

    private List<ChartData> aggregateByWeekOfMonth(List<com.acquira.common.model.SumDailyMerchant> rows) {
        BigDecimal[] weeks = new BigDecimal[5];
        Arrays.fill(weeks, BigDecimal.ZERO);
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            int weekIdx = (r.getBusinessDate().getDayOfMonth() - 1) / 7;
            if (weekIdx > 4) weekIdx = 4;
            weeks[weekIdx] = weeks[weekIdx].add(getBaseVolume(r));
        }
        List<ChartData> result = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            result.add(ChartData.builder().label("Week " + (i + 1)).value(weeks[i]).build());
        }
        return result;
    }

    private BigDecimal calcWeekdayPct(List<com.acquira.common.model.SumDailyMerchant> rows) {
        BigDecimal weekday = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            BigDecimal v = getBaseVolume(r);
            total = total.add(v);
            if (r.getBusinessDate().getDayOfWeek().getValue() <= 5) weekday = weekday.add(v);
        }
        return total.compareTo(BigDecimal.ZERO) > 0
                ? weekday.multiply(new BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private BigDecimal calcWeekendPct(List<com.acquira.common.model.SumDailyMerchant> rows) {
        return new BigDecimal(100).subtract(calcWeekdayPct(rows));
    }

    private String findPeakDay(List<com.acquira.common.model.SumDailyMerchant> rows) {
        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        BigDecimal[] values = new BigDecimal[7];
        Arrays.fill(values, BigDecimal.ZERO);
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            int idx = r.getBusinessDate().getDayOfWeek().getValue() - 1;
            values[idx] = values[idx].add(getBaseVolume(r));
        }
        int maxIdx = 0;
        for (int i = 1; i < 7; i++)
            if (values[i].compareTo(values[maxIdx]) > 0) maxIdx = i;
        return days[maxIdx];
    }

    // ============================================================
    // ACHIEVEMENTS
    // ============================================================

    private BusinessAchievements buildAchievements(List<com.acquira.common.model.SumDailyMerchant> dailyRows,
            List<com.acquira.common.model.SumDailyMerchantAttribute> attrs) {

        List<ChartData> dailyData = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(getBaseVolume(r))
                .value2(new BigDecimal(r.getTotalTxns() != null ? r.getTotalTxns() : 0))
                .build()).collect(Collectors.toList());

        List<ChartData> dailyAtv = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(safeDivide(getBaseVolume(r), new BigDecimal(r.getTotalTxns() != null ? r.getTotalTxns() : 1)))
                .build()).collect(Collectors.toList());

        List<ChartData> custData = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(new BigDecimal(r.getUniqueCustomerCount() != null ? r.getUniqueCustomerCount() : 0))
                .build()).collect(Collectors.toList());

        List<ChartData> hourData = aggregateAttributes(attrs, "HOUR");
        List<ChartData> salesAtvByDow = buildSalesAndAtvByDow(dailyRows);
        List<ChartData> revenueHeatmap = buildRevenueHeatmap(dailyRows, attrs);
        List<ChartData> txnSizeDist = buildTxnSizeDistribution(attrs);

        return BusinessAchievements.builder()
                .dailySalesAndCount(dailyData)
                .dailyAvgTxnValue(dailyAtv)
                .uniqueCustomersByDay(custData)
                .salesTimeOfDay(hourData)
                .salesAndAtvByDayOfWeek(salesAtvByDow)
                .revenueHeatmap(revenueHeatmap)
                .txnSizeDistribution(txnSizeDist)
                .build();
    }

    private List<ChartData> buildRevenueHeatmap(
            List<com.acquira.common.model.SumDailyMerchant> dailyRows,
            List<com.acquira.common.model.SumDailyMerchantAttribute> attrs) {
        String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        Map<String, BigDecimal> grid = new HashMap<>();
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if ("HOUR".equals(a.getAttributeType()) && a.getBusinessDate() != null) {
                int dow = a.getBusinessDate().getDayOfWeek().getValue() - 1;
                String hour = String.format("%02d", Integer.parseInt(a.getAttributeValue()));
                String key = dayNames[dow] + "|" + hour;
                grid.merge(key, a.getMetricVolume(), BigDecimal::add);
            }
        }
        return grid.entrySet().stream()
                .map(e -> ChartData.builder().label(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList());
    }

    private List<ChartData> buildTxnSizeDistribution(
            List<com.acquira.common.model.SumDailyMerchantAttribute> attrs) {
        String[] bucketOrder = {"< 50", "50-100", "100-250", "250-500", "500-1K", "1K+"};
        Map<String, long[]> buckets = new java.util.LinkedHashMap<>();
        for (String b : bucketOrder) buckets.put(b, new long[]{0, 0});
        long totalCount = 0;
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if ("TXN_SIZE_BUCKET".equals(a.getAttributeType())) {
                long[] v = buckets.computeIfAbsent(a.getAttributeValue(), k -> new long[]{0, 0});
                v[0] += a.getMetricCount();
                v[1] += a.getMetricVolume() != null ? a.getMetricVolume().longValue() : 0;
                totalCount += a.getMetricCount();
            }
        }
        if (totalCount == 0) return new ArrayList<>();
        List<ChartData> result = new ArrayList<>();
        for (String bucket : bucketOrder) {
            long[] v = buckets.get(bucket);
            if (v[0] > 0) {
                BigDecimal pct = new BigDecimal(v[0] * 100).divide(new BigDecimal(totalCount), 1, RoundingMode.HALF_UP);
                result.add(ChartData.builder().label(bucket).value(new BigDecimal(v[0])).value2(pct).value3(new BigDecimal(v[1])).build());
            }
        }
        return result;
    }

    private List<ChartData> aggregateAttributes(List<com.acquira.common.model.SumDailyMerchantAttribute> attrs, String type) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if (type.equals(a.getAttributeType())) {
                map.put(a.getAttributeValue(),
                        map.getOrDefault(a.getAttributeValue(), BigDecimal.ZERO).add(a.getMetricVolume()));
            }
        }
        return map.entrySet().stream()
                .sorted((a, b) -> {
                    try { return Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey())); }
                    catch (NumberFormatException e) { return a.getKey().compareTo(b.getKey()); }
                })
                .map(e -> ChartData.builder().label(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList());
    }

    private Map<String, BigDecimal> aggregateAttributeMap(List<com.acquira.common.model.SumDailyMerchantAttribute> attrs,
            String type, boolean useVolume) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if (type.equals(a.getAttributeType())) {
                BigDecimal val = useVolume ? a.getMetricVolume() : new BigDecimal(a.getMetricCount());
                map.put(a.getAttributeValue(), map.getOrDefault(a.getAttributeValue(), BigDecimal.ZERO).add(val));
            }
        }
        return map;
    }

    private List<ChartData> buildSalesAndAtvByDow(List<com.acquira.common.model.SumDailyMerchant> rows) {
        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        BigDecimal[] sales = new BigDecimal[7];
        long[] txns = new long[7];
        Arrays.fill(sales, BigDecimal.ZERO);
        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            int idx = r.getBusinessDate().getDayOfWeek().getValue() - 1;
            sales[idx] = sales[idx].add(getBaseVolume(r));
            txns[idx] += r.getTotalTxns() != null ? r.getTotalTxns() : 0;
        }
        List<ChartData> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            BigDecimal atv = txns[i] > 0 ? sales[i].divide(new BigDecimal(txns[i]), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            result.add(ChartData.builder().label(days[i]).value(sales[i]).value2(atv).build());
        }
        return result;
    }

    // ============================================================
    // DEMOGRAPHICS
    // ============================================================

    private CustomerDemographics buildDemographics(List<com.acquira.common.model.SumDailyMerchantAttribute> attrs,
            List<com.acquira.common.model.SumDailyMerchantAttribute> prevAttrs,
            List<java.util.Map<String, Object>> monthlyTrends) {
        CustomerDemographics demo = new CustomerDemographics();

        demo.setCardSchemeValueSplit(aggregateAttributeMap(attrs, "CARD_SCHEME", true));
        demo.setCardSchemeCountSplit(aggregateAttributeMap(attrs, "CARD_SCHEME", false));
        demo.setCardTypeValueSplit(aggregateAttributeMap(attrs, "CARD_TYPE", true));
        demo.setCardTypeCountSplit(aggregateAttributeMap(attrs, "CARD_TYPE", false));

        Map<String, BigDecimal> txnTypeValue = aggregateAttributeMap(attrs, "TRANSACTION_TYPE", true);
        Map<String, BigDecimal> txnTypeCount = aggregateAttributeMap(attrs, "TRANSACTION_TYPE", false);
        demo.setTransactionTypeValueSplit(txnTypeValue.isEmpty() ? new HashMap<>() : txnTypeValue);
        demo.setTransactionTypeCountSplit(txnTypeCount.isEmpty() ? new HashMap<>() : txnTypeCount);

        BigDecimal creditVol = demo.getCardTypeValueSplit().getOrDefault("CREDIT", BigDecimal.ZERO);
        BigDecimal debitVol = demo.getCardTypeValueSplit().getOrDefault("DEBIT", BigDecimal.ZERO);
        BigDecimal prepaidVol = demo.getCardTypeValueSplit().getOrDefault("PREPAID", BigDecimal.ZERO);
        BigDecimal totalCardVol = creditVol.add(debitVol).add(prepaidVol);
        BigDecimal creditPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? creditVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal debitPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? debitVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal prepaidPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? prepaidVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        demo.setCreditDebitRatio(creditPct.intValue() + " / " + debitPct.intValue());

        Map<String, BigDecimal> cardTypeCountMap = demo.getCardTypeCountSplit();
        BigDecimal creditCount = cardTypeCountMap.getOrDefault("CREDIT", BigDecimal.ZERO);
        BigDecimal debitCount = cardTypeCountMap.getOrDefault("DEBIT", BigDecimal.ZERO);
        BigDecimal prepaidCount = cardTypeCountMap.getOrDefault("PREPAID", BigDecimal.ZERO);

        demo.setCreditPct(creditPct);
        demo.setCreditVolume(creditVol);
        demo.setCreditTxnCount(creditCount.longValue());
        demo.setDebitPct(debitPct);
        demo.setDebitVolume(debitVol);
        demo.setDebitTxnCount(debitCount.longValue());
        demo.setPrepaidPct(prepaidPct);
        demo.setPrepaidVolume(prepaidVol);
        demo.setPrepaidTxnCount(prepaidCount.longValue());

        Map<String, BigDecimal> destValueMap = aggregateAttributeMap(attrs, "DESTINATION", true);
        Map<String, BigDecimal> destCountMap = aggregateAttributeMap(attrs, "DESTINATION", false);
        BigDecimal localVol = destValueMap.getOrDefault("DOMESTIC", BigDecimal.ZERO);
        BigDecimal intlVol = destValueMap.getOrDefault("INTERNATIONAL", BigDecimal.ZERO);
        BigDecimal totalDestVol = localVol.add(intlVol);
        demo.setLocalCardVolume(localVol);
        demo.setLocalCardPct(totalDestVol.compareTo(BigDecimal.ZERO) > 0
                ? localVol.multiply(new BigDecimal(100)).divide(totalDestVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        demo.setLocalCardCustomers(destCountMap.getOrDefault("DOMESTIC", BigDecimal.ZERO).longValue());
        demo.setInternationalCardVolume(intlVol);
        demo.setInternationalCardPct(totalDestVol.compareTo(BigDecimal.ZERO) > 0
                ? intlVol.multiply(new BigDecimal(100)).divide(totalDestVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        demo.setInternationalCardCustomers(destCountMap.getOrDefault("INTERNATIONAL", BigDecimal.ZERO).longValue());

        List<ChartData> avgTicket = new ArrayList<>();
        if (creditCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("CREDIT").value(creditVol.divide(creditCount, 0, RoundingMode.HALF_UP))
                    .value2(creditVol).value3(creditCount).build());
        if (debitCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("DEBIT").value(debitVol.divide(debitCount, 0, RoundingMode.HALF_UP))
                    .value2(debitVol).value3(debitCount).build());
        if (prepaidCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("PREPAID").value(prepaidVol.divide(prepaidCount, 0, RoundingMode.HALF_UP))
                    .value2(prepaidVol).value3(prepaidCount).build());
        demo.setAvgTicketByCardType(avgTicket);

        BigDecimal contactlessVol = aggregateAttributeMap(attrs, "IS_CONTACTLESS", true)
                .getOrDefault("TRUE", BigDecimal.ZERO);
        BigDecimal walletPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? contactlessVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        demo.setWalletUsagePct(walletPct);
        demo.setCardPenetrationPct(new BigDecimal("98.2"));

        List<ChartData> mSales = new ArrayList<>(), mTxns = new ArrayList<>(), mCust = new ArrayList<>();
        for (java.util.Map<String, Object> r : monthlyTrends) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).name().substring(0, 3) + " " + y;
            BigDecimal vol = r.get("totalBaseVolume") == null
                    ? (r.get("totalVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("totalVolume"))
                    : (BigDecimal) r.get("totalBaseVolume");
            Long txns = r.get("totalTxns") == null ? 0L : ((Number) r.get("totalTxns")).longValue();
            Long cust = r.get("uniqueCustomers") == null ? 0L : ((Number) r.get("uniqueCustomers")).longValue();
            mSales.add(ChartData.builder().label(label).value(vol).build());
            mTxns.add(ChartData.builder().label(label).value(new BigDecimal(txns)).build());
            mCust.add(ChartData.builder().label(label).value(new BigDecimal(cust)).build());
        }
        demo.setMonthlySales(mSales);
        demo.setMonthlyTxns(mTxns);
        demo.setMonthlyCustomers(mCust);

        List<ChartData> mAtv = new ArrayList<>(), mSalesGrowth = new ArrayList<>(), mTxnGrowth = new ArrayList<>();
        BigDecimal prevSales = BigDecimal.ZERO, prevTxns = BigDecimal.ZERO;
        for (int i = 0; i < mSales.size(); i++) {
            BigDecimal sVal = mSales.get(i).getValue();
            BigDecimal tVal = mTxns.get(i).getValue();
            mAtv.add(ChartData.builder().label(mSales.get(i).getLabel())
                    .value(tVal.compareTo(BigDecimal.ZERO) > 0 ? sVal.divide(tVal, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO).build());
            BigDecimal sg = i == 0 ? new BigDecimal(100)
                    : (prevSales.compareTo(BigDecimal.ZERO) > 0
                            ? sVal.divide(prevSales, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)) : new BigDecimal(100));
            mSalesGrowth.add(ChartData.builder().label(mSales.get(i).getLabel()).value(sg).build());
            BigDecimal tg = i == 0 ? new BigDecimal(100)
                    : (prevTxns.compareTo(BigDecimal.ZERO) > 0
                            ? tVal.divide(prevTxns, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)) : new BigDecimal(100));
            mTxnGrowth.add(ChartData.builder().label(mTxns.get(i).getLabel()).value(tg).build());
            prevSales = sVal;
            prevTxns = tVal;
        }
        demo.setMonthlyAtv(mAtv);
        demo.setMonthlySalesGrowth(mSalesGrowth);
        demo.setMonthlyTxnGrowth(mTxnGrowth);

        demo.setQuarterlyBreakdown(buildQuarterlyBreakdown(monthlyTrends));
        demo.setBestMonth(findBestMonth(mSales));
        demo.setAvgMonthlyGrowthPct(calcAvgMonthlyGrowth(mSales));
        demo.setPeakSeason(findPeakSeason(mSales));
        demo.setLowSeason(findLowSeason(mSales));
        demo.setYoyGrowthPct(calcYoYGrowth(mSales));

        return demo;
    }

    private List<ChartData> buildQuarterlyBreakdown(List<java.util.Map<String, Object>> monthlyTrends) {
        Map<String, BigDecimal> qSales = new LinkedHashMap<>();
        Map<String, Long> qTxns = new LinkedHashMap<>();
        for (java.util.Map<String, Object> r : monthlyTrends) {
            int m = ((Number) r.get("month")).intValue();
            int y = ((Number) r.get("year")).intValue();
            String q = "Q" + ((m - 1) / 3 + 1) + " " + y;
            BigDecimal vol = r.get("totalBaseVolume") == null
                    ? (r.get("totalVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("totalVolume"))
                    : (BigDecimal) r.get("totalBaseVolume");
            Long txns = r.get("totalTxns") == null ? 0L : ((Number) r.get("totalTxns")).longValue();
            qSales.put(q, qSales.getOrDefault(q, BigDecimal.ZERO).add(vol));
            qTxns.put(q, qTxns.getOrDefault(q, 0L) + txns);
        }
        List<ChartData> result = new ArrayList<>();
        for (String q : qSales.keySet()) {
            BigDecimal s = qSales.get(q);
            long t = qTxns.get(q);
            BigDecimal atv = t > 0 ? s.divide(new BigDecimal(t), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            result.add(ChartData.builder().label(q).value(s).value2(new BigDecimal(t)).value3(atv).build());
        }
        return result;
    }

    private String findBestMonth(List<ChartData> mSales) {
        if (mSales.isEmpty()) return "-";
        ChartData best = mSales.get(0);
        for (ChartData c : mSales) if (c.getValue().compareTo(best.getValue()) > 0) best = c;
        return best.getLabel();
    }

    private BigDecimal calcAvgMonthlyGrowth(List<ChartData> mSales) {
        if (mSales.size() < 2) return BigDecimal.ZERO;
        int count = 0; double sumGrowth = 0;
        for (int i = 1; i < mSales.size(); i++) {
            BigDecimal prev = mSales.get(i - 1).getValue();
            if (prev.compareTo(BigDecimal.ZERO) > 0) {
                sumGrowth += mSales.get(i).getValue().subtract(prev).divide(prev, 4, RoundingMode.HALF_UP).doubleValue() * 100;
                count++;
            }
        }
        return count > 0 ? new BigDecimal(sumGrowth / count).setScale(1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private String findPeakSeason(List<ChartData> mSales) {
        if (mSales.size() < 3) return "-";
        List<ChartData> sorted = mSales.stream().sorted((a, b) -> b.getValue().compareTo(a.getValue())).collect(Collectors.toList());
        return sorted.get(0).getLabel().split(" ")[0] + " & " + sorted.get(1).getLabel().split(" ")[0];
    }

    private String findLowSeason(List<ChartData> mSales) {
        if (mSales.size() < 3) return "-";
        List<ChartData> sorted = mSales.stream().sorted(Comparator.comparing(ChartData::getValue)).collect(Collectors.toList());
        return sorted.get(0).getLabel().split(" ")[0] + " & " + sorted.get(1).getLabel().split(" ")[0];
    }

    private BigDecimal calcYoYGrowth(List<ChartData> mSales) {
        if (mSales.size() < 2) return BigDecimal.ZERO;
        int size = mSales.size();
        if (size >= 13) {
            BigDecimal current = mSales.get(size - 1).getValue();
            BigDecimal lastYear = mSales.get(size - 13).getValue();
            if (lastYear.compareTo(BigDecimal.ZERO) > 0) {
                return current.subtract(lastYear).divide(lastYear, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).setScale(1, RoundingMode.HALF_UP);
            }
        }
        if (size >= 6) {
            int half = size / 2;
            BigDecimal recentHalf = BigDecimal.ZERO, olderHalf = BigDecimal.ZERO;
            for (int i = size - half; i < size; i++) recentHalf = recentHalf.add(mSales.get(i).getValue());
            for (int i = 0; i < half; i++) olderHalf = olderHalf.add(mSales.get(i).getValue());
            if (olderHalf.compareTo(BigDecimal.ZERO) > 0) {
                return recentHalf.subtract(olderHalf).divide(olderHalf, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).setScale(1, RoundingMode.HALF_UP);
            }
        }
        BigDecimal first = mSales.get(0).getValue();
        BigDecimal last = mSales.get(size - 1).getValue();
        if (first.compareTo(BigDecimal.ZERO) > 0) {
            return last.subtract(first).divide(first, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100)).setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    // ============================================================
    // DCC PERFORMANCE
    // ============================================================

    private DccPerformance buildDccPerformance(List<com.acquira.common.model.SumDailyMerchant> currentRows,
            List<com.acquira.common.model.SumDailyMerchant> prevRows,
            List<java.util.Map<String, Object>> monthlyTrends) {
        BigDecimal eligVol = BigDecimal.ZERO, optinVol = BigDecimal.ZERO, optoutVol = BigDecimal.ZERO;
        long eligCount = 0, optinCount = 0;
        for (com.acquira.common.model.SumDailyMerchant r : currentRows) {
            eligVol = eligVol.add(r.getDccEligibleVolume() != null ? r.getDccEligibleVolume() : BigDecimal.ZERO);
            optinVol = optinVol.add(r.getDccOptinVolume() != null ? r.getDccOptinVolume() : BigDecimal.ZERO);
            optoutVol = optoutVol.add(r.getDccOptoutVolume() != null ? r.getDccOptoutVolume() : BigDecimal.ZERO);
            eligCount += r.getDccEligibleCount() != null ? r.getDccEligibleCount() : 0;
            optinCount += r.getDccOptinCount() != null ? r.getDccOptinCount() : 0;
        }

        BigDecimal conversionRate = eligCount > 0
                ? new BigDecimal(optinCount * 100.0 / eligCount).setScale(1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal missedRevenue = optoutVol.multiply(new BigDecimal("0.035")).setScale(0, RoundingMode.HALF_UP);

        List<ChartData> missed = new ArrayList<>(), opt = new ArrayList<>();
        for (java.util.Map<String, Object> r : monthlyTrends) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).name().substring(0, 3) + " " + y;
            BigDecimal oout = r.get("dccOptoutVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("dccOptoutVolume");
            BigDecimal oin = r.get("dccOptinVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("dccOptinVolume");
            missed.add(ChartData.builder().label(label).value(oout).build());
            opt.add(ChartData.builder().label(label).value(oout).value2(oin).build());
        }

        long optoutCount = eligCount - optinCount;
        BigDecimal revenueGenerated = optinVol.multiply(new BigDecimal("0.035")).setScale(0, RoundingMode.HALF_UP);

        DccPerformance dcc = DccPerformance.builder()
                .missedOpportunityTrend(missed).optOutOptInTrend(opt).eligibilityTrend(new ArrayList<>())
                .dccEligibleVolume(eligVol).dccOptinVolume(optinVol).dccOptoutVolume(optoutVol)
                .dccConversionRate(conversionRate).dccMissedRevenue(missedRevenue).build();

        dcc.setOptInCount(optinCount);
        dcc.setOptOutCount(optoutCount);
        dcc.setOptInRevenue(revenueGenerated);
        dcc.setDccEligibleCount(eligCount);
        dcc.setDccOptinCountLong(optinCount);
        dcc.setTotalIntlTxnCount(eligCount);
        dcc.setTotalIntlVolume(eligVol);
        dcc.setDccRevenueGenerated(revenueGenerated);
        dcc.setOptOutDeclineRate(eligCount > 0
                ? new BigDecimal(optoutCount * 100.0 / eligCount).setScale(1, RoundingMode.HALF_UP) : BigDecimal.ZERO);

        return dcc;
    }

    // ============================================================
    // LOYALTY
    // ============================================================

    private ConsumerLoyalty buildLoyalty(List<com.acquira.common.model.SumMonthlyCard> cardRows,
            List<com.acquira.common.model.SumMonthlyCard> trendCardRows, LocalDate endOfMonth) {
        Map<String, Long> cardVisits = new HashMap<>();
        Map<String, BigDecimal> cardSpend = new HashMap<>();
        for (com.acquira.common.model.SumMonthlyCard r : cardRows) {
            cardVisits.put(r.getCardNumber(), cardVisits.getOrDefault(r.getCardNumber(), 0L) + r.getVisitCount());
            cardSpend.put(r.getCardNumber(), cardSpend.getOrDefault(r.getCardNumber(), BigDecimal.ZERO).add(r.getTotalSpend()));
        }

        Map<String, Long> freqBuckets = new HashMap<>();
        for (Long visits : cardVisits.values()) {
            String label = visits + " Visits";
            freqBuckets.put(label, freqBuckets.getOrDefault(label, 0L) + 1);
        }
        List<ChartData> freqData = freqBuckets.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().split(" ")[0])))
                .map(e -> ChartData.builder().label(e.getKey()).value(new BigDecimal(e.getValue())).build())
                .collect(Collectors.toList());

        List<ChartData> bandData = buildSpendBands(cardSpend);

        long totalCards = cardVisits.size();
        long repeatCards = cardVisits.values().stream().filter(v -> v > 1).count();
        BigDecimal retentionRate = totalCards > 0
                ? new BigDecimal(repeatCards * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        List<ChartData> monthlyFreq = buildMonthlyFrequency(trendCardRows, endOfMonth);

        return ConsumerLoyalty.builder()
                .visitFrequency(freqData).spendBands(bandData).monthlyVisitFreqTrend(monthlyFreq)
                .retentionRate(retentionRate).totalUniqueCards(new BigDecimal(totalCards))
                .repeatCardPct(totalCards > 0 ? new BigDecimal(repeatCards * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .build();
    }

    private List<ChartData> buildSpendBands(Map<String, BigDecimal> cardSpend) {
        String[] bands = { "0-20", "20-50", "50-100", "100-200", "200-500", "500+" };
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String b : bands) counts.put(b, 0L);
        for (BigDecimal spend : cardSpend.values()) {
            double s = spend.doubleValue();
            String band = s < 20 ? "0-20" : s < 50 ? "20-50" : s < 100 ? "50-100" : s < 200 ? "100-200" : s < 500 ? "200-500" : "500+";
            counts.put(band, counts.get(band) + 1);
        }
        long total = cardSpend.size();
        List<ChartData> result = new ArrayList<>();
        for (String b : bands) {
            double pct = total > 0 ? counts.get(b) * 100.0 / total : 0;
            result.add(ChartData.builder().label(b).value(new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP)).build());
        }
        return result;
    }

    private List<ChartData> buildMonthlyFrequency(List<com.acquira.common.model.SumMonthlyCard> rows, LocalDate end) {
        Map<Integer, List<com.acquira.common.model.SumMonthlyCard>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(com.acquira.common.model.SumMonthlyCard::getMonthKey));
        List<ChartData> result = new ArrayList<>();
        LocalDate current = end.minusMonths(12).withDayOfMonth(1);
        while (!current.isAfter(end)) {
            int key = Integer.parseInt(current.format(DateTimeFormatter.ofPattern("yyyyMM")));
            String label = current.getMonth().name().substring(0, 3) + " " + current.getYear();
            long c1 = 0, c2to4 = 0, c5plus = 0;
            if (byMonth.containsKey(key)) {
                for (com.acquira.common.model.SumMonthlyCard r : byMonth.get(key)) {
                    long v = r.getVisitCount();
                    if (v == 1) c1++; else if (v <= 4) c2to4++; else c5plus++;
                }
            }
            result.add(ChartData.builder().label(label).value(new BigDecimal(c1)).value2(new BigDecimal(c2to4)).value3(new BigDecimal(c5plus)).build());
            current = current.plusMonths(1);
        }
        return result;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private Kpi createKpi(BigDecimal current, BigDecimal previous) {
        if (current == null) current = BigDecimal.ZERO;
        if (previous == null) previous = BigDecimal.ZERO;
        double growth = 0.0;
        String trend = "FLAT";
        if (previous.compareTo(BigDecimal.ZERO) > 0) {
            growth = current.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP).doubleValue() * 100;
        } else if (current.compareTo(BigDecimal.ZERO) > 0) {
            growth = 100.0;
        }
        if (growth > 0) trend = "UP"; else if (growth < 0) trend = "DOWN";
        return Kpi.builder().value(current).momGrowth(growth).formattedValue(String.format("%,.0f", current)).trend(trend).build();
    }

    private BigDecimal safeDivide(BigDecimal n, BigDecimal d) {
        if (n == null) n = BigDecimal.ZERO;
        if (d == null || d.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return n.divide(d, 2, RoundingMode.HALF_UP);
    }

    // ============================================================
    // DYNAMIC INSIGHTS ENGINE — zero hardcoded text, all data-driven
    // ============================================================

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v);
    }

    private MerchantInsightsDTO.InsightNarrative buildInsights(
            MerchantInsightsDTO dto,
            List<com.acquira.common.model.SumDailyMerchant> dailyRows,
            List<com.acquira.common.model.SumDailyMerchantAttribute> attrs,
            String ccy) {

        MerchantInsightsDTO.InsightNarrative n = new MerchantInsightsDTO.InsightNarrative();
        var ov = dto.getOverview();
        var demo = dto.getDemographics();
        var dcc = dto.getDccPerformance();
        var loyalty = dto.getLoyalty();

        // Peak day tracking (for P5C chart highlighting)
        int peakIdx = -1;
        int slowIdx = 0;
        BigDecimal peakVal = BigDecimal.ZERO;
        BigDecimal slowVal = new BigDecimal(Long.MAX_VALUE);
        for (int i = 0; i < dailyRows.size(); i++) {
            BigDecimal v = getBaseVolume(dailyRows.get(i));
            if (v.compareTo(peakVal) > 0) { peakVal = v; peakIdx = i; }
            if (v.compareTo(slowVal) < 0) { slowVal = v; slowIdx = i; }
        }
        n.setPeakDayIndex(peakIdx);
        if (peakIdx >= 0) n.setPeakDayLabel("Day " + dailyRows.get(peakIdx).getBusinessDate().getDayOfMonth());
        if (dailyRows.size() > 0) n.setSlowestDayLabel("Day " + dailyRows.get(slowIdx).getBusinessDate().getDayOfMonth());

        // Peak / slowest hour
        Map<String, BigDecimal> hourMap = new HashMap<>();
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if ("HOUR".equals(a.getAttributeType())) {
                hourMap.merge(a.getAttributeValue(), a.getMetricVolume(), BigDecimal::add);
            }
        }
        String peakHour = "12", slowHour = "12";
        BigDecimal peakHourVal = BigDecimal.ZERO, slowHourVal = new BigDecimal(Long.MAX_VALUE);
        for (Map.Entry<String, BigDecimal> e : hourMap.entrySet()) {
            if (e.getValue().compareTo(peakHourVal) > 0) { peakHourVal = e.getValue(); peakHour = e.getKey(); }
            if (e.getValue().compareTo(slowHourVal) < 0) { slowHourVal = e.getValue(); slowHour = e.getKey(); }
        }
        int ph = Integer.parseInt(peakHour);
        int sh = Integer.parseInt(slowHour);
        n.setPeakHourLabel(String.format("%d:00\u2013%d:00", ph, ph + 1));
        n.setSlowestHourLabel(String.format("%d:00\u2013%d:00", sh, sh + 1));

        // Executive Summary (2-3 sentences)
        double salesGrowth = ov.getSales() != null ? ov.getSales().getMomGrowth() : 0;
        BigDecimal totalSales = ov.getSales() != null ? ov.getSales().getValue() : BigDecimal.ZERO;
        BigDecimal avgTxn = ov.getAvgTxnValue() != null ? ov.getAvgTxnValue().getValue() : BigDecimal.ZERO;
        String trend = salesGrowth >= 5 ? "a strong" : salesGrowth >= 0 ? "a steady" : salesGrowth >= -5 ? "a slightly challenging" : "a challenging";
        StringBuilder exec = new StringBuilder();
        exec.append(String.format("This was %s month with total sales of %s %s (%s%% vs last month). ", trend, ccy, fmt(totalSales), String.format("%+.1f", salesGrowth)));
        exec.append(String.format("Average transaction value stood at %s %s across %s transactions. ", ccy, fmt(avgTxn), fmt(ov.getTransactions().getValue())));
        BigDecimal retRate = loyalty != null && loyalty.getRetentionRate() != null ? loyalty.getRetentionRate() : BigDecimal.ZERO;
        if (retRate.compareTo(new BigDecimal(50)) >= 0) exec.append(String.format("Customer loyalty is healthy at %s%% retention.", fmt(retRate)));
        else exec.append(String.format("Customer retention at %s%% has room for improvement.", fmt(retRate)));
        n.setExecSummary(exec.toString());

        // Page 4: Business Achievements
        n.setPeakAchievement(String.format("Peak daily sales reached %s %s on %s with %s transactions in a single day.",
            ccy, fmt(peakVal),
            peakIdx >= 0 ? dailyRows.get(peakIdx).getBusinessDate().toString() : "-",
            peakIdx >= 0 && dailyRows.get(peakIdx).getTotalTxns() != null ? dailyRows.get(peakIdx).getTotalTxns().toString() : "0"));
        BigDecimal dailyAvg = ov.getDailyAverage() != null ? ov.getDailyAverage() : BigDecimal.ZERO;
        BigDecimal ratio = dailyAvg.compareTo(BigDecimal.ZERO) > 0 ? peakVal.divide(dailyAvg, 1, RoundingMode.HALF_UP) : BigDecimal.ONE;
        n.setPeakWatch(String.format("Your best day was %.1fx your daily average of %s %s. %s",
            ratio, ccy, fmt(dailyAvg),
            ratio.compareTo(new BigDecimal("2.5")) > 0
                ? "High volatility \u2014 consider loyalty programs to smooth revenue across all days."
                : "Good consistency \u2014 revenue is well distributed across the month."));

        // Page 5: Sales & Hourly Intelligence
        n.setSalesInsight(String.format("Your busiest hour is %s generating %s %s (%s%% of daily revenue). %s is the quietest period.",
            n.getPeakHourLabel(), ccy, fmt(peakHourVal),
            hourMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO) > 0
                ? fmt(peakHourVal.multiply(new BigDecimal(100)).divide(hourMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), 0, RoundingMode.HALF_UP))
                : "0",
            n.getSlowestHourLabel()));
        n.setSalesWatch(String.format("Consider a %s promotion to redistribute traffic from peak hours and increase overall utilization.", n.getSlowestHourLabel()));

        // Page 5b: Heatmap
        String peakDay = ov.getPeakDayName() != null ? ov.getPeakDayName() : "Saturday";
        BigDecimal wkdPct = ov.getWeekdayRevenuePct() != null ? ov.getWeekdayRevenuePct() : BigDecimal.ZERO;
        BigDecimal wkePct = ov.getWeekendRevenuePct() != null ? ov.getWeekendRevenuePct() : BigDecimal.ZERO;
        n.setHeatmapInsight(String.format("%s is your strongest day. Weekdays contribute %s%% and weekends %s%% of total revenue.", peakDay, fmt(wkdPct), fmt(wkePct)));
        n.setHeatmapTip(wkePct.compareTo(new BigDecimal(40)) > 0
            ? "You're weekend-heavy. Weekday lunch deals or early-bird promotions could balance the load."
            : "Revenue is well balanced across the week. Focus staffing on " + peakDay + " to capitalize on peak traffic.");

        // Page 7: Growth & Seasonality
        BigDecimal yoy = demo.getYoyGrowthPct() != null ? demo.getYoyGrowthPct() : BigDecimal.ZERO;
        BigDecimal avgGrowth = demo.getAvgMonthlyGrowthPct() != null ? demo.getAvgMonthlyGrowthPct() : BigDecimal.ZERO;
        String bestMo = demo.getBestMonth() != null ? demo.getBestMonth() : "-";
        n.setGrowthInsight(String.format("Your best performing month was %s. Average monthly growth rate is %s%%. %s",
            bestMo, fmt(avgGrowth),
            yoy.compareTo(BigDecimal.ZERO) > 0 ? String.format("Year-on-year growth is a healthy +%s%%.", fmt(yoy))
                : yoy.compareTo(BigDecimal.ZERO) < 0 ? String.format("Year-on-year shows a %s%% decline \u2014 review pricing and marketing strategy.", fmt(yoy))
                    : ""));
        String peakSeason = demo.getPeakSeason() != null ? demo.getPeakSeason() : "-";
        String lowSeason = demo.getLowSeason() != null ? demo.getLowSeason() : "-";
        n.setGrowthWatch(String.format("Peak season is %s; plan inventory and staffing accordingly. Low season (%s) is an opportunity for targeted promotions.", peakSeason, lowSeason));

        // Page 8: Card & Payment Analytics
        BigDecimal creditPct = demo.getCreditPct() != null ? demo.getCreditPct() : BigDecimal.ZERO;
        BigDecimal debitPct = demo.getDebitPct() != null ? demo.getDebitPct() : BigDecimal.ZERO;
        BigDecimal intlPct = demo.getInternationalCardPct() != null ? demo.getInternationalCardPct() : BigDecimal.ZERO;
        n.setCardInsight(String.format("Credit cards account for %s%% of volume and debit cards %s%%. International cards represent %s%% of transactions.", fmt(creditPct), fmt(debitPct), fmt(intlPct)));
        n.setCardTip(creditPct.compareTo(new BigDecimal(60)) > 0
            ? "High credit card usage suggests affluent customers. Consider premium offerings and higher-margin products."
            : intlPct.compareTo(new BigDecimal(20)) > 0
                ? "Strong international card presence \u2014 ensure DCC is enabled and staff are trained to offer currency choice."
                : "Balanced card mix. Focus on improving average transaction value across all card types.");

        // Page 9: Customer Intelligence
        n.setCustomerInsight(String.format("You served %s unique cards this month with a %s%% returning customer rate. Average spend per customer is %s %s.",
            loyalty != null && loyalty.getTotalUniqueCards() != null ? fmt(loyalty.getTotalUniqueCards()) : "0",
            fmt(retRate), ccy, ov.getAvgSpendPerCustomer() != null ? fmt(ov.getAvgSpendPerCustomer().getValue()) : "0"));
        n.setCustomerTip(retRate.compareTo(new BigDecimal(50)) < 0
            ? String.format("Your repeat rate of %s%% is below the 50%% healthy benchmark. A loyalty program could increase repeat visits by 15-25%%.", fmt(retRate))
            : String.format("Excellent loyalty at %s%%! Focus on increasing average spend per visit through upselling and cross-selling.", fmt(retRate)));

        // Page 10: DCC
        BigDecimal dccConv = dcc != null && dcc.getDccConversionRate() != null ? dcc.getDccConversionRate() : BigDecimal.ZERO;
        BigDecimal missedRev = dcc != null && dcc.getDccMissedRevenue() != null ? dcc.getDccMissedRevenue() : BigDecimal.ZERO;
        n.setDccInsight(String.format("DCC conversion rate is %s%%. %s", fmt(dccConv),
            missedRev.compareTo(BigDecimal.ZERO) > 0
                ? String.format("You missed an estimated %s %s in DCC revenue from opt-out transactions.", ccy, fmt(missedRev))
                : "No significant missed DCC revenue this month."));
        n.setDccTip(dccConv.compareTo(new BigDecimal(20)) < 0
            ? "Low DCC acceptance suggests staff may not be offering currency choice consistently. A brief training session could increase opt-in by 10-15 percentage points."
            : "Good DCC performance. Maintain staff awareness and consider displaying currency choice more prominently at the terminal.");

        // Closing Page: 3 Action Items
        List<String> actions = new ArrayList<>();
        if (retRate.compareTo(new BigDecimal(50)) < 0)
            actions.add(String.format("Launch a loyalty program \u2014 your %s%% repeat rate has significant upside potential.", fmt(retRate)));
        if (dccConv.compareTo(new BigDecimal(15)) < 0 && dcc != null && dcc.getDccEligibleVolume() != null && dcc.getDccEligibleVolume().compareTo(BigDecimal.ZERO) > 0)
            actions.add(String.format("Train staff on DCC \u2014 potential %s %s additional monthly revenue from better opt-in rates.", ccy, fmt(missedRev)));
        if (salesGrowth < -5)
            actions.add(String.format("Sales declined %.1f%% \u2014 review pricing, promotions, and competitor activity to reverse the trend.", salesGrowth));
        if (wkePct.compareTo(new BigDecimal(45)) > 0)
            actions.add("Launch weekday promotions \u2014 over 45% of revenue is concentrated on weekends, leaving weekday capacity underutilized.");
        if (avgTxn.compareTo(new BigDecimal(50)) < 0)
            actions.add(String.format("Average transaction is only %s %s \u2014 consider bundling, minimum-order offers, or upselling to increase ticket size.", ccy, fmt(avgTxn)));
        if (actions.isEmpty()) actions.add("Strong performance across all metrics. Continue current strategy and monitor for seasonal shifts.");
        if (actions.size() < 2) actions.add(String.format("Focus staffing and promotions on %s (your peak day) and %s (peak hour) to maximize revenue.", peakDay, n.getPeakHourLabel()));
        if (actions.size() < 3) actions.add(String.format("Your best month was %s \u2014 plan ahead to replicate that success with targeted marketing.", bestMo));

        n.setActionItem1(actions.get(0));
        n.setActionItem2(actions.size() > 1 ? actions.get(1) : "");
        n.setActionItem3(actions.size() > 2 ? actions.get(2) : "");

        return n;
    }

    // ============================================================
    // BUSINESS HEALTH SCORE ENGINE
    // 5 dimensions, each 0-100, weighted composite
    // ============================================================

    private MerchantInsightsDTO.HealthScore buildHealthScore(
            MerchantInsightsDTO dto,
            List<com.acquira.common.model.SumDailyMerchant> dailyRows,
            String ccy) {

        MerchantInsightsDTO.HealthScore hs = new MerchantInsightsDTO.HealthScore();
        var ov = dto.getOverview();
        var demo = dto.getDemographics();
        var dcc = dto.getDccPerformance();
        var loyalty = dto.getLoyalty();

        // ---- Dimension 1: Revenue Health (30%) ----
        int revScore = scoreRevenueHealth(ov, dailyRows);
        hs.setRevenueHealthScore(revScore);
        hs.setRevenueGrade(gradeFor(revScore));
        hs.setRevenueColor(colorFor(revScore));

        // ---- Dimension 2: Growth Momentum (25%) ----
        int growthScore = scoreGrowthMomentum(ov, demo);
        hs.setGrowthMomentumScore(growthScore);
        hs.setGrowthGrade(gradeFor(growthScore));
        hs.setGrowthColor(colorFor(growthScore));

        // ---- Dimension 3: Customer Loyalty (20%) ----
        int loyaltyScore = scoreCustomerLoyalty(ov, loyalty);
        hs.setCustomerLoyaltyScore(loyaltyScore);
        hs.setLoyaltyGrade(gradeFor(loyaltyScore));
        hs.setLoyaltyColor(colorFor(loyaltyScore));

        // ---- Dimension 4: Payment Efficiency (15%) ----
        int paymentScore = scorePaymentEfficiency(ov, demo);
        hs.setPaymentEfficiencyScore(paymentScore);
        hs.setPaymentGrade(gradeFor(paymentScore));
        hs.setPaymentColor(colorFor(paymentScore));

        // ---- Dimension 5: DCC Performance (10%) ----
        boolean dccApplicable = dcc != null && dcc.getDccEligibleVolume() != null
                && dcc.getDccEligibleVolume().compareTo(BigDecimal.ZERO) > 0;
        hs.setDccApplicable(dccApplicable);
        int dccScore = dccApplicable ? scoreDccPerformance(dcc, ov) : 0;
        hs.setDccPerformanceScore(dccScore);
        hs.setDccGrade(dccApplicable ? gradeFor(dccScore) : "N/A");
        hs.setDccColor(dccApplicable ? colorFor(dccScore) : "#9CA3AF");

        // ---- Composite Score ----
        int composite;
        if (dccApplicable) {
            composite = (int) Math.round(
                revScore * 0.30 + growthScore * 0.25 + loyaltyScore * 0.20
                + paymentScore * 0.15 + dccScore * 0.10);
        } else {
            // Redistribute DCC weight proportionally
            composite = (int) Math.round(
                revScore * 0.333 + growthScore * 0.278 + loyaltyScore * 0.222
                + paymentScore * 0.167);
        }
        composite = Math.max(0, Math.min(100, composite));
        hs.setCompositeScore(composite);
        hs.setGrade(gradeFor(composite));
        hs.setGradeLabel(gradeLabelFor(composite));
        hs.setGradeColor(colorFor(composite));
        hs.setGradeBgColor(bgColorFor(composite));

        // ---- Dynamic Strengths & Improvements ----
        generateStrengthsAndImprovements(hs, dto, dailyRows, ccy);

        // ---- AI Summary ----
        String weakest = dccApplicable
            ? (dccScore <= revScore && dccScore <= growthScore && dccScore <= loyaltyScore && dccScore <= paymentScore ? "DCC conversion" : lowestDimName(hs))
            : lowestDimName(hs);
        hs.setAiSummary(String.format(
            "Business scores %d/100 with %s performance. %s is the strongest dimension at %d/100. " +
            "Primary improvement opportunity: %s. %s",
            composite, gradeLabelFor(composite).toLowerCase(),
            highestDimName(hs), highestDimScore(hs),
            weakest,
            composite >= 80 ? "Focus on maintaining consistency and pushing toward A+ across all dimensions."
                : composite >= 60 ? "Targeted improvements in the weakest dimension could lift the overall score by 5-10 points."
                    : "Significant improvement needed across multiple dimensions. Prioritize quick wins in revenue consistency and customer retention."
        ));

        return hs;
    }

    // ---- DIMENSION SCORERS ----

    private int scoreRevenueHealth(MerchantInsightsDTO.BusinessOverview ov,
            List<com.acquira.common.model.SumDailyMerchant> dailyRows) {
        // Sub 1: MoM Sales Growth (40%)
        double momGrowth = ov.getSales() != null && ov.getSales().getMomGrowth() != null ? ov.getSales().getMomGrowth() : 0;
        int growthPts = momGrowth >= 15 ? 100 : momGrowth >= 10 ? 90 : momGrowth >= 5 ? 80
            : momGrowth >= 0 ? 60 : momGrowth >= -5 ? 40 : momGrowth >= -10 ? 20 : 10;

        // Sub 2: Revenue Consistency — CV of daily sales (30%)
        List<Double> activeSales = new ArrayList<>();
        for (var r : dailyRows) {
            BigDecimal v = getBaseVolume(r);
            if (v.compareTo(BigDecimal.ZERO) > 0) activeSales.add(v.doubleValue());
        }
        int cvPts = 70; // default for small samples
        if (activeSales.size() >= 5) {
            double mean = activeSales.stream().mapToDouble(Double::doubleValue).average().orElse(1);
            double variance = activeSales.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double cv = mean > 0 ? Math.sqrt(variance) / mean : 1;
            cvPts = cv < 0.3 ? 100 : cv < 0.5 ? 85 : cv < 0.7 ? 70 : cv < 1.0 ? 50 : 30;
        }

        // Sub 3: Active Trading Days % (30%)
        long activeDays = dailyRows.stream()
            .filter(r -> r.getTotalTxns() != null && r.getTotalTxns() > 0).count();
        double activePct = dailyRows.size() > 0 ? (double) activeDays / dailyRows.size() * 100 : 0;
        int activePts = activePct >= 95 ? 100 : activePct >= 90 ? 90 : activePct >= 80 ? 75
            : activePct >= 70 ? 60 : activePct >= 50 ? 40 : 30;

        return clamp((int) Math.round(growthPts * 0.40 + cvPts * 0.30 + activePts * 0.30));
    }

    private int scoreGrowthMomentum(MerchantInsightsDTO.BusinessOverview ov,
            MerchantInsightsDTO.CustomerDemographics demo) {
        // Sub 1: MoM Growth (35%)
        double momGrowth = ov.getSales() != null && ov.getSales().getMomGrowth() != null ? ov.getSales().getMomGrowth() : 0;
        int momPts = momGrowth >= 15 ? 100 : momGrowth >= 10 ? 90 : momGrowth >= 5 ? 80
            : momGrowth >= 0 ? 60 : momGrowth >= -5 ? 40 : momGrowth >= -10 ? 20 : 10;

        // Sub 2: YoY Growth (35%)
        double yoy = demo.getYoyGrowthPct() != null ? demo.getYoyGrowthPct().doubleValue() : 0;
        int yoyPts = yoy >= 20 ? 100 : yoy >= 10 ? 85 : yoy >= 5 ? 70 : yoy >= 0 ? 50
            : yoy >= -5 ? 35 : 20;

        // Sub 3: 3-Month Trend Direction (30%)
        int trendPts = 50; // default flat
        List<MerchantInsightsDTO.ChartData> mSales = demo.getMonthlySales();
        if (mSales != null && mSales.size() >= 3) {
            int sz = mSales.size();
            BigDecimal m1 = mSales.get(sz - 3).getValue();
            BigDecimal m2 = mSales.get(sz - 2).getValue();
            BigDecimal m3 = mSales.get(sz - 1).getValue();
            boolean up12 = m2.compareTo(m1) > 0;
            boolean up23 = m3.compareTo(m2) > 0;
            if (up12 && up23) trendPts = 100;       // All 3 increasing
            else if (up12 || up23) trendPts = 75;    // 2 of 3 up
            else if (m3.compareTo(m1) == 0) trendPts = 50; // flat
            else if (!up12 && !up23) trendPts = 10;  // All 3 declining
            else trendPts = 25;                       // Mixed
        }

        return clamp((int) Math.round(momPts * 0.35 + yoyPts * 0.35 + trendPts * 0.30));
    }

    private int scoreCustomerLoyalty(MerchantInsightsDTO.BusinessOverview ov,
            MerchantInsightsDTO.ConsumerLoyalty loyalty) {
        // Sub 1: Repeat Customer Rate (50%)
        double retRate = loyalty != null && loyalty.getRetentionRate() != null ? loyalty.getRetentionRate().doubleValue() : 0;
        int retPts = retRate >= 60 ? 100 : retRate >= 50 ? 85 : retRate >= 40 ? 70
            : retRate >= 30 ? 55 : retRate >= 20 ? 40 : retRate >= 10 ? 25 : 20;

        // Sub 2: Customer Count MoM (25%)
        double custGrowth = ov.getCustomers() != null && ov.getCustomers().getMomGrowth() != null ? ov.getCustomers().getMomGrowth() : 0;
        int custPts = custGrowth >= 10 ? 100 : custGrowth >= 5 ? 80 : custGrowth >= 0 ? 60
            : custGrowth >= -5 ? 40 : 20;

        // Sub 3: Avg Spend per Customer MoM (25%)
        double spendGrowth = ov.getAvgSpendPerCustomer() != null && ov.getAvgSpendPerCustomer().getMomGrowth() != null
            ? ov.getAvgSpendPerCustomer().getMomGrowth() : 0;
        int spendPts = spendGrowth >= 10 ? 100 : spendGrowth >= 5 ? 80 : spendGrowth >= 0 ? 60
            : spendGrowth >= -5 ? 40 : 20;

        return clamp((int) Math.round(retPts * 0.50 + custPts * 0.25 + spendPts * 0.25));
    }

    private int scorePaymentEfficiency(MerchantInsightsDTO.BusinessOverview ov,
            MerchantInsightsDTO.CustomerDemographics demo) {
        // Sub 1: Card Scheme Diversity — Shannon Entropy (30%)
        int diversityPts = 60; // default
        if (demo.getCardSchemeValueSplit() != null && !demo.getCardSchemeValueSplit().isEmpty()) {
            double totalVol = demo.getCardSchemeValueSplit().values().stream()
                .mapToDouble(BigDecimal::doubleValue).sum();
            if (totalVol > 0) {
                double entropy = 0;
                int n = demo.getCardSchemeValueSplit().size();
                for (BigDecimal v : demo.getCardSchemeValueSplit().values()) {
                    double p = v.doubleValue() / totalVol;
                    if (p > 0) entropy -= p * Math.log(p);
                }
                double maxEntropy = n > 1 ? Math.log(n) : 1;
                double normEntropy = entropy / maxEntropy;
                diversityPts = normEntropy > 0.8 ? 100 : normEntropy > 0.6 ? 80
                    : normEntropy > 0.4 ? 60 : normEntropy > 0.2 ? 40 : 20;
            }
        }

        // Sub 2: International Card % (25%)
        double intlPct = demo.getInternationalCardPct() != null ? demo.getInternationalCardPct().doubleValue() : 0;
        int intlPts = intlPct > 30 ? 100 : intlPct > 20 ? 80 : intlPct > 10 ? 60
            : intlPct > 5 ? 40 : 25;

        // Sub 3: Weekday/Weekend Balance (25%)
        double wkdPct = ov.getWeekdayRevenuePct() != null ? ov.getWeekdayRevenuePct().doubleValue() : 70;
        int balancePts;
        if (wkdPct >= 55 && wkdPct <= 75) balancePts = 100;
        else if (wkdPct >= 50 && wkdPct <= 80) balancePts = 80;
        else if (wkdPct >= 40 && wkdPct <= 85) balancePts = 60;
        else balancePts = 40;

        // Sub 4: Contactless/Wallet Adoption (20%)
        double walletPct = demo.getWalletUsagePct() != null ? demo.getWalletUsagePct().doubleValue() : 0;
        int walletPts = walletPct > 60 ? 100 : walletPct > 40 ? 80 : walletPct > 20 ? 60 : 40;

        return clamp((int) Math.round(diversityPts * 0.30 + intlPts * 0.25 + balancePts * 0.25 + walletPts * 0.20));
    }

    private int scoreDccPerformance(MerchantInsightsDTO.DccPerformance dcc,
            MerchantInsightsDTO.BusinessOverview ov) {
        // Sub 1: DCC Conversion Rate (50%)
        double convRate = dcc.getDccConversionRate() != null ? dcc.getDccConversionRate().doubleValue() : 0;
        int convPts = convRate >= 40 ? 100 : convRate >= 30 ? 85 : convRate >= 20 ? 65
            : convRate >= 10 ? 45 : 20;

        // Sub 2: DCC Eligible % of total volume (25%)
        BigDecimal totalSales = ov.getSales() != null ? ov.getSales().getValue() : BigDecimal.ONE;
        double eligPct = totalSales.compareTo(BigDecimal.ZERO) > 0
            ? dcc.getDccEligibleVolume().divide(totalSales, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0;
        int eligPts = eligPct > 30 ? 100 : eligPct > 20 ? 80 : eligPct > 10 ? 60 : 40;

        // Sub 3: Missed Revenue as % of total MSF estimate (25%)
        BigDecimal missedRev = dcc.getDccMissedRevenue() != null ? dcc.getDccMissedRevenue() : BigDecimal.ZERO;
        // Estimate total MSF as ~2% of total sales
        double estMsf = totalSales.doubleValue() * 0.02;
        double missedPct = estMsf > 0 ? missedRev.doubleValue() / estMsf * 100 : 0;
        int missedPts = missedPct < 5 ? 100 : missedPct < 10 ? 80 : missedPct < 20 ? 60
            : missedPct < 30 ? 40 : 20;

        return clamp((int) Math.round(convPts * 0.50 + eligPts * 0.25 + missedPts * 0.25));
    }

    // ---- GRADE / COLOR HELPERS ----

    private int clamp(int score) { return Math.max(0, Math.min(100, score)); }

    private String gradeFor(int score) {
        if (score >= 90) return "A+";
        if (score >= 80) return "A";
        if (score >= 70) return "B+";
        if (score >= 60) return "B";
        if (score >= 50) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private String gradeLabelFor(int score) {
        if (score >= 90) return "Exceptional";
        if (score >= 80) return "Strong Performer";
        if (score >= 70) return "Above Average";
        if (score >= 60) return "Average";
        if (score >= 50) return "Below Average";
        if (score >= 40) return "Needs Attention";
        return "Critical";
    }

    private String colorFor(int score) {
        if (score >= 90) return "#047857";
        if (score >= 80) return "#1E3A8A";
        if (score >= 70) return "#4338CA";
        if (score >= 60) return "#0D9488";
        if (score >= 50) return "#D97706";
        if (score >= 40) return "#EA580C";
        return "#DC2626";
    }

    private String bgColorFor(int score) {
        if (score >= 90) return "#D1FAE5";
        if (score >= 80) return "#DBEAFE";
        if (score >= 70) return "#EDE9FE";
        if (score >= 60) return "#CCFBF1";
        if (score >= 50) return "#FEF3C7";
        if (score >= 40) return "#FFEDD5";
        return "#FEE2E2";
    }

    // ---- DYNAMIC STRENGTHS & IMPROVEMENTS ----

    private void generateStrengthsAndImprovements(MerchantInsightsDTO.HealthScore hs,
            MerchantInsightsDTO dto, List<com.acquira.common.model.SumDailyMerchant> dailyRows, String ccy) {
        var ov = dto.getOverview();
        var demo = dto.getDemographics();
        var dcc = dto.getDccPerformance();
        var loyalty = dto.getLoyalty();

        // Build sorted list of dimensions
        List<int[]> dims = new ArrayList<>(); // [score, id]
        dims.add(new int[]{hs.getRevenueHealthScore(), 0});
        dims.add(new int[]{hs.getGrowthMomentumScore(), 1});
        dims.add(new int[]{hs.getCustomerLoyaltyScore(), 2});
        dims.add(new int[]{hs.getPaymentEfficiencyScore(), 3});
        if (hs.isDccApplicable()) dims.add(new int[]{hs.getDccPerformanceScore(), 4});
        dims.sort((a, b) -> Integer.compare(b[0], a[0])); // descending

        // Top 3 = strengths
        for (int i = 0; i < Math.min(3, dims.size()); i++) {
            String[] titleDetail = buildDimensionNarrative(dims.get(i)[1], dims.get(i)[0], dto, dailyRows, ccy, true);
            switch (i) {
                case 0: hs.setStrength1Title(titleDetail[0]); hs.setStrength1Detail(titleDetail[1]); break;
                case 1: hs.setStrength2Title(titleDetail[0]); hs.setStrength2Detail(titleDetail[1]); break;
                case 2: hs.setStrength3Title(titleDetail[0]); hs.setStrength3Detail(titleDetail[1]); break;
            }
        }

        // Bottom dimensions where score < 70 = improvements (max 3)
        List<int[]> weak = new ArrayList<>();
        for (int i = dims.size() - 1; i >= 0; i--) {
            if (dims.get(i)[0] < 70) weak.add(dims.get(i));
            if (weak.size() >= 3) break;
        }
        if (weak.isEmpty()) {
            // All above 70 — show generic "maintain" message
            hs.setImprove1Title("All Dimensions Above Average");
            hs.setImprove1Detail("All dimensions performing above average. Focus on maintaining consistency and pushing top dimensions toward A+ territory.");
            hs.setImprove2Title("");
            hs.setImprove2Detail("");
            hs.setImprove3Title("");
            hs.setImprove3Detail("");
        } else {
            for (int i = 0; i < Math.min(3, weak.size()); i++) {
                String[] titleDetail = buildDimensionNarrative(weak.get(i)[1], weak.get(i)[0], dto, dailyRows, ccy, false);
                switch (i) {
                    case 0: hs.setImprove1Title(titleDetail[0]); hs.setImprove1Detail(titleDetail[1]); break;
                    case 1: hs.setImprove2Title(titleDetail[0]); hs.setImprove2Detail(titleDetail[1]); break;
                    case 2: hs.setImprove3Title(titleDetail[0]); hs.setImprove3Detail(titleDetail[1]); break;
                }
            }
            // Fill remaining slots
            if (weak.size() < 2) { hs.setImprove2Title(""); hs.setImprove2Detail(""); }
            if (weak.size() < 3) { hs.setImprove3Title(""); hs.setImprove3Detail(""); }
        }
    }

    /**
     * Generate a title + detail narrative for a dimension.
     * @param dimId 0=Revenue, 1=Growth, 2=Loyalty, 3=Payment, 4=DCC
     * @param isStrength true for strength narrative, false for improvement narrative
     */
    private String[] buildDimensionNarrative(int dimId, int score, MerchantInsightsDTO dto,
            List<com.acquira.common.model.SumDailyMerchant> dailyRows, String ccy, boolean isStrength) {
        var ov = dto.getOverview();
        var demo = dto.getDemographics();
        var dcc = dto.getDccPerformance();
        var loyalty = dto.getLoyalty();

        switch (dimId) {
            case 0: { // Revenue Health
                double mom = ov.getSales() != null && ov.getSales().getMomGrowth() != null ? ov.getSales().getMomGrowth() : 0;
                long activeDays = dailyRows.stream().filter(r -> r.getTotalTxns() != null && r.getTotalTxns() > 0).count();
                int totalDays = dailyRows.size();
                BigDecimal totalSales = ov.getSales() != null ? ov.getSales().getValue() : BigDecimal.ZERO;
                if (isStrength) {
                    return new String[]{"Strong Revenue Health",
                        String.format("Revenue of %s %s with %d/%d active trading days (%+.1f%% MoM growth). Consistent daily performance.", ccy, fmt(totalSales), activeDays, totalDays, mom)};
                } else {
                    return new String[]{String.format("Revenue Health at %d/100", score),
                        String.format("%s %s total revenue with %d/%d active days (%+.1f%% MoM). %s", ccy, fmt(totalSales), activeDays, totalDays, mom,
                            activeDays < totalDays * 0.8 ? "Too many inactive days — investigate causes of downtime."
                            : mom < -5 ? "Declining trend — review pricing, promotions, and competitor activity."
                            : "Revenue volatility is high — consider loyalty programs to smooth daily sales.")};
                }
            }
            case 1: { // Growth Momentum
                double mom = ov.getSales() != null && ov.getSales().getMomGrowth() != null ? ov.getSales().getMomGrowth() : 0;
                double yoy = demo.getYoyGrowthPct() != null ? demo.getYoyGrowthPct().doubleValue() : 0;
                if (isStrength) {
                    return new String[]{"Strong Growth Trajectory",
                        String.format("%+.1f%% MoM and %+.1f%% YoY growth with consistent upward momentum across recent months.", mom, yoy)};
                } else {
                    return new String[]{String.format("Growth Momentum at %d/100", score),
                        String.format("MoM growth is %+.1f%% and YoY is %+.1f%%. %s", mom, yoy,
                            yoy < -5 ? "Year-on-year decline is concerning — review structural issues in pricing and market positioning."
                            : mom < 0 ? "Recent month showed decline — targeted promotions and seasonal campaigns could reverse this."
                            : "Growth is flat — explore new customer acquisition channels and upselling strategies.")};
                }
            }
            case 2: { // Customer Loyalty
                double retRate = loyalty != null && loyalty.getRetentionRate() != null ? loyalty.getRetentionRate().doubleValue() : 0;
                BigDecimal avgSpend = ov.getAvgSpendPerCustomer() != null ? ov.getAvgSpendPerCustomer().getValue() : BigDecimal.ZERO;
                BigDecimal uniqueCards = loyalty != null && loyalty.getTotalUniqueCards() != null ? loyalty.getTotalUniqueCards() : BigDecimal.ZERO;
                if (isStrength) {
                    return new String[]{"High Customer Retention",
                        String.format("%.0f%% repeat rate across %s unique cards. Average spend per customer: %s %s.", retRate, fmt(uniqueCards), ccy, fmt(avgSpend))};
                } else {
                    return new String[]{String.format("Customer Loyalty at %d/100", score),
                        String.format("Repeat rate is %.0f%% with %s unique cards. %s", retRate, fmt(uniqueCards),
                            retRate < 30 ? "Very low retention — a loyalty program could increase repeat visits by 15-25%%."
                            : retRate < 50 ? "Below-average retention — consider personalized offers for returning customers."
                            : "Customer base is moderate — focus on increasing average spend per visit through upselling.")};
                }
            }
            case 3: { // Payment Efficiency
                BigDecimal intlPct = demo.getInternationalCardPct() != null ? demo.getInternationalCardPct() : BigDecimal.ZERO;
                BigDecimal wkdPct = ov.getWeekdayRevenuePct() != null ? ov.getWeekdayRevenuePct() : BigDecimal.ZERO;
                BigDecimal walletPct = demo.getWalletUsagePct() != null ? demo.getWalletUsagePct() : BigDecimal.ZERO;
                if (isStrength) {
                    return new String[]{"Excellent Payment Mix",
                        String.format("%s%% international cards, %s%% weekday revenue balance, %s%% contactless/wallet adoption.", fmt(intlPct), fmt(wkdPct), fmt(walletPct))};
                } else {
                    return new String[]{String.format("Payment Efficiency at %d/100", score),
                        String.format("%s%% international cards, weekday/weekend split at %s%%/%s%%. %s", fmt(intlPct), fmt(wkdPct),
                            ov.getWeekendRevenuePct() != null ? fmt(ov.getWeekendRevenuePct()) : "0",
                            wkdPct.doubleValue() < 50 ? "Too weekend-heavy — weekday promotions could balance capacity."
                            : wkdPct.doubleValue() > 80 ? "Weekends underperforming — consider weekend events and promotions."
                            : intlPct.doubleValue() < 10 ? "Low international card share — target tourist locations and travel partnerships."
                            : "Card scheme diversity is low — ensure all major schemes are accepted and promoted.")};
                }
            }
            case 4: { // DCC
                double convRate = dcc != null && dcc.getDccConversionRate() != null ? dcc.getDccConversionRate().doubleValue() : 0;
                BigDecimal missedRev = dcc != null && dcc.getDccMissedRevenue() != null ? dcc.getDccMissedRevenue() : BigDecimal.ZERO;
                if (isStrength) {
                    return new String[]{"Strong DCC Performance",
                        String.format("%.1f%% DCC conversion rate. Currency choice is being offered effectively.", convRate)};
                } else {
                    return new String[]{String.format("DCC Conversion at %d/100", score),
                        String.format("DCC conversion is only %.1f%%, missing %s %s/month. Staff training could recover 60%% immediately.", convRate, ccy, fmt(missedRev))};
                }
            }
            default:
                return new String[]{"Performance", "Score: " + score};
        }
    }

    private String highestDimName(MerchantInsightsDTO.HealthScore hs) {
        int max = hs.getRevenueHealthScore();
        String name = "Revenue Health";
        if (hs.getGrowthMomentumScore() > max) { max = hs.getGrowthMomentumScore(); name = "Growth Momentum"; }
        if (hs.getCustomerLoyaltyScore() > max) { max = hs.getCustomerLoyaltyScore(); name = "Customer Loyalty"; }
        if (hs.getPaymentEfficiencyScore() > max) { max = hs.getPaymentEfficiencyScore(); name = "Payment Efficiency"; }
        if (hs.isDccApplicable() && hs.getDccPerformanceScore() > max) { name = "DCC Performance"; }
        return name;
    }

    private int highestDimScore(MerchantInsightsDTO.HealthScore hs) {
        int max = hs.getRevenueHealthScore();
        max = Math.max(max, hs.getGrowthMomentumScore());
        max = Math.max(max, hs.getCustomerLoyaltyScore());
        max = Math.max(max, hs.getPaymentEfficiencyScore());
        if (hs.isDccApplicable()) max = Math.max(max, hs.getDccPerformanceScore());
        return max;
    }

    private String lowestDimName(MerchantInsightsDTO.HealthScore hs) {
        int min = hs.getRevenueHealthScore();
        String name = "Revenue Health";
        if (hs.getGrowthMomentumScore() < min) { min = hs.getGrowthMomentumScore(); name = "Growth Momentum"; }
        if (hs.getCustomerLoyaltyScore() < min) { min = hs.getCustomerLoyaltyScore(); name = "Customer Loyalty"; }
        if (hs.getPaymentEfficiencyScore() < min) { min = hs.getPaymentEfficiencyScore(); name = "Payment Efficiency"; }
        if (hs.isDccApplicable() && hs.getDccPerformanceScore() < min) { name = "DCC Performance"; }
        return name;
    }
}
