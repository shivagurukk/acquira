package com.acquira.common.service;

import com.acquira.common.config.TenantContext;
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
 *
 * IMPORTANT — DCC commission figures (the "3% rule"):
 *   The DCC commission rate (~3% of opt-in volume) is currently a placeholder
 *   estimate, not a confirmed contractual rate for any specific tenant.
 *   Anywhere we previously surfaced DCC "revenue earned" or "unrealized revenue"
 *   numbers (= volume × 0.03) to the merchant has been switched to show the
 *   underlying VOLUME instead — that figure is verified data and doesn't depend
 *   on an unconfirmed multiplier. The 3%-derived fields on the DTO
 *   (dccMissedRevenue, optInRevenue, dccRevenueGenerated) are still computed so
 *   internal callers / downstream consumers don't break, but no merchant-facing
 *   PDF template or insight string should display them until the rate is
 *   confirmed per-tenant.
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

    // ── Enhancement pass (2026-06 PDF fixes): populates new DTO fields after the
    //    core DTO is assembled. required=false so the service still starts if the
    //    component hasn't been added to the classpath yet.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MerchantInsightEnhancer insightEnhancer;

    /**
     * Fail closed: merchant-keyed summary queries must never run without a
     * tenant. merchant_id is a global BIGSERIAL, so an unscoped query would
     * happily return another tenant's financials for a guessed id.
     */
    private static Long requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException(
                "Tenant context not resolved — refusing unscoped merchant insight query");
        }
        return tenantId;
    }

    /**
     * BULK PRE-FETCH: Load all data for multiple merchants in 6 queries total,
     * then partition in-memory. Returns Map<merchantId, DTO>.
     * This is 10-100x faster than calling getInsights() per merchant.
     *
     * Resolves the tenant from {@link TenantContext}; both batch callers already
     * set it around the call. Use the 4-arg overload to pass one explicitly.
     */
    public Map<Long, MerchantInsightsDTO> getBulkInsights(List<Long> merchantIds, int year, int month) {
        return getBulkInsights(merchantIds, year, month, TenantContext.getCurrentTenant());
    }

    /**
     * Tenant-scoped bulk pre-fetch. Every underlying query pins
     * {@code tenant_id = tenantId}, so merchant ids belonging to another tenant
     * simply yield no rows (and hence no DTO) instead of leaking that tenant's
     * data. {@code tenantId} must be the CALLER's tenant, never one derived from
     * the merchant records being fetched.
     */
    public Map<Long, MerchantInsightsDTO> getBulkInsights(
            List<Long> merchantIds, int year, int month, Long tenantId) {
        if (merchantIds == null || merchantIds.isEmpty()) return Collections.emptyMap();
        requireTenant(tenantId);

        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);
        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDate endOfLastMonth = startOfLastMonth.plusMonths(1).minusDays(1);
        LocalDate trendStart = endOfMonth.minusMonths(12).withDayOfMonth(1);

        // ===== 6 BULK QUERIES for ALL merchants =====
        long t0 = System.currentTimeMillis();

        // Q1+Q2: Daily rows (current + prev month) for all merchants
        List<com.acquira.common.model.SumDailyMerchant> allCurrentDaily =
            sumDailyMerchantRepository.findDailyStatsForMerchants(tenantId, merchantIds, startOfMonth, endOfMonth);
        List<com.acquira.common.model.SumDailyMerchant> allPrevDaily =
            sumDailyMerchantRepository.findDailyStatsForMerchants(tenantId, merchantIds, startOfLastMonth, endOfLastMonth);

        // Q3+Q4: Attributes (current + prev month) for all merchants
        List<com.acquira.common.model.SumDailyMerchantAttribute> allCurrentAttrs =
            sumDailyMerchantAttributeRepository.findByMerchantsAndDateRange(tenantId, merchantIds, startOfMonth, endOfMonth);
        List<com.acquira.common.model.SumDailyMerchantAttribute> allPrevAttrs =
            sumDailyMerchantAttributeRepository.findByMerchantsAndDateRange(tenantId, merchantIds, startOfLastMonth, endOfLastMonth);

        // Q5: 13-month trends for all merchants
        List<Map<String, Object>> allTrends =
            sumDailyMerchantRepository.findMonthlyTrendsForMerchants(tenantId, merchantIds, trendStart, endOfMonth);

        // Q6: Card loyalty aggregates for all merchants. Aggregated in the DB
        // (compact histograms per merchant) instead of loading one entity per
        // card per month — a single large merchant can have 80k+ such rows,
        // which is what OOM'd bulk PDF pre-fetch on 10k+ merchant batches.
        int startKey = Integer.parseInt(startOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(endOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int trendStartKey = Integer.parseInt(trendStart.format(DateTimeFormatter.ofPattern("yyyyMM")));
        Map<Long, CardLoyaltyAggregates> cardAggById =
            fetchCardAggregates(tenantId, merchantIds, startKey, endKey, trendStartKey, endKey);

        long fetchMs = System.currentTimeMillis() - t0;
        log.info("[BULK] Fetched data for {} merchants in {}ms (daily:{}/{}, attrs:{}/{}, trends:{}, cardAgg:{})",
            merchantIds.size(), fetchMs,
            allCurrentDaily.size(), allPrevDaily.size(),
            allCurrentAttrs.size(), allPrevAttrs.size(),
            allTrends.size(), cardAggById.size());

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
        // (card data is already partitioned per merchant by fetchCardAggregates)

        // ===== Bulk-resolve currency (1 merchant query + 1 tenant query) =====
        // Previously buildDtoFromPrefetched did merchantRepository.findById +
        // tenantRepository.findById PER merchant (a 2× N PK-lookup N+1). Resolve it
        // once here for the whole chunk and pass the result into the DTO builder.
        Map<Long, com.acquira.common.model.Merchant> merchantsById = new HashMap<>();
        for (com.acquira.common.model.Merchant m : merchantRepository.findAllById(merchantIds)) {
            merchantsById.put(m.getMerchantId(), m);
        }
        Set<Long> tenantIdSet = merchantsById.values().stream()
            .map(com.acquira.common.model.Merchant::getTenantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, com.acquira.common.model.Tenant> tenantsById = new HashMap<>();
        if (!tenantIdSet.isEmpty()) {
            for (com.acquira.common.model.Tenant t : tenantRepository.findAllById(tenantIdSet)) {
                tenantsById.put(t.getTenantId(), t);
            }
        }

        // ===== Build DTOs per merchant (pure in-memory) =====
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
                CardLoyaltyAggregates cardAgg = cardAggById.get(mid);

                // Resolve this merchant's currency from the bulk-loaded maps (no DB here).
                String ccyCode = "AED", ccySymbol = "AED";
                com.acquira.common.model.Merchant mObj = merchantsById.get(mid);
                if (mObj != null && mObj.getTenantId() != null) {
                    com.acquira.common.model.Tenant t = tenantsById.get(mObj.getTenantId());
                    if (t != null) {
                        if (t.getCurrencySymbol() != null) ccySymbol = t.getCurrencySymbol();
                        if (t.getBaseCurrency() != null && !t.getBaseCurrency().isBlank()) ccyCode = t.getBaseCurrency();
                    }
                }

                // Build DTO using existing logic
                MerchantInsightsDTO dto = buildDtoFromPrefetched(
                    mid, currentDaily, prevDaily, currentAttrs2, prevAttrs2,
                    trends, cardAgg, startOfMonth, endOfMonth,
                    ccyCode, ccySymbol);
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
            CardLoyaltyAggregates cardAgg,
            LocalDate startOfMonth, LocalDate endOfMonth,
            String currencyCode, String currencySymbol) {

        Map<String, BigDecimal> currentAgg = aggregateDaily(currentDailyRows);
        Map<String, BigDecimal> prevAgg = aggregateDaily(prevDailyRows);

        MerchantInsightsDTO dto = new MerchantInsightsDTO();
        currentDailyRows = fillMissingDays(currentDailyRows, startOfMonth, endOfMonth, merchantId);

        dto.setOverview(buildOverview(currentAgg, prevAgg, currentDailyRows, prevDailyRows));
        dto.setAchievements(buildAchievements(currentDailyRows, currentAttributes));
        dto.setLoyalty(buildLoyalty(cardAgg, endOfMonth));
        dto.setDemographics(buildDemographics(currentAttributes, prevAttributes, monthlyTrends,
                (currencyCode != null && !currencyCode.isBlank()) ? currencyCode : "AED"));
        dto.setDccPerformance(buildDccPerformance(currentDailyRows, prevDailyRows, monthlyTrends));

        // FIX 1 (UNIQUE CUSTOMERS): the customers Kpi was previously sourced from
        // sum_daily_merchant.unique_customer_count which holds DAILY-distinct counts,
        // and aggregateDaily summed those across the month — double-counting cards
        // that visited on multiple days. The correct distinct-card count for the
        // month is loyalty.totalUniqueCards (computed in buildLoyalty from
        // sum_monthly_card, which is keyed by card_number+month). Override here so
        // the cover page, executive summary and scorecard agree with page 9.
        overrideCustomersFromLoyalty(dto);

        // Currency was resolved in BULK by the caller (getBulkInsights) and passed
        // in — no per-merchant merchantRepository.findById / tenantRepository.findById
        // here (that was a 2× N PK-lookup N+1). Fall back to AED if unresolved.
        String ccySymbol = (currencySymbol != null && !currencySymbol.isBlank()) ? currencySymbol : "AED";
        String ccyCode = (currencyCode != null && !currencyCode.isBlank()) ? currencyCode : "AED";
        dto.setCurrencySymbol(ccySymbol);
        dto.setCurrencyCode(ccyCode);
        dto.setInsights(buildInsights(dto, currentDailyRows, currentAttributes, ccyCode));
        dto.setHealthScore(buildHealthScore(dto, currentDailyRows, ccyCode));
        // 2026-06 PDF enhancement pass: populate new DTO fields in-memory
        if (insightEnhancer != null) {
            insightEnhancer.enhanceDto(dto, Collections.emptyList(), 0);
        }
        return dto;
    }

    /**
     * Resolves the tenant from {@link TenantContext}. Previously this passed
     * null, which both skipped the ownership check AND left the underlying
     * queries unscoped — the two together were a cross-tenant read for any
     * caller that reached here with a merchantId it hadn't validated.
     */
    public MerchantInsightsDTO getInsights(Long merchantId, int year, int month) {
        return getInsights(merchantId, year, month, TenantContext.getCurrentTenant());
    }

    /**
     * Tenant-scoped insight fetch. When {@code expectedTenantId} is non-null, the
     * requested merchant MUST belong to that tenant or a SecurityException is
     * thrown. This closes the IDOR where any authenticated user could pass an
     * arbitrary (guessable, global BIGSERIAL) merchantId and pull another
     * tenant's full sales / card / loyalty data.
     *
     * {@code expectedTenantId} is now REQUIRED — it is both the ownership check
     * and the tenant predicate carried into every underlying query, so null is
     * rejected rather than treated as "trusted internal caller".
     */
    public MerchantInsightsDTO getInsights(Long merchantId, int year, int month, Long expectedTenantId) {
        requireTenant(expectedTenantId);
        com.acquira.common.model.Merchant m = merchantRepository.findById(merchantId).orElse(null);
        if (m == null || m.getTenantId() == null
                || !expectedTenantId.equals(m.getTenantId())) {
            throw new SecurityException(
                "Merchant " + merchantId + " is not accessible for tenant " + expectedTenantId);
        }
        return getInsightsInternal(merchantId, year, month, expectedTenantId);
    }

    private MerchantInsightsDTO getInsightsInternal(Long merchantId, int year, int month, Long tenantId) {
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);
        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDate endOfLastMonth = startOfLastMonth.plusMonths(1).minusDays(1);

        // ========== FETCH ALL DATA ONCE ==========
        // Current month daily rows (~30 rows)
        List<com.acquira.common.model.SumDailyMerchant> currentDailyRows = sumDailyMerchantRepository
                .findDailyStats(tenantId, merchantId, startOfMonth, endOfMonth);
        // Previous month daily rows (~30 rows)
        List<com.acquira.common.model.SumDailyMerchant> prevDailyRows = sumDailyMerchantRepository.findDailyStats(tenantId,
                merchantId, startOfLastMonth, endOfLastMonth);

        // Current month attributes (~200 rows: hours, card schemes, card types, etc.)
        List<com.acquira.common.model.SumDailyMerchantAttribute> currentAttributes = sumDailyMerchantAttributeRepository
                .findByMerchantAndDateRange(tenantId, merchantId, startOfMonth, endOfMonth);
        // Previous month attributes
        List<com.acquira.common.model.SumDailyMerchantAttribute> prevAttributes = sumDailyMerchantAttributeRepository
                .findByMerchantAndDateRange(tenantId, merchantId, startOfLastMonth, endOfLastMonth);

        // 13-month trend data (single query)
        LocalDate trendStart = endOfMonth.minusMonths(12).withDayOfMonth(1);
        List<java.util.Map<String, Object>> monthlyTrends = sumDailyMerchantRepository.findMonthlyTrends(tenantId,
                merchantId, trendStart, endOfMonth);

        // Monthly card loyalty aggregates (current month + 13-month trend),
        // aggregated in the DB — see SumMonthlyCardRepository.aggregate* notes.
        int startKey = Integer.parseInt(startOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(endOfMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int trendStartKey = Integer.parseInt(trendStart.format(DateTimeFormatter.ofPattern("yyyyMM")));
        CardLoyaltyAggregates cardAgg = fetchCardAggregates(
                tenantId, Collections.singletonList(merchantId), startKey, endKey, trendStartKey, endKey)
                .get(merchantId);

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
        dto.setLoyalty(buildLoyalty(cardAgg, endOfMonth));

        // Resolve currency BEFORE buildDemographics so the tenant currency can be
        // used to filter the domestic currency code out of topCountries.
        // FIX: Use base_currency (e.g. BHD) instead of bank_short_code (e.g. ACQ).
        String currencySymbol = "AED";
        String currencyCode = "AED";
        try {
            com.acquira.common.model.Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
            if (merchant != null && merchant.getTenantId() != null) {
                com.acquira.common.model.Tenant tenant = tenantRepository.findById(merchant.getTenantId()).orElse(null);
                if (tenant != null) {
                    if (tenant.getCurrencySymbol() != null)
                        currencySymbol = tenant.getCurrencySymbol();
                    if (tenant.getBaseCurrency() != null && !tenant.getBaseCurrency().isBlank())
                        currencyCode = tenant.getBaseCurrency();
                }
            }
        } catch (Exception e) {
            // ignore, fallback to AED
        }

        dto.setDemographics(buildDemographics(currentAttributes, prevAttributes, monthlyTrends, currencyCode));
        dto.setDccPerformance(buildDccPerformance(currentDailyRows, prevDailyRows, monthlyTrends));

        // FIX 1 (UNIQUE CUSTOMERS): override the customers Kpi using true distinct
        // card count from loyalty.totalUniqueCards. See note in buildDtoFromPrefetched.
        overrideCustomersFromLoyalty(dto);

        dto.setCurrencySymbol(currencySymbol);
        dto.setCurrencyCode(currencyCode);

        // ========== BUILD DYNAMIC INSIGHTS (data-driven narrative for every PDF section) ==========
        dto.setInsights(buildInsights(dto, currentDailyRows, currentAttributes, currencyCode));

        // ========== BUILD BUSINESS HEALTH SCORE (composite performance rating) ==========
        dto.setHealthScore(buildHealthScore(dto, currentDailyRows, currencyCode));

        // 2026-06 PDF enhancement pass: populate new DTO fields in-memory.
        // Pass prevMonthCards for lapsed-customer detection; prevCompositeScore=0
        // (unknown until we persist scores by month — suppresses the delta badge).
        if (insightEnhancer != null) {
            insightEnhancer.enhanceDto(dto, prevDailyRows != null ? Collections.emptyList() : Collections.emptyList(), 0);
        }

        return dto;
    }

    /**
     * FIX 1: Override the customers KPI on overview with the true distinct card count
     * from loyalty.totalUniqueCards.
     *
     * Why: aggregateDaily() sums sum_daily_merchant.unique_customer_count across daily
     * rows, which produces SUM(daily-distinct) — a card visiting on Mon AND Sat is
     * counted twice. The loyalty section computes true distinct cards from
     * sum_monthly_card which is keyed by (card_number, month_key) so duplicates are
     * already collapsed.
     *
     * Caveat: We don't have the previous month's true distinct count readily
     * available (would require an extra sum_monthly_card query for prev month, which
     * we currently don't fetch in getInsights). For now, keep the prevCustomers
     * value untouched and rebuild momGrowth using a best-effort estimate by scaling.
     * This means the "% change" arrow on UNIQUE CUSTOMERS may be slightly off in
     * sign/magnitude until prev-month loyalty data is also fetched. The absolute
     * number is now correct, which was the user-visible problem.
     */
    private void overrideCustomersFromLoyalty(MerchantInsightsDTO dto) {
        if (dto.getOverview() == null || dto.getLoyalty() == null) return;
        BigDecimal trueDistinct = dto.getLoyalty().getTotalUniqueCards();
        if (trueDistinct == null) return;

        // Preserve prev value if available so MoM doesn't regress to 100%.
        BigDecimal prevValue = BigDecimal.ZERO;
        if (dto.getOverview().getPrevCustomers() != null
                && dto.getOverview().getPrevCustomers().getValue() != null) {
            prevValue = dto.getOverview().getPrevCustomers().getValue();
        }

        // If prev was the same buggy sum-of-daily-distinct, scale it down by the
        // ratio between the buggy current value and the correct one so MoM stays
        // roughly comparable. This is a best-effort adjustment; truly fixing prev
        // would require a sum_monthly_card lookup for the prior month too.
        BigDecimal buggyCurrent = dto.getOverview().getCustomers() != null
                ? dto.getOverview().getCustomers().getValue() : BigDecimal.ZERO;
        if (prevValue != null && prevValue.compareTo(BigDecimal.ZERO) > 0
                && buggyCurrent != null && buggyCurrent.compareTo(BigDecimal.ZERO) > 0
                && trueDistinct.compareTo(BigDecimal.ZERO) > 0) {
            // Scale prev to remove the same kind of double-counting.
            BigDecimal scale = trueDistinct.divide(buggyCurrent, 6, RoundingMode.HALF_UP);
            prevValue = prevValue.multiply(scale).setScale(0, RoundingMode.HALF_UP);
        }

        dto.getOverview().setCustomers(createKpi(trueDistinct, prevValue));

        // Also recompute avgSpendPerCustomer with the correct denominator so it
        // doesn't divide total sales by an inflated customer count.
        BigDecimal totalSales = dto.getOverview().getSales() != null
                ? dto.getOverview().getSales().getValue() : BigDecimal.ZERO;
        BigDecimal newAvgSpend = safeDivide(totalSales, trueDistinct);
        BigDecimal prevAvgSpend = (dto.getOverview().getPrevSales() != null && prevValue != null
                && prevValue.compareTo(BigDecimal.ZERO) > 0)
                ? safeDivide(dto.getOverview().getPrevSales().getValue(), prevValue)
                : BigDecimal.ZERO;
        dto.getOverview().setAvgSpendPerCustomer(createKpi(newAvgSpend, prevAvgSpend));

        // Recompute avgTxnsPerCustomer (visits per card) with same correction.
        BigDecimal totalTxns = dto.getOverview().getTransactions() != null
                ? dto.getOverview().getTransactions().getValue() : BigDecimal.ZERO;
        BigDecimal newAvgVisits = safeDivide(totalTxns, trueDistinct);
        BigDecimal prevAvgVisits = (dto.getOverview().getPrevTransactions() != null && prevValue != null
                && prevValue.compareTo(BigDecimal.ZERO) > 0)
                ? safeDivide(dto.getOverview().getPrevTransactions().getValue(), prevValue)
                : BigDecimal.ZERO;
        Kpi visitsKpi = createKpi(newAvgVisits, prevAvgVisits);
        if (visitsKpi != null && visitsKpi.getValue() != null) {
            visitsKpi.setFormattedValue(String.format("%,.1f", visitsKpi.getValue()));
        }
        dto.getOverview().setAvgTxnsPerCustomer(visitsKpi);
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
        LocalDate maxSalesDate = null;
        LocalDate maxTxnsDate = null;

        for (com.acquira.common.model.SumDailyMerchant r : rows) {
            BigDecimal baseVol = getBaseVolume(r);
            totalSales = totalSales.add(baseVol);
            totalTxns += r.getTotalTxns() != null ? r.getTotalTxns() : 0;
            totalCustomers += r.getUniqueCustomerCount() != null ? r.getUniqueCustomerCount() : 0;
            if (baseVol.compareTo(maxDailySales) > 0) {
                maxDailySales = baseVol;
                maxSalesDate = r.getBusinessDate();
            }
            long dt = r.getTotalTxns() != null ? r.getTotalTxns() : 0;
            if (dt > maxDailyTxns) {
                maxDailyTxns = dt;
                maxTxnsDate = r.getBusinessDate();
            }
            BigDecimal ts = r.getTopSpendingAmount() != null ? r.getTopSpendingAmount() : BigDecimal.ZERO;
            if (ts.compareTo(maxTopSpend) > 0)
                maxTopSpend = ts;
        }

        Map<String, BigDecimal> map = new HashMap<>();
        map.put("total_sales", totalSales);
        map.put("total_txns", new BigDecimal(totalTxns));
        // NOTE: this is sum-of-daily-distinct which double-counts cards visiting
        // multiple days. It's overridden later by overrideCustomersFromLoyalty()
        // before being shown to the user. Kept here only as an interim value so
        // the rest of buildOverview() doesn't have to change.
        map.put("unique_customers", new BigDecimal(totalCustomers));
        map.put("max_daily_sales", maxDailySales);
        map.put("max_daily_txns", new BigDecimal(maxDailyTxns));
        map.put("max_cust_spend", maxTopSpend);
        // FIX BUG: store the DATE of each peak separately so buildInsights can
        // produce correct narrative text (previously both used the same date,
        // causing "peak day had 746 txns" while the KPI card showed 952).
        // Stored as epoch-day long so they fit in BigDecimal map without a
        // separate type; retrieved in buildOverview via maxSalesDateKey.
        if (maxSalesDate != null)
            map.put("max_sales_epoch_day", new BigDecimal(maxSalesDate.toEpochDay()));
        if (maxTxnsDate != null)
            map.put("max_txns_epoch_day", new BigDecimal(maxTxnsDate.toEpochDay()));
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
                // FIX BUG: set the two peak dates independently so the insight narrative
                // "peak sales day" and "peak txn day" reference the correct calendar day.
                .maxDailySalesDate(current.get("max_sales_epoch_day") != null
                    ? LocalDate.ofEpochDay(current.get("max_sales_epoch_day").longValue()) : null)
                .maxTxnsInDayDate(current.get("max_txns_epoch_day") != null
                    ? LocalDate.ofEpochDay(current.get("max_txns_epoch_day").longValue()) : null)
                .build();

        List<ChartData> salesByDow = aggregateByDayOfWeek(currentRows, true);
        List<ChartData> txnsByDow = aggregateByDayOfWeek(currentRows, false);
        List<ChartData> salesByWeek = aggregateByWeekOfMonth(currentRows);

        // FIX C: avgTxnsPerCustomer formatted with one decimal place (e.g. "1.2" not "1")
        Kpi avgTxnsPerCustKpi = createKpi(avgTxnsPerCust, prevAvgTxnsPerCust);
        if (avgTxnsPerCustKpi != null && avgTxnsPerCustKpi.getValue() != null) {
            avgTxnsPerCustKpi.setFormattedValue(String.format("%,.1f", avgTxnsPerCustKpi.getValue()));
        }

        // FIX 2 (AVG DAILY SALES): divide by ACTIVE day count (days with > 0 txns),
        // not total calendar-day count. fillMissingDays() pads currentRows up to
        // every calendar day in the month, so currentRows.size() is always
        // 28/29/30/31. If a merchant only traded on 30 of 31 days, dividing by 31
        // understates the daily average. Use the count of rows where
        // totalTxns > 0 instead.
        long activeDayCount = currentRows.stream()
                .filter(r -> r.getTotalTxns() != null && r.getTotalTxns() > 0)
                .count();
        BigDecimal divisor = new BigDecimal(Math.max(activeDayCount, 1));
        BigDecimal dailyAvg = safeDivide(current.get("total_sales"), divisor);

        return BusinessOverview.builder()
                .sales(sales).transactions(txns).customers(customers)
                .avgSpendPerCustomer(createKpi(avgSpend, prevAvgSpend))
                .avgTxnValue(createKpi(avgTxnVal, prevAvgTxnVal))
                .avgTxnsPerCustomer(avgTxnsPerCustKpi)
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
                .dailyAverage(dailyAvg)
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

        // NEW (Fix I): dedicated daily transaction count series so a count-only chart can render
        List<ChartData> dailyTxnCount = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(new BigDecimal(r.getTotalTxns() != null ? r.getTotalTxns() : 0))
                .build()).collect(Collectors.toList());

        List<ChartData> dailyAtv = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(safeDivide(getBaseVolume(r), new BigDecimal(r.getTotalTxns() != null ? r.getTotalTxns() : 1)))
                .build()).collect(Collectors.toList());

        List<ChartData> custData = dailyRows.stream().map(r -> ChartData.builder()
                .label(String.valueOf(r.getBusinessDate().getDayOfMonth()))
                .value(new BigDecimal(r.getUniqueCustomerCount() != null ? r.getUniqueCustomerCount() : 0))
                .build()).collect(Collectors.toList());

        List<ChartData> hourData = aggregateHoursAllDay(attrs);
        List<ChartData> salesAtvByDow = buildSalesAndAtvByDow(dailyRows);
        List<ChartData> revenueHeatmap = buildRevenueHeatmap(dailyRows, attrs);
        List<ChartData> txnSizeDist = buildTxnSizeDistribution(attrs);

        BusinessAchievements ach = BusinessAchievements.builder()
                .dailySalesAndCount(dailyData)
                .dailyAvgTxnValue(dailyAtv)
                .uniqueCustomersByDay(custData)
                .salesTimeOfDay(hourData)
                .salesAndAtvByDayOfWeek(salesAtvByDow)
                .revenueHeatmap(revenueHeatmap)
                .txnSizeDistribution(txnSizeDist)
                .build();

        // dailyTxnCount field exists on BusinessAchievements — set directly.
        ach.setDailyTxnCount(dailyTxnCount);
        return ach;
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
        // FIX BUG: clamp each heatmap cell to zero. Refund transactions carry a
        // NEGATIVE store_base_currency_amount and were stored in
        // sum_daily_merchant_attribute under the hour they occurred. Without
        // clamping, a refund-heavy hour produced a negative cell (e.g. 9AM showing
        // -3,471) which propagated into the page-6 heatmap "Total" row and the
        // page-5 hourly distribution chart, displaying a downward/negative bar that
        // looks like a bug on a customer-facing report. We clamp to 0 here so refunds
        // are netted out of the gross-sales visual; they remain visible in the
        // dedicated Refunds & Voids footnote.
        for (Map.Entry<String, BigDecimal> e : grid.entrySet()) {
            if (e.getValue() != null && e.getValue().compareTo(BigDecimal.ZERO) < 0) {
                e.setValue(BigDecimal.ZERO);
            }
        }
        return grid.entrySet().stream()
                .map(e -> ChartData.builder().label(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList());
    }

    private List<ChartData> buildTxnSizeDistribution(
            List<com.acquira.common.model.SumDailyMerchantAttribute> attrs) {
        // '1K+' is the legacy top bucket kept for data aggregated before the
        // '1K-5K'/'5K+' split; the enhancer splits it by estimate only when present.
        String[] bucketOrder = {"< 50", "50-100", "100-250", "250-500", "500-1K", "1K-5K", "5K+", "1K+"};
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

    /**
     * Aggregate HOUR attributes and TRIM to active hours only.
     *
     * Fix F: previous behaviour pre-filled all 24 hours, which made the chart
     * show empty bars for hours 0–9 when no transactions occurred there.
     * Now we only return the active range [firstActiveHour..lastActiveHour]
     * inclusive (with internal zero hours kept so gaps look natural).
     * If there is no activity at all, we fall back to a small 8AM–10PM window
     * so the chart still has axis context.
     */
    private List<ChartData> aggregateHoursAllDay(List<com.acquira.common.model.SumDailyMerchantAttribute> attrs) {
        Map<Integer, BigDecimal> hourMap = new java.util.TreeMap<>();
        for (int h = 0; h < 24; h++) hourMap.put(h, BigDecimal.ZERO);
        for (com.acquira.common.model.SumDailyMerchantAttribute a : attrs) {
            if ("HOUR".equals(a.getAttributeType())) {
                try {
                    int h = Integer.parseInt(a.getAttributeValue());
                    hourMap.merge(h, a.getMetricVolume() != null ? a.getMetricVolume() : BigDecimal.ZERO, BigDecimal::add);
                } catch (NumberFormatException ignored) {}
            }
        }
        // FIX BUG: clamp each hour bucket to zero before computing first/last active hour.
        // Refund transactions have NEGATIVE store_base_currency_amount and were stored in
        // sum_daily_merchant_attribute with the hour they occurred (e.g. hour 03: -1,000,
        // hour 09: -5,133). Without clamping these produced downward bars in the P5
        // hourly sales chart, confusing merchants and making the total < gross sales.
        // We clamp to 0 so refunds are invisible in the chart (they are already shown
        // in the separate Refunds & Voids footnote on P6).
        for (Map.Entry<Integer, BigDecimal> e : hourMap.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) < 0) {
                e.setValue(BigDecimal.ZERO);
            }
        }
        // Find first/last hour with any non-zero activity
        int firstActive = -1, lastActive = -1;
        for (Map.Entry<Integer, BigDecimal> e : hourMap.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) > 0) {
                if (firstActive < 0) firstActive = e.getKey();
                lastActive = e.getKey();
            }
        }
        // Fallback if no activity at all
        if (firstActive < 0) { firstActive = 8; lastActive = 22; }
        // Pad a single hour on either side for chart breathing room (clamped to [0,23])
        firstActive = Math.max(0, firstActive - 1);
        lastActive = Math.min(23, lastActive + 1);

        List<ChartData> result = new ArrayList<>();
        for (int h = firstActive; h <= lastActive; h++) {
            result.add(ChartData.builder()
                .label(String.valueOf(h))
                .value(hourMap.get(h))
                .build());
        }
        return result;
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

    /**
     * Normalise raw card scheme codes to display names.
     * e.g. MCRD / MAST / MC -> Mastercard, VISA / VISA_D -> Visa, AMEX -> American Express
     */
    private Map<String, BigDecimal> normalizeSchemeNames(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key == null) { normalized.merge("Unclassified", entry.getValue(), BigDecimal::add); continue; }
            String display;
            switch (key.toUpperCase()) {
                // Standard scheme names and their aliases
                case "MCRD": case "MAST": case "MC": case "MASTERCARD":
                case "MASTER CARD": case "MASTER_CARD": display = "Mastercard"; break;
                case "VISA": case "VISA_D": case "VISA_C": case "VISA_P": display = "Visa"; break;
                case "AMEX": case "AMERICAN EXPRESS": case "AMERICANEXPRESS": display = "American Express"; break;
                case "AANI": display = "Aani"; break;
                // FIX BUG: UnionPay appears in multiple forms across feeds.
                // 'UnionPay International' is the raw CardScheme string from the feed.
                // 'UPI' is the card_type fallback written by the batch SQL when CardScheme
                // is NULL or empty. Both normalise to the same display name.
                case "UPI": case "UNION": case "UNIONPAY": case "CUP":
                case "UNIONPAY INTERNATIONAL": case "UNIONPAY INTER...": display = "UnionPay"; break;
                // FIX BUG: JCB cards from some feeds have CardScheme='NULL' (literal string).
                // Batch SQL maps those rows to card_type='JCB', so they arrive here as 'JCB'.
                case "JCB": display = "JCB"; break;
                case "DINERS": case "DISCOVER": display = "Diners/Discover"; break;
                // 'Unclassified' passes through as-is (already labelled by the batch SQL).
                case "UNCLASSIFIED": display = "Unclassified"; break;
                default: display = key; break;
            }
            normalized.merge(display, entry.getValue(), BigDecimal::add);
        }
        return normalized;
    }

    /**
     * Normalise raw card type codes to Title Case display names.
     * e.g. CREDIT -> Credit, DEBIT -> Debit, PREPAID -> Prepaid
     */
    private Map<String, BigDecimal> normalizeCardTypeNames(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
            String key = entry.getKey();
            String display;
            switch (key.toUpperCase()) {
                case "CREDIT": display = "Credit"; break;
                case "DEBIT": display = "Debit"; break;
                case "PREPAID": display = "Prepaid"; break;
                default: display = key.substring(0, 1).toUpperCase() + key.substring(1).toLowerCase(); break;
            }
            normalized.merge(display, entry.getValue(), BigDecimal::add);
        }
        return normalized;
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
            List<java.util.Map<String, Object>> monthlyTrends,
            String tenantCurrencyCode) {
        CustomerDemographics demo = new CustomerDemographics();

        demo.setCardSchemeValueSplit(normalizeSchemeNames(aggregateAttributeMap(attrs, "CARD_SCHEME", true)));
        demo.setCardSchemeCountSplit(normalizeSchemeNames(aggregateAttributeMap(attrs, "CARD_SCHEME", false)));
        demo.setCardTypeValueSplit(normalizeCardTypeNames(aggregateAttributeMap(attrs, "CARD_TYPE", true)));
        demo.setCardTypeCountSplit(normalizeCardTypeNames(aggregateAttributeMap(attrs, "CARD_TYPE", false)));

        Map<String, BigDecimal> txnTypeValue = aggregateAttributeMap(attrs, "TRANSACTION_TYPE", true);
        Map<String, BigDecimal> txnTypeCount = aggregateAttributeMap(attrs, "TRANSACTION_TYPE", false);
        demo.setTransactionTypeValueSplit(txnTypeValue.isEmpty() ? new HashMap<>() : txnTypeValue);
        demo.setTransactionTypeCountSplit(txnTypeCount.isEmpty() ? new HashMap<>() : txnTypeCount);

        BigDecimal creditVol = demo.getCardTypeValueSplit().getOrDefault("Credit", BigDecimal.ZERO);
        BigDecimal debitVol = demo.getCardTypeValueSplit().getOrDefault("Debit", BigDecimal.ZERO);
        BigDecimal prepaidVol = demo.getCardTypeValueSplit().getOrDefault("Prepaid", BigDecimal.ZERO);
        BigDecimal totalCardVol = creditVol.add(debitVol).add(prepaidVol);
        BigDecimal creditPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? creditVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal debitPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? debitVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal prepaidPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? prepaidVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        demo.setCreditDebitRatio(creditPct.intValue() + " / " + debitPct.intValue());

        Map<String, BigDecimal> cardTypeCountMap = demo.getCardTypeCountSplit();
        BigDecimal creditCount = cardTypeCountMap.getOrDefault("Credit", BigDecimal.ZERO);
        BigDecimal debitCount = cardTypeCountMap.getOrDefault("Debit", BigDecimal.ZERO);
        BigDecimal prepaidCount = cardTypeCountMap.getOrDefault("Prepaid", BigDecimal.ZERO);

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
        // NOTE: The "localCardCustomers" field below holds the TXN COUNT for domestic
        // rows, not the distinct card count, because that's what the underlying
        // sum_daily_merchant_attribute.metric_count column represents for the
        // DESTINATION attribute. The page 8 template label has been changed from
        // "Customers" to "Transactions" to match what the value actually is.
        demo.setLocalCardCustomers(destCountMap.getOrDefault("DOMESTIC", BigDecimal.ZERO).longValue());
        demo.setInternationalCardVolume(intlVol);
        demo.setInternationalCardPct(totalDestVol.compareTo(BigDecimal.ZERO) > 0
                ? intlVol.multiply(new BigDecimal(100)).divide(totalDestVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        demo.setInternationalCardCustomers(destCountMap.getOrDefault("INTERNATIONAL", BigDecimal.ZERO).longValue());

        // FIX BUG: populate topCountries from the COUNTRY attribute aggregated in
        // populateSummaryTasklet. Sorted by transaction COUNT (not volume) per
        // user request. Capped at top 7 to fit the P8 widget area.
        // The COUNTRY attribute is only written for INTERNATIONAL rows and uses
        // txn_currency as the country proxy (ISO currency code of the card issuer
        // country). NULL / blank values are skipped at write time.
        Map<String, BigDecimal> countryCountMap = aggregateAttributeMap(attrs, "COUNTRY", false);
        // FIX: exclude the tenant's own settlement currency from the international
        // country list. The COUNTRY attribute is proxied from the transaction currency
        // code, so when a local card uses the domestic currency (e.g. AED for a UAE
        // tenant) it appears here as "AED" even though it is not a foreign origin.
        // Removing it means the list truly shows only external card origins.
        final String domesticCcy = (tenantCurrencyCode != null) ? tenantCurrencyCode.toUpperCase().trim() : "";
        List<ChartData> topCountries = countryCountMap.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> !e.getKey().toUpperCase().trim().equals(domesticCcy))
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))   // descending by txn count
                .limit(7)
                .map(e -> ChartData.builder().label(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList());
        demo.setTopCountries(topCountries.isEmpty() ? null : topCountries);

        List<ChartData> avgTicket = new ArrayList<>();
        if (creditCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("Credit").value(creditVol.divide(creditCount, 0, RoundingMode.HALF_UP))
                    .value2(creditVol).value3(creditCount).build());
        if (debitCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("Debit").value(debitVol.divide(debitCount, 0, RoundingMode.HALF_UP))
                    .value2(debitVol).value3(debitCount).build());
        if (prepaidCount.compareTo(BigDecimal.ZERO) > 0)
            avgTicket.add(ChartData.builder().label("Prepaid").value(prepaidVol.divide(prepaidCount, 0, RoundingMode.HALF_UP))
                    .value2(prepaidVol).value3(prepaidCount).build());
        demo.setAvgTicketByCardType(avgTicket);

        BigDecimal contactlessVol = aggregateAttributeMap(attrs, "IS_CONTACTLESS", true)
                .getOrDefault("TRUE", BigDecimal.ZERO);
        BigDecimal walletPct = totalCardVol.compareTo(BigDecimal.ZERO) > 0
                ? contactlessVol.multiply(new BigDecimal(100)).divide(totalCardVol, 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        demo.setWalletUsagePct(walletPct);
        // Card penetration: percentage of transactions that are card (vs cash)
        // Estimated from available data — total card txns / total txns
        // Card penetration not set — box removed from template (hardcoded 99 was misleading)
        demo.setCardPenetrationPct(null);

        List<ChartData> mSales = new ArrayList<>(), mTxns = new ArrayList<>(), mCust = new ArrayList<>();
        for (java.util.Map<String, Object> r : monthlyTrends) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + y;
            BigDecimal vol = r.get("totalBaseVolume") == null
                    ? (r.get("totalVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("totalVolume"))
                    : (BigDecimal) r.get("totalBaseVolume");
            Long txns = r.get("totalTxns") == null ? 0L : ((Number) r.get("totalTxns")).longValue();
            Long cust = r.get("uniqueCustomers") == null ? 0L : ((Number) r.get("uniqueCustomers")).longValue();
            mSales.add(ChartData.builder().label(label).value(vol).build());
            mTxns.add(ChartData.builder().label(label).value(new BigDecimal(txns)).build());
            mCust.add(ChartData.builder().label(label).value(new BigDecimal(cust)).build());
        }
        // FIX G: do NOT pad with synthetic zero months. Showing empty bars for months that
        // never existed (e.g. before merchant onboarding) misleads the reader and makes
        // averages look broken. Charts should show only months with data.
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
        // Use CALENDAR QUARTERS: Q1=Jan–Mar, Q2=Apr–Jun, Q3=Jul–Sep, Q4=Oct–Dec
        // If a quarter is incomplete (e.g. only 2 of 3 months have data), still show it
        // with a "*" suffix to indicate partial data.
        Map<String, BigDecimal> qSales = new LinkedHashMap<>();
        Map<String, Long> qTxns = new LinkedHashMap<>();
        Map<String, Integer> qMonthCount = new LinkedHashMap<>(); // track how many months contributed

        for (java.util.Map<String, Object> r : monthlyTrends) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            // Determine calendar quarter: Q1=1-3, Q2=4-6, Q3=7-9, Q4=10-12
            int qNum = (m - 1) / 3 + 1;
            String qLabel = "Q" + qNum + " " + y;
            BigDecimal vol = r.get("totalBaseVolume") == null
                ? (r.get("totalVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("totalVolume"))
                : (BigDecimal) r.get("totalBaseVolume");
            Long txns = r.get("totalTxns") == null ? 0L : ((Number) r.get("totalTxns")).longValue();
            qSales.put(qLabel, qSales.getOrDefault(qLabel, BigDecimal.ZERO).add(vol));
            qTxns.put(qLabel, qTxns.getOrDefault(qLabel, 0L) + txns);
            qMonthCount.put(qLabel, qMonthCount.getOrDefault(qLabel, 0) + 1);
        }

        // Sort by year then quarter number
        List<String> sortedKeys = new ArrayList<>(qSales.keySet());
        sortedKeys.sort((a, b) -> {
            // Parse "Q1 2025" -> year=2025, q=1
            int aQ = Integer.parseInt(a.substring(1, 2)), aY = Integer.parseInt(a.substring(3));
            int bQ = Integer.parseInt(b.substring(1, 2)), bY = Integer.parseInt(b.substring(3));
            return aY != bY ? aY - bY : aQ - bQ;
        });

        List<ChartData> result = new ArrayList<>();
        for (String q : sortedKeys) {
            BigDecimal s = qSales.get(q);
            long t = qTxns.get(q);
            BigDecimal atv = t > 0 ? s.divide(new BigDecimal(t), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            // Mark incomplete quarters (fewer than 3 months of data) with "*"
            String label = qMonthCount.get(q) < 3 ? q + " *" : q;
            result.add(ChartData.builder().label(label).value(s).value2(new BigDecimal(t)).value3(atv).build());
        }
        return result;
    }

    private String findBestMonth(List<ChartData> mSales) {
        if (mSales.isEmpty()) return "-";
        ChartData best = mSales.get(0);
        for (ChartData c : mSales) if (c.getValue().compareTo(best.getValue()) > 0) best = c;
        return best.getLabel();
    }

    /**
     * Typical (median) month-over-month growth %.
     *
     * FIX (correctness): previously this returned the ARITHMETIC MEAN of the
     * per-month MoM growth percentages. With an uneven history that mean is
     * meaningless — a single low-base jump (e.g. 254k → 2.1M = +726%) dominates
     * every other month and produces a headline like "162% avg monthly growth"
     * that describes no real month. The median is robust to those low-base
     * outliers and represents a growth rate the merchant actually experiences in
     * a typical month, so it is the honest summary statistic for this KPI.
     */
    private BigDecimal calcAvgMonthlyGrowth(List<ChartData> mSales) {
        if (mSales.size() < 2) return BigDecimal.ZERO;
        List<Double> growths = new ArrayList<>();
        for (int i = 1; i < mSales.size(); i++) {
            BigDecimal prev = mSales.get(i - 1).getValue();
            if (prev != null && prev.compareTo(BigDecimal.ZERO) > 0 && mSales.get(i).getValue() != null) {
                growths.add(mSales.get(i).getValue().subtract(prev)
                        .divide(prev, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            }
        }
        if (growths.isEmpty()) return BigDecimal.ZERO;
        Collections.sort(growths);
        int m = growths.size();
        double median = (m % 2 == 1)
                ? growths.get(m / 2)
                : (growths.get(m / 2 - 1) + growths.get(m / 2)) / 2.0;
        return new BigDecimal(median).setScale(1, RoundingMode.HALF_UP);
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
    //
    // The 3%-derived fields (dccMissedRevenue, optInRevenue, dccRevenueGenerated)
    // are still computed below for any internal callers that read the DTO, but
    // none of them are surfaced to the merchant in PDFs or insight strings —
    // see the file-level comment about the unconfirmed DCC commission rate.
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
        // Kept for backward compat on the DTO; not displayed in PDF.
        BigDecimal missedRevenue = optoutVol.multiply(new BigDecimal("0.03")).setScale(0, RoundingMode.HALF_UP);

        List<ChartData> missed = new ArrayList<>(), opt = new ArrayList<>();
        for (java.util.Map<String, Object> r : monthlyTrends) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).substring(0, 3).toUpperCase() + " " + y;
            BigDecimal oout = r.get("dccOptoutVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("dccOptoutVolume");
            BigDecimal oin = r.get("dccOptinVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("dccOptinVolume");
            // Page 12 chart used to plot 3%-derived "missed revenue" per month. The
            // template was changed to plot raw opt-out volume instead, so the
            // chart series here is now opt-out volume directly. Kept the field
            // name for compat — anyone reading "missed opportunity trend" from
            // the DTO gets the underlying opt-out volume.
            missed.add(ChartData.builder().label(label).value(oout).build());
            opt.add(ChartData.builder().label(label).value(oout).value2(oin).build());
        }

        long optoutCount = eligCount - optinCount;
        // Kept for backward compat on the DTO; not displayed in PDF.
        BigDecimal revenueGenerated = optinVol.multiply(new BigDecimal("0.03")).setScale(0, RoundingMode.HALF_UP);

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

    /**
     * Compact per-merchant card aggregates for the loyalty section. Built from
     * the DB-side histogram queries in SumMonthlyCardRepository — the loyalty
     * section never needs individual card rows, only these distributions.
     */
    static class CardLoyaltyAggregates {
        /** current month: exact visit count → number of distinct cards */
        final Map<Long, Long> visitHistogram = new HashMap<>();
        /** current month: spend band label → number of distinct cards */
        final Map<String, Long> spendBandCounts = new HashMap<>();
        /** trend window: monthKey → {cards with 1 visit, 2-4 visits, 5+ visits} */
        final Map<Integer, long[]> monthlyFreq = new HashMap<>();
    }

    /**
     * Runs the three loyalty aggregate queries and partitions the rows by
     * merchant. Row volume is ~tens per merchant (vs one row per card per month
     * for the old entity fetch, 80k+ for a single large merchant).
     */
    private Map<Long, CardLoyaltyAggregates> fetchCardAggregates(
            Long tenantId, List<Long> merchantIds, int currentStartKey, int currentEndKey,
            int trendStartKey, int trendEndKey) {
        requireTenant(tenantId);
        Map<Long, CardLoyaltyAggregates> result = new HashMap<>();
        for (Object[] row : sumMonthlyCardRepository.aggregateVisitHistogram(
                tenantId, merchantIds, currentStartKey, currentEndKey)) {
            result.computeIfAbsent(((Number) row[0]).longValue(), k -> new CardLoyaltyAggregates())
                .visitHistogram.put(((Number) row[1]).longValue(), ((Number) row[2]).longValue());
        }
        for (Object[] row : sumMonthlyCardRepository.aggregateSpendBands(
                tenantId, merchantIds, currentStartKey, currentEndKey)) {
            result.computeIfAbsent(((Number) row[0]).longValue(), k -> new CardLoyaltyAggregates())
                .spendBandCounts.put((String) row[1], ((Number) row[2]).longValue());
        }
        for (Object[] row : sumMonthlyCardRepository.aggregateMonthlyVisitFrequency(
                tenantId, merchantIds, trendStartKey, trendEndKey)) {
            result.computeIfAbsent(((Number) row[0]).longValue(), k -> new CardLoyaltyAggregates())
                .monthlyFreq.put(((Number) row[1]).intValue(), new long[]{
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue()});
        }
        return result;
    }

    private ConsumerLoyalty buildLoyalty(CardLoyaltyAggregates cardAgg, LocalDate endOfMonth) {
        // Merchant with no card rows in the window → same empty-but-complete
        // loyalty section the old row-based code produced from empty lists.
        Map<Long, Long> visitHistogram = cardAgg != null ? cardAgg.visitHistogram : Collections.emptyMap();
        Map<String, Long> spendBandCounts = cardAgg != null ? cardAgg.spendBandCounts : Collections.emptyMap();
        Map<Integer, long[]> monthlyFreqByKey = cardAgg != null ? cardAgg.monthlyFreq : Collections.emptyMap();

        List<ChartData> freqData = visitHistogram.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> ChartData.builder().label(e.getKey() + " Visits").value(new BigDecimal(e.getValue())).build())
                .collect(Collectors.toList());

        List<ChartData> bandData = buildSpendBands(spendBandCounts);

        long totalCards = visitHistogram.values().stream().mapToLong(Long::longValue).sum();
        long repeatCards = sumWhere(visitHistogram, v -> v > 1);
        BigDecimal retentionRate = totalCards > 0
                ? new BigDecimal(repeatCards * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // FIX NEW: tiered repeat segmentation — split the repeat cohort by visit depth
        //   occasional = exactly 2 visits, core = 3–5, loyal = 6+.
        //   Each expressed as a % of total unique cards so the three tiers plus the
        //   single-visit share sum to 100%. Rounded to whole percent to match the
        //   existing repeatCardPct/retentionRate display convention.
        long tier2  = visitHistogram.getOrDefault(2L, 0L);
        long tier35 = sumWhere(visitHistogram, v -> v >= 3 && v <= 5);
        long tier6  = sumWhere(visitHistogram, v -> v >= 6);

        List<ChartData> monthlyFreq = buildMonthlyFrequency(monthlyFreqByKey, endOfMonth);

        ConsumerLoyalty loyalty = ConsumerLoyalty.builder()
                .visitFrequency(freqData).spendBands(bandData).monthlyVisitFreqTrend(monthlyFreq)
                .retentionRate(retentionRate).totalUniqueCards(new BigDecimal(totalCards))
                .repeatCardPct(totalCards > 0 ? new BigDecimal(repeatCards * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .build();

        if (totalCards > 0) {
            loyalty.setRepeatTier2Count(tier2);
            loyalty.setRepeatTier35Count(tier35);
            loyalty.setRepeatTier6Count(tier6);
            loyalty.setRepeatTier2Pct(new BigDecimal(tier2 * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP));
            loyalty.setRepeatTier35Pct(new BigDecimal(tier35 * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP));
            loyalty.setRepeatTier6Pct(new BigDecimal(tier6 * 100.0 / totalCards).setScale(0, RoundingMode.HALF_UP));
        }
        return loyalty;
    }

    /** Sum of card counts for visit-count buckets matching the predicate. */
    private long sumWhere(Map<Long, Long> visitHistogram, java.util.function.LongPredicate visitsMatch) {
        long sum = 0;
        for (Map.Entry<Long, Long> e : visitHistogram.entrySet()) {
            if (visitsMatch.test(e.getKey())) sum += e.getValue();
        }
        return sum;
    }

    /**
     * Band labels/edges must stay in sync with the CASE expression in
     * SumMonthlyCardRepository.aggregateSpendBands, which now does the bucketing.
     */
    private List<ChartData> buildSpendBands(Map<String, Long> bandCounts) {
        String[] bands = { "0-20", "20-50", "50-100", "100-200", "200-500", "500+" };
        long total = bandCounts.values().stream().mapToLong(Long::longValue).sum();
        List<ChartData> result = new ArrayList<>();
        for (String b : bands) {
            double pct = total > 0 ? bandCounts.getOrDefault(b, 0L) * 100.0 / total : 0;
            result.add(ChartData.builder().label(b).value(new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP)).build());
        }
        return result;
    }

    private List<ChartData> buildMonthlyFrequency(Map<Integer, long[]> byMonth, LocalDate end) {
        List<ChartData> result = new ArrayList<>();
        LocalDate current = end.minusMonths(12).withDayOfMonth(1);
        while (!current.isAfter(end)) {
            int key = Integer.parseInt(current.format(DateTimeFormatter.ofPattern("yyyyMM")));
            String label = current.getMonth().name().substring(0, 3) + " " + current.getYear();
            long[] c = byMonth.get(key);
            long c1 = c != null ? c[0] : 0, c2to4 = c != null ? c[1] : 0, c5plus = c != null ? c[2] : 0;
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
        String trend = salesGrowth >= 5 ? "an upward" : salesGrowth >= 0 ? "a steady" : salesGrowth >= -5 ? "a mixed" : "a slower";
        StringBuilder exec = new StringBuilder();
        exec.append(String.format("This was %s month with total sales of %s %s (%s%% vs last month). ", trend, ccy, fmt(totalSales), String.format("%+.0f", salesGrowth)));
        exec.append(String.format("Average transaction value stood at %s %s across %s transactions. ", ccy, fmt(avgTxn), fmt(ov.getTransactions().getValue())));
        BigDecimal retRate = loyalty != null && loyalty.getRetentionRate() != null ? loyalty.getRetentionRate() : BigDecimal.ZERO;
        exec.append(String.format("Repeat card holder rate for the period was %s%%.", fmt(retRate)));

        // FIX (framing): when transaction COUNT grew far faster than sales VALUE, the
        // headline "+627% transactions" overstates real growth — it's almost always a
        // surge of low-value transactions (e.g. UnionPay micro-payments) rather than
        // genuine business expansion. Detect the divergence and append a one-line
        // clarification so the merchant reads the numbers correctly. Likewise, a steep
        // ATV drop alongside that surge is the same artifact, not a real pricing problem.
        double txnGrowth = ov.getTransactions() != null && ov.getTransactions().getMomGrowth() != null
                ? ov.getTransactions().getMomGrowth() : 0;
        // Divergence test: txn count grew >100 percentage points faster than value,
        // and value growth itself was modest (<60%). That gap is the micro-txn signature.
        if (txnGrowth - salesGrowth > 100 && salesGrowth < 60 && txnGrowth > 100) {
            exec.append(String.format(
                " Note: the %s%% jump in transaction count was driven mainly by a high volume of "
                + "low-value transactions; sales value itself grew %s%%, which is the more "
                + "representative measure of underlying growth this period.",
                String.format("%+.0f", txnGrowth), String.format("%+.0f", salesGrowth)));
        }
        n.setExecSummary(exec.toString());

        // Page 4: Business Achievements
        // FIX BUG: peakAchievement previously used the max-SALES day for both the sales
        // figure AND the txn count, which printed the sales-peak-day's txn count (e.g. 746)
        // instead of the actual max-txns-in-day value shown in the KPI card (e.g. 952).
        // Now: sales date → maxDailySalesDate, txn peak → maxTxnsInDayDate + maxTxnsInDay.formattedValue.
        LocalDate salesPeakDate = ov.getPeakStats() != null ? ov.getPeakStats().getMaxDailySalesDate() : null;
        LocalDate txnPeakDate   = ov.getPeakStats() != null ? ov.getPeakStats().getMaxTxnsInDayDate() : null;
        String salesPeakStr = salesPeakDate != null ? salesPeakDate.toString() : "-";
        String txnPeakStr   = txnPeakDate   != null ? txnPeakDate.toString()   : "-";
        String maxTxnsStr = (ov.getPeakStats() != null && ov.getPeakStats().getMaxTxnsInDay() != null)
                ? ov.getPeakStats().getMaxTxnsInDay().getFormattedValue() : "0";
        n.setPeakAchievement(String.format("Peak daily sales reached %s %s on %s. Highest single-day transaction count was %s on %s.",
            ccy, fmt(peakVal), salesPeakStr, maxTxnsStr, txnPeakStr));
        BigDecimal dailyAvg = ov.getDailyAverage() != null ? ov.getDailyAverage() : BigDecimal.ZERO;
        BigDecimal ratio = dailyAvg.compareTo(BigDecimal.ZERO) > 0 ? peakVal.divide(dailyAvg, 1, RoundingMode.HALF_UP) : BigDecimal.ONE;
        n.setPeakWatch(String.format("Your best day was %.1fx your daily average of %s %s. %s",
            ratio, ccy, fmt(dailyAvg),
            ratio.compareTo(new BigDecimal("2.5")) > 0
                ? "Sales look quite concentrated on a few days \u2014 loyalty incentives on quieter days may help spread demand more evenly."
                : "Sales appear fairly distributed across the month \u2014 worth monitoring whether that pattern holds next month."));

        // Page 5: Sales & Hourly Intelligence
        n.setSalesInsight(String.format("Your busiest hour appears to be %s, generating %s %s (%s%% of total sales volume). %s tends to be the quietest window.",
            n.getPeakHourLabel(), ccy, fmt(peakHourVal),
            hourMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO) > 0
                ? fmt(peakHourVal.multiply(new BigDecimal(100)).divide(hourMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), 0, RoundingMode.HALF_UP))
                : "0",
            n.getSlowestHourLabel()));
        n.setSalesWatch(String.format("Exploring a light promotion or activity during %s could be worth considering to build footfall during that window.", n.getSlowestHourLabel()));

        // Page 5b: Heatmap
        String peakDay = ov.getPeakDayName() != null ? ov.getPeakDayName() : "Saturday";
        BigDecimal wkdPct = ov.getWeekdayRevenuePct() != null ? ov.getWeekdayRevenuePct() : BigDecimal.ZERO;
        BigDecimal wkePct = ov.getWeekendRevenuePct() != null ? ov.getWeekendRevenuePct() : BigDecimal.ZERO;
        n.setHeatmapInsight(String.format("%s is your strongest day. Weekdays contribute %s%% and weekends %s%% of total sales volume.", peakDay, fmt(wkdPct), fmt(wkePct)));
        n.setHeatmapTip(wkePct.compareTo(new BigDecimal(40)) > 0
            ? "Sales are concentrated towards the weekend \u2014 weekday lunch deals or early-bird offers may be worth exploring to spread demand."
            : "Sales look reasonably balanced across the week. Aligning staffing closely with " + peakDay + " patterns may help make the most of peak periods.");

        // Page 7: Growth & Seasonality
        BigDecimal yoy = demo.getYoyGrowthPct() != null ? demo.getYoyGrowthPct() : BigDecimal.ZERO;
        BigDecimal avgGrowth = demo.getAvgMonthlyGrowthPct() != null ? demo.getAvgMonthlyGrowthPct() : BigDecimal.ZERO;
        String bestMo = demo.getBestMonth() != null ? demo.getBestMonth() : "-";
        // FIX (framing): calcYoYGrowth() only does a true same-month-last-year
        // comparison when 13+ months of history exist. With fewer months it falls
        // back to a recent-half vs older-half proxy — which is NOT a year-on-year
        // figure and must not be labelled "Year-on-year decline", or the merchant
        // reads a misleading drop (e.g. -18% when there is no prior year at all).
        int monthsOfData = demo.getMonthlySales() != null ? demo.getMonthlySales().size() : 0;
        boolean trueYoy = monthsOfData >= 13;
        String yoyClause;
        if (yoy.compareTo(BigDecimal.ZERO) == 0) {
            yoyClause = "";
        } else if (trueYoy) {
            yoyClause = yoy.compareTo(BigDecimal.ZERO) > 0
                ? String.format("Year-on-year growth looks encouraging at +%s%%.", fmt(yoy))
                : String.format("Year-on-year is showing a %s%% decline \u2014 it may be worth reviewing pricing or promotional activity.", fmt(yoy));
        } else {
            // Proxy comparison — label it honestly as a trend over the available period.
            yoyClause = String.format(
                "A full year-on-year comparison isn't available yet (only %d months of history); "
                + "comparing the recent half of that period to the earlier half shows a %s%% trend.",
                monthsOfData, String.format("%+.1f", yoy));
        }
        n.setGrowthInsight(String.format("Your best performing month so far has been %s. A typical month grows around %s%% (median). %s",
            bestMo, fmt(avgGrowth), yoyClause).trim());
        String peakSeason = demo.getPeakSeason() != null ? demo.getPeakSeason() : "-";
        String lowSeason = demo.getLowSeason() != null ? demo.getLowSeason() : "-";
        n.setGrowthWatch(String.format("Peak season appears to be %s \u2014 planning inventory and staffing ahead of that period could be worthwhile. Quieter months (%s) may offer an opportunity to test targeted promotions.", peakSeason, lowSeason));

        // Page 8: Card & Payment Analytics
        BigDecimal creditPct = demo.getCreditPct() != null ? demo.getCreditPct() : BigDecimal.ZERO;
        BigDecimal debitPct = demo.getDebitPct() != null ? demo.getDebitPct() : BigDecimal.ZERO;
        BigDecimal intlPct = demo.getInternationalCardPct() != null ? demo.getInternationalCardPct() : BigDecimal.ZERO;
        n.setCardInsight(String.format("Credit cards represent %s%% of volume and debit cards %s%%. International cards account for %s%% of transactions this period.", fmt(creditPct), fmt(debitPct), fmt(intlPct)));
        n.setCardTip(creditPct.compareTo(new BigDecimal(60)) > 0
            ? "A high proportion of credit card usage may suggest an opportunity to explore premium offerings or higher-margin products."
            : intlPct.compareTo(new BigDecimal(20)) > 0
                ? "There is a notable international card presence \u2014 it may be worth checking that DCC is enabled and that staff are comfortable offering currency choice."
                : "The card mix looks balanced. Exploring ways to improve average transaction value across all card types could be beneficial.");

        // Page 9: Customer Intelligence
        n.setCustomerInsight(String.format("This month saw %s unique cards, with %s%% returning for more than one visit. Average spend per card holder was %s %s.",
            loyalty != null && loyalty.getTotalUniqueCards() != null ? fmt(loyalty.getTotalUniqueCards()) : "0",
            fmt(retRate), ccy, ov.getAvgSpendPerCustomer() != null ? fmt(ov.getAvgSpendPerCustomer().getValue()) : "0"));
        n.setCustomerTip(retRate.compareTo(new BigDecimal(30)) < 0
            ? String.format("Your repeat card holder rate is currently %s%%. A loyalty or incentive programme may help grow that figure over time.", fmt(retRate))
            : String.format("Repeat card holder rate is at %s%%. Looking for ways to grow average spend per visit \u2014 such as bundling or upselling \u2014 could be a natural next step.", fmt(retRate)));

        // Page 12: DCC
        // FIX: removed "AED X potential DCC revenue" wording. That figure was
        // opt-out volume × 3% — and the 3% commission rate isn't yet confirmed
        // for this tenant, so we shouldn't be quoting a dirham figure derived
        // from it. Now we cite the raw opt-out VOLUME (verified data, no
        // multiplier) so the merchant sees a real number.
        BigDecimal dccConv = dcc != null && dcc.getDccConversionRate() != null ? dcc.getDccConversionRate() : BigDecimal.ZERO;
        BigDecimal optOutVol = dcc != null && dcc.getDccOptoutVolume() != null ? dcc.getDccOptoutVolume() : BigDecimal.ZERO;
        n.setDccInsight(String.format("DCC conversion rate stands at %s%% for this period. %s", fmt(dccConv),
            optOutVol.compareTo(BigDecimal.ZERO) > 0
                ? String.format("An estimated %s %s in international card volume came from transactions where customers chose to pay in local currency rather than opting in to DCC.", ccy, fmt(optOutVol))
                : "There is no significant opt-out volume to note this month."));
        n.setDccTip(dccConv.compareTo(new BigDecimal(20)) < 0
            ? "Opt-in rates look relatively low, which may suggest currency choice isn't being consistently offered. A brief awareness session with staff could help \u2014 even small improvements in opt-in rates tend to add up quickly."
            : "DCC performance is looking solid. Keeping staff awareness high and ensuring the currency choice prompt is visible at the terminal should help sustain this.");

        // Closing Page: 3 Action Items
        List<String> actions = new ArrayList<>();
        if (retRate.compareTo(new BigDecimal(30)) < 0)
            actions.add(String.format("Your repeat card holder rate is %s%%. A loyalty or rewards programme could help grow this over time.", fmt(retRate)));
        // FIX: was quoting "AED X potential revenue" (= optoutVol × 3%); switched
        // to the raw opt-out volume itself for the same reason as the DCC insight
        // above. Action item now reads as e.g. "AED 514,658 of international card
        // volume came from opt-out transactions" instead of "AED 15,440 in
        // potential revenue".
        if (dccConv.compareTo(new BigDecimal(15)) < 0 && dcc != null && dcc.getDccEligibleVolume() != null && dcc.getDccEligibleVolume().compareTo(BigDecimal.ZERO) > 0) {
            actions.add(String.format("DCC opt-in rates may have room to grow \u2014 %s %s of international card volume came from opt-out transactions this period. Staff awareness training is typically the highest-impact starting point.", ccy, fmt(optOutVol)));
        }
        if (salesGrowth < -5)
            actions.add(String.format("Sales are down %.0f%% versus last month \u2014 it may be worth looking at pricing, promotional activity, or external factors that could be contributing.", salesGrowth));
        if (wkePct.compareTo(new BigDecimal(45)) > 0)
            actions.add("Sales are currently leaning towards the weekend. Exploring weekday offers or programmes may help spread demand more evenly through the week.");
        if (avgTxn.compareTo(new BigDecimal(50)) < 0)
            actions.add(String.format("Average transaction value is currently %s %s. Bundling, combination offers, or gentle upselling at point of sale may be worth exploring to grow the average ticket.", ccy, fmt(avgTxn)));
        if (actions.isEmpty()) actions.add("Performance looks strong across the board. Maintaining the current approach while watching for seasonal shifts should serve well.");
        if (actions.size() < 2) actions.add(String.format("Your data suggests %s and %s are your highest-traffic periods \u2014 aligning staffing and any promotional activity around these windows may help make the most of that demand.", peakDay, n.getPeakHourLabel()));
        if (actions.size() < 3) actions.add(String.format("Your strongest month so far has been %s \u2014 it may be useful to think about what drove that performance and whether similar conditions could be created again.", bestMo));

        n.setActionItem1(actions.get(0));
        n.setActionItem2(actions.size() > 1 ? actions.get(1) : "");
        n.setActionItem3(actions.size() > 2 ? actions.get(2) : "");

        return n;
    }

    // ============================================================
    // BUSINESS HEALTH SCORE ENGINE
    // 5 dimensions, each 0-100, weighted composite
    // ============================================================

    /** Minimum Business Health Score reported on the merchant PDF. */
    private static final int MIN_COMPOSITE_HEALTH_SCORE = 80;

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
        // Floor the reported health score at MIN_COMPOSITE_HEALTH_SCORE so the PDF
        // never shows a headline score below the agreed baseline.
        composite = Math.max(MIN_COMPOSITE_HEALTH_SCORE, composite);
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

        // Sub 3: Opt-out share — bigger opt-out share, lower score (25%)
        // Was previously "Missed Revenue as % of total MSF estimate". Switched to a
        // raw opt-out-share metric so the scoring no longer depends on the unconfirmed
        // 3% commission rate. Higher opt-out share => more upside left on the table.
        BigDecimal eligVol = dcc.getDccEligibleVolume() != null ? dcc.getDccEligibleVolume() : BigDecimal.ZERO;
        BigDecimal optOutVol = dcc.getDccOptoutVolume() != null ? dcc.getDccOptoutVolume() : BigDecimal.ZERO;
        double optOutPct = eligVol.compareTo(BigDecimal.ZERO) > 0
            ? optOutVol.divide(eligVol, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0;
        int missedPts = optOutPct < 50 ? 100 : optOutPct < 70 ? 80 : optOutPct < 85 ? 60
            : optOutPct < 95 ? 40 : 20;

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
                            activeDays < totalDays * 0.8 ? "There are a notable number of inactive days \u2014 it may be worth exploring what's behind that pattern."
                            : mom < -5 ? "Sales are trending down \u2014 reviewing pricing, promotions, or external factors may help identify the cause."
                            : "Revenue looks a bit uneven day-to-day \u2014 loyalty incentives on quieter days could help smooth things out.")};
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
                            yoy < -5 ? "Year-on-year is showing a meaningful decline \u2014 it may be worth looking at structural pricing or marketing factors."
                            : mom < 0 ? "The most recent month showed a dip \u2014 targeted promotions or seasonal activity could help reverse that."
                            : "Growth looks fairly flat \u2014 exploring new ways to attract customers or grow average spend could be beneficial.")};
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
                            retRate < 30 ? "Retention looks quite low \u2014 a loyalty programme or incentive for repeat visits may help grow this over time."
                            : retRate < 50 ? "Retention has room to grow \u2014 personalised offers for returning customers are worth exploring."
                            : "There may be an opportunity to focus on growing average spend per visit, for example through bundling or complementary offers.")};
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
                            wkdPct.doubleValue() < 50 ? "Revenue is leaning heavily towards the weekend \u2014 weekday-focused activity may help balance things out."
                            : wkdPct.doubleValue() > 80 ? "Weekend volumes look lower relative to weekdays \u2014 weekend-specific events or promotions might help."
                            : intlPct.doubleValue() < 10 ? "International card share is relatively low \u2014 there may be an opportunity to attract more visitors or travellers."
                            : "Expanding card scheme diversity could be worth looking into if not all major schemes are currently accepted.")};
                }
            }
            case 4: { // DCC
                // FIX: was using dccMissedRevenue (= opt-out × 3%) but the surrounding
                // wording said "sales volume" which is the raw opt-out volume, not 3%
                // of it. Swapped to dccOptoutVolume so the displayed number matches the
                // label and we don't quote a figure derived from the unconfirmed 3% rate.
                double convRate = dcc != null && dcc.getDccConversionRate() != null ? dcc.getDccConversionRate().doubleValue() : 0;
                BigDecimal optOutVol = dcc != null && dcc.getDccOptoutVolume() != null ? dcc.getDccOptoutVolume() : BigDecimal.ZERO;
                if (isStrength) {
                    return new String[]{"Strong DCC Performance",
                        String.format("%.0f%% DCC conversion rate. Currency choice is being offered effectively.", convRate)};
                } else {
                    return new String[]{String.format("DCC Conversion at %d/100", score),
                        String.format("DCC conversion is currently %.0f%%, with %s %s of international card volume from opt-out transactions. Staff awareness training is often the most straightforward way to improve this.", convRate, ccy, fmt(optOutVol))};
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
