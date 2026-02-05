package com.acquira.dto;

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

    public MerchantInsightsDTO() {
    }

    public MerchantInsightsDTO(BusinessOverview overview, BusinessAchievements achievements, ConsumerLoyalty loyalty,
            CustomerDemographics demographics, DccPerformance dccPerformance) {
        this.overview = overview;
        this.achievements = achievements;
        this.loyalty = loyalty;
        this.demographics = demographics;
        this.dccPerformance = dccPerformance;
    }

    public BusinessOverview getOverview() {
        return overview;
    }

    public void setOverview(BusinessOverview overview) {
        this.overview = overview;
    }

    public BusinessAchievements getAchievements() {
        return achievements;
    }

    public void setAchievements(BusinessAchievements achievements) {
        this.achievements = achievements;
    }

    public ConsumerLoyalty getLoyalty() {
        return loyalty;
    }

    public void setLoyalty(ConsumerLoyalty loyalty) {
        this.loyalty = loyalty;
    }

    public CustomerDemographics getDemographics() {
        return demographics;
    }

    public void setDemographics(CustomerDemographics demographics) {
        this.demographics = demographics;
    }

    public DccPerformance getDccPerformance() {
        return dccPerformance;
    }

    public void setDccPerformance(DccPerformance dccPerformance) {
        this.dccPerformance = dccPerformance;
    }

    public static MerchantInsightsDTOBuilder builder() {
        return new MerchantInsightsDTOBuilder();
    }

    public static class MerchantInsightsDTOBuilder {
        private BusinessOverview overview;
        private BusinessAchievements achievements;
        private ConsumerLoyalty loyalty;
        private CustomerDemographics demographics;
        private DccPerformance dccPerformance;

        public MerchantInsightsDTOBuilder overview(BusinessOverview overview) {
            this.overview = overview;
            return this;
        }

        public MerchantInsightsDTOBuilder achievements(BusinessAchievements achievements) {
            this.achievements = achievements;
            return this;
        }

        public MerchantInsightsDTOBuilder loyalty(ConsumerLoyalty loyalty) {
            this.loyalty = loyalty;
            return this;
        }

        public MerchantInsightsDTOBuilder demographics(CustomerDemographics demographics) {
            this.demographics = demographics;
            return this;
        }

        public MerchantInsightsDTOBuilder dccPerformance(DccPerformance dccPerformance) {
            this.dccPerformance = dccPerformance;
            return this;
        }

        public MerchantInsightsDTO build() {
            return new MerchantInsightsDTO(overview, achievements, loyalty, demographics, dccPerformance);
        }
    }

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

        public BusinessOverview() {
        }

        public BusinessOverview(Kpi sales, Kpi transactions, Kpi customers, Kpi avgSpendPerCustomer, Kpi avgTxnValue,
                Kpi avgTxnsPerCustomer, PeakStats peakStats, List<ChartData> salesByDayOfWeek,
                List<ChartData> transactionsByDayOfWeek, List<ChartData> salesByWeekOfMonth,
                List<ChartData> transactionsByWeekOfMonth) {
            this.sales = sales;
            this.transactions = transactions;
            this.customers = customers;
            this.avgSpendPerCustomer = avgSpendPerCustomer;
            this.avgTxnValue = avgTxnValue;
            this.avgTxnsPerCustomer = avgTxnsPerCustomer;
            this.peakStats = peakStats;
            this.salesByDayOfWeek = salesByDayOfWeek;
            this.transactionsByDayOfWeek = transactionsByDayOfWeek;
            this.salesByWeekOfMonth = salesByWeekOfMonth;
            this.transactionsByWeekOfMonth = transactionsByWeekOfMonth;
        }

        public Kpi getSales() {
            return sales;
        }

        public void setSales(Kpi sales) {
            this.sales = sales;
        }

        public Kpi getTransactions() {
            return transactions;
        }

        public void setTransactions(Kpi transactions) {
            this.transactions = transactions;
        }

        public Kpi getCustomers() {
            return customers;
        }

        public void setCustomers(Kpi customers) {
            this.customers = customers;
        }

        public Kpi getAvgSpendPerCustomer() {
            return avgSpendPerCustomer;
        }

        public void setAvgSpendPerCustomer(Kpi avgSpendPerCustomer) {
            this.avgSpendPerCustomer = avgSpendPerCustomer;
        }

        public Kpi getAvgTxnValue() {
            return avgTxnValue;
        }

        public void setAvgTxnValue(Kpi avgTxnValue) {
            this.avgTxnValue = avgTxnValue;
        }

        public Kpi getAvgTxnsPerCustomer() {
            return avgTxnsPerCustomer;
        }

        public void setAvgTxnsPerCustomer(Kpi avgTxnsPerCustomer) {
            this.avgTxnsPerCustomer = avgTxnsPerCustomer;
        }

        public PeakStats getPeakStats() {
            return peakStats;
        }

        public void setPeakStats(PeakStats peakStats) {
            this.peakStats = peakStats;
        }

        public List<ChartData> getSalesByDayOfWeek() {
            return salesByDayOfWeek;
        }

        public void setSalesByDayOfWeek(List<ChartData> salesByDayOfWeek) {
            this.salesByDayOfWeek = salesByDayOfWeek;
        }

        public List<ChartData> getTransactionsByDayOfWeek() {
            return transactionsByDayOfWeek;
        }

        public void setTransactionsByDayOfWeek(List<ChartData> transactionsByDayOfWeek) {
            this.transactionsByDayOfWeek = transactionsByDayOfWeek;
        }

        public List<ChartData> getSalesByWeekOfMonth() {
            return salesByWeekOfMonth;
        }

        public void setSalesByWeekOfMonth(List<ChartData> salesByWeekOfMonth) {
            this.salesByWeekOfMonth = salesByWeekOfMonth;
        }

        public List<ChartData> getTransactionsByWeekOfMonth() {
            return transactionsByWeekOfMonth;
        }

        public void setTransactionsByWeekOfMonth(List<ChartData> transactionsByWeekOfMonth) {
            this.transactionsByWeekOfMonth = transactionsByWeekOfMonth;
        }

        public static BusinessOverviewBuilder builder() {
            return new BusinessOverviewBuilder();
        }

        public static class BusinessOverviewBuilder {
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

            public BusinessOverviewBuilder sales(Kpi sales) {
                this.sales = sales;
                return this;
            }

            public BusinessOverviewBuilder transactions(Kpi transactions) {
                this.transactions = transactions;
                return this;
            }

            public BusinessOverviewBuilder customers(Kpi customers) {
                this.customers = customers;
                return this;
            }

            public BusinessOverviewBuilder avgSpendPerCustomer(Kpi avgSpendPerCustomer) {
                this.avgSpendPerCustomer = avgSpendPerCustomer;
                return this;
            }

            public BusinessOverviewBuilder avgTxnValue(Kpi avgTxnValue) {
                this.avgTxnValue = avgTxnValue;
                return this;
            }

            public BusinessOverviewBuilder avgTxnsPerCustomer(Kpi avgTxnsPerCustomer) {
                this.avgTxnsPerCustomer = avgTxnsPerCustomer;
                return this;
            }

            public BusinessOverviewBuilder peakStats(PeakStats peakStats) {
                this.peakStats = peakStats;
                return this;
            }

            public BusinessOverviewBuilder salesByDayOfWeek(List<ChartData> salesByDayOfWeek) {
                this.salesByDayOfWeek = salesByDayOfWeek;
                return this;
            }

            public BusinessOverviewBuilder transactionsByDayOfWeek(List<ChartData> transactionsByDayOfWeek) {
                this.transactionsByDayOfWeek = transactionsByDayOfWeek;
                return this;
            }

            public BusinessOverviewBuilder salesByWeekOfMonth(List<ChartData> salesByWeekOfMonth) {
                this.salesByWeekOfMonth = salesByWeekOfMonth;
                return this;
            }

            public BusinessOverviewBuilder transactionsByWeekOfMonth(List<ChartData> transactionsByWeekOfMonth) {
                this.transactionsByWeekOfMonth = transactionsByWeekOfMonth;
                return this;
            }

            public BusinessOverview build() {
                return new BusinessOverview(sales, transactions, customers, avgSpendPerCustomer, avgTxnValue,
                        avgTxnsPerCustomer, peakStats, salesByDayOfWeek, transactionsByDayOfWeek, salesByWeekOfMonth,
                        transactionsByWeekOfMonth);
            }
        }
    }

    public static class PeakStats {
        private Kpi maxDailySales;
        private Kpi maxTxnsInDay;
        private Kpi highestTxnValue;
        private Kpi highestCustomerSpend;
        private LocalDate maxDailySalesDate;
        private LocalDate maxTxnsInDayDate;
        private LocalDate highestTxnDate;

        public PeakStats() {
        }

        public PeakStats(Kpi maxDailySales, Kpi maxTxnsInDay, Kpi highestTxnValue, Kpi highestCustomerSpend,
                LocalDate maxDailySalesDate, LocalDate maxTxnsInDayDate, LocalDate highestTxnDate) {
            this.maxDailySales = maxDailySales;
            this.maxTxnsInDay = maxTxnsInDay;
            this.highestTxnValue = highestTxnValue;
            this.highestCustomerSpend = highestCustomerSpend;
            this.maxDailySalesDate = maxDailySalesDate;
            this.maxTxnsInDayDate = maxTxnsInDayDate;
            this.highestTxnDate = highestTxnDate;
        }

        public Kpi getMaxDailySales() {
            return maxDailySales;
        }

        public void setMaxDailySales(Kpi maxDailySales) {
            this.maxDailySales = maxDailySales;
        }

        public Kpi getMaxTxnsInDay() {
            return maxTxnsInDay;
        }

        public void setMaxTxnsInDay(Kpi maxTxnsInDay) {
            this.maxTxnsInDay = maxTxnsInDay;
        }

        public Kpi getHighestTxnValue() {
            return highestTxnValue;
        }

        public void setHighestTxnValue(Kpi highestTxnValue) {
            this.highestTxnValue = highestTxnValue;
        }

        public Kpi getHighestCustomerSpend() {
            return highestCustomerSpend;
        }

        public void setHighestCustomerSpend(Kpi highestCustomerSpend) {
            this.highestCustomerSpend = highestCustomerSpend;
        }

        public LocalDate getMaxDailySalesDate() {
            return maxDailySalesDate;
        }

        public void setMaxDailySalesDate(LocalDate maxDailySalesDate) {
            this.maxDailySalesDate = maxDailySalesDate;
        }

        public LocalDate getMaxTxnsInDayDate() {
            return maxTxnsInDayDate;
        }

        public void setMaxTxnsInDayDate(LocalDate maxTxnsInDayDate) {
            this.maxTxnsInDayDate = maxTxnsInDayDate;
        }

        public LocalDate getHighestTxnDate() {
            return highestTxnDate;
        }

        public void setHighestTxnDate(LocalDate highestTxnDate) {
            this.highestTxnDate = highestTxnDate;
        }

        public static PeakStatsBuilder builder() {
            return new PeakStatsBuilder();
        }

        public static class PeakStatsBuilder {
            private Kpi maxDailySales;
            private Kpi maxTxnsInDay;
            private Kpi highestTxnValue;
            private Kpi highestCustomerSpend;
            private LocalDate maxDailySalesDate;
            private LocalDate maxTxnsInDayDate;
            private LocalDate highestTxnDate;

            public PeakStatsBuilder maxDailySales(Kpi maxDailySales) {
                this.maxDailySales = maxDailySales;
                return this;
            }

            public PeakStatsBuilder maxTxnsInDay(Kpi maxTxnsInDay) {
                this.maxTxnsInDay = maxTxnsInDay;
                return this;
            }

            public PeakStatsBuilder highestTxnValue(Kpi highestTxnValue) {
                this.highestTxnValue = highestTxnValue;
                return this;
            }

            public PeakStatsBuilder highestCustomerSpend(Kpi highestCustomerSpend) {
                this.highestCustomerSpend = highestCustomerSpend;
                return this;
            }

            public PeakStatsBuilder maxDailySalesDate(LocalDate maxDailySalesDate) {
                this.maxDailySalesDate = maxDailySalesDate;
                return this;
            }

            public PeakStatsBuilder maxTxnsInDayDate(LocalDate maxTxnsInDayDate) {
                this.maxTxnsInDayDate = maxTxnsInDayDate;
                return this;
            }

            public PeakStatsBuilder highestTxnDate(LocalDate highestTxnDate) {
                this.highestTxnDate = highestTxnDate;
                return this;
            }

            public PeakStats build() {
                return new PeakStats(maxDailySales, maxTxnsInDay, highestTxnValue, highestCustomerSpend,
                        maxDailySalesDate, maxTxnsInDayDate, highestTxnDate);
            }
        }
    }

    public static class Kpi {
        private BigDecimal value;
        private Double momGrowth;
        private String formattedValue;
        private String trend;

        public Kpi() {
        }

        public Kpi(BigDecimal value, Double momGrowth, String formattedValue, String trend) {
            this.value = value;
            this.momGrowth = momGrowth;
            this.formattedValue = formattedValue;
            this.trend = trend;
        }

        public BigDecimal getValue() {
            return value;
        }

        public void setValue(BigDecimal value) {
            this.value = value;
        }

        public Double getMomGrowth() {
            return momGrowth;
        }

        public void setMomGrowth(Double momGrowth) {
            this.momGrowth = momGrowth;
        }

        public String getFormattedValue() {
            return formattedValue;
        }

        public void setFormattedValue(String formattedValue) {
            this.formattedValue = formattedValue;
        }

        public String getTrend() {
            return trend;
        }

        public void setTrend(String trend) {
            this.trend = trend;
        }

        public static KpiBuilder builder() {
            return new KpiBuilder();
        }

        public static class KpiBuilder {
            private BigDecimal value;
            private Double momGrowth;
            private String formattedValue;
            private String trend;

            public KpiBuilder value(BigDecimal value) {
                this.value = value;
                return this;
            }

            public KpiBuilder momGrowth(Double momGrowth) {
                this.momGrowth = momGrowth;
                return this;
            }

            public KpiBuilder formattedValue(String formattedValue) {
                this.formattedValue = formattedValue;
                return this;
            }

            public KpiBuilder trend(String trend) {
                this.trend = trend;
                return this;
            }

            public Kpi build() {
                return new Kpi(value, momGrowth, formattedValue, trend);
            }
        }
    }

    public static class BusinessAchievements {
        private List<ChartData> dailySalesAndCount;
        private List<ChartData> dailyAvgTxnValue;
        private List<ChartData> uniqueCustomersByDay;
        private List<ChartData> salesTimeOfDay;
        private List<ChartData> salesByDayOfMonth;
        private List<ChartData> salesAndAtvByDayOfWeek;

        public BusinessAchievements() {
        }

        public BusinessAchievements(List<ChartData> dailySalesAndCount, List<ChartData> dailyAvgTxnValue,
                List<ChartData> uniqueCustomersByDay, List<ChartData> salesTimeOfDay, List<ChartData> salesByDayOfMonth,
                List<ChartData> salesAndAtvByDayOfWeek) {
            this.dailySalesAndCount = dailySalesAndCount;
            this.dailyAvgTxnValue = dailyAvgTxnValue;
            this.uniqueCustomersByDay = uniqueCustomersByDay;
            this.salesTimeOfDay = salesTimeOfDay;
            this.salesByDayOfMonth = salesByDayOfMonth;
            this.salesAndAtvByDayOfWeek = salesAndAtvByDayOfWeek;
        }

        public List<ChartData> getDailySalesAndCount() {
            return dailySalesAndCount;
        }

        public void setDailySalesAndCount(List<ChartData> dailySalesAndCount) {
            this.dailySalesAndCount = dailySalesAndCount;
        }

        public List<ChartData> getDailyAvgTxnValue() {
            return dailyAvgTxnValue;
        }

        public void setDailyAvgTxnValue(List<ChartData> dailyAvgTxnValue) {
            this.dailyAvgTxnValue = dailyAvgTxnValue;
        }

        public List<ChartData> getUniqueCustomersByDay() {
            return uniqueCustomersByDay;
        }

        public void setUniqueCustomersByDay(List<ChartData> uniqueCustomersByDay) {
            this.uniqueCustomersByDay = uniqueCustomersByDay;
        }

        public List<ChartData> getSalesTimeOfDay() {
            return salesTimeOfDay;
        }

        public void setSalesTimeOfDay(List<ChartData> salesTimeOfDay) {
            this.salesTimeOfDay = salesTimeOfDay;
        }

        public List<ChartData> getSalesByDayOfMonth() {
            return salesByDayOfMonth;
        }

        public void setSalesByDayOfMonth(List<ChartData> salesByDayOfMonth) {
            this.salesByDayOfMonth = salesByDayOfMonth;
        }

        public List<ChartData> getSalesAndAtvByDayOfWeek() {
            return salesAndAtvByDayOfWeek;
        }

        public void setSalesAndAtvByDayOfWeek(List<ChartData> salesAndAtvByDayOfWeek) {
            this.salesAndAtvByDayOfWeek = salesAndAtvByDayOfWeek;
        }

        public static BusinessAchievementsBuilder builder() {
            return new BusinessAchievementsBuilder();
        }

        public static class BusinessAchievementsBuilder {
            private List<ChartData> dailySalesAndCount;
            private List<ChartData> dailyAvgTxnValue;
            private List<ChartData> uniqueCustomersByDay;
            private List<ChartData> salesTimeOfDay;
            private List<ChartData> salesByDayOfMonth;
            private List<ChartData> salesAndAtvByDayOfWeek;

            public BusinessAchievementsBuilder dailySalesAndCount(List<ChartData> dailySalesAndCount) {
                this.dailySalesAndCount = dailySalesAndCount;
                return this;
            }

            public BusinessAchievementsBuilder dailyAvgTxnValue(List<ChartData> dailyAvgTxnValue) {
                this.dailyAvgTxnValue = dailyAvgTxnValue;
                return this;
            }

            public BusinessAchievementsBuilder uniqueCustomersByDay(List<ChartData> uniqueCustomersByDay) {
                this.uniqueCustomersByDay = uniqueCustomersByDay;
                return this;
            }

            public BusinessAchievementsBuilder salesTimeOfDay(List<ChartData> salesTimeOfDay) {
                this.salesTimeOfDay = salesTimeOfDay;
                return this;
            }

            public BusinessAchievementsBuilder salesByDayOfMonth(List<ChartData> salesByDayOfMonth) {
                this.salesByDayOfMonth = salesByDayOfMonth;
                return this;
            }

            public BusinessAchievementsBuilder salesAndAtvByDayOfWeek(List<ChartData> salesAndAtvByDayOfWeek) {
                this.salesAndAtvByDayOfWeek = salesAndAtvByDayOfWeek;
                return this;
            }

            public BusinessAchievements build() {
                return new BusinessAchievements(dailySalesAndCount, dailyAvgTxnValue, uniqueCustomersByDay,
                        salesTimeOfDay, salesByDayOfMonth, salesAndAtvByDayOfWeek);
            }
        }
    }

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

        public ConsumerLoyalty() {
        }

        public ConsumerLoyalty(List<ChartData> visitFrequency, List<ChartData> spendBands,
                List<ChartData> domesticVsInternational, List<ChartData> customerCategoryTrend,
                Map<String, BigDecimal> customerCategorySplit, List<ChartData> monthlyVisitFreqTrend,
                List<ChartData> monthlySpendBandTrend, Map<String, BigDecimal> customerCategoryCountSplit,
                Map<String, BigDecimal> customerCategoryValueSplit) {
            this.visitFrequency = visitFrequency;
            this.spendBands = spendBands;
            this.domesticVsInternational = domesticVsInternational;
            this.customerCategoryTrend = customerCategoryTrend;
            this.customerCategorySplit = customerCategorySplit;
            this.monthlyVisitFreqTrend = monthlyVisitFreqTrend;
            this.monthlySpendBandTrend = monthlySpendBandTrend;
            this.customerCategoryCountSplit = customerCategoryCountSplit;
            this.customerCategoryValueSplit = customerCategoryValueSplit;
        }

        public List<ChartData> getVisitFrequency() {
            return visitFrequency;
        }

        public void setVisitFrequency(List<ChartData> visitFrequency) {
            this.visitFrequency = visitFrequency;
        }

        public List<ChartData> getSpendBands() {
            return spendBands;
        }

        public void setSpendBands(List<ChartData> spendBands) {
            this.spendBands = spendBands;
        }

        public List<ChartData> getDomesticVsInternational() {
            return domesticVsInternational;
        }

        public void setDomesticVsInternational(List<ChartData> domesticVsInternational) {
            this.domesticVsInternational = domesticVsInternational;
        }

        public List<ChartData> getCustomerCategoryTrend() {
            return customerCategoryTrend;
        }

        public void setCustomerCategoryTrend(List<ChartData> customerCategoryTrend) {
            this.customerCategoryTrend = customerCategoryTrend;
        }

        public Map<String, BigDecimal> getCustomerCategorySplit() {
            return customerCategorySplit;
        }

        public void setCustomerCategorySplit(Map<String, BigDecimal> customerCategorySplit) {
            this.customerCategorySplit = customerCategorySplit;
        }

        public List<ChartData> getMonthlyVisitFreqTrend() {
            return monthlyVisitFreqTrend;
        }

        public void setMonthlyVisitFreqTrend(List<ChartData> monthlyVisitFreqTrend) {
            this.monthlyVisitFreqTrend = monthlyVisitFreqTrend;
        }

        public List<ChartData> getMonthlySpendBandTrend() {
            return monthlySpendBandTrend;
        }

        public void setMonthlySpendBandTrend(List<ChartData> monthlySpendBandTrend) {
            this.monthlySpendBandTrend = monthlySpendBandTrend;
        }

        public Map<String, BigDecimal> getCustomerCategoryCountSplit() {
            return customerCategoryCountSplit;
        }

        public void setCustomerCategoryCountSplit(Map<String, BigDecimal> customerCategoryCountSplit) {
            this.customerCategoryCountSplit = customerCategoryCountSplit;
        }

        public Map<String, BigDecimal> getCustomerCategoryValueSplit() {
            return customerCategoryValueSplit;
        }

        public void setCustomerCategoryValueSplit(Map<String, BigDecimal> customerCategoryValueSplit) {
            this.customerCategoryValueSplit = customerCategoryValueSplit;
        }

        public static ConsumerLoyaltyBuilder builder() {
            return new ConsumerLoyaltyBuilder();
        }

        public static class ConsumerLoyaltyBuilder {
            private List<ChartData> visitFrequency;
            private List<ChartData> spendBands;
            private List<ChartData> domesticVsInternational;
            private List<ChartData> customerCategoryTrend;
            private Map<String, BigDecimal> customerCategorySplit;
            private List<ChartData> monthlyVisitFreqTrend;
            private List<ChartData> monthlySpendBandTrend;
            private Map<String, BigDecimal> customerCategoryCountSplit;
            private Map<String, BigDecimal> customerCategoryValueSplit;

            public ConsumerLoyaltyBuilder visitFrequency(List<ChartData> visitFrequency) {
                this.visitFrequency = visitFrequency;
                return this;
            }

            public ConsumerLoyaltyBuilder spendBands(List<ChartData> spendBands) {
                this.spendBands = spendBands;
                return this;
            }

            public ConsumerLoyaltyBuilder domesticVsInternational(List<ChartData> domesticVsInternational) {
                this.domesticVsInternational = domesticVsInternational;
                return this;
            }

            public ConsumerLoyaltyBuilder customerCategoryTrend(List<ChartData> customerCategoryTrend) {
                this.customerCategoryTrend = customerCategoryTrend;
                return this;
            }

            public ConsumerLoyaltyBuilder customerCategorySplit(Map<String, BigDecimal> customerCategorySplit) {
                this.customerCategorySplit = customerCategorySplit;
                return this;
            }

            public ConsumerLoyaltyBuilder monthlyVisitFreqTrend(List<ChartData> monthlyVisitFreqTrend) {
                this.monthlyVisitFreqTrend = monthlyVisitFreqTrend;
                return this;
            }

            public ConsumerLoyaltyBuilder monthlySpendBandTrend(List<ChartData> monthlySpendBandTrend) {
                this.monthlySpendBandTrend = monthlySpendBandTrend;
                return this;
            }

            public ConsumerLoyaltyBuilder customerCategoryCountSplit(
                    Map<String, BigDecimal> customerCategoryCountSplit) {
                this.customerCategoryCountSplit = customerCategoryCountSplit;
                return this;
            }

            public ConsumerLoyaltyBuilder customerCategoryValueSplit(
                    Map<String, BigDecimal> customerCategoryValueSplit) {
                this.customerCategoryValueSplit = customerCategoryValueSplit;
                return this;
            }

            public ConsumerLoyalty build() {
                return new ConsumerLoyalty(visitFrequency, spendBands, domesticVsInternational, customerCategoryTrend,
                        customerCategorySplit, monthlyVisitFreqTrend, monthlySpendBandTrend, customerCategoryCountSplit,
                        customerCategoryValueSplit);
            }
        }
    }

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

        public CustomerDemographics() {
        }

        public CustomerDemographics(Map<String, BigDecimal> cardSchemeValueSplit,
                Map<String, BigDecimal> cardSchemeCountSplit, Map<String, BigDecimal> cardTypeValueSplit,
                Map<String, BigDecimal> cardTypeCountSplit, Map<String, BigDecimal> transactionTypeValueSplit,
                Map<String, BigDecimal> transactionTypeCountSplit, Map<String, BigDecimal> cardSegmentSplit,
                Map<String, BigDecimal> consumerSegmentSplit, List<ChartData> txnValueBands,
                List<ChartData> topCountries, List<ChartData> topDomesticBanks, List<ChartData> monthlySales,
                List<ChartData> monthlyTxns, List<ChartData> monthlyCustomers, List<ChartData> monthlyAtv,
                List<ChartData> monthlySalesGrowth, List<ChartData> monthlyTxnGrowth) {
            this.cardSchemeValueSplit = cardSchemeValueSplit;
            this.cardSchemeCountSplit = cardSchemeCountSplit;
            this.cardTypeValueSplit = cardTypeValueSplit;
            this.cardTypeCountSplit = cardTypeCountSplit;
            this.transactionTypeValueSplit = transactionTypeValueSplit;
            this.transactionTypeCountSplit = transactionTypeCountSplit;
            this.cardSegmentSplit = cardSegmentSplit;
            this.consumerSegmentSplit = consumerSegmentSplit;
            this.txnValueBands = txnValueBands;
            this.topCountries = topCountries;
            this.topDomesticBanks = topDomesticBanks;
            this.monthlySales = monthlySales;
            this.monthlyTxns = monthlyTxns;
            this.monthlyCustomers = monthlyCustomers;
            this.monthlyAtv = monthlyAtv;
            this.monthlySalesGrowth = monthlySalesGrowth;
            this.monthlyTxnGrowth = monthlyTxnGrowth;
        }

        public Map<String, BigDecimal> getCardSchemeValueSplit() {
            return cardSchemeValueSplit;
        }

        public void setCardSchemeValueSplit(Map<String, BigDecimal> cardSchemeValueSplit) {
            this.cardSchemeValueSplit = cardSchemeValueSplit;
        }

        public Map<String, BigDecimal> getCardSchemeCountSplit() {
            return cardSchemeCountSplit;
        }

        public void setCardSchemeCountSplit(Map<String, BigDecimal> cardSchemeCountSplit) {
            this.cardSchemeCountSplit = cardSchemeCountSplit;
        }

        public Map<String, BigDecimal> getCardTypeValueSplit() {
            return cardTypeValueSplit;
        }

        public void setCardTypeValueSplit(Map<String, BigDecimal> cardTypeValueSplit) {
            this.cardTypeValueSplit = cardTypeValueSplit;
        }

        public Map<String, BigDecimal> getCardTypeCountSplit() {
            return cardTypeCountSplit;
        }

        public void setCardTypeCountSplit(Map<String, BigDecimal> cardTypeCountSplit) {
            this.cardTypeCountSplit = cardTypeCountSplit;
        }

        public Map<String, BigDecimal> getTransactionTypeValueSplit() {
            return transactionTypeValueSplit;
        }

        public void setTransactionTypeValueSplit(Map<String, BigDecimal> transactionTypeValueSplit) {
            this.transactionTypeValueSplit = transactionTypeValueSplit;
        }

        public Map<String, BigDecimal> getTransactionTypeCountSplit() {
            return transactionTypeCountSplit;
        }

        public void setTransactionTypeCountSplit(Map<String, BigDecimal> transactionTypeCountSplit) {
            this.transactionTypeCountSplit = transactionTypeCountSplit;
        }

        public Map<String, BigDecimal> getCardSegmentSplit() {
            return cardSegmentSplit;
        }

        public void setCardSegmentSplit(Map<String, BigDecimal> cardSegmentSplit) {
            this.cardSegmentSplit = cardSegmentSplit;
        }

        public Map<String, BigDecimal> getConsumerSegmentSplit() {
            return consumerSegmentSplit;
        }

        public void setConsumerSegmentSplit(Map<String, BigDecimal> consumerSegmentSplit) {
            this.consumerSegmentSplit = consumerSegmentSplit;
        }

        public List<ChartData> getTxnValueBands() {
            return txnValueBands;
        }

        public void setTxnValueBands(List<ChartData> txnValueBands) {
            this.txnValueBands = txnValueBands;
        }

        public List<ChartData> getTopCountries() {
            return topCountries;
        }

        public void setTopCountries(List<ChartData> topCountries) {
            this.topCountries = topCountries;
        }

        public List<ChartData> getTopDomesticBanks() {
            return topDomesticBanks;
        }

        public void setTopDomesticBanks(List<ChartData> topDomesticBanks) {
            this.topDomesticBanks = topDomesticBanks;
        }

        public List<ChartData> getMonthlySales() {
            return monthlySales;
        }

        public void setMonthlySales(List<ChartData> monthlySales) {
            this.monthlySales = monthlySales;
        }

        public List<ChartData> getMonthlyTxns() {
            return monthlyTxns;
        }

        public void setMonthlyTxns(List<ChartData> monthlyTxns) {
            this.monthlyTxns = monthlyTxns;
        }

        public List<ChartData> getMonthlyCustomers() {
            return monthlyCustomers;
        }

        public void setMonthlyCustomers(List<ChartData> monthlyCustomers) {
            this.monthlyCustomers = monthlyCustomers;
        }

        public List<ChartData> getMonthlyAtv() {
            return monthlyAtv;
        }

        public void setMonthlyAtv(List<ChartData> monthlyAtv) {
            this.monthlyAtv = monthlyAtv;
        }

        public List<ChartData> getMonthlySalesGrowth() {
            return monthlySalesGrowth;
        }

        public void setMonthlySalesGrowth(List<ChartData> monthlySalesGrowth) {
            this.monthlySalesGrowth = monthlySalesGrowth;
        }

        public List<ChartData> getMonthlyTxnGrowth() {
            return monthlyTxnGrowth;
        }

        public void setMonthlyTxnGrowth(List<ChartData> monthlyTxnGrowth) {
            this.monthlyTxnGrowth = monthlyTxnGrowth;
        }

        public static CustomerDemographicsBuilder builder() {
            return new CustomerDemographicsBuilder();
        }

        public static class CustomerDemographicsBuilder {
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

            public CustomerDemographicsBuilder cardSchemeValueSplit(Map<String, BigDecimal> cardSchemeValueSplit) {
                this.cardSchemeValueSplit = cardSchemeValueSplit;
                return this;
            }

            public CustomerDemographicsBuilder cardSchemeCountSplit(Map<String, BigDecimal> cardSchemeCountSplit) {
                this.cardSchemeCountSplit = cardSchemeCountSplit;
                return this;
            }

            public CustomerDemographicsBuilder cardTypeValueSplit(Map<String, BigDecimal> cardTypeValueSplit) {
                this.cardTypeValueSplit = cardTypeValueSplit;
                return this;
            }

            public CustomerDemographicsBuilder cardTypeCountSplit(Map<String, BigDecimal> cardTypeCountSplit) {
                this.cardTypeCountSplit = cardTypeCountSplit;
                return this;
            }

            public CustomerDemographicsBuilder transactionTypeValueSplit(
                    Map<String, BigDecimal> transactionTypeValueSplit) {
                this.transactionTypeValueSplit = transactionTypeValueSplit;
                return this;
            }

            public CustomerDemographicsBuilder transactionTypeCountSplit(
                    Map<String, BigDecimal> transactionTypeCountSplit) {
                this.transactionTypeCountSplit = transactionTypeCountSplit;
                return this;
            }

            public CustomerDemographicsBuilder cardSegmentSplit(Map<String, BigDecimal> cardSegmentSplit) {
                this.cardSegmentSplit = cardSegmentSplit;
                return this;
            }

            public CustomerDemographicsBuilder consumerSegmentSplit(Map<String, BigDecimal> consumerSegmentSplit) {
                this.consumerSegmentSplit = consumerSegmentSplit;
                return this;
            }

            public CustomerDemographicsBuilder txnValueBands(List<ChartData> txnValueBands) {
                this.txnValueBands = txnValueBands;
                return this;
            }

            public CustomerDemographicsBuilder topCountries(List<ChartData> topCountries) {
                this.topCountries = topCountries;
                return this;
            }

            public CustomerDemographicsBuilder topDomesticBanks(List<ChartData> topDomesticBanks) {
                this.topDomesticBanks = topDomesticBanks;
                return this;
            }

            public CustomerDemographicsBuilder monthlySales(List<ChartData> monthlySales) {
                this.monthlySales = monthlySales;
                return this;
            }

            public CustomerDemographicsBuilder monthlyTxns(List<ChartData> monthlyTxns) {
                this.monthlyTxns = monthlyTxns;
                return this;
            }

            public CustomerDemographicsBuilder monthlyCustomers(List<ChartData> monthlyCustomers) {
                this.monthlyCustomers = monthlyCustomers;
                return this;
            }

            public CustomerDemographicsBuilder monthlyAtv(List<ChartData> monthlyAtv) {
                this.monthlyAtv = monthlyAtv;
                return this;
            }

            public CustomerDemographicsBuilder monthlySalesGrowth(List<ChartData> monthlySalesGrowth) {
                this.monthlySalesGrowth = monthlySalesGrowth;
                return this;
            }

            public CustomerDemographicsBuilder monthlyTxnGrowth(List<ChartData> monthlyTxnGrowth) {
                this.monthlyTxnGrowth = monthlyTxnGrowth;
                return this;
            }

            public CustomerDemographics build() {
                return new CustomerDemographics(cardSchemeValueSplit, cardSchemeCountSplit, cardTypeValueSplit,
                        cardTypeCountSplit, transactionTypeValueSplit, transactionTypeCountSplit, cardSegmentSplit,
                        consumerSegmentSplit, txnValueBands, topCountries, topDomesticBanks, monthlySales, monthlyTxns,
                        monthlyCustomers, monthlyAtv, monthlySalesGrowth, monthlyTxnGrowth);
            }
        }
    }

    public static class DccPerformance {
        private List<ChartData> missedOpportunityTrend;
        private List<ChartData> eligibilityTrend;
        private List<ChartData> optOutOptInTrend;

        public DccPerformance() {
        }

        public DccPerformance(List<ChartData> missedOpportunityTrend, List<ChartData> eligibilityTrend,
                List<ChartData> optOutOptInTrend) {
            this.missedOpportunityTrend = missedOpportunityTrend;
            this.eligibilityTrend = eligibilityTrend;
            this.optOutOptInTrend = optOutOptInTrend;
        }

        public List<ChartData> getMissedOpportunityTrend() {
            return missedOpportunityTrend;
        }

        public void setMissedOpportunityTrend(List<ChartData> missedOpportunityTrend) {
            this.missedOpportunityTrend = missedOpportunityTrend;
        }

        public List<ChartData> getEligibilityTrend() {
            return eligibilityTrend;
        }

        public void setEligibilityTrend(List<ChartData> eligibilityTrend) {
            this.eligibilityTrend = eligibilityTrend;
        }

        public List<ChartData> getOptOutOptInTrend() {
            return optOutOptInTrend;
        }

        public void setOptOutOptInTrend(List<ChartData> optOutOptInTrend) {
            this.optOutOptInTrend = optOutOptInTrend;
        }

        public static DccPerformanceBuilder builder() {
            return new DccPerformanceBuilder();
        }

        public static class DccPerformanceBuilder {
            private List<ChartData> missedOpportunityTrend;
            private List<ChartData> eligibilityTrend;
            private List<ChartData> optOutOptInTrend;

            public DccPerformanceBuilder missedOpportunityTrend(List<ChartData> missedOpportunityTrend) {
                this.missedOpportunityTrend = missedOpportunityTrend;
                return this;
            }

            public DccPerformanceBuilder eligibilityTrend(List<ChartData> eligibilityTrend) {
                this.eligibilityTrend = eligibilityTrend;
                return this;
            }

            public DccPerformanceBuilder optOutOptInTrend(List<ChartData> optOutOptInTrend) {
                this.optOutOptInTrend = optOutOptInTrend;
                return this;
            }

            public DccPerformance build() {
                return new DccPerformance(missedOpportunityTrend, eligibilityTrend, optOutOptInTrend);
            }
        }
    }

    public static class ChartData {
        private String label;
        private BigDecimal value;
        private BigDecimal value2;
        private BigDecimal value3;

        public ChartData() {
        }

        public ChartData(String label, BigDecimal value, BigDecimal value2, BigDecimal value3) {
            this.label = label;
            this.value = value;
            this.value2 = value2;
            this.value3 = value3;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public BigDecimal getValue() {
            return value;
        }

        public void setValue(BigDecimal value) {
            this.value = value;
        }

        public BigDecimal getValue2() {
            return value2;
        }

        public void setValue2(BigDecimal value2) {
            this.value2 = value2;
        }

        public BigDecimal getValue3() {
            return value3;
        }

        public void setValue3(BigDecimal value3) {
            this.value3 = value3;
        }

        public static ChartDataBuilder builder() {
            return new ChartDataBuilder();
        }

        public static class ChartDataBuilder {
            private String label;
            private BigDecimal value;
            private BigDecimal value2;
            private BigDecimal value3;

            public ChartDataBuilder label(String label) {
                this.label = label;
                return this;
            }

            public ChartDataBuilder value(BigDecimal value) {
                this.value = value;
                return this;
            }

            public ChartDataBuilder value2(BigDecimal value2) {
                this.value2 = value2;
                return this;
            }

            public ChartDataBuilder value3(BigDecimal value3) {
                this.value3 = value3;
                return this;
            }

            public ChartData build() {
                return new ChartData(label, value, value2, value3);
            }
        }
    }
}
