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

    // For "Loyalty - Visit Frequency"
    // querying by range is needed for Page 9 trends.

    // BULK: fetch card data for all merchants at once
    @Query("SELECT s FROM SumMonthlyCard s WHERE s.merchantId IN :merchantIds AND s.monthKey BETWEEN :startMonth AND :endMonth")
    List<SumMonthlyCard> findByMerchantsAndMonthRange(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
            @org.springframework.data.repository.query.Param("startMonth") Integer startMonth,
            @org.springframework.data.repository.query.Param("endMonth") Integer endMonth);
}
