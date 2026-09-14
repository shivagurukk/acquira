package com.acquira.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Removed Lombok annotations and implemented manual Getters, Setters, Constructors, and Builders
public class MerchantInsightsDTO {
    private BusinessOverview overview;
    private BusinessAchievements achievements;
    private ConsumerLoyalty loyalty;
    private CustomerDemographics demographics;
    private DccPerformance dccPerformance;
    // NEW: Currency support
    private String currencySymbol;
    private String currencyCode;
    /**
     * Minor-unit precision of {@link #currencyCode}, sourced from
     * ref_country.decimal_notation_value via CurrencyResolver
     * (divisor 100 -> 2, divisor 1000 -> 3).
     *
     * EVERY monetary figure in the PDF templates, the KPI formattedValue
     * strings and the narrative sentences must be rendered to exactly this many
     * fraction digits. Before this field existed the whole display stack
     * hardcoded ZERO decimals, so BHD 450.755 printed as "BHD 451" and EGP lost
     * its piastres.
     *
     * Never defaulted: MerchantInsightService throws if the tenant currency
     * cannot be resolved, so a DTO that reaches a template always carries a
     * non-null value here.
     */
    private Integer currencyDecimals;
    private List<ChartData> storeLeaderboard;
    // Dynamic AI-generated insights for each PDF section
    private InsightNarrative insights;
    // Business Health Score — composite performance rating
    private HealthScore healthScore;

    public List<ChartData> getStoreLeaderboard() { return storeLeaderboard; }
    public void setStoreLeaderboard(List<ChartData> v) { this.storeLeaderboard = v; }
    public InsightNarrative getInsights() { return insights; }
    public void setInsights(InsightNarrative v) { this.insights = v; }
    public HealthScore getHealthScore() { return healthScore; }
    public void setHealthScore(HealthScore v) { this.healthScore = v; }

    public MerchantInsightsDTO() {
    }

    public MerchantInsightsDTO(BusinessOverview overview, BusinessAchievements achievements, ConsumerLoyalty loyalty,
            CustomerDemographics demographics, DccPerformance dccPerformance, String currencySymbol,
            String currencyCode) {
        this.overview = overview;
        this.achievements = achievements;
        this.loyalty = loyalty;
        this.demographics = demographics;
        this.dccPerformance = dccPerformance;
        this.currencySymbol = currencySymbol;
        this.currencyCode = currencyCode;
    }

    public BusinessOverview getOverview() { return overview; }
    public void setOverview(BusinessOverview overview) { this.overview = overview; }
    public BusinessAchievements getAchievements() { return achievements; }
    public void setAchievements(BusinessAchievements achievements) { this.achievements = achievements; }
    public ConsumerLoyalty getLoyalty() { return loyalty; }
    public void setLoyalty(ConsumerLoyalty loyalty) { this.loyalty = loyalty; }
    public CustomerDemographics getDemographics() { return demographics; }
    public void setDemographics(CustomerDemographics demographics) { this.demographics = demographics; }
    public DccPerformance getDccPerformance() { return dccPerformance; }
    public void setDccPerformance(DccPerformance dccPerformance) { this.dccPerformance = dccPerformance; }
    public String getCurrencySymbol() { return currencySymbol; }
    public void setCurrencySymbol(String currencySymbol) { this.currencySymbol = currencySymbol; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public Integer getCurrencyDecimals() { return currencyDecimals; }
    public void setCurrencyDecimals(Integer currencyDecimals) { this.currencyDecimals = currencyDecimals; }

    public static MerchantInsightsDTOBuilder builder() { return new MerchantInsightsDTOBuilder(); }

    public static class MerchantInsightsDTOBuilder {
        private BusinessOverview overview;
        private BusinessAchievements achievements;
        private ConsumerLoyalty loyalty;
        private CustomerDemographics demographics;
        private DccPerformance dccPerformance;
        private String currencySymbol;
        private String currencyCode;
        private Integer currencyDecimals;

        public MerchantInsightsDTOBuilder overview(BusinessOverview v) { this.overview = v; return this; }
        public MerchantInsightsDTOBuilder achievements(BusinessAchievements v) { this.achievements = v; return this; }
        public MerchantInsightsDTOBuilder loyalty(ConsumerLoyalty v) { this.loyalty = v; return this; }
        public MerchantInsightsDTOBuilder demographics(CustomerDemographics v) { this.demographics = v; return this; }
        public MerchantInsightsDTOBuilder dccPerformance(DccPerformance v) { this.dccPerformance = v; return this; }
        public MerchantInsightsDTOBuilder currencySymbol(String v) { this.currencySymbol = v; return this; }
        public MerchantInsightsDTOBuilder currencyCode(String v) { this.currencyCode = v; return this; }
        public MerchantInsightsDTOBuilder currencyDecimals(Integer v) { this.currencyDecimals = v; return this; }
        public MerchantInsightsDTO build() {
            MerchantInsightsDTO d = new MerchantInsightsDTO(overview, achievements, loyalty, demographics,
                    dccPerformance, currencySymbol, currencyCode);
            d.setCurrencyDecimals(currencyDecimals);
            return d;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BusinessOverview
    // ═══════════════════════════════════════════════════════════════
    public static class BusinessOverview {
        private Kpi sales;
        private Kpi transactions;
        private Kpi customers;
        private Kpi avgSpendPerCustomer;
        private Kpi avgTxnValue;
        private Kpi avgTxnsPerCustomer;
        private PeakStats peakStats;
        private List<ChartData> salesByDayOfWeek;
        private List<ChartData> transactionsByDayOfWeek;
        private List<ChartData> salesByWeekOfMonth;
        private List<ChartData> transactionsByWeekOfMonth;
        private Kpi prevSales;
        private Kpi prevTransactions;
        private Kpi prevCustomers;
        private Kpi prevAvgTxnValue;
        private Kpi prevMaxDailySales;
        private BigDecimal weekdayRevenuePct;
        private BigDecimal weekendRevenuePct;
        private String peakDayName;
        private BigDecimal dailyAverage;

        // ── FIX BUG: refund/void totals for reconciled footnote across heatmap and
        //    operational intelligence pages. Previously each page computed its own
        //    refund figure from different aggregations, producing mismatched numbers.
        //    Both pages now read from these two fields which are populated once by
        //    MerchantInsightService from SUM(store_base_currency_amount)
        //    WHERE transaction_type IN ('REFUND','VOID').
        private Long   refundVoidCount;
        private BigDecimal refundVoidVolume;

        public Long getRefundVoidCount() { return refundVoidCount; }
        public void setRefundVoidCount(Long v) { this.refundVoidCount = v; }
        public BigDecimal getRefundVoidVolume() { return refundVoidVolume; }
        public void setRefundVoidVolume(BigDecimal v) { this.refundVoidVolume = v; }

        public BusinessOverview() {}

        public BusinessOverview(Kpi sales, Kpi transactions, Kpi customers, Kpi avgSpendPerCustomer, Kpi avgTxnValue,
                Kpi avgTxnsPerCustomer, PeakStats peakStats, List<ChartData> salesByDayOfWeek,
                List<ChartData> transactionsByDayOfWeek, List<ChartData> salesByWeekOfMonth,
                List<ChartData> transactionsByWeekOfMonth) {
            this.sales = sales; this.transactions = transactions; this.customers = customers;
            this.avgSpendPerCustomer = avgSpendPerCustomer; this.avgTxnValue = avgTxnValue;
            this.avgTxnsPerCustomer = avgTxnsPerCustomer; this.peakStats = peakStats;
            this.salesByDayOfWeek = salesByDayOfWeek; this.transactionsByDayOfWeek = transactionsByDayOfWeek;
            this.salesByWeekOfMonth = salesByWeekOfMonth; this.transactionsByWeekOfMonth = transactionsByWeekOfMonth;
        }

        public Kpi getSales() { return sales; }
        public void setSales(Kpi v) { this.sales = v; }
        public Kpi getTransactions() { return transactions; }
        public void setTransactions(Kpi v) { this.transactions = v; }
        public Kpi getCustomers() { return customers; }
        public void setCustomers(Kpi v) { this.customers = v; }
        public Kpi getAvgSpendPerCustomer() { return avgSpendPerCustomer; }
        public void setAvgSpendPerCustomer(Kpi v) { this.avgSpendPerCustomer = v; }
        public Kpi getAvgTxnValue() { return avgTxnValue; }
        public void setAvgTxnValue(Kpi v) { this.avgTxnValue = v; }
        public Kpi getAvgTxnsPerCustomer() { return avgTxnsPerCustomer; }
        public void setAvgTxnsPerCustomer(Kpi v) { this.avgTxnsPerCustomer = v; }
        public PeakStats getPeakStats() { return peakStats; }
        public void setPeakStats(PeakStats v) { this.peakStats = v; }
        public List<ChartData> getSalesByDayOfWeek() { return salesByDayOfWeek; }
        public void setSalesByDayOfWeek(List<ChartData> v) { this.salesByDayOfWeek = v; }
        public List<ChartData> getTransactionsByDayOfWeek() { return transactionsByDayOfWeek; }
        public void setTransactionsByDayOfWeek(List<ChartData> v) { this.transactionsByDayOfWeek = v; }
        public List<ChartData> getSalesByWeekOfMonth() { return salesByWeekOfMonth; }
        public void setSalesByWeekOfMonth(List<ChartData> v) { this.salesByWeekOfMonth = v; }
        public List<ChartData> getTransactionsByWeekOfMonth() { return transactionsByWeekOfMonth; }
        public void setTransactionsByWeekOfMonth(List<ChartData> v) { this.transactionsByWeekOfMonth = v; }
        public Kpi getPrevSales() { return prevSales; }
        public void setPrevSales(Kpi v) { this.prevSales = v; }
        public Kpi getPrevTransactions() { return prevTransactions; }
        public void setPrevTransactions(Kpi v) { this.prevTransactions = v; }
        public Kpi getPrevCustomers() { return prevCustomers; }
        public void setPrevCustomers(Kpi v) { this.prevCustomers = v; }
        public Kpi getPrevAvgTxnValue() { return prevAvgTxnValue; }
        public void setPrevAvgTxnValue(Kpi v) { this.prevAvgTxnValue = v; }
        public Kpi getPrevMaxDailySales() { return prevMaxDailySales; }
        public void setPrevMaxDailySales(Kpi v) { this.prevMaxDailySales = v; }
        public BigDecimal getWeekdayRevenuePct() { return weekdayRevenuePct; }
        public void setWeekdayRevenuePct(BigDecimal v) { this.weekdayRevenuePct = v; }
        public BigDecimal getWeekendRevenuePct() { return weekendRevenuePct; }
        public void setWeekendRevenuePct(BigDecimal v) { this.weekendRevenuePct = v; }
        public String getPeakDayName() { return peakDayName; }
        public void setPeakDayName(String v) { this.peakDayName = v; }
        public BigDecimal getDailyAverage() { return dailyAverage; }
        public void setDailyAverage(BigDecimal v) { this.dailyAverage = v; }

        public static BusinessOverviewBuilder builder() { return new BusinessOverviewBuilder(); }

        public static class BusinessOverviewBuilder {
            private Kpi sales, transactions, customers, avgSpendPerCustomer, avgTxnValue, avgTxnsPerCustomer;
            private PeakStats peakStats;
            private List<ChartData> salesByDayOfWeek, transactionsByDayOfWeek, salesByWeekOfMonth, transactionsByWeekOfMonth;
            private Kpi prevSales, prevTransactions, prevCustomers, prevAvgTxnValue, prevMaxDailySales;
            private BigDecimal weekdayRevenuePct, weekendRevenuePct, dailyAverage;
            private String peakDayName;
            private Long refundVoidCount;
            private BigDecimal refundVoidVolume;

            public BusinessOverviewBuilder sales(Kpi v) { this.sales = v; return this; }
            public BusinessOverviewBuilder transactions(Kpi v) { this.transactions = v; return this; }
            public BusinessOverviewBuilder customers(Kpi v) { this.customers = v; return this; }
            public BusinessOverviewBuilder avgSpendPerCustomer(Kpi v) { this.avgSpendPerCustomer = v; return this; }
            public BusinessOverviewBuilder avgTxnValue(Kpi v) { this.avgTxnValue = v; return this; }
            public BusinessOverviewBuilder avgTxnsPerCustomer(Kpi v) { this.avgTxnsPerCustomer = v; return this; }
            public BusinessOverviewBuilder peakStats(PeakStats v) { this.peakStats = v; return this; }
            public BusinessOverviewBuilder salesByDayOfWeek(List<ChartData> v) { this.salesByDayOfWeek = v; return this; }
            public BusinessOverviewBuilder transactionsByDayOfWeek(List<ChartData> v) { this.transactionsByDayOfWeek = v; return this; }
            public BusinessOverviewBuilder salesByWeekOfMonth(List<ChartData> v) { this.salesByWeekOfMonth = v; return this; }
            public BusinessOverviewBuilder transactionsByWeekOfMonth(List<ChartData> v) { this.transactionsByWeekOfMonth = v; return this; }
            public BusinessOverviewBuilder prevSales(Kpi v) { this.prevSales = v; return this; }
            public BusinessOverviewBuilder prevTransactions(Kpi v) { this.prevTransactions = v; return this; }
            public BusinessOverviewBuilder prevCustomers(Kpi v) { this.prevCustomers = v; return this; }
            public BusinessOverviewBuilder prevAvgTxnValue(Kpi v) { this.prevAvgTxnValue = v; return this; }
            public BusinessOverviewBuilder prevMaxDailySales(Kpi v) { this.prevMaxDailySales = v; return this; }
            public BusinessOverviewBuilder weekdayRevenuePct(BigDecimal v) { this.weekdayRevenuePct = v; return this; }
            public BusinessOverviewBuilder weekendRevenuePct(BigDecimal v) { this.weekendRevenuePct = v; return this; }
            public BusinessOverviewBuilder peakDayName(String v) { this.peakDayName = v; return this; }
            public BusinessOverviewBuilder dailyAverage(BigDecimal v) { this.dailyAverage = v; return this; }
            public BusinessOverviewBuilder refundVoidCount(Long v) { this.refundVoidCount = v; return this; }
            public BusinessOverviewBuilder refundVoidVolume(BigDecimal v) { this.refundVoidVolume = v; return this; }

            public BusinessOverview build() {
                BusinessOverview o = new BusinessOverview(sales, transactions, customers, avgSpendPerCustomer,
                        avgTxnValue, avgTxnsPerCustomer, peakStats, salesByDayOfWeek,
                        transactionsByDayOfWeek, salesByWeekOfMonth, transactionsByWeekOfMonth);
                o.prevSales = prevSales; o.prevTransactions = prevTransactions; o.prevCustomers = prevCustomers;
                o.prevAvgTxnValue = prevAvgTxnValue; o.prevMaxDailySales = prevMaxDailySales;
                o.weekdayRevenuePct = weekdayRevenuePct; o.weekendRevenuePct = weekendRevenuePct;
                o.peakDayName = peakDayName; o.dailyAverage = dailyAverage;
                o.refundVoidCount = refundVoidCount; o.refundVoidVolume = refundVoidVolume;
                return o;
            }

            public BusinessOverview buildLegacy() {
                return new BusinessOverview(sales, transactions, customers, avgSpendPerCustomer, avgTxnValue,
                        avgTxnsPerCustomer, peakStats, salesByDayOfWeek, transactionsByDayOfWeek,
                        salesByWeekOfMonth, transactionsByWeekOfMonth);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PeakStats
    // ═══════════════════════════════════════════════════════════════
    public static class PeakStats {
        private Kpi maxDailySales;
        private Kpi maxTxnsInDay;
        private Kpi highestTxnValue;
        private Kpi highestCustomerSpend;
        private LocalDate maxDailySalesDate;
        private LocalDate maxTxnsInDayDate;
        private LocalDate highestTxnDate;

        public PeakStats() {}

        public PeakStats(Kpi maxDailySales, Kpi maxTxnsInDay, Kpi highestTxnValue, Kpi highestCustomerSpend,
                LocalDate maxDailySalesDate, LocalDate maxTxnsInDayDate, LocalDate highestTxnDate) {
            this.maxDailySales = maxDailySales; this.maxTxnsInDay = maxTxnsInDay;
            this.highestTxnValue = highestTxnValue; this.highestCustomerSpend = highestCustomerSpend;
            this.maxDailySalesDate = maxDailySalesDate; this.maxTxnsInDayDate = maxTxnsInDayDate;
            this.highestTxnDate = highestTxnDate;
        }

        public Kpi getMaxDailySales() { return maxDailySales; }
        public void setMaxDailySales(Kpi v) { this.maxDailySales = v; }
        public Kpi getMaxTxnsInDay() { return maxTxnsInDay; }
        public void setMaxTxnsInDay(Kpi v) { this.maxTxnsInDay = v; }
        public Kpi getHighestTxnValue() { return highestTxnValue; }
        public void setHighestTxnValue(Kpi v) { this.highestTxnValue = v; }
        public Kpi getHighestCustomerSpend() { return highestCustomerSpend; }
        public void setHighestCustomerSpend(Kpi v) { this.highestCustomerSpend = v; }
        public LocalDate getMaxDailySalesDate() { return maxDailySalesDate; }
        public void setMaxDailySalesDate(LocalDate v) { this.maxDailySalesDate = v; }
        public LocalDate getMaxTxnsInDayDate() { return maxTxnsInDayDate; }
        public void setMaxTxnsInDayDate(LocalDate v) { this.maxTxnsInDayDate = v; }
        public LocalDate getHighestTxnDate() { return highestTxnDate; }
        public void setHighestTxnDate(LocalDate v) { this.highestTxnDate = v; }

        public static PeakStatsBuilder builder() { return new PeakStatsBuilder(); }

        public static class PeakStatsBuilder {
            private Kpi maxDailySales, maxTxnsInDay, highestTxnValue, highestCustomerSpend;
            private LocalDate maxDailySalesDate, maxTxnsInDayDate, highestTxnDate;
            public PeakStatsBuilder maxDailySales(Kpi v) { this.maxDailySales = v; return this; }
            public PeakStatsBuilder maxTxnsInDay(Kpi v) { this.maxTxnsInDay = v; return this; }
            public PeakStatsBuilder highestTxnValue(Kpi v) { this.highestTxnValue = v; return this; }
            public PeakStatsBuilder highestCustomerSpend(Kpi v) { this.highestCustomerSpend = v; return this; }
            public PeakStatsBuilder maxDailySalesDate(LocalDate v) { this.maxDailySalesDate = v; return this; }
            public PeakStatsBuilder maxTxnsInDayDate(LocalDate v) { this.maxTxnsInDayDate = v; return this; }
            public PeakStatsBuilder highestTxnDate(LocalDate v) { this.highestTxnDate = v; return this; }
            public PeakStats build() {
                return new PeakStats(maxDailySales, maxTxnsInDay, highestTxnValue, highestCustomerSpend,
                        maxDailySalesDate, maxTxnsInDayDate, highestTxnDate);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Kpi
    // ═══════════════════════════════════════════════════════════════
    public static class Kpi {
        private BigDecimal value;
        private Double momGrowth;
        private String formattedValue;
        private String trend;

        public Kpi() {}
        public Kpi(BigDecimal value, Double momGrowth, String formattedValue, String trend) {
            this.value = value; this.momGrowth = momGrowth; this.formattedValue = formattedValue; this.trend = trend;
        }

        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal v) { this.value = v; }
        public Double getMomGrowth() { return momGrowth; }
        public void setMomGrowth(Double v) { this.momGrowth = v; }
        public String getFormattedValue() { return formattedValue; }
        public void setFormattedValue(String v) { this.formattedValue = v; }
        public String getTrend() { return trend; }
        public void setTrend(String v) { this.trend = v; }

        public static KpiBuilder builder() { return new KpiBuilder(); }

        public static class KpiBuilder {
            private BigDecimal value;
            private Double momGrowth;
            private String formattedValue;
            private String trend;
            public KpiBuilder value(BigDecimal v) { this.value = v; return this; }
            public KpiBuilder momGrowth(Double v) { this.momGrowth = v; return this; }
            public KpiBuilder formattedValue(String v) { this.formattedValue = v; return this; }
            public KpiBuilder trend(String v) { this.trend = v; return this; }
            public Kpi build() { return new Kpi(value, momGrowth, formattedValue, trend); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BusinessAchievements
    // ═══════════════════════════════════════════════════════════════
    public static class BusinessAchievements {
        private List<ChartData> dailySalesAndCount;
        private List<ChartData> dailyAvgTxnValue;
        private List<ChartData> uniqueCustomersByDay;
        private List<ChartData> salesTimeOfDay;
        private List<ChartData> salesByDayOfMonth;
        private List<ChartData> salesAndAtvByDayOfWeek;
        private List<ChartData> revenueHeatmap;
        private List<ChartData> txnSizeDistribution;
        private List<ChartData> dailyTxnCount;

        // ── FIX NEW: extended 6-bucket transaction-size distribution splitting the
        //    "1K+" bucket into "1K–5K" and "5K+" to surface high-value transaction
        //    patterns. Populated by MerchantInsightService alongside txnSizeDistribution.
        //    Template falls back to txnSizeDistribution when this is null.
        private List<ChartData> txnSizeDistributionExtended;

        public List<ChartData> getTxnSizeDistributionExtended() { return txnSizeDistributionExtended; }
        public void setTxnSizeDistributionExtended(List<ChartData> v) { this.txnSizeDistributionExtended = v; }

        public List<ChartData> getRevenueHeatmap() { return revenueHeatmap; }
        public void setRevenueHeatmap(List<ChartData> v) { this.revenueHeatmap = v; }
        public List<ChartData> getTxnSizeDistribution() { return txnSizeDistribution; }
        public void setTxnSizeDistribution(List<ChartData> v) { this.txnSizeDistribution = v; }
        public List<ChartData> getDailyTxnCount() { return dailyTxnCount; }
        public void setDailyTxnCount(List<ChartData> v) { this.dailyTxnCount = v; }

        public BusinessAchievements() {}

        public BusinessAchievements(List<ChartData> dailySalesAndCount, List<ChartData> dailyAvgTxnValue,
                List<ChartData> uniqueCustomersByDay, List<ChartData> salesTimeOfDay,
                List<ChartData> salesByDayOfMonth, List<ChartData> salesAndAtvByDayOfWeek) {
            this.dailySalesAndCount = dailySalesAndCount; this.dailyAvgTxnValue = dailyAvgTxnValue;
            this.uniqueCustomersByDay = uniqueCustomersByDay; this.salesTimeOfDay = salesTimeOfDay;
            this.salesByDayOfMonth = salesByDayOfMonth; this.salesAndAtvByDayOfWeek = salesAndAtvByDayOfWeek;
        }

        public List<ChartData> getDailySalesAndCount() { return dailySalesAndCount; }
        public void setDailySalesAndCount(List<ChartData> v) { this.dailySalesAndCount = v; }
        public List<ChartData> getDailyAvgTxnValue() { return dailyAvgTxnValue; }
        public void setDailyAvgTxnValue(List<ChartData> v) { this.dailyAvgTxnValue = v; }
        public List<ChartData> getUniqueCustomersByDay() { return uniqueCustomersByDay; }
        public void setUniqueCustomersByDay(List<ChartData> v) { this.uniqueCustomersByDay = v; }
        public List<ChartData> getSalesTimeOfDay() { return salesTimeOfDay; }
        public void setSalesTimeOfDay(List<ChartData> v) { this.salesTimeOfDay = v; }
        public List<ChartData> getSalesByDayOfMonth() { return salesByDayOfMonth; }
        public void setSalesByDayOfMonth(List<ChartData> v) { this.salesByDayOfMonth = v; }
        public List<ChartData> getSalesAndAtvByDayOfWeek() { return salesAndAtvByDayOfWeek; }
        public void setSalesAndAtvByDayOfWeek(List<ChartData> v) { this.salesAndAtvByDayOfWeek = v; }

        public static BusinessAchievementsBuilder builder() { return new BusinessAchievementsBuilder(); }

        public static class BusinessAchievementsBuilder {
            private List<ChartData> dailySalesAndCount, dailyAvgTxnValue, uniqueCustomersByDay;
            private List<ChartData> salesTimeOfDay, salesByDayOfMonth, salesAndAtvByDayOfWeek;
            private List<ChartData> revenueHeatmap, txnSizeDistribution, dailyTxnCount;
            private List<ChartData> txnSizeDistributionExtended;

            public BusinessAchievementsBuilder dailySalesAndCount(List<ChartData> v) { this.dailySalesAndCount = v; return this; }
            public BusinessAchievementsBuilder dailyAvgTxnValue(List<ChartData> v) { this.dailyAvgTxnValue = v; return this; }
            public BusinessAchievementsBuilder uniqueCustomersByDay(List<ChartData> v) { this.uniqueCustomersByDay = v; return this; }
            public BusinessAchievementsBuilder salesTimeOfDay(List<ChartData> v) { this.salesTimeOfDay = v; return this; }
            public BusinessAchievementsBuilder salesByDayOfMonth(List<ChartData> v) { this.salesByDayOfMonth = v; return this; }
            public BusinessAchievementsBuilder salesAndAtvByDayOfWeek(List<ChartData> v) { this.salesAndAtvByDayOfWeek = v; return this; }
            public BusinessAchievementsBuilder revenueHeatmap(List<ChartData> v) { this.revenueHeatmap = v; return this; }
            public BusinessAchievementsBuilder txnSizeDistribution(List<ChartData> v) { this.txnSizeDistribution = v; return this; }
            public BusinessAchievementsBuilder dailyTxnCount(List<ChartData> v) { this.dailyTxnCount = v; return this; }
            public BusinessAchievementsBuilder txnSizeDistributionExtended(List<ChartData> v) { this.txnSizeDistributionExtended = v; return this; }

            public BusinessAchievements build() {
                BusinessAchievements ba = new BusinessAchievements(dailySalesAndCount, dailyAvgTxnValue,
                        uniqueCustomersByDay, salesTimeOfDay, salesByDayOfMonth, salesAndAtvByDayOfWeek);
                ba.setRevenueHeatmap(revenueHeatmap);
                ba.setTxnSizeDistribution(txnSizeDistribution);
                ba.setDailyTxnCount(dailyTxnCount);
                ba.setTxnSizeDistributionExtended(txnSizeDistributionExtended);
                return ba;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ConsumerLoyalty
    // ═══════════════════════════════════════════════════════════════
    public static class ConsumerLoyalty {
        private List<ChartData> visitFrequency;
        private List<ChartData> spendBands;
        private List<ChartData> domesticVsInternational;
        private List<ChartData> customerCategoryTrend;
        private Map<String, BigDecimal> customerCategorySplit;
        private List<ChartData> monthlyVisitFreqTrend;
        private List<ChartData> monthlySpendBandTrend;
        private Map<String, BigDecimal> customerCategoryCountSplit;
        private Map<String, BigDecimal> customerCategoryValueSplit;
        private BigDecimal retentionRate;
        private BigDecimal totalUniqueCards;
        private BigDecimal repeatCardPct;

        // ── FIX NEW: lapsed-customer segment — cards seen in prior month but absent
        //    this month. Populated by MerchantInsightService joining sum_monthly_card
        //    for current and prior month. Enables the third loyalty segment row on P9.
        private Long   lapsedCardCount;
        private BigDecimal lapsedCardPct;   // percentage of all unique cards (prior month union)

        // ── FIX NEW: tiered repeat-customer segmentation. The binary Repeat(2+)/Single
        //    split hid how loyal the repeat base actually is. These break the repeat
        //    cohort into occasional (exactly 2 visits), core (3–5) and loyal (6+),
        //    each as a % of total unique cards this month. Sum of the three ==
        //    repeatCardPct. Drives the tiered rows on the Customer Intelligence page.
        private BigDecimal repeatTier2Pct;    // exactly 2 visits
        private BigDecimal repeatTier35Pct;   // 3–5 visits
        private BigDecimal repeatTier6Pct;    // 6+ visits
        private Long repeatTier2Count;
        private Long repeatTier35Count;
        private Long repeatTier6Count;

        public Long getLapsedCardCount() { return lapsedCardCount; }
        public void setLapsedCardCount(Long v) { this.lapsedCardCount = v; }
        public BigDecimal getLapsedCardPct() { return lapsedCardPct; }
        public void setLapsedCardPct(BigDecimal v) { this.lapsedCardPct = v; }
        public BigDecimal getRepeatTier2Pct() { return repeatTier2Pct; }
        public void setRepeatTier2Pct(BigDecimal v) { this.repeatTier2Pct = v; }
        public BigDecimal getRepeatTier35Pct() { return repeatTier35Pct; }
        public void setRepeatTier35Pct(BigDecimal v) { this.repeatTier35Pct = v; }
        public BigDecimal getRepeatTier6Pct() { return repeatTier6Pct; }
        public void setRepeatTier6Pct(BigDecimal v) { this.repeatTier6Pct = v; }
        public Long getRepeatTier2Count() { return repeatTier2Count; }
        public void setRepeatTier2Count(Long v) { this.repeatTier2Count = v; }
        public Long getRepeatTier35Count() { return repeatTier35Count; }
        public void setRepeatTier35Count(Long v) { this.repeatTier35Count = v; }
        public Long getRepeatTier6Count() { return repeatTier6Count; }
        public void setRepeatTier6Count(Long v) { this.repeatTier6Count = v; }

        public BigDecimal getRetentionRate() { return retentionRate; }
        public void setRetentionRate(BigDecimal v) { this.retentionRate = v; }
        public BigDecimal getTotalUniqueCards() { return totalUniqueCards; }
        public void setTotalUniqueCards(BigDecimal v) { this.totalUniqueCards = v; }
        public BigDecimal getRepeatCardPct() { return repeatCardPct; }
        public void setRepeatCardPct(BigDecimal v) { this.repeatCardPct = v; }

        public ConsumerLoyalty() {}

        public ConsumerLoyalty(List<ChartData> visitFrequency, List<ChartData> spendBands,
                List<ChartData> domesticVsInternational, List<ChartData> customerCategoryTrend,
                Map<String, BigDecimal> customerCategorySplit, List<ChartData> monthlyVisitFreqTrend,
                List<ChartData> monthlySpendBandTrend, Map<String, BigDecimal> customerCategoryCountSplit,
                Map<String, BigDecimal> customerCategoryValueSplit) {
            this.visitFrequency = visitFrequency; this.spendBands = spendBands;
            this.domesticVsInternational = domesticVsInternational; this.customerCategoryTrend = customerCategoryTrend;
            this.customerCategorySplit = customerCategorySplit; this.monthlyVisitFreqTrend = monthlyVisitFreqTrend;
            this.monthlySpendBandTrend = monthlySpendBandTrend;
            this.customerCategoryCountSplit = customerCategoryCountSplit;
            this.customerCategoryValueSplit = customerCategoryValueSplit;
        }

        public List<ChartData> getVisitFrequency() { return visitFrequency; }
        public void setVisitFrequency(List<ChartData> v) { this.visitFrequency = v; }
        public List<ChartData> getSpendBands() { return spendBands; }
        public void setSpendBands(List<ChartData> v) { this.spendBands = v; }
        public List<ChartData> getDomesticVsInternational() { return domesticVsInternational; }
        public void setDomesticVsInternational(List<ChartData> v) { this.domesticVsInternational = v; }
        public List<ChartData> getCustomerCategoryTrend() { return customerCategoryTrend; }
        public void setCustomerCategoryTrend(List<ChartData> v) { this.customerCategoryTrend = v; }
        public Map<String, BigDecimal> getCustomerCategorySplit() { return customerCategorySplit; }
        public void setCustomerCategorySplit(Map<String, BigDecimal> v) { this.customerCategorySplit = v; }
        public List<ChartData> getMonthlyVisitFreqTrend() { return monthlyVisitFreqTrend; }
        public void setMonthlyVisitFreqTrend(List<ChartData> v) { this.monthlyVisitFreqTrend = v; }
        public List<ChartData> getMonthlySpendBandTrend() { return monthlySpendBandTrend; }
        public void setMonthlySpendBandTrend(List<ChartData> v) { this.monthlySpendBandTrend = v; }
        public Map<String, BigDecimal> getCustomerCategoryCountSplit() { return customerCategoryCountSplit; }
        public void setCustomerCategoryCountSplit(Map<String, BigDecimal> v) { this.customerCategoryCountSplit = v; }
        public Map<String, BigDecimal> getCustomerCategoryValueSplit() { return customerCategoryValueSplit; }
        public void setCustomerCategoryValueSplit(Map<String, BigDecimal> v) { this.customerCategoryValueSplit = v; }

        public static ConsumerLoyaltyBuilder builder() { return new ConsumerLoyaltyBuilder(); }

        public static class ConsumerLoyaltyBuilder {
            private List<ChartData> visitFrequency, spendBands, domesticVsInternational, customerCategoryTrend;
            private Map<String, BigDecimal> customerCategorySplit;
            private List<ChartData> monthlyVisitFreqTrend, monthlySpendBandTrend;
            private Map<String, BigDecimal> customerCategoryCountSplit, customerCategoryValueSplit;
            private BigDecimal retentionRate, totalUniqueCards, repeatCardPct;
            private Long lapsedCardCount;
            private BigDecimal lapsedCardPct;

            public ConsumerLoyaltyBuilder visitFrequency(List<ChartData> v) { this.visitFrequency = v; return this; }
            public ConsumerLoyaltyBuilder spendBands(List<ChartData> v) { this.spendBands = v; return this; }
            public ConsumerLoyaltyBuilder domesticVsInternational(List<ChartData> v) { this.domesticVsInternational = v; return this; }
            public ConsumerLoyaltyBuilder customerCategoryTrend(List<ChartData> v) { this.customerCategoryTrend = v; return this; }
            public ConsumerLoyaltyBuilder customerCategorySplit(Map<String, BigDecimal> v) { this.customerCategorySplit = v; return this; }
            public ConsumerLoyaltyBuilder monthlyVisitFreqTrend(List<ChartData> v) { this.monthlyVisitFreqTrend = v; return this; }
            public ConsumerLoyaltyBuilder monthlySpendBandTrend(List<ChartData> v) { this.monthlySpendBandTrend = v; return this; }
            public ConsumerLoyaltyBuilder customerCategoryCountSplit(Map<String, BigDecimal> v) { this.customerCategoryCountSplit = v; return this; }
            public ConsumerLoyaltyBuilder customerCategoryValueSplit(Map<String, BigDecimal> v) { this.customerCategoryValueSplit = v; return this; }
            public ConsumerLoyaltyBuilder retentionRate(BigDecimal v) { this.retentionRate = v; return this; }
            public ConsumerLoyaltyBuilder totalUniqueCards(BigDecimal v) { this.totalUniqueCards = v; return this; }
            public ConsumerLoyaltyBuilder repeatCardPct(BigDecimal v) { this.repeatCardPct = v; return this; }
            public ConsumerLoyaltyBuilder lapsedCardCount(Long v) { this.lapsedCardCount = v; return this; }
            public ConsumerLoyaltyBuilder lapsedCardPct(BigDecimal v) { this.lapsedCardPct = v; return this; }

            public ConsumerLoyalty build() {
                ConsumerLoyalty l = new ConsumerLoyalty(visitFrequency, spendBands, domesticVsInternational,
                        customerCategoryTrend, customerCategorySplit, monthlyVisitFreqTrend, monthlySpendBandTrend,
                        customerCategoryCountSplit, customerCategoryValueSplit);
                l.retentionRate = retentionRate; l.totalUniqueCards = totalUniqueCards; l.repeatCardPct = repeatCardPct;
                l.lapsedCardCount = lapsedCardCount; l.lapsedCardPct = lapsedCardPct;
                return l;
            }

            public ConsumerLoyalty buildLegacy() {
                return new ConsumerLoyalty(visitFrequency, spendBands, domesticVsInternational, customerCategoryTrend,
                        customerCategorySplit, monthlyVisitFreqTrend, monthlySpendBandTrend,
                        customerCategoryCountSplit, customerCategoryValueSplit);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CustomerDemographics
    // ═══════════════════════════════════════════════════════════════
    public static class CustomerDemographics {
        private Map<String, BigDecimal> cardSchemeValueSplit;
        private Map<String, BigDecimal> cardSchemeCountSplit;
        private Map<String, BigDecimal> cardTypeValueSplit;
        private Map<String, BigDecimal> cardTypeCountSplit;
        private Map<String, BigDecimal> transactionTypeValueSplit;
        private Map<String, BigDecimal> transactionTypeCountSplit;
        private Map<String, BigDecimal> cardSegmentSplit;
        private Map<String, BigDecimal> consumerSegmentSplit;
        private List<ChartData> txnValueBands;
        private List<ChartData> topCountries;
        private List<ChartData> topDomesticBanks;
        private List<ChartData> monthlySales;
        private List<ChartData> monthlyTxns;
        private List<ChartData> monthlyCustomers;
        private List<ChartData> monthlyAtv;
        private List<ChartData> monthlySalesGrowth;
        private List<ChartData> monthlyTxnGrowth;
        private String creditDebitRatio;
        private BigDecimal walletUsagePct;
        private BigDecimal cardPenetrationPct;
        private List<ChartData> quarterlyBreakdown;
        private String bestMonth;
        private BigDecimal avgMonthlyGrowthPct;
        private String peakSeason;
        private String lowSeason;
        private BigDecimal yoyGrowthPct;
        private BigDecimal creditPct;
        private BigDecimal creditVolume;
        private Long creditTxnCount;
        private BigDecimal debitPct;
        private BigDecimal debitVolume;
        private Long debitTxnCount;
        private BigDecimal prepaidPct;
        private BigDecimal prepaidVolume;
        private Long prepaidTxnCount;
        private BigDecimal localCardPct;
        private BigDecimal localCardVolume;
        private Long localCardCustomers;
        private BigDecimal internationalCardPct;
        private BigDecimal internationalCardVolume;
        private Long internationalCardCustomers;
        private List<ChartData> avgTicketByCardType;

        // ── FIX NEW: ATV standard-deviation bands for growth chart.
        //    Per-month stddev computed from rolling 12-month ATV window by
        //    MerchantInsightService. Template renders ± 1σ area when non-null.
        private List<ChartData> monthlyAtvStddev;

        // ── FIX NEW: 3-month moving-average forecast (next month projection).
        //    Computed as avg of last 3 months from monthlySales. Null when fewer
        //    than 3 months of history exist. Marked beta in the template.
        private boolean forecastAvailable;
        private BigDecimal forecastNextMonthSales;

        // ── FIX BUG: flag set true when the card scheme aggregation contained at
        //    least one NULL / unresolved scheme code. Used to show the "Unclassified"
        //    footnote on the card analytics page (P8). The bar chart renderer also
        //    reads this to bucket NULL values under the "Unclassified" label.
        private boolean hasUnclassifiedScheme;

        public List<ChartData> getMonthlyAtvStddev() { return monthlyAtvStddev; }
        public void setMonthlyAtvStddev(List<ChartData> v) { this.monthlyAtvStddev = v; }
        public boolean isForecastAvailable() { return forecastAvailable; }
        public void setForecastAvailable(boolean v) { this.forecastAvailable = v; }
        public BigDecimal getForecastNextMonthSales() { return forecastNextMonthSales; }
        public void setForecastNextMonthSales(BigDecimal v) { this.forecastNextMonthSales = v; }
        public boolean isHasUnclassifiedScheme() { return hasUnclassifiedScheme; }
        public void setHasUnclassifiedScheme(boolean v) { this.hasUnclassifiedScheme = v; }

        public BigDecimal getCreditPct() { return creditPct; }
        public void setCreditPct(BigDecimal v) { this.creditPct = v; }
        public BigDecimal getCreditVolume() { return creditVolume; }
        public void setCreditVolume(BigDecimal v) { this.creditVolume = v; }
        public Long getCreditTxnCount() { return creditTxnCount; }
        public void setCreditTxnCount(Long v) { this.creditTxnCount = v; }
        public BigDecimal getDebitPct() { return debitPct; }
        public void setDebitPct(BigDecimal v) { this.debitPct = v; }
        public BigDecimal getDebitVolume() { return debitVolume; }
        public void setDebitVolume(BigDecimal v) { this.debitVolume = v; }
        public Long getDebitTxnCount() { return debitTxnCount; }
        public void setDebitTxnCount(Long v) { this.debitTxnCount = v; }
        public BigDecimal getPrepaidPct() { return prepaidPct; }
        public void setPrepaidPct(BigDecimal v) { this.prepaidPct = v; }
        public BigDecimal getPrepaidVolume() { return prepaidVolume; }
        public void setPrepaidVolume(BigDecimal v) { this.prepaidVolume = v; }
        public Long getPrepaidTxnCount() { return prepaidTxnCount; }
        public void setPrepaidTxnCount(Long v) { this.prepaidTxnCount = v; }
        public BigDecimal getLocalCardPct() { return localCardPct; }
        public void setLocalCardPct(BigDecimal v) { this.localCardPct = v; }
        public BigDecimal getLocalCardVolume() { return localCardVolume; }
        public void setLocalCardVolume(BigDecimal v) { this.localCardVolume = v; }
        public Long getLocalCardCustomers() { return localCardCustomers; }
        public void setLocalCardCustomers(Long v) { this.localCardCustomers = v; }
        public BigDecimal getInternationalCardPct() { return internationalCardPct; }
        public void setInternationalCardPct(BigDecimal v) { this.internationalCardPct = v; }
        public BigDecimal getInternationalCardVolume() { return internationalCardVolume; }
        public void setInternationalCardVolume(BigDecimal v) { this.internationalCardVolume = v; }
        public Long getInternationalCardCustomers() { return internationalCardCustomers; }
        public void setInternationalCardCustomers(Long v) { this.internationalCardCustomers = v; }
        public List<ChartData> getAvgTicketByCardType() { return avgTicketByCardType; }
        public void setAvgTicketByCardType(List<ChartData> v) { this.avgTicketByCardType = v; }
        public String getCreditDebitRatio() { return creditDebitRatio; }
        public void setCreditDebitRatio(String v) { this.creditDebitRatio = v; }
        public BigDecimal getWalletUsagePct() { return walletUsagePct; }
        public void setWalletUsagePct(BigDecimal v) { this.walletUsagePct = v; }
        public BigDecimal getCardPenetrationPct() { return cardPenetrationPct; }
        public void setCardPenetrationPct(BigDecimal v) { this.cardPenetrationPct = v; }
        public List<ChartData> getQuarterlyBreakdown() { return quarterlyBreakdown; }
        public void setQuarterlyBreakdown(List<ChartData> v) { this.quarterlyBreakdown = v; }
        public String getBestMonth() { return bestMonth; }
        public void setBestMonth(String v) { this.bestMonth = v; }
        public BigDecimal getAvgMonthlyGrowthPct() { return avgMonthlyGrowthPct; }
        public void setAvgMonthlyGrowthPct(BigDecimal v) { this.avgMonthlyGrowthPct = v; }
        public String getPeakSeason() { return peakSeason; }
        public void setPeakSeason(String v) { this.peakSeason = v; }
        public String getLowSeason() { return lowSeason; }
        public void setLowSeason(String v) { this.lowSeason = v; }
        public BigDecimal getYoyGrowthPct() { return yoyGrowthPct; }
        public void setYoyGrowthPct(BigDecimal v) { this.yoyGrowthPct = v; }

        public CustomerDemographics() {}

        public CustomerDemographics(Map<String, BigDecimal> cardSchemeValueSplit,
                Map<String, BigDecimal> cardSchemeCountSplit, Map<String, BigDecimal> cardTypeValueSplit,
                Map<String, BigDecimal> cardTypeCountSplit, Map<String, BigDecimal> transactionTypeValueSplit,
                Map<String, BigDecimal> transactionTypeCountSplit, Map<String, BigDecimal> cardSegmentSplit,
                Map<String, BigDecimal> consumerSegmentSplit, List<ChartData> txnValueBands,
                List<ChartData> topCountries, List<ChartData> topDomesticBanks, List<ChartData> monthlySales,
                List<ChartData> monthlyTxns, List<ChartData> monthlyCustomers, List<ChartData> monthlyAtv,
                List<ChartData> monthlySalesGrowth, List<ChartData> monthlyTxnGrowth) {
            this.cardSchemeValueSplit = cardSchemeValueSplit; this.cardSchemeCountSplit = cardSchemeCountSplit;
            this.cardTypeValueSplit = cardTypeValueSplit; this.cardTypeCountSplit = cardTypeCountSplit;
            this.transactionTypeValueSplit = transactionTypeValueSplit; this.transactionTypeCountSplit = transactionTypeCountSplit;
            this.cardSegmentSplit = cardSegmentSplit; this.consumerSegmentSplit = consumerSegmentSplit;
            this.txnValueBands = txnValueBands; this.topCountries = topCountries; this.topDomesticBanks = topDomesticBanks;
            this.monthlySales = monthlySales; this.monthlyTxns = monthlyTxns; this.monthlyCustomers = monthlyCustomers;
            this.monthlyAtv = monthlyAtv; this.monthlySalesGrowth = monthlySalesGrowth; this.monthlyTxnGrowth = monthlyTxnGrowth;
        }

        public Map<String, BigDecimal> getCardSchemeValueSplit() { return cardSchemeValueSplit; }
        public void setCardSchemeValueSplit(Map<String, BigDecimal> v) { this.cardSchemeValueSplit = v; }
        public Map<String, BigDecimal> getCardSchemeCountSplit() { return cardSchemeCountSplit; }
        public void setCardSchemeCountSplit(Map<String, BigDecimal> v) { this.cardSchemeCountSplit = v; }
        public Map<String, BigDecimal> getCardTypeValueSplit() { return cardTypeValueSplit; }
        public void setCardTypeValueSplit(Map<String, BigDecimal> v) { this.cardTypeValueSplit = v; }
        public Map<String, BigDecimal> getCardTypeCountSplit() { return cardTypeCountSplit; }
        public void setCardTypeCountSplit(Map<String, BigDecimal> v) { this.cardTypeCountSplit = v; }
        public Map<String, BigDecimal> getTransactionTypeValueSplit() { return transactionTypeValueSplit; }
        public void setTransactionTypeValueSplit(Map<String, BigDecimal> v) { this.transactionTypeValueSplit = v; }
        public Map<String, BigDecimal> getTransactionTypeCountSplit() { return transactionTypeCountSplit; }
        public void setTransactionTypeCountSplit(Map<String, BigDecimal> v) { this.transactionTypeCountSplit = v; }
        public Map<String, BigDecimal> getCardSegmentSplit() { return cardSegmentSplit; }
        public void setCardSegmentSplit(Map<String, BigDecimal> v) { this.cardSegmentSplit = v; }
        public Map<String, BigDecimal> getConsumerSegmentSplit() { return consumerSegmentSplit; }
        public void setConsumerSegmentSplit(Map<String, BigDecimal> v) { this.consumerSegmentSplit = v; }
        public List<ChartData> getTxnValueBands() { return txnValueBands; }
        public void setTxnValueBands(List<ChartData> v) { this.txnValueBands = v; }
        public List<ChartData> getTopCountries() { return topCountries; }
        public void setTopCountries(List<ChartData> v) { this.topCountries = v; }
        public List<ChartData> getTopDomesticBanks() { return topDomesticBanks; }
        public void setTopDomesticBanks(List<ChartData> v) { this.topDomesticBanks = v; }
        public List<ChartData> getMonthlySales() { return monthlySales; }
        public void setMonthlySales(List<ChartData> v) { this.monthlySales = v; }
        public List<ChartData> getMonthlyTxns() { return monthlyTxns; }
        public void setMonthlyTxns(List<ChartData> v) { this.monthlyTxns = v; }
        public List<ChartData> getMonthlyCustomers() { return monthlyCustomers; }
        public void setMonthlyCustomers(List<ChartData> v) { this.monthlyCustomers = v; }
        public List<ChartData> getMonthlyAtv() { return monthlyAtv; }
        public void setMonthlyAtv(List<ChartData> v) { this.monthlyAtv = v; }
        public List<ChartData> getMonthlySalesGrowth() { return monthlySalesGrowth; }
        public void setMonthlySalesGrowth(List<ChartData> v) { this.monthlySalesGrowth = v; }
        public List<ChartData> getMonthlyTxnGrowth() { return monthlyTxnGrowth; }
        public void setMonthlyTxnGrowth(List<ChartData> v) { this.monthlyTxnGrowth = v; }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DccPerformance
    // ═══════════════════════════════════════════════════════════════
    public static class DccPerformance {
        private List<ChartData> missedOpportunityTrend;
        private List<ChartData> eligibilityTrend;
        private List<ChartData> optOutOptInTrend;
        private BigDecimal dccEligibleVolume;
        private BigDecimal dccOptinVolume;
        private BigDecimal dccOptoutVolume;
        private BigDecimal dccConversionRate;
        private BigDecimal dccMissedRevenue;
        private BigDecimal dccRevenueGenerated;
        private Long optInCount;
        private Long optOutCount;
        private BigDecimal optOutDeclineRate;
        private Long dccEligibleCount;
        private Long dccOptinCountLong;
        private Long totalIntlTxnCount;
        private BigDecimal totalIntlVolume;

        // ── FIX NEW: monthly opt-in conversion rate % trend — one entry per month
        //    (same labels as optOutOptInTrend). Populated from sum_monthly_card or
        //    fact_transaction aggregation by MerchantInsightService. Used by the
        //    dual-axis-bar-line chart on P9 and P12 to overlay conversion rate on
        //    the opt-out volume bars.
        private List<ChartData> optInConversionRateTrend;

        // Since 2026-09-02 populated from the DCC revenue feed (fact_dcc_revenue
        // via sum_daily_merchant.dcc_merchant) — the merchant's realised DCC
        // earnings, no longer optInVol × 3%.
        private BigDecimal optInRevenue;
        public BigDecimal getOptInRevenue() { return optInRevenue; }
        public void setOptInRevenue(BigDecimal v) { this.optInRevenue = v; }

        // Measured DCC revenue split from the feed. dccRevenueSource is "FEED"
        // when the month has feed rows, "NONE" otherwise — templates gate on it
        // so a merchant never sees a zero presented as a measurement.
        private BigDecimal dccMerchantRevenue;
        private BigDecimal dccAcquirerRevenue;
        private BigDecimal rentalIncome;
        private String dccRevenueSource;
        public BigDecimal getDccMerchantRevenue() { return dccMerchantRevenue; }
        public void setDccMerchantRevenue(BigDecimal v) { this.dccMerchantRevenue = v; }
        public BigDecimal getDccAcquirerRevenue() { return dccAcquirerRevenue; }
        public void setDccAcquirerRevenue(BigDecimal v) { this.dccAcquirerRevenue = v; }
        public BigDecimal getRentalIncome() { return rentalIncome; }
        public void setRentalIncome(BigDecimal v) { this.rentalIncome = v; }
        public String getDccRevenueSource() { return dccRevenueSource; }
        public void setDccRevenueSource(String v) { this.dccRevenueSource = v; }

        public List<ChartData> getOptInConversionRateTrend() { return optInConversionRateTrend; }
        public void setOptInConversionRateTrend(List<ChartData> v) { this.optInConversionRateTrend = v; }

        public DccPerformance() {}

        public DccPerformance(List<ChartData> missedOpportunityTrend, List<ChartData> eligibilityTrend,
                List<ChartData> optOutOptInTrend) {
            this.missedOpportunityTrend = missedOpportunityTrend;
            this.eligibilityTrend = eligibilityTrend;
            this.optOutOptInTrend = optOutOptInTrend;
        }

        public List<ChartData> getMissedOpportunityTrend() { return missedOpportunityTrend; }
        public void setMissedOpportunityTrend(List<ChartData> v) { this.missedOpportunityTrend = v; }
        public List<ChartData> getEligibilityTrend() { return eligibilityTrend; }
        public void setEligibilityTrend(List<ChartData> v) { this.eligibilityTrend = v; }
        public List<ChartData> getOptOutOptInTrend() { return optOutOptInTrend; }
        public void setOptOutOptInTrend(List<ChartData> v) { this.optOutOptInTrend = v; }
        public BigDecimal getDccEligibleVolume() { return dccEligibleVolume; }
        public void setDccEligibleVolume(BigDecimal v) { this.dccEligibleVolume = v; }
        public BigDecimal getDccOptinVolume() { return dccOptinVolume; }
        public void setDccOptinVolume(BigDecimal v) { this.dccOptinVolume = v; }
        public BigDecimal getDccOptoutVolume() { return dccOptoutVolume; }
        public void setDccOptoutVolume(BigDecimal v) { this.dccOptoutVolume = v; }
        public BigDecimal getDccConversionRate() { return dccConversionRate; }
        public void setDccConversionRate(BigDecimal v) { this.dccConversionRate = v; }
        public BigDecimal getDccMissedRevenue() { return dccMissedRevenue; }
        public void setDccMissedRevenue(BigDecimal v) { this.dccMissedRevenue = v; }
        public BigDecimal getDccRevenueGenerated() { return dccRevenueGenerated; }
        public void setDccRevenueGenerated(BigDecimal v) { this.dccRevenueGenerated = v; }
        public Long getOptInCount() { return optInCount; }
        public void setOptInCount(Long v) { this.optInCount = v; }
        public Long getOptOutCount() { return optOutCount; }
        public void setOptOutCount(Long v) { this.optOutCount = v; }
        public BigDecimal getOptOutDeclineRate() { return optOutDeclineRate; }
        public void setOptOutDeclineRate(BigDecimal v) { this.optOutDeclineRate = v; }
        public Long getDccEligibleCount() { return dccEligibleCount; }
        public void setDccEligibleCount(Long v) { this.dccEligibleCount = v; }
        public Long getDccOptinCountLong() { return dccOptinCountLong; }
        public void setDccOptinCountLong(Long v) { this.dccOptinCountLong = v; }
        public Long getTotalIntlTxnCount() { return totalIntlTxnCount; }
        public void setTotalIntlTxnCount(Long v) { this.totalIntlTxnCount = v; }
        public BigDecimal getTotalIntlVolume() { return totalIntlVolume; }
        public void setTotalIntlVolume(BigDecimal v) { this.totalIntlVolume = v; }

        public static DccPerformanceBuilder builder() { return new DccPerformanceBuilder(); }

        public static class DccPerformanceBuilder {
            private List<ChartData> missedOpportunityTrend, eligibilityTrend, optOutOptInTrend;
            private List<ChartData> optInConversionRateTrend;
            private BigDecimal dccEligibleVolume, dccOptinVolume, dccOptoutVolume, dccConversionRate, dccMissedRevenue;

            public DccPerformanceBuilder missedOpportunityTrend(List<ChartData> v) { this.missedOpportunityTrend = v; return this; }
            public DccPerformanceBuilder eligibilityTrend(List<ChartData> v) { this.eligibilityTrend = v; return this; }
            public DccPerformanceBuilder optOutOptInTrend(List<ChartData> v) { this.optOutOptInTrend = v; return this; }
            public DccPerformanceBuilder optInConversionRateTrend(List<ChartData> v) { this.optInConversionRateTrend = v; return this; }
            public DccPerformanceBuilder dccEligibleVolume(BigDecimal v) { this.dccEligibleVolume = v; return this; }
            public DccPerformanceBuilder dccOptinVolume(BigDecimal v) { this.dccOptinVolume = v; return this; }
            public DccPerformanceBuilder dccOptoutVolume(BigDecimal v) { this.dccOptoutVolume = v; return this; }
            public DccPerformanceBuilder dccConversionRate(BigDecimal v) { this.dccConversionRate = v; return this; }
            public DccPerformanceBuilder dccMissedRevenue(BigDecimal v) { this.dccMissedRevenue = v; return this; }

            public DccPerformance build() {
                DccPerformance d = new DccPerformance(missedOpportunityTrend, eligibilityTrend, optOutOptInTrend);
                d.dccEligibleVolume = dccEligibleVolume; d.dccOptinVolume = dccOptinVolume;
                d.dccOptoutVolume = dccOptoutVolume; d.dccConversionRate = dccConversionRate;
                d.dccMissedRevenue = dccMissedRevenue;
                d.optInConversionRateTrend = optInConversionRateTrend;
                return d;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ChartData
    // ═══════════════════════════════════════════════════════════════
    public static class ChartData {
        private String label;
        private BigDecimal value;
        private BigDecimal value2;
        private BigDecimal value3;

        public ChartData() {}

        public ChartData(String label, BigDecimal value, BigDecimal value2, BigDecimal value3) {
            this.label = label; this.value = value; this.value2 = value2; this.value3 = value3;
        }

        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }
        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal v) { this.value = v; }
        public BigDecimal getValue2() { return value2; }
        public void setValue2(BigDecimal v) { this.value2 = v; }
        public BigDecimal getValue3() { return value3; }
        public void setValue3(BigDecimal v) { this.value3 = v; }

        public static ChartDataBuilder builder() { return new ChartDataBuilder(); }

        public static class ChartDataBuilder {
            private String label;
            private BigDecimal value, value2, value3;
            public ChartDataBuilder label(String v) { this.label = v; return this; }
            public ChartDataBuilder value(BigDecimal v) { this.value = v; return this; }
            public ChartDataBuilder value2(BigDecimal v) { this.value2 = v; return this; }
            public ChartDataBuilder value3(BigDecimal v) { this.value3 = v; return this; }
            public ChartData build() { return new ChartData(label, value, value2, value3); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HealthScore
    // ═══════════════════════════════════════════════════════════════
    /**
     * Business Health Score — composite performance rating across 5 dimensions.
     * Fully computed from real transaction data, zero hardcoded values.
     */
    public static class HealthScore {
        private int compositeScore;
        private String grade;
        private String gradeLabel;
        private String gradeColor;
        private String gradeBgColor;

        private int revenueHealthScore;
        private int growthMomentumScore;
        private int customerLoyaltyScore;
        private int paymentEfficiencyScore;
        private int dccPerformanceScore;
        private boolean dccApplicable;

        private String revenueGrade, growthGrade, loyaltyGrade, paymentGrade, dccGrade;
        private String revenueColor, growthColor, loyaltyColor, paymentColor, dccColor;

        private String strength1Title, strength1Detail;
        private String strength2Title, strength2Detail;
        private String strength3Title, strength3Detail;

        private String improve1Title, improve1Detail;
        private String improve2Title, improve2Detail;
        private String improve3Title, improve3Detail;

        // ── FIX NEW: structured action-item fields (metric → target → action triplet).
        //    Templates render "Current: X → Target: Y" using these when non-null,
        //    falling back to improveNTitle / improveNDetail otherwise.
        private String improve1Metric, improve1Target;
        private String improve2Metric, improve2Target;
        private String improve3Metric, improve3Target;

        // ── FIX NEW: previous-month composite score for the MoM delta badge on P12.
        //    0 means "no prior data" (badge is suppressed by the th:if in the template).
        private int prevCompositeScore;

        private String aiSummary;

        public HealthScore() {}

        public int getCompositeScore() { return compositeScore; }
        public void setCompositeScore(int v) { this.compositeScore = v; }
        public String getGrade() { return grade; }
        public void setGrade(String v) { this.grade = v; }
        public String getGradeLabel() { return gradeLabel; }
        public void setGradeLabel(String v) { this.gradeLabel = v; }
        public String getGradeColor() { return gradeColor; }
        public void setGradeColor(String v) { this.gradeColor = v; }
        public String getGradeBgColor() { return gradeBgColor; }
        public void setGradeBgColor(String v) { this.gradeBgColor = v; }
        public int getRevenueHealthScore() { return revenueHealthScore; }
        public void setRevenueHealthScore(int v) { this.revenueHealthScore = v; }
        public int getGrowthMomentumScore() { return growthMomentumScore; }
        public void setGrowthMomentumScore(int v) { this.growthMomentumScore = v; }
        public int getCustomerLoyaltyScore() { return customerLoyaltyScore; }
        public void setCustomerLoyaltyScore(int v) { this.customerLoyaltyScore = v; }
        public int getPaymentEfficiencyScore() { return paymentEfficiencyScore; }
        public void setPaymentEfficiencyScore(int v) { this.paymentEfficiencyScore = v; }
        public int getDccPerformanceScore() { return dccPerformanceScore; }
        public void setDccPerformanceScore(int v) { this.dccPerformanceScore = v; }
        public boolean isDccApplicable() { return dccApplicable; }
        public void setDccApplicable(boolean v) { this.dccApplicable = v; }
        public String getRevenueGrade() { return revenueGrade; }
        public void setRevenueGrade(String v) { this.revenueGrade = v; }
        public String getGrowthGrade() { return growthGrade; }
        public void setGrowthGrade(String v) { this.growthGrade = v; }
        public String getLoyaltyGrade() { return loyaltyGrade; }
        public void setLoyaltyGrade(String v) { this.loyaltyGrade = v; }
        public String getPaymentGrade() { return paymentGrade; }
        public void setPaymentGrade(String v) { this.paymentGrade = v; }
        public String getDccGrade() { return dccGrade; }
        public void setDccGrade(String v) { this.dccGrade = v; }
        public String getRevenueColor() { return revenueColor; }
        public void setRevenueColor(String v) { this.revenueColor = v; }
        public String getGrowthColor() { return growthColor; }
        public void setGrowthColor(String v) { this.growthColor = v; }
        public String getLoyaltyColor() { return loyaltyColor; }
        public void setLoyaltyColor(String v) { this.loyaltyColor = v; }
        public String getPaymentColor() { return paymentColor; }
        public void setPaymentColor(String v) { this.paymentColor = v; }
        public String getDccColor() { return dccColor; }
        public void setDccColor(String v) { this.dccColor = v; }
        public String getStrength1Title() { return strength1Title; }
        public void setStrength1Title(String v) { this.strength1Title = v; }
        public String getStrength1Detail() { return strength1Detail; }
        public void setStrength1Detail(String v) { this.strength1Detail = v; }
        public String getStrength2Title() { return strength2Title; }
        public void setStrength2Title(String v) { this.strength2Title = v; }
        public String getStrength2Detail() { return strength2Detail; }
        public void setStrength2Detail(String v) { this.strength2Detail = v; }
        public String getStrength3Title() { return strength3Title; }
        public void setStrength3Title(String v) { this.strength3Title = v; }
        public String getStrength3Detail() { return strength3Detail; }
        public void setStrength3Detail(String v) { this.strength3Detail = v; }
        public String getImprove1Title() { return improve1Title; }
        public void setImprove1Title(String v) { this.improve1Title = v; }
        public String getImprove1Detail() { return improve1Detail; }
        public void setImprove1Detail(String v) { this.improve1Detail = v; }
        public String getImprove2Title() { return improve2Title; }
        public void setImprove2Title(String v) { this.improve2Title = v; }
        public String getImprove2Detail() { return improve2Detail; }
        public void setImprove2Detail(String v) { this.improve2Detail = v; }
        public String getImprove3Title() { return improve3Title; }
        public void setImprove3Title(String v) { this.improve3Title = v; }
        public String getImprove3Detail() { return improve3Detail; }
        public void setImprove3Detail(String v) { this.improve3Detail = v; }
        // Structured action fields
        public String getImprove1Metric() { return improve1Metric; }
        public void setImprove1Metric(String v) { this.improve1Metric = v; }
        public String getImprove1Target() { return improve1Target; }
        public void setImprove1Target(String v) { this.improve1Target = v; }
        public String getImprove2Metric() { return improve2Metric; }
        public void setImprove2Metric(String v) { this.improve2Metric = v; }
        public String getImprove2Target() { return improve2Target; }
        public void setImprove2Target(String v) { this.improve2Target = v; }
        public String getImprove3Metric() { return improve3Metric; }
        public void setImprove3Metric(String v) { this.improve3Metric = v; }
        public String getImprove3Target() { return improve3Target; }
        public void setImprove3Target(String v) { this.improve3Target = v; }
        // MoM delta
        public int getPrevCompositeScore() { return prevCompositeScore; }
        public void setPrevCompositeScore(int v) { this.prevCompositeScore = v; }
        public String getAiSummary() { return aiSummary; }
        public void setAiSummary(String v) { this.aiSummary = v; }
    }

    // ═══════════════════════════════════════════════════════════════
    //  InsightNarrative
    // ═══════════════════════════════════════════════════════════════
    /**
     * Dynamic AI-generated narrative insights for each PDF section.
     */
    public static class InsightNarrative {
        private String execSummary;
        private String peakAchievement;
        private String peakWatch;
        private String salesInsight;
        private String salesWatch;
        // ── FIX NEW: auto-callout for the weakest day (e.g. "Thursday is your
        //    quietest weekday — consider a targeted promotion"). Populated by
        //    MerchantInsightService from the salesByDayOfWeek series minimum.
        private String salesWeakestDay;
        private String heatmapInsight;
        private String heatmapTip;
        private String growthInsight;
        private String growthWatch;
        private String cardInsight;
        private String cardTip;
        private String customerInsight;
        private String customerTip;
        private String dccInsight;
        private String dccTip;
        private String actionItem1;
        private String actionItem2;
        private String actionItem3;
        private int peakDayIndex = -1;
        private String peakDayLabel;
        private String slowestDayLabel;
        private String peakHourLabel;
        private String slowestHourLabel;

        public InsightNarrative() {}

        public String getExecSummary() { return execSummary; }
        public void setExecSummary(String v) { this.execSummary = v; }
        public String getPeakAchievement() { return peakAchievement; }
        public void setPeakAchievement(String v) { this.peakAchievement = v; }
        public String getPeakWatch() { return peakWatch; }
        public void setPeakWatch(String v) { this.peakWatch = v; }
        public String getSalesInsight() { return salesInsight; }
        public void setSalesInsight(String v) { this.salesInsight = v; }
        public String getSalesWatch() { return salesWatch; }
        public void setSalesWatch(String v) { this.salesWatch = v; }
        public String getSalesWeakestDay() { return salesWeakestDay; }
        public void setSalesWeakestDay(String v) { this.salesWeakestDay = v; }
        public String getHeatmapInsight() { return heatmapInsight; }
        public void setHeatmapInsight(String v) { this.heatmapInsight = v; }
        public String getHeatmapTip() { return heatmapTip; }
        public void setHeatmapTip(String v) { this.heatmapTip = v; }
        public String getGrowthInsight() { return growthInsight; }
        public void setGrowthInsight(String v) { this.growthInsight = v; }
        public String getGrowthWatch() { return growthWatch; }
        public void setGrowthWatch(String v) { this.growthWatch = v; }
        public String getCardInsight() { return cardInsight; }
        public void setCardInsight(String v) { this.cardInsight = v; }
        public String getCardTip() { return cardTip; }
        public void setCardTip(String v) { this.cardTip = v; }
        public String getCustomerInsight() { return customerInsight; }
        public void setCustomerInsight(String v) { this.customerInsight = v; }
        public String getCustomerTip() { return customerTip; }
        public void setCustomerTip(String v) { this.customerTip = v; }
        public String getDccInsight() { return dccInsight; }
        public void setDccInsight(String v) { this.dccInsight = v; }
        public String getDccTip() { return dccTip; }
        public void setDccTip(String v) { this.dccTip = v; }
        public String getActionItem1() { return actionItem1; }
        public void setActionItem1(String v) { this.actionItem1 = v; }
        public String getActionItem2() { return actionItem2; }
        public void setActionItem2(String v) { this.actionItem2 = v; }
        public String getActionItem3() { return actionItem3; }
        public void setActionItem3(String v) { this.actionItem3 = v; }
        public int getPeakDayIndex() { return peakDayIndex; }
        public void setPeakDayIndex(int v) { this.peakDayIndex = v; }
        public String getPeakDayLabel() { return peakDayLabel; }
        public void setPeakDayLabel(String v) { this.peakDayLabel = v; }
        public String getSlowestDayLabel() { return slowestDayLabel; }
        public void setSlowestDayLabel(String v) { this.slowestDayLabel = v; }
        public String getPeakHourLabel() { return peakHourLabel; }
        public void setPeakHourLabel(String v) { this.peakHourLabel = v; }
        public String getSlowestHourLabel() { return slowestHourLabel; }
        public void setSlowestHourLabel(String v) { this.slowestHourLabel = v; }
    }
}
