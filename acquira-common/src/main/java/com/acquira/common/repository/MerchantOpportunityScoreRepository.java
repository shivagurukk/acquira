package com.acquira.common.repository;

import com.acquira.common.model.MerchantOpportunityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantOpportunityScoreRepository extends JpaRepository<MerchantOpportunityScore, Long> {

    // Kept for backward compatibility. WARNING: this returns EVERY historical
    // row — calculateBusinessMetrics inserts one row per merchant per upload
    // date, so on a tenant with months of data this is ~(merchants x dates)
    // rows (e.g. 470k+). Do not use it for the Opportunity Intelligence screen;
    // use findLatestByTenant() instead.
    List<MerchantOpportunityScore> findByTenantIdOrderByScoreDesc(Long tenantId);

    /**
     * Returns ONE row per merchant — the score from that merchant's most recent
     * calc_date — ordered by score descending.
     *
     * Why this exists: merchant_opportunity_score accumulates a row per merchant
     * per processed business date. The Opportunity Intelligence screen wants the
     * CURRENT score per merchant (~one per merchant), not every dated snapshot.
     * findByTenantIdOrderByScoreDesc returned all historical rows, which both
     * showed each merchant dozens of times and produced a multi-hundred-thousand
     * row response that could hang the grid.
     *
     * The DISTINCT ON (merchant_id) ... ORDER BY merchant_id, calc_date DESC
     * picks the latest row per merchant; the outer query re-sorts by score.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT DISTINCT ON (merchant_id) *
                FROM merchant_opportunity_score
                WHERE tenant_id = :tenantId
                ORDER BY merchant_id, calc_date DESC
            ) latest
            ORDER BY score DESC
            """, nativeQuery = true)
    List<MerchantOpportunityScore> findLatestByTenant(@Param("tenantId") Long tenantId);
}
