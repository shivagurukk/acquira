package com.acquira.common.repository;

import com.acquira.common.model.SumMonthlyCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SumMonthlyCardRepository extends JpaRepository<SumMonthlyCard, Long> {

    // Fetch all card summaries for a merchant within a month range (e.g. for Trend
    // Charts or Single Month distribution)
    @Query("SELECT s FROM SumMonthlyCard s WHERE s.merchantId = :merchantId AND s.monthKey BETWEEN :startMonth AND :endMonth")
    List<SumMonthlyCard> findByMerchantAndMonthRange(Long merchantId, Integer startMonth, Integer endMonth);

    // For "Loyalty - Visit Frequency" (Total Period aggregation might be needed if
    // PDF purely strictly asks for "Total Period" histograms from daily sum?
    // Actually the PDF asks for "For the Month" (Page 8) and "Monthly Trends" (Page
    // 9).
    // So usually querying by single monthKey is enough for Page 8.
    // querying by range is needed for Page 9.
}
