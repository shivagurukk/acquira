package com.acquira.common.repository;

import com.acquira.common.model.MerchantChurnScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantChurnScoreRepository extends JpaRepository<MerchantChurnScore, Long> {

    /**
     * Latest churn score per merchant for a tenant (one row per merchant, most
     * recent calc_date). Mirrors MerchantOpportunityScoreRepository.findLatestByTenant
     * so the Attrition Report can join it the same way. Native query using a
     * correlated MAX(calc_date) so it works regardless of JPA dialect quirks.
     */
    @Query(value =
        "SELECT c.* FROM merchant_churn_score c " +
        "WHERE c.tenant_id = :tenantId " +
        "  AND c.calc_date = (SELECT MAX(c2.calc_date) FROM merchant_churn_score c2 " +
        "                     WHERE c2.tenant_id = c.tenant_id AND c2.merchant_id = c.merchant_id) " +
        "ORDER BY c.churn_probability DESC",
        nativeQuery = true)
    List<MerchantChurnScore> findLatestByTenant(@Param("tenantId") Long tenantId);

    List<MerchantChurnScore> findByTenantIdAndCalcDate(Long tenantId, java.time.LocalDate calcDate);
}
