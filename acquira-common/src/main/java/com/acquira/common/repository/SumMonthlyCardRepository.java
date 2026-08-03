package com.acquira.common.repository;

import com.acquira.common.model.SumMonthlyCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SumMonthlyCardRepository extends JpaRepository<SumMonthlyCard, Long> {

    // ─── DB-side aggregation for the loyalty section ────────────────────────
    // The loyalty section only ever consumes HISTOGRAMS of per-card aggregates,
    // never individual card rows — but the entity queries above return one row
    // per card per month (80k+ rows for a single large merchant), which is what
    // made bulk PDF pre-fetch blow the heap. These return ~tens of rows per
    // merchant instead and keep the exact same downstream semantics.

    /**
     * Visit-count histogram per merchant for a month window:
     * (merchant_id, visits, cards) — how many distinct cards visited exactly
     * N times. Cards are first summed across the window (matches the old
     * in-Java per-card accumulation), then bucketed.
     */
    @Query(value = """
            SELECT t.merchant_id, t.visits, COUNT(*) AS cards
            FROM (SELECT merchant_id, card_number, SUM(visit_count) AS visits
                  FROM sum_monthly_card
                  WHERE merchant_id IN (:merchantIds) AND month_key BETWEEN :startMonth AND :endMonth
                  GROUP BY merchant_id, card_number) t
            GROUP BY t.merchant_id, t.visits
            """, nativeQuery = true)
    List<Object[]> aggregateVisitHistogram(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
            @org.springframework.data.repository.query.Param("startMonth") Integer startMonth,
            @org.springframework.data.repository.query.Param("endMonth") Integer endMonth);

    /**
     * Spend-band histogram per merchant for a month window:
     * (merchant_id, band, cards). Band edges MUST stay in sync with the
     * loyalty spend-band labels in MerchantInsightService.
     */
    @Query(value = """
            SELECT t.merchant_id,
                   CASE WHEN t.spend < 20 THEN '0-20' WHEN t.spend < 50 THEN '20-50'
                        WHEN t.spend < 100 THEN '50-100' WHEN t.spend < 200 THEN '100-200'
                        WHEN t.spend < 500 THEN '200-500' ELSE '500+' END AS band,
                   COUNT(*) AS cards
            FROM (SELECT merchant_id, card_number, SUM(total_spend) AS spend
                  FROM sum_monthly_card
                  WHERE merchant_id IN (:merchantIds) AND month_key BETWEEN :startMonth AND :endMonth
                  GROUP BY merchant_id, card_number) t
            GROUP BY 1, 2
            """, nativeQuery = true)
    List<Object[]> aggregateSpendBands(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
            @org.springframework.data.repository.query.Param("startMonth") Integer startMonth,
            @org.springframework.data.repository.query.Param("endMonth") Integer endMonth);

    /**
     * Monthly visit-frequency buckets per merchant over a month range:
     * (merchant_id, month_key, c1, c2to4, c5plus). Buckets match the old
     * in-Java logic exactly: v == 1 → c1, else v <= 4 → c2to4, else c5plus.
     */
    @Query(value = """
            SELECT merchant_id, month_key,
                   COUNT(*) FILTER (WHERE visit_count = 1)                        AS c1,
                   COUNT(*) FILTER (WHERE visit_count <> 1 AND visit_count <= 4) AS c2to4,
                   COUNT(*) FILTER (WHERE visit_count > 4)                       AS c5plus
            FROM sum_monthly_card
            WHERE merchant_id IN (:merchantIds) AND month_key BETWEEN :startMonth AND :endMonth
            GROUP BY merchant_id, month_key
            """, nativeQuery = true)
    List<Object[]> aggregateMonthlyVisitFrequency(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
            @org.springframework.data.repository.query.Param("startMonth") Integer startMonth,
            @org.springframework.data.repository.query.Param("endMonth") Integer endMonth);
}
