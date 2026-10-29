package com.acquira.common.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ticket-size bands are configured per country (ticket_size_bucket), so the
 * insight service must keep whatever labels the batch produced and order them
 * by amount — not filter them through the UAE label list. The Bahrain
 * 2026-09 Insight PDF lost 86% of its transactions to exactly that filter.
 */
class MerchantInsightServiceBandsTest {

    @Test
    void bahrainBandsAreKeptAndOrderedByAmount() {
        List<String> ordered = MerchantInsightService.orderBucketLabels(
                List.of("500+", "50-100", "< 5", "100-500", "25-50", "5-10", "10-25"));
        assertEquals(List.of("< 5", "5-10", "10-25", "25-50", "50-100", "100-500", "500+"), ordered);
    }

    @Test
    void uaeBandsKeepTheirHistoricalOrder() {
        List<String> ordered = MerchantInsightService.orderBucketLabels(
                List.of("5K+", "1K-5K", "500-1K", "250-500", "100-250", "50-100", "< 50"));
        assertEquals(List.of("< 50", "50-100", "100-250", "250-500", "500-1K", "1K-5K", "5K+"), ordered);
    }

    @Test
    void legacyOpenBandSortsAfterTheClosedBandWithTheSameStart() {
        // "1K+" (pre-split data) must not interleave ahead of "1K-5K".
        List<String> ordered = MerchantInsightService.orderBucketLabels(List.of("1K+", "1K-5K", "5K+", "500-1K"));
        assertEquals(List.of("500-1K", "1K-5K", "1K+", "5K+"), ordered);
    }

    @Test
    void egyptBandsWithDecimalKSuffixParse() {
        List<String> ordered = MerchantInsightService.orderBucketLabels(
                List.of("50K+", "2.5K-5K", "< 500", "10K-50K", "1K-2.5K", "5K-10K", "500-1K"));
        assertEquals(List.of("< 500", "500-1K", "1K-2.5K", "2.5K-5K", "5K-10K", "10K-50K", "50K+"), ordered);
        assertEquals(2500d, MerchantInsightService.bucketLowerBound("2.5K-5K"));
        assertEquals(0d, MerchantInsightService.bucketLowerBound("< 500"));
    }

    @Test
    void weekendLabelFollowsTheTenantsWeekRule() {
        assertEquals("Friday & Saturday",
                MerchantInsightService.weekendLabel(WeekRules.weekendDays("BH")));
        assertEquals("Saturday & Sunday",
                MerchantInsightService.weekendLabel(WeekRules.weekendDays("AE")));
        assertEquals("Friday & Saturday", MerchantInsightService.weekendLabel(null));
        assertEquals("Friday", MerchantInsightService.weekendLabel(EnumSet.of(DayOfWeek.FRIDAY)));
    }
}
