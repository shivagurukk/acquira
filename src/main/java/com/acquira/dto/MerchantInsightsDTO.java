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
    // NEW: Currency support
    private String currencySymbol;
    private String currencyCode;
    // NEW: Store-level leaderboard (top stores by volume)
    private List<ChartData> storeLeaderboard; // label=storeName, value=volume, value2=txnCount, value3=avgTicket

    public List<ChartData> getStoreLeaderboard() { return storeLeaderboard; }
    public void setStoreLeaderboard(List<ChartData> v) { this.storeLeaderboard = v; }

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

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public void setCurrencySymbol(String currencySymbol) {
        this.currencySymbol = currencySymbol;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
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
        private String currencySymbol;
        private String currencyCode;

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

        public MerchantInsightsDTOBuilder currencySymbol(String currencySymbol) {
            this.currencySymbol = currencySymbol;
            return this;
        }

        public MerchantInsightsDTOBuilder currencyCode(String currencyCode) {
            this.currencyCode = currencyCode;
            return this;
        }

        public MerchantInsightsDTO build() {
            return new MerchantInsightsDTO(overview, achievements, loyalty, demographics, dccPerformance,
                    currencySymbol, currencyCode);
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
        // NEW: Previous month values for MoM comparison page
        private Kpi prevSales;
        private Kpi prevTransactions;
        private Kpi prevCustomers;
        private Kpi prevAvgTxnValue;
        private Kpi prevMaxDailySales;
        // NEW: Computed metrics for revenue/day-type pages
        private BigDecimal weekdayRevenuePct;
        private BigDecimal weekendRevenuePct;
        private String peakDayName;
        private BigDecimal dailyAverage;

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

        public Kpi getPrevSales() {
            return prevSales;
        }

        public void setPrevSales(Kpi v) {
            this.prevSales = v;
        }

        public Kpi getPrevTransactions() {
            return prevTransactions;
        }

        public void setPrevTransactions(Kpi v) {
            this.prevTransactions = v;
        }

        public Kpi getPrevCustomers() {
            return prevCustomers;
        }

        public void setPrevCustomers(Kpi v) {
            this.prevCustomers = v;
        }

        public Kpi getPrevAvgTxnValue() {
            return prevAvgTxnValue;
        }

        public void setPrevAvgTxnValue(Kpi v) {
            this.prevAvgTxnValue = v;
        }

        public Kpi getPrevMaxDailySales() {
            return prevMaxDailySales;
        }

        public void setPrevMaxDailySales(Kpi v) {
            this.prevMaxDailySales = v;
        }

        public BigDecimal getWeekdayRevenuePct() {
            return weekdayRevenuePct;
        }

        public void setWeekdayRevenuePct(BigDecimal v) {
            this.weekdayRevenuePct = v;
        }

        public BigDecimal getWeekendRevenuePct() {
            return weekendRevenuePct;
        }

        public void setWeekendRevenuePct(BigDecimal v) {
            this.weekendRevenuePct = v;
        }

        public String getPeakDayName() {
            return peakDayName;
        }

        public void setPeakDayName(String v) {
            this.peakDayName = v;
        }

        public BigDecimal getDailyAverage() {
            return dailyAverage;
        }

        public void setDailyAverage(BigDecimal v) {
            this.dailyAverage = v;
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

            private Kpi prevSales, prevTransactions, prevCustomers, prevAvgTxnValue, prevMaxDailySales;
            private BigDecimal weekdayRevenuePct, weekendRevenuePct, dailyAverage;
            private String peakDayName;

            public BusinessOverviewBuilder prevSales(Kpi v) {
                this.prevSales = v;
                return this;
            }

            public BusinessOverviewBuilder prevTransactions(Kpi v) {
                this.prevTransactions = v;
                return this;
            }

            public BusinessOverviewBuilder prevCustomers(Kpi v) {
                this.prevCustomers = v;
                return this;
            }

            public BusinessOverviewBuilder prevAvgTxnValue(Kpi v) {
                this.prevAvgTxnValue = v;
                return this;
            }

            public BusinessOverviewBuilder prevMaxDailySales(Kpi v) {
                this.prevMaxDailySales = v;
                return this;
            }

            public BusinessOverviewBuilder weekdayRevenuePct(BigDecimal v) {
                this.weekdayRevenuePct = v;
                return this;
            }

            public BusinessOverviewBuilder weekendRevenuePct(BigDecimal v) {
                this.weekendRevenuePct = v;
                return this;
            }

            public BusinessOverviewBuilder peakDayName(String v) {
                this.peakDayName = v;
                return this;
            }

            public BusinessOverviewBuilder dailyAverage(BigDecimal v) {
                this.dailyAverage = v;
                return this;
            }

            public BusinessOverview build() {
                BusinessOverview o = new BusinessOverview(sales, transactions, customers, avgSpendPerCustomer,
                        avgTxnValue,
                        avgTxnsPerCustomer, peakStats, salesByDayOfWeek, transactionsByDayOfWeek, salesByWeekOfMonth,
                        transactionsByWeekOfMonth);
                o.prevSales = prevSales;
                o.prevTransactions = prevTransactions;
                o.prevCustomers = prevCustomers;
                o.prevAvgTxnValue = prevAvgTxnValue;
                o.prevMaxDailySales = prevMaxDailySales;
                o.weekdayRevenuePct = weekdayRevenuePct;
                o.weekendRevenuePct = weekendRevenuePct;
                o.peakDayName = peakDayName;
                o.dailyAverage = dailyAverage;
                return o;
            }

            public BusinessOverview buildLegacy() {
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
        // NEW: Revenue Heatmap (Day×Hour), Txn Size Distribution
        private List<ChartData> revenueHeatmap; // label="Mon|09", value=volume
        private List<ChartData> txnSizeDistribution; // label="0-50", value=count, value2=pct

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

        public List<ChartData> getRevenueHeatmap() { return revenueHeatmap; }
        public void setRevenueHeatmap(List<ChartData> v) { this.revenueHeatmap = v; }
        public List<ChartData> getTxnSizeDistribution() { return txnSizeDistribution; }
        public void setTxnSizeDistribution(List<ChartData> v) { this.txnSizeDistribution = v; }

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
        // NEW: Computed loyalty KPIs
        private BigDecimal retentionRate;
        private BigDecimal totalUniqueCards;
        private BigDecimal repeatCardPct;
        // NEW: Customer Intelligence metrics for p09
        private BigDecimal avgVisitsPerMonth;
        private Long singleVisitCards;
        private BigDecimal singleVisitPct;
        private BigDecimal singleVisitRevenue;
        private Long repeatVisitCards;
        private BigDecimal repeatVisitPct;
        private BigDecimal repeatVisitRevenue;

        public BigDecimal getRetentionRate() { return retentionRate; }
        public void setRetentionRate(BigDecimal v) { this.retentionRate = v; }
        public BigDecimal getTotalUniqueCards() { return totalUniqueCards; }
        public void setTotalUniqueCards(BigDecimal v) { this.totalUniqueCards = v; }
        public BigDecimal getRepeatCardPct() { return repeatCardPct; }
        public void setRepeatCardPct(BigDecimal v) { this.repeatCardPct = v; }
        public BigDecimal getAvgVisitsPerMonth() { return avgVisitsPerMonth; }
        public void setAvgVisitsPerMonth(BigDecimal v) { this.avgVisitsPerMonth = v; }
        public Long getSingleVisitCards() { return singleVisitCards; }
        public void setSingleVisitCards(Long v) { this.singleVisitCards = v; }
        public BigDecimal getSingleVisitPct() { return singleVisitPct; }
        public void setSingleVisitPct(BigDecimal v) { this.singleVisitPct = v; }
        public BigDecimal getSingleVisitRevenue() { return singleVisitRevenue; }
        public void setSingleVisitRevenue(BigDecimal v) { this.singleVisitRevenue = v; }
        public Long getRepeatVisitCards() { return repeatVisitCards; }
        public void setRepeatVisitCards(Long v) { this.repeatVisitCards = v; }
        public BigDecimal getRepeatVisitPct() { return repeatVisitPct; }
        public void setRepeatVisitPct(BigDecimal v) { this.repeatVisitPct = v; }
        public BigDecimal getRepeatVisitRevenue() { return repeatVisitRevenue; }
        public void setRepeatVisitRevenue(BigDecimal v) { this.repeatVisitRevenue = v; }

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

            private BigDecimal retentionRate, totalUniqueCards, repeatCardPct;

            public ConsumerLoyaltyBuilder retentionRate(BigDecimal v) {
                this.retentionRate = v;
                return this;
            }

            public ConsumerLoyaltyBuilder totalUniqueCards(BigDecimal v) {
                this.totalUniqueCards = v;
                return this;
            }

            public ConsumerLoyaltyBuilder repeatCardPct(BigDecimal v) {
                this.repeatCardPct = v;
                return this;
            }

            public ConsumerLoyalty build() {
                ConsumerLoyalty l = new ConsumerLoyalty(visitFrequency, spendBands, domesticVsInternational,
                        customerCategoryTrend,
                        customerCategorySplit, monthlyVisitFreqTrend, monthlySpendBandTrend, customerCategoryCountSplit,
                        customerCategoryValueSplit);
                l.retentionRate = retentionRate;
                l.totalUniqueCards = totalUniqueCards;
                l.repeatCardPct = repeatCardPct;
                return l;
            }

            public ConsumerLoyalty buildLegacy() {
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
        // NEW: Computed fields
        private String creditDebitRatio;
        private BigDecimal walletUsagePct;
        private BigDecimal cardPenetrationPct;
        private List<ChartData> quarterlyBreakdown;
        private String bestMonth;
        private BigDecimal avgMonthlyGrowthPct;
        private String peakSeason;
        private String lowSeason;
        private BigDecimal yoyGrowthPct;
        // NEW: Credit/Debit/Prepaid card type metrics
        private BigDecimal creditPct;
        private BigDecimal creditVolume;
        private Long creditTxnCount;
        private BigDecimal debitPct;
        private BigDecimal debitVolume;
        private Long debitTxnCount;
        private BigDecimal prepaidPct;
        private BigDecimal prepaidVolume;
        private Long prepaidTxnCount;
        // NEW: Local vs International card metrics
        private BigDecimal localCardPct;
        private BigDecimal localCardVolume;
        private Long localCardCustomers;
        private BigDecimal internationalCardPct;
        private BigDecimal internationalCardVolume;
        private Long internationalCardCustomers;
        // NEW: Avg Ticket by Card Type
        private List<ChartData> avgTicketByCardType; // label=CREDIT/DEBIT/PREPAID, value=ATV, value2=volume, value3=count

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

        public String getCreditDebitRatio() {
            return creditDebitRatio;
        }

        public void setCreditDebitRatio(String v) {
            this.creditDebitRatio = v;
        }

        public BigDecimal getWalletUsagePct() {
            return walletUsagePct;
        }

        public void setWalletUsagePct(BigDecimal v) {
            this.walletUsagePct = v;
        }

        public BigDecimal getCardPenetrationPct() {
            return cardPenetrationPct;
        }

        public void setCardPenetrationPct(BigDecimal v) {
            this.cardPenetrationPct = v;
        }

        public List<ChartData> getQuarterlyBreakdown() {
            return quarterlyBreakdown;
        }

        public void setQuarterlyBreakdown(List<ChartData> v) {
            this.quarterlyBreakdown = v;
        }

        public String getBestMonth() {
            return bestMonth;
        }

        public void setBestMonth(String v) {
            this.bestMonth = v;
        }

        public BigDecimal getAvgMonthlyGrowthPct() {
            return avgMonthlyGrowthPct;
        }

        public void setAvgMonthlyGrowthPct(BigDecimal v) {
            this.avgMonthlyGrowthPct = v;
        }

        public String getPeakSeason() {
            return peakSeason;
        }

        public void setPeakSeason(String v) {
            this.peakSeason = v;
        }

        public String getLowSeason() {
            return lowSeason;
        }

        public void setLowSeason(String v) {
            this.lowSeason = v;
        }

        public BigDecimal getYoyGrowthPct() {
            return yoyGrowthPct;
        }

        public void setYoyGrowthPct(BigDecimal v) {
            this.yoyGrowthPct = v;
        }

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
        // NEW: Computed DCC KPIs
        private BigDecimal dccEligibleVolume;
        private BigDecimal dccOptinVolume;
        private BigDecimal dccOptoutVolume;
        private BigDecimal dccConversionRate;
        private BigDecimal dccMissedRevenue;
        // NEW: Opt-in/Opt-out counts and revenue for p09-customer-intel
        private Long optInCount;
        private BigDecimal optInRevenue;
        private Long optOutCount;
        private BigDecimal optOutRevenue;
        private BigDecimal optOutDeclineRate;
        private Long dccEligibleCount;
        private Long dccOptinCountLong;

        public Long getOptInCount() { return optInCount; }
        public void setOptInCount(Long v) { this.optInCount = v; }
        public BigDecimal getOptInRevenue() { return optInRevenue; }
        public void setOptInRevenue(BigDecimal v) { this.optInRevenue = v; }
        public Long getOptOutCount() { return optOutCount; }
        public void setOptOutCount(Long v) { this.optOutCount = v; }
        public BigDecimal getOptOutRevenue() { return optOutRevenue; }
        public void setOptOutRevenue(BigDecimal v) { this.optOutRevenue = v; }
        public BigDecimal getOptOutDeclineRate() { return optOutDeclineRate; }
        public void setOptOutDeclineRate(BigDecimal v) { this.optOutDeclineRate = v; }
        public Long getDccEligibleCount() { return dccEligibleCount; }
        public void setDccEligibleCount(Long v) { this.dccEligibleCount = v; }
        public Long getDccOptinCountLong() { return dccOptinCountLong; }
        public void setDccOptinCountLong(Long v) { this.dccOptinCountLong = v; }
        // NEW: DCC Funnel — total international txn volume for the funnel top
        private Long totalIntlTxnCount;
        private BigDecimal totalIntlVolume;
        private BigDecimal dccRevenueGenerated; // actual margin earned from DCC opt-ins
        public Long getTotalIntlTxnCount() { return totalIntlTxnCount; }
        public void setTotalIntlTxnCount(Long v) { this.totalIntlTxnCount = v; }
        public BigDecimal getTotalIntlVolume() { return totalIntlVolume; }
        public void setTotalIntlVolume(BigDecimal v) { this.totalIntlVolume = v; }
        public BigDecimal getDccRevenueGenerated() { return dccRevenueGenerated; }
        public void setDccRevenueGenerated(BigDecimal v) { this.dccRevenueGenerated = v; }

        public BigDecimal getDccEligibleVolume() {
            return dccEligibleVolume;
        }

        public void setDccEligibleVolume(BigDecimal v) {
            this.dccEligibleVolume = v;
        }

        public BigDecimal getDccOptinVolume() {
            return dccOptinVolume;
        }

        public void setDccOptinVolume(BigDecimal v) {
            this.dccOptinVolume = v;
        }

        public BigDecimal getDccOptoutVolume() {
            return dccOptoutVolume;
        }

        public void setDccOptoutVolume(BigDecimal v) {
            this.dccOptoutVolume = v;
        }

        public BigDecimal getDccConversionRate() {
            return dccConversionRate;
        }

        public void setDccConversionRate(BigDecimal v) {
            this.dccConversionRate = v;
        }

        public BigDecimal getDccMissedRevenue() {
            return dccMissedRevenue;
        }

        public void setDccMissedRevenue(BigDecimal v) {
            this.dccMissedRevenue = v;
        }

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

            private BigDecimal dccEligibleVolume, dccOptinVolume, dccOptoutVolume, dccConversionRate, dccMissedRevenue;

            public DccPerformanceBuilder dccEligibleVolume(BigDecimal v) {
                this.dccEligibleVolume = v;
                return this;
            }

            public DccPerformanceBuilder dccOptinVolume(BigDecimal v) {
                this.dccOptinVolume = v;
                return this;
            }

            public DccPerformanceBuilder dccOptoutVolume(BigDecimal v) {
                this.dccOptoutVolume = v;
                return this;
            }

            public DccPerformanceBuilder dccConversionRate(BigDecimal v) {
                this.dccConversionRate = v;
                return this;
            }

            public DccPerformanceBuilder dccMissedRevenue(BigDecimal v) {
                this.dccMissedRevenue = v;
                return this;
            }

            public DccPerformance build() {
                DccPerformance d = new DccPerformance(missedOpportunityTrend, eligibilityTrend, optOutOptInTrend);
                d.dccEligibleVolume = dccEligibleVolume;
                d.dccOptinVolume = dccOptinVolume;
                d.dccOptoutVolume = dccOptoutVolume;
                d.dccConversionRate = dccConversionRate;
                d.dccMissedRevenue = dccMissedRevenue;
                return d;
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
