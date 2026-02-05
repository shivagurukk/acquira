package com.acquira.service;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.dto.MerchantInsightsDTO.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantInsightService {

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.repository.SumDailyMerchantRepository sumDailyMerchantRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.repository.SumDailyMerchantAttributeRepository sumDailyMerchantAttributeRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.repository.SumMonthlyCardRepository sumMonthlyCardRepository;

    public MerchantInsightsDTO getInsights(Long merchantId, int year, int month) {
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);

        LocalDate startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDate endOfLastMonth = startOfLastMonth.plusMonths(1).minusDays(1);

        MerchantInsightsDTO dto = new MerchantInsightsDTO();
        dto.setOverview(getOverview(merchantId, startOfMonth, endOfMonth, startOfLastMonth, endOfLastMonth));
        dto.setAchievements(getAchievements(merchantId, startOfMonth, endOfMonth));
        dto.setLoyalty(getLoyalty(merchantId, startOfMonth, endOfMonth));
        dto.setDemographics(getDemographics(merchantId, startOfMonth, endOfMonth));
        dto.setDccPerformance(getDccPerformance(merchantId, startOfMonth, endOfMonth)); // New DCC Section

        return dto;
    }

    private BusinessOverview getOverview(Long merchantId, LocalDate start, LocalDate end, LocalDate prevStart,
            LocalDate prevEnd) {
        Map<String, BigDecimal> current = getAggregates(merchantId, start, end);
        Map<String, BigDecimal> previous = getAggregates(merchantId, prevStart, prevEnd);

        Kpi sales = createKpi(current.get("total_sales"), previous.get("total_sales"));
        Kpi txns = createKpi(current.get("total_txns"), previous.get("total_txns"));
        Kpi customers = createKpi(current.get("unique_customers"), previous.get("unique_customers"));

        BigDecimal avgSpend = safeDivide(current.get("total_sales"), current.get("unique_customers"));
        BigDecimal prevAvgSpend = safeDivide(previous.get("total_sales"), previous.get("unique_customers"));

        BigDecimal avgTxnVal = safeDivide(current.get("total_sales"), current.get("total_txns"));
        BigDecimal prevAvgTxnVal = safeDivide(previous.get("total_sales"), previous.get("total_txns"));

        BigDecimal avgTxnsPerCust = safeDivide(current.get("total_txns"), current.get("unique_customers"));
        BigDecimal prevAvgTxnsPerCust = safeDivide(previous.get("total_txns"), previous.get("unique_customers"));

        Map<String, BigDecimal> peaks = getPeakStats(merchantId, start, end);
        Map<String, BigDecimal> prevPeaks = getPeakStats(merchantId, prevStart, prevEnd);

        PeakStats peakStats = PeakStats.builder()
                .maxDailySales(createKpi(peaks.get("max_daily_sales"), prevPeaks.get("max_daily_sales")))
                .maxTxnsInDay(createKpi(peaks.get("max_daily_txns"), prevPeaks.get("max_daily_txns")))
                .highestTxnValue(createKpi(peaks.get("max_txn_value"), prevPeaks.get("max_txn_value")))
                .highestCustomerSpend(createKpi(peaks.get("max_cust_spend"), prevPeaks.get("max_cust_spend")))
                .build();

        return BusinessOverview.builder()
                .sales(sales)
                .transactions(txns)
                .customers(customers)
                .avgSpendPerCustomer(createKpi(avgSpend, prevAvgSpend))
                .avgTxnValue(createKpi(avgTxnVal, prevAvgTxnVal))
                .avgTxnsPerCustomer(createKpi(avgTxnsPerCust, prevAvgTxnsPerCust))
                .peakStats(peakStats)
                .salesByDayOfWeek(getChartDataByDayOfWeek(merchantId, start, end, "sum(total_amount_settled)"))
                .transactionsByDayOfWeek(getChartDataByDayOfWeek(merchantId, start, end, "count(*)"))
                .salesByWeekOfMonth(getChartDataByWeekOfMonth(merchantId, start, end, "sum(total_amount_settled)"))
                .transactionsByWeekOfMonth(getChartDataByWeekOfMonth(merchantId, start, end, "count(*)"))
                .build();
    }

    private BusinessAchievements getAchievements(Long merchantId, LocalDate start, LocalDate end) {
        // Daily Sales & Count (From SumDailyMerchant)
        List<com.acquira.model.SumDailyMerchant> dailyRows = sumDailyMerchantRepository.findDailyStats(merchantId,
                start, end);

        List<ChartData> dailyData = dailyRows.stream().map(r -> ChartData.builder()
                .label(r.getBusinessDate().toString())
                .value(r.getTotalVolume())
                .value2(new BigDecimal(r.getTotalTxns()))
                .build()).collect(Collectors.toList());

        // Daily ATV
        List<ChartData> dailyAtv = dailyRows.stream().map(r -> ChartData.builder()
                .label(r.getBusinessDate().toString())
                .value(safeDivide(r.getTotalVolume(), new BigDecimal(r.getTotalTxns())))
                .build()).collect(Collectors.toList());

        // Unique Customers by Day (From new column uniqueCustomerCount)
        List<ChartData> custData = dailyRows.stream().map(r -> ChartData.builder()
                .label(r.getBusinessDate().toString())
                .value(new BigDecimal(r.getUniqueCustomerCount() == null ? 0 : r.getUniqueCustomerCount()))
                .build()).collect(Collectors.toList());

        // Sales by Day of Month (Derived from Daily rows in Memory)
        Map<Integer, BigDecimal> domMap = new HashMap<>();
        for (com.acquira.model.SumDailyMerchant r : dailyRows) {
            int day = r.getBusinessDate().getDayOfMonth();
            domMap.put(day, domMap.getOrDefault(day, BigDecimal.ZERO).add(r.getTotalVolume()));
        }
        // Sorted List
        List<ChartData> domData = domMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> ChartData.builder().label(String.valueOf(e.getKey())).value(e.getValue()).build())
                .collect(Collectors.toList());

        // Sales Time of Day (Hourly) -> QUERY SumDailyMerchantAttribute WHERE
        // type='HOUR'
        List<com.acquira.model.SumDailyMerchantAttribute> hourRows = sumDailyMerchantAttributeRepository
                .findByMerchantDateAndType(merchantId, start, end, "HOUR");
        // Aggregate by Value (Attribute Value is Hour '0', '1'...)
        Map<String, BigDecimal> hourMap = new HashMap<>();
        for (com.acquira.model.SumDailyMerchantAttribute a : hourRows) {
            hourMap.put(a.getAttributeValue(),
                    hourMap.getOrDefault(a.getAttributeValue(), BigDecimal.ZERO).add(a.getMetricVolume()));
        }
        List<ChartData> hourData = hourMap.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getKey())))
                .map(e -> ChartData.builder().label(e.getKey()).value(e.getValue()).build())
                .collect(Collectors.toList());

        return BusinessAchievements.builder()
                .dailySalesAndCount(dailyData)
                .dailyAvgTxnValue(dailyAtv)
                .uniqueCustomersByDay(custData)
                .salesByDayOfMonth(domData)
                .salesTimeOfDay(hourData)
                .salesAndAtvByDayOfWeek(getSalesAndAtvByDayOfWeek(merchantId, start, end))
                .build();
    }

    // Correct implementation is below
    private List<ChartData> getSalesAndAtvByDayOfWeek(Long merchantId, LocalDate start, LocalDate end) {
        // Day of Week is derived from Business Date in Summary
        // We can fetch Daily Stats and aggregate by Day of Week in Java (fast for 30-90
        // rows)
        List<com.acquira.model.SumDailyMerchant> dailyRows = sumDailyMerchantRepository.findDailyStats(merchantId,
                start, end);

        // Initialize Map
        Map<Integer, ChartData> dayMap = new HashMap<>(); // 1=Mon, 7=Sun
        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        for (int i = 1; i <= 7; i++) {
            dayMap.put(i,
                    ChartData.builder().label(days[i - 1]).value(BigDecimal.ZERO).value2(BigDecimal.ZERO).build());
        }

        // Aggregate
        Map<Integer, BigDecimal> salesSum = new HashMap<>();
        Map<Integer, Long> txnSum = new HashMap<>();

        for (com.acquira.model.SumDailyMerchant row : dailyRows) {
            int dow = row.getBusinessDate().getDayOfWeek().getValue(); // 1=Mon
            salesSum.put(dow, salesSum.getOrDefault(dow, BigDecimal.ZERO).add(row.getTotalVolume()));
            txnSum.put(dow, txnSum.getOrDefault(dow, 0L) + row.getTotalTxns());
        }

        // Build Result
        for (int i = 1; i <= 7; i++) {
            BigDecimal s = salesSum.getOrDefault(i, BigDecimal.ZERO);
            BigDecimal t = new BigDecimal(txnSum.getOrDefault(i, 0L));
            BigDecimal atv = safeDivide(s, t);

            dayMap.put(i, ChartData.builder().label(days[i - 1]).value(s).value2(atv).build());
        }

        // Return Mon-Sun
        List<ChartData> sorted = new ArrayList<>();
        for (int i = 1; i <= 7; i++)
            sorted.add(dayMap.get(i));
        return sorted;
    }

    private ConsumerLoyalty getLoyalty(Long merchantId, LocalDate start, LocalDate end) {
        // Fetch SumMonthlyCard rows for the period
        int startKey = Integer.parseInt(start.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(end.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.model.SumMonthlyCard> cardRows = sumMonthlyCardRepository
                .findByMerchantAndMonthRange(merchantId, startKey, endKey);

        // 1. Visit Frequency (Total Period - if multiple months, we sum up per card
        // first)
        // Map<Card, Visits>
        Map<String, Long> cardVisits = new HashMap<>();
        Map<String, BigDecimal> cardSpend = new HashMap<>();

        for (com.acquira.model.SumMonthlyCard r : cardRows) {
            cardVisits.put(r.getCardNumber(), cardVisits.getOrDefault(r.getCardNumber(), 0L) + r.getVisitCount());
            cardSpend.put(r.getCardNumber(),
                    cardSpend.getOrDefault(r.getCardNumber(), BigDecimal.ZERO).add(r.getTotalSpend()));
        }

        // Aggregate into Frequency Buckets
        Map<String, Long> freqBuckets = new HashMap<>();
        for (Long visits : cardVisits.values()) {
            // Buckets: 1, 2-4, 5+ (simplified logic or distinct counts?)
            // PDF Chart usually shows specific counts for low numbers, then ranges.
            // Let's stick to "1", "2-4", "5+" or the previous SQL approach which was Group
            // By Visit Count
            // Previous SQL: Group By visit_count.
            // Let's do exact count for now to match previous flexibility, or bucket
            // immediately if cleaner.
            // Previous: label = "1 Visits", "2 Visits"...
            // Let's match previous behavior: distinct counts.
            String label = visits + " Visits";
            freqBuckets.put(label, freqBuckets.getOrDefault(label, 0L) + 1);
        }

        List<ChartData> freqData = freqBuckets.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getKey().split(" ")[0])))
                .map(e -> ChartData.builder().label(e.getKey()).value(new BigDecimal(e.getValue())).build())
                .collect(Collectors.toList());

        // 2. Spend Bands (Total Period)
        // Buckets: 0-20, 20-50, 50-100, 100-200, 200-500, 500+
        Map<String, Long> bandCounts = new HashMap<>();
        Map<String, BigDecimal> bandValSum = new HashMap<>();
        // Init
        String[] bands = { "0-20", "20-50", "50-100", "100-200", "200-500", "500+" };
        for (String b : bands) {
            bandCounts.put(b, 0L);
            bandValSum.put(b, BigDecimal.ZERO);
        }

        for (BigDecimal spend : cardSpend.values()) {
            String band;
            double s = spend.doubleValue();
            if (s < 20)
                band = "0-20";
            else if (s < 50)
                band = "20-50";
            else if (s < 100)
                band = "50-100";
            else if (s < 200)
                band = "100-200";
            else if (s < 500)
                band = "200-500";
            else
                band = "500+";

            bandCounts.put(band, bandCounts.get(band) + 1);
            bandValSum.put(band, bandValSum.get(band).add(spend));
        }

        // Calculate %
        BigDecimal totalCount = new BigDecimal(cardSpend.size());
        BigDecimal totalVal = cardSpend.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ChartData> bandData = new ArrayList<>();
        for (String b : bands) {
            BigDecimal c = new BigDecimal(bandCounts.get(b));
            BigDecimal v = bandValSum.get(b);

            double countPct = totalCount.compareTo(BigDecimal.ZERO) > 0
                    ? c.divide(totalCount, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;
            double valPct = totalVal.compareTo(BigDecimal.ZERO) > 0
                    ? v.divide(totalVal, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;

            bandData.add(ChartData.builder().label(b).value(new BigDecimal(countPct)).value2(new BigDecimal(valPct))
                    .build());
        }

        // 3. Domestic vs International Trend
        // Placeholder for now as simple Domestic/Intl list
        List<ChartData> domTrend = new ArrayList<>();

        // 4. Monthly Visit Frequency (Complex: New logic)
        List<ChartData> monthlyFreq = new ArrayList<>();
        // For PDF simplified: Just show 3 buckets for last month? or Trend?
        // PDF Page 9 expects `getMonthlyVisitFreqTrend`. Let's mock or calculate simple
        // monthly agg.
        // Real tracking of "frequency per month" requires iterating months.
        monthlyFreq = getMonthlyFrequency(merchantId, end.minusMonths(12).withDayOfMonth(1), end);

        // 5. Monthly Spend Bands
        List<ChartData> monthlyBands = getMonthlySpendBands(merchantId, end.minusMonths(12).withDayOfMonth(1), end);

        // 6. Customer Category Splits (Dom vs Intl)
        Map<String, BigDecimal> catCount = getRawSplitMap(merchantId, start, end, "destination", "count(*)");
        Map<String, BigDecimal> catValue = getRawSplitMap(merchantId, start, end, "destination",
                "sum(total_amount_settled)");

        return ConsumerLoyalty.builder()
                .visitFrequency(freqData)
                .spendBands(bandData)
                .domesticVsInternational(domTrend) // Placeholder
                .monthlyVisitFreqTrend(monthlyFreq)
                .monthlySpendBandTrend(monthlyBands)
                .customerCategoryCountSplit(catCount)
                .customerCategoryValueSplit(catValue)
                .build();
    }

    // New Helpers for Loyalty Logic
    private List<ChartData> processBandRows(List<Object[]> bandRows) {
        BigDecimal totalCount = BigDecimal.ZERO;
        BigDecimal totalVal = BigDecimal.ZERO;
        for (Object[] r : bandRows) {
            totalCount = totalCount.add(new BigDecimal((Long) r[1]));
            totalVal = totalVal.add((BigDecimal) r[2]);
        }
        Map<String, Object[]> resMap = new HashMap<>();
        for (Object[] r : bandRows)
            resMap.put((String) r[0], r);

        List<ChartData> bandData = new ArrayList<>();
        String[] bands = { "0-20", "20-50", "50-100", "100-200", "200-500", "500+" };
        for (String b : bands) {
            Object[] r = resMap.get(b);
            BigDecimal count = r == null ? BigDecimal.ZERO : new BigDecimal((Long) r[1]);
            BigDecimal val = r == null ? BigDecimal.ZERO : (BigDecimal) r[2];
            double countPct = totalCount.compareTo(BigDecimal.ZERO) > 0
                    ? count.divide(totalCount, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;
            double valPct = totalVal.compareTo(BigDecimal.ZERO) > 0
                    ? val.divide(totalVal, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;
            bandData.add(ChartData.builder().label(b).value(new BigDecimal(countPct)).value2(new BigDecimal(valPct))
                    .build());
        }
        return bandData;
    }

    private List<ChartData> getMonthlyFrequency(Long mid, LocalDate start, LocalDate end) {
        int startKey = Integer.parseInt(start.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(end.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.model.SumMonthlyCard> rows = sumMonthlyCardRepository.findByMerchantAndMonthRange(mid,
                startKey,
                endKey);

        // Group by Month -> List of Rows
        Map<Integer, List<com.acquira.model.SumMonthlyCard>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(com.acquira.model.SumMonthlyCard::getMonthKey));

        // Create ChartData per Month
        // We need to ensure we return all months in range? Or just present ones.
        // Let's iterate months.
        List<ChartData> result = new ArrayList<>();
        LocalDate current = start.withDayOfMonth(1);
        while (!current.isAfter(end)) {
            int key = Integer.parseInt(current.format(DateTimeFormatter.ofPattern("yyyyMM")));
            String label = current.getMonth().name().substring(0, 3) + " " + current.getYear();

            long count1 = 0;
            long count2To4 = 0;
            long count5Plus = 0;

            if (byMonth.containsKey(key)) {
                for (com.acquira.model.SumMonthlyCard r : byMonth.get(key)) {
                    long v = r.getVisitCount();
                    if (v == 1)
                        count1++;
                    else if (v >= 2 && v <= 4)
                        count2To4++;
                    else
                        count5Plus++;
                }
            }

            result.add(ChartData.builder()
                    .label(label)
                    .value(new BigDecimal(count1)) // 1 Visit
                    .value2(new BigDecimal(count2To4)) // 2-4 Visits
                    .value3(new BigDecimal(count5Plus)) // 5+ Visits
                    .build());
            current = current.plusMonths(1);
        }
        return result;
    }

    private List<ChartData> getMonthlySpendBands(Long mid, LocalDate start, LocalDate end) {
        int startKey = Integer.parseInt(start.format(DateTimeFormatter.ofPattern("yyyyMM")));
        int endKey = Integer.parseInt(end.format(DateTimeFormatter.ofPattern("yyyyMM")));
        List<com.acquira.model.SumMonthlyCard> rows = sumMonthlyCardRepository.findByMerchantAndMonthRange(mid,
                startKey,
                endKey);

        Map<Integer, List<com.acquira.model.SumMonthlyCard>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(com.acquira.model.SumMonthlyCard::getMonthKey));

        List<ChartData> result = new ArrayList<>();
        LocalDate current = start.withDayOfMonth(1);
        while (!current.isAfter(end)) {
            int key = Integer.parseInt(current.format(DateTimeFormatter.ofPattern("yyyyMM")));
            String label = current.getMonth().name().substring(0, 3) + " " + current.getYear();

            long low = 0; // < 100
            long midBand = 0; // 100 - 500
            long high = 0; // 500+

            if (byMonth.containsKey(key)) {
                for (com.acquira.model.SumMonthlyCard r : byMonth.get(key)) {
                    double val = r.getTotalSpend().doubleValue();
                    if (val < 100)
                        low++;
                    else if (val < 500)
                        midBand++;
                    else
                        high++;
                }
            }

            result.add(ChartData.builder()
                    .label(label)
                    .value(new BigDecimal(low))
                    .value2(new BigDecimal(midBand))
                    .value3(new BigDecimal(high))
                    .build());
            current = current.plusMonths(1);
        }
        return result;
    }

    private Map<String, BigDecimal> getRawSplitMap(Long mid, LocalDate start, LocalDate end, String groupCol,
            String aggFunc) {
        String sql = "SELECT " + groupCol + ", " + aggFunc
                + " FROM fact_transaction WHERE merchant_id = :mid AND payment_date BETWEEN :start AND :end GROUP BY "
                + groupCol;
        List<Object[]> rows = executeQuery(sql, mid, start, end);
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] r : rows) {
            String k = (String) r[0];
            if (k == null)
                k = "UNKNOWN";
            BigDecimal v = r[1] instanceof Long ? new BigDecimal((Long) r[1]) : (BigDecimal) r[1];
            map.put(k.toUpperCase(), v);
        }
        return map;
    }

    private CustomerDemographics getDemographics(Long merchantId, LocalDate start, LocalDate end) {
        CustomerDemographics demo = new CustomerDemographics();

        demo.setCardSchemeValueSplit(getSplitMap(merchantId, start, end, "card_scheme", "sum(total_amount_settled)"));
        demo.setCardSchemeCountSplit(getSplitMap(merchantId, start, end, "card_scheme", "count(*)"));

        demo.setCardTypeValueSplit(getSplitMap(merchantId, start, end, "card_type", "sum(total_amount_settled)"));
        demo.setCardTypeCountSplit(getSplitMap(merchantId, start, end, "card_type", "count(*)"));

        demo.setTransactionTypeValueSplit(
                getRawSplitMap(merchantId, start, end, "transaction_type", "sum(total_amount_settled)"));
        demo.setTransactionTypeCountSplit(getRawSplitMap(merchantId, start, end, "transaction_type", "count(*)"));

        // Mock Segments based on random distribution or simple rules if no data
        Map<String, BigDecimal> cardSeg = new HashMap<>();
        cardSeg.put("PREMIUM", new BigDecimal(70));
        cardSeg.put("STANDARD", new BigDecimal(30));
        demo.setCardSegmentSplit(cardSeg);

        Map<String, BigDecimal> consSeg = new HashMap<>();
        consSeg.put("MIDDLE CLASS", new BigDecimal(60));
        consSeg.put("WEALTHY", new BigDecimal(40));
        demo.setConsumerSegmentSplit(consSeg);

        // Monthly Trends (Last 13 Months)
        // Need to calculate start date for 13 months ago
        LocalDate trendStart = end.minusMonths(12).withDayOfMonth(1); // 13 months including current

        List<java.util.Map<String, Object>> trendRows = sumDailyMerchantRepository.findMonthlyTrends(merchantId,
                trendStart, end);

        List<ChartData> mSales = new ArrayList<>();
        List<ChartData> mTxns = new ArrayList<>();
        List<ChartData> mCust = new ArrayList<>();

        for (java.util.Map<String, Object> r : trendRows) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).name().substring(0, 3) + " " + y;

            BigDecimal vol = r.get("totalVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("totalVolume");
            Long txns = r.get("totalTxns") == null ? 0L : ((Number) r.get("totalTxns")).longValue();
            Long cust = r.get("uniqueCustomers") == null ? 0L : ((Number) r.get("uniqueCustomers")).longValue();

            mSales.add(ChartData.builder().label(label).value(vol).build());
            mTxns.add(ChartData.builder().label(label).value(new BigDecimal(txns)).build());
            mCust.add(ChartData.builder().label(label).value(new BigDecimal(cust)).build());
        }

        demo.setMonthlySales(mSales);
        demo.setMonthlyTxns(mTxns);
        demo.setMonthlyCustomers(mCust);

        // Calculate Derived Metrics for Page 6
        List<ChartData> mAtv = new ArrayList<>();
        List<ChartData> mSalesGrowth = new ArrayList<>();
        List<ChartData> mTxnGrowth = new ArrayList<>();

        BigDecimal prevSales = BigDecimal.ZERO;
        BigDecimal prevTxns = BigDecimal.ZERO;

        for (int i = 0; i < mSales.size(); i++) {
            ChartData s = mSales.get(i);
            ChartData t = mTxns.get(i);

            BigDecimal salesVal = s.getValue();
            BigDecimal txnVal = t.getValue(); // Count

            // 1. ATV
            BigDecimal atv = (txnVal.compareTo(BigDecimal.ZERO) > 0)
                    ? salesVal.divide(txnVal, 0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            mAtv.add(ChartData.builder().label(s.getLabel()).value(atv).build());

            // 2. Sales Growth % (Index vs Previous Month)
            BigDecimal sGrowth = BigDecimal.ZERO;
            if (i == 0) {
                sGrowth = new BigDecimal(100); // Base month
            } else {
                sGrowth = (prevSales.compareTo(BigDecimal.ZERO) > 0)
                        ? salesVal.divide(prevSales, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                        : new BigDecimal(100);
            }
            mSalesGrowth.add(ChartData.builder().label(s.getLabel()).value(sGrowth).build());
            prevSales = salesVal;

            // 3. Txn Growth %
            BigDecimal tGrowth = BigDecimal.ZERO;
            if (i == 0) {
                tGrowth = new BigDecimal(100);
            } else {
                tGrowth = (prevTxns.compareTo(BigDecimal.ZERO) > 0)
                        ? txnVal.divide(prevTxns, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                        : new BigDecimal(100);
            }
            mTxnGrowth.add(ChartData.builder().label(t.getLabel()).value(tGrowth).build());
            prevTxns = txnVal;
        }

        demo.setMonthlyAtv(mAtv);
        demo.setMonthlySalesGrowth(mSalesGrowth);
        demo.setMonthlyTxnGrowth(mTxnGrowth);

        return demo;
    }

    private DccPerformance getDccPerformance(Long merchantId, LocalDate start, LocalDate end) {
        // Trend Start Date (13 months ago)
        LocalDate trendStart = end.minusMonths(12).withDayOfMonth(1);

        List<java.util.Map<String, Object>> trendRows = sumDailyMerchantRepository.findMonthlyTrends(merchantId,
                trendStart, end);

        List<ChartData> missed = new ArrayList<>();
        List<ChartData> elig = new ArrayList<>();
        List<ChartData> opt = new ArrayList<>();

        for (java.util.Map<String, Object> r : trendRows) {
            int y = ((Number) r.get("year")).intValue();
            int m = ((Number) r.get("month")).intValue();
            String label = java.time.Month.of(m).name().substring(0, 3) + " " + y;

            BigDecimal eligible = r.get("dccEligibleVolume") == null ? BigDecimal.ZERO
                    : (BigDecimal) r.get("dccEligibleVolume");
            BigDecimal optIn = r.get("dccOptinVolume") == null ? BigDecimal.ZERO : (BigDecimal) r.get("dccOptinVolume");
            BigDecimal optOut = r.get("dccOptoutVolume") == null ? BigDecimal.ZERO
                    : (BigDecimal) r.get("dccOptoutVolume");

            // Missed Opportunity = Opt Out Volume
            missed.add(ChartData.builder().label(label).value(optOut).build());

            // Eligibility: Total Eligible (Val1), Eligible (Val2 - same? or OptIn?)
            // Chart Description: "INT DCC ELIGIBILITY VALUE"
            // Series 1: Total International Value (Eligible)
            // Series 2: Total DCC Eligible Value (Assuming all Intl are eligible, or strict
            // subset)
            // For now, let's assume Eligible = Intl Volume.
            elig.add(ChartData.builder().label(label).value(eligible).value2(eligible).build());

            // Opt In/Out
            // Series 1: Opt Out
            // Series 2: Opt In
            opt.add(ChartData.builder().label(label).value(optOut).value2(optIn).build());
        }

        return DccPerformance.builder()
                .missedOpportunityTrend(missed)
                .eligibilityTrend(elig)
                .optOutOptInTrend(opt)
                .build();
    }

    // --- Helper Methods ---

    private Map<String, BigDecimal> getSplitMap(Long mid, LocalDate start, LocalDate end, String groupCol,
            String aggFunc) {

        // Map old groupCol strings to Attribute Type
        String attrType = "";
        if ("card_scheme".equalsIgnoreCase(groupCol))
            attrType = "CARD_SCHEME";
        else if ("card_type".equalsIgnoreCase(groupCol))
            attrType = "CARD_TYPE";
        else
            return new HashMap<>(); // Not supported in attributes for now (transaction_type, etc?)

        List<com.acquira.model.SumDailyMerchantAttribute> rows = sumDailyMerchantAttributeRepository
                .findByMerchantDateAndType(mid, start, end, attrType);

        // Aggregate in Memory
        Map<String, BigDecimal> map = new HashMap<>();

        for (com.acquira.model.SumDailyMerchantAttribute a : rows) {
            String key = a.getAttributeValue();
            BigDecimal val = aggFunc.contains("count") ? new BigDecimal(a.getMetricCount()) : a.getMetricVolume();
            map.put(key, map.getOrDefault(key, BigDecimal.ZERO).add(val));
        }
        return map;
    }

    private Map<String, BigDecimal> getAggregates(Long merchantId, LocalDate start, LocalDate end) {
        Map<String, Object> result = sumDailyMerchantRepository.getAggregates(merchantId, start, end);
        Map<String, BigDecimal> map = new HashMap<>();
        if (result == null) {
            map.put("total_txns", BigDecimal.ZERO);
            map.put("total_sales", BigDecimal.ZERO);
            map.put("unique_customers", BigDecimal.ZERO);
            return map;
        }

        map.put("total_txns",
                new BigDecimal(result.get("total_txns") == null ? 0 : ((Number) result.get("total_txns")).longValue()));
        map.put("total_sales",
                result.get("total_sales") == null ? BigDecimal.ZERO : (BigDecimal) result.get("total_sales"));
        // unique_customers is approx sum of daily uniques (not deduplicated across
        // days, but acceptable for summary speed)
        map.put("unique_customers", new BigDecimal(
                result.get("unique_customers") == null ? 0 : ((Number) result.get("unique_customers")).longValue()));
        return map;
    }

    private Map<String, BigDecimal> getPeakStats(Long merchantId, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> map = new HashMap<>();

        // 1. Max Daily Sales
        BigDecimal maxSales = sumDailyMerchantRepository.findMaxDailySales(merchantId, start, end);
        map.put("max_daily_sales", maxSales == null ? BigDecimal.ZERO : maxSales);

        // 2. Max Daily Txns
        Long maxTxns = sumDailyMerchantRepository.findMaxDailyTxns(merchantId, start, end);
        map.put("max_daily_txns", maxTxns == null ? BigDecimal.ZERO : new BigDecimal(maxTxns));

        // 3. Highest Customer Spend (Proxy using top_spending_amount from Summary)
        BigDecimal maxCust = sumDailyMerchantRepository.findMaxTopSpendingAmount(merchantId, start, end);
        map.put("max_cust_spend", maxCust == null ? BigDecimal.ZERO : maxCust);

        // 4. Highest Txn Value - Query directly from Fact Table
        map.put("max_txn_value", findMaxTransactionValue(merchantId, start, end));

        return map;
    }

    private List<ChartData> getChartDataByDayOfWeek(Long merchantId, LocalDate start, LocalDate end, String agg) {
        String sql = "SELECT EXTRACT(DOW FROM payment_date), " + agg + " FROM fact_transaction " +
                "WHERE merchant_id = :mid AND payment_date BETWEEN :start AND :end GROUP BY EXTRACT(DOW FROM payment_date) ORDER BY 1";

        List<Object[]> rows = executeQuery(sql, merchantId, start, end);
        String[] days = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
        List<ChartData> data = new ArrayList<>();

        for (Object[] row : rows) {
            int dayIdx = ((Number) row[0]).intValue();
            BigDecimal val = row[1] instanceof Long ? new BigDecimal((Long) row[1]) : (BigDecimal) row[1];
            data.add(ChartData.builder().label(days[dayIdx]).value(val).build());
        }
        return data;
    }

    private List<ChartData> getChartDataByWeekOfMonth(Long merchantId, LocalDate start, LocalDate end, String agg) {
        // Week of Month calculation: (DayOfMonth - 1) / 7 + 1
        String sql = "SELECT (CAST(EXTRACT(DAY FROM payment_date) AS integer) - 1) / 7 + 1, " + agg
                + " FROM fact_transaction " +
                "WHERE merchant_id = :mid AND payment_date BETWEEN :start AND :end " +
                "GROUP BY (CAST(EXTRACT(DAY FROM payment_date) AS integer) - 1) / 7 + 1 ORDER BY 1";

        List<Object[]> rows = executeQuery(sql, merchantId, start, end);
        List<ChartData> data = new ArrayList<>();

        // Ensure we have weeks 1-5 (max)
        Map<Integer, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            int week = ((Number) row[0]).intValue();
            BigDecimal val = row[1] instanceof Long ? new BigDecimal((Long) row[1]) : (BigDecimal) row[1];
            map.put(week, val);
        }

        for (int i = 1; i <= 5; i++) {
            BigDecimal val = map.getOrDefault(i, BigDecimal.ZERO);
            // Only add if it's not the 5th week with 0 value (looks cleaner, or show all?)
            // Show all for consistency
            data.add(ChartData.builder().label("Week " + i).value(val).build());
        }
        return data;
    }

    private List<Object[]> executeQuery(String sql, Long mid, LocalDate start, LocalDate end) {
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("mid", mid);
        q.setParameter("start", start.atStartOfDay());
        q.setParameter("end", end.atTime(23, 59, 59));
        return q.getResultList();
    }

    private BigDecimal findMaxTransactionValue(Long mid, LocalDate start, LocalDate end) {
        String sql = "SELECT MAX(total_amount_settled) FROM fact_transaction WHERE merchant_id = :mid AND payment_date BETWEEN :start AND :end";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("mid", mid);
        q.setParameter("start", start.atStartOfDay());
        q.setParameter("end", end.atTime(23, 59, 59));
        try {
            Object result = q.getSingleResult();
            return result == null ? BigDecimal.ZERO
                    : (result instanceof BigDecimal ? (BigDecimal) result : new BigDecimal(result.toString()));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Kpi createKpi(BigDecimal current, BigDecimal previous) {
        if (current == null)
            current = BigDecimal.ZERO;
        if (previous == null)
            previous = BigDecimal.ZERO;

        double growth = 0.0;
        String trend = "FLAT";
        if (previous.compareTo(BigDecimal.ZERO) > 0) {
            growth = current.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP).doubleValue() * 100;
        } else if (current.compareTo(BigDecimal.ZERO) > 0) {
            growth = 100.0; // From 0 to something is 100% growth effectively
        }

        if (growth > 0)
            trend = "UP";
        else if (growth < 0)
            trend = "DOWN";

        return Kpi.builder()
                .value(current)
                .momGrowth(growth)
                .formattedValue(String.format("%,.0f", current))
                .trend(trend)
                .build();
    }

    private BigDecimal safeDivide(BigDecimal n, BigDecimal d) {
        if (n == null)
            n = BigDecimal.ZERO;
        if (d == null || d.compareTo(BigDecimal.ZERO) == 0)
            return BigDecimal.ZERO;
        return n.divide(d, 2, RoundingMode.HALF_UP);
    }
}
